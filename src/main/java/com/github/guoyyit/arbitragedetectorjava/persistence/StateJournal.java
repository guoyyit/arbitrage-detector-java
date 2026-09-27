package com.github.guoyyit.arbitragedetectorjava.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.arbitragedetectorjava.config.DetectorProperties;
import com.github.guoyyit.arbitragedetectorjava.domain.Hop;
import com.github.guoyyit.arbitragedetectorjava.domain.Opportunity;
import com.github.guoyyit.arbitragedetectorjava.domain.OpportunityEvent;
import com.github.guoyyit.arbitragedetectorjava.domain.Quote;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Append-only, durable JSON-lines journal for crash recovery and auditing.
 *
 * Three record kinds are written, one JSON object per line:
 * <ul>
 *   <li>{@code type=QUOTE}      - an accepted quote (write-ahead: it is durable
 *                                  before any detection can use it),</li>
 *   <li>{@code type=EVENT}      - an opportunity lifecycle event (audit trail),</li>
 *   <li>{@code type=CHECKPOINT} - the last fully-completed detection run
 *                                  (ingestSeq/snapshotVersion).</li>
 * </ul>
 * Each append is flushed and fsynced, so after a crash at most one in-flight
 * (not-yet-completed) detection is lost. On restart the quote log is replayed,
 * the last completed checkpoint is located, and the active opportunity set is
 * reconstructed from the event stream - which prevents both duplicate reports
 * and missed opportunities.
 */
@Component
public class StateJournal {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final DetectorProperties properties;
    private Path path;
    private FileChannel channel;

    private final List<OpportunityEvent> loadedEvents = new ArrayList<>();
    private final List<Quote> replayedQuotes = new ArrayList<>();
    private final Set<String> activeIdsAtCrash = new LinkedHashSet<>();
    private long recoveredMaxIngestSeq = 0L;
    private long recoveredCheckpointIngestSeq = 0L;
    private long recoveredCheckpointSnapshotVersion = 0L;
    private boolean recovered = false;

    @org.springframework.beans.factory.annotation.Autowired
    public StateJournal(DetectorProperties properties) {
        this.properties = properties;
    }

    public StateJournal(Path path) {
        this.properties = null;
        this.path = path;
    }

    private synchronized Path currentPath() {
        if (path == null && properties != null) {
            path = Path.of(properties.getJournalPath());
        }
        return path;
    }

    private synchronized FileChannel channel() throws IOException {
        String configured = properties == null ? null : properties.getJournalPath();
        if (channel != null && configured != null
                && !path.toString().equals(configured)) {
            channel.close();
            channel = null;
            path = Path.of(configured);
        }
        if (channel == null || !channel.isOpen()) {
            Path p = currentPath();
            if (p == null) {
                throw new IllegalStateException("journal path not configured");
            }
            Path parent = p.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            channel = FileChannel.open(p, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        }
        return channel;
    }

    private void writeLine(Object record) throws IOException {
        byte[] bytes = (MAPPER.writeValueAsString(record) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        synchronized (this) {
            FileChannel ch = channel();
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                ch.write(buffer);
            }
            ch.force(false); // fsync: durable across process or OS crash
        }
    }

    public void appendQuote(Quote q) throws IOException {
        writeLine(new QuoteRec("QUOTE", q.ingestSeq(), q.market(), q.base(), q.quote(),
                q.bid(), q.ask(), q.bidQty(), q.askQty(),
                q.quoteTime().toEpochMilli()));
    }

    public void appendEvent(OpportunityEvent event) throws IOException {
        writeLine(new EventRec("EVENT", event.type().name(), OppRec.from(event.opportunity()),
                event.at().toEpochMilli(), event.reason()));
    }

    public void appendCheckpoint(long ingestSeq, long snapshotVersion, int activeCount)
            throws IOException {
        writeLine(new CheckpointRec("CHECKPOINT", ingestSeq, snapshotVersion, activeCount,
                Instant.now().toEpochMilli()));
    }

    /**
     * Read the journal once and reconstruct recovery state: quotes to replay,
     * max ingest sequence, the last completed-detection checkpoint, the prior
     * audit events and the active opportunity id set at crash (APPEARED/UPDATED
     * adds, DISAPPEARED removes).
     */
    public synchronized RecoveryState recover() throws IOException {
        if (recovered) {
            return new RecoveryState(replayedQuotes, recoveredMaxIngestSeq,
                    recoveredCheckpointIngestSeq, recoveredCheckpointSnapshotVersion,
                    loadedEvents, activeIdsAtCrash);
        }
        Path p = currentPath();
        if (p != null && Files.exists(p)) {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                var node = MAPPER.readTree(line);
                String type = node.path("type").asText();
                switch (type) {
                    case "QUOTE" -> {
                        QuoteRec r = MAPPER.treeToValue(node, QuoteRec.class);
                        Quote q = new Quote(r.market(), r.base(), r.quote(),
                                r.bid(), r.ask(), r.bidQty(), r.askQty(),
                                Instant.ofEpochMilli(r.quoteTimeMs()), r.seq());
                        replayedQuotes.add(q);
                        recoveredMaxIngestSeq = Math.max(recoveredMaxIngestSeq, r.seq());
                    }
                    case "EVENT" -> {
                        EventRec r = MAPPER.treeToValue(node, EventRec.class);
                        OpportunityEvent ev = new OpportunityEvent(
                                OpportunityEvent.Type.valueOf(r.eventType()),
                                r.opp().toOpportunity(),
                                Instant.ofEpochMilli(r.atMs()), r.reason());
                        loadedEvents.add(ev);
                        if (ev.type() == OpportunityEvent.Type.DISAPPEARED) {
                            activeIdsAtCrash.remove(ev.opportunity().id());
                        } else {
                            activeIdsAtCrash.add(ev.opportunity().id());
                        }
                    }
                    case "CHECKPOINT" -> {
                        CheckpointRec r = MAPPER.treeToValue(node, CheckpointRec.class);
                        recoveredCheckpointIngestSeq =
                                Math.max(recoveredCheckpointIngestSeq, r.ingestSeq());
                        recoveredCheckpointSnapshotVersion =
                                Math.max(recoveredCheckpointSnapshotVersion, r.snapshotVersion());
                    }
                    default -> { /* unknown kinds are ignored for forward compatibility */ }
                }
            }
        }
        recovered = true;
        return new RecoveryState(replayedQuotes, recoveredMaxIngestSeq,
                recoveredCheckpointIngestSeq, recoveredCheckpointSnapshotVersion,
                loadedEvents, activeIdsAtCrash);
    }

    /** Audit events already in the journal before this process started. */
    public List<OpportunityEvent> loadedEvents() { return List.copyOf(loadedEvents); }

    public long lastCheckpointIngestSeq() { return recoveredCheckpointIngestSeq; }
    public long lastCheckpointSnapshotVersion() { return recoveredCheckpointSnapshotVersion; }

    public Path getPath() { return path; }

    /** Close the underlying channel (used on shutdown and in tests). */
    public synchronized void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }

    /** Result of replaying the journal. */
    public record RecoveryState(List<Quote> quotes, long maxIngestSeq,
                                long checkpointIngestSeq, long checkpointSnapshotVersion,
                                List<OpportunityEvent> priorEvents,
                                Set<String> activeIds) {
        public RecoveryState {
            quotes = List.copyOf(quotes);
            priorEvents = List.copyOf(priorEvents);
            activeIds = Set.copyOf(activeIds);
        }
    }

    // ---- serialization records ----
    record QuoteRec(String type, long seq, String market, String base, String quote,
                    double bid, double ask, double bidQty, double askQty, long quoteTimeMs) {}

    record EventRec(String type, String eventType, OppRec opp, long atMs, String reason) {}

    record CheckpointRec(String type, long ingestSeq, long snapshotVersion,
                         int activeCount, long atMs) {}

    record HopRec(String market, String from, String to, String side,
                  double price, double maxQty) {
        static HopRec from(Hop h) {
            return new HopRec(h.market(), h.from(), h.to(), h.side().name(),
                    h.price(), h.maxQty());
        }
        Hop toHop() {
            return new Hop(market, from, to, Hop.Side.valueOf(side), price, maxQty);
        }
    }

    record OppRec(String id, List<HopRec> hops, double grossRate, double netRate,
                  double maxVolume, long detectedAtMs, long snapshotVersion) {
        static OppRec from(Opportunity o) {
            List<HopRec> hops = new ArrayList<>();
            for (Hop h : o.hops()) {
                hops.add(HopRec.from(h));
            }
            return new OppRec(o.id(), hops, o.grossRate(), o.netRate(), o.maxVolume(),
                    o.detectedAt().toEpochMilli(), o.snapshotVersion());
        }
        Opportunity toOpportunity() {
            List<Hop> hs = new ArrayList<>();
            for (HopRec h : hops) {
                hs.add(h.toHop());
            }
            return new Opportunity(id, hs, grossRate, netRate, maxVolume,
                    Instant.ofEpochMilli(detectedAtMs), snapshotVersion);
        }
    }
}
