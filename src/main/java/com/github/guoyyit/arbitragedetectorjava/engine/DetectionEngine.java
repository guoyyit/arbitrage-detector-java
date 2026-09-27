package com.github.guoyyit.arbitragedetectorjava.engine;

import com.github.guoyyit.arbitragedetectorjava.config.DetectorProperties;
import com.github.guoyyit.arbitragedetectorjava.detect.ArbitrageDetector;
import com.github.guoyyit.arbitragedetectorjava.domain.Opportunity;
import com.github.guoyyit.arbitragedetectorjava.domain.OpportunityEvent;
import com.github.guoyyit.arbitragedetectorjava.input.QuoteIngester;
import com.github.guoyyit.arbitragedetectorjava.opportunity.OpportunityTracker;
import com.github.guoyyit.arbitragedetectorjava.persistence.StateJournal;
import com.github.guoyyit.arbitragedetectorjava.quote.QuoteSnapshot;
import com.github.guoyyit.arbitragedetectorjava.quote.QuoteStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates detection cycles and crash recovery.
 *
 * Each {@link #runOnce()} reads one immutable copy of the configuration and one
 * immutable quote snapshot, then performs the whole cycle under a single engine
 * lock: snapshot -> detect -> reconcile lifecycle -> durably journal events ->
 * durable checkpoint. A crash mid-cycle leaves the journal with the quotes and
 * (possibly) some events but no later checkpoint; on recovery the cycle is
 * simply replayed, and restored-active opportunities are refreshed rather than
 * reported again.
 */
@Component
public class DetectionEngine {
    private static final Logger log = LoggerFactory.getLogger(DetectionEngine.class);

    private final QuoteStore store;
    private final ArbitrageDetector detector;
    private final OpportunityTracker tracker;
    private final StateJournal journal;
    private final DetectorProperties properties;
    private final QuoteIngester ingester;
    private final Clock clock;
    private final Object runLock = new Object();

    private volatile boolean recovered = false;
    /** ids active at crash; refreshed, not re-reported, during the first run */
    private volatile Set<String> restoredActiveIds = Set.of();

    private volatile long lastCheckpointAtMs = 0L;
    private volatile long ingestSeqAtLastCheckpoint = -1L;
    /** bound on idle-checkpoint growth even when nothing changes */
    private static final long CHECKPOINT_FLUSH_INTERVAL_MS = 2_000L;

    @org.springframework.beans.factory.annotation.Autowired
    public DetectionEngine(QuoteStore store, ArbitrageDetector detector,
                           OpportunityTracker tracker, StateJournal journal,
                           DetectorProperties properties, QuoteIngester ingester) {
        this(store, detector, tracker, journal, properties, ingester, Clock.systemUTC());
    }

    public DetectionEngine(QuoteStore store, ArbitrageDetector detector,
                           OpportunityTracker tracker, StateJournal journal,
                           DetectorProperties properties, QuoteIngester ingester,
                           Clock clock) {
        this.store = store;
        this.detector = detector;
        this.tracker = tracker;
        this.journal = journal;
        this.properties = properties;
        this.ingester = ingester;
        this.clock = clock;
    }

    /** Replay the journal before the service starts accepting new detections. */
    @PostConstruct
    public void recover() {
        if (recovered) {
            return;
        }
        try {
            StateJournal.RecoveryState state = journal.recover();
            for (var q : state.quotes()) {
                store.restore(q);
            }
            ingester.restoreSeq(state.maxIngestSeq());
            tracker.restore(state.priorEvents(), state.activeIds(), List.of());
            restoredActiveIds = state.activeIds();
            recovered = true;
            log.info("recovery complete: replayed {} quotes, seq={}, {} active opportunities",
                    state.quotes().size(), state.maxIngestSeq(), state.activeIds().size());
        } catch (IOException e) {
            throw new UncheckedIOException("recovery failed", e);
        }
    }

    public boolean isRecovered() {
        return recovered;
    }

    /**
     * Run one detection cycle against a fresh consistent snapshot. Returns the
     * events emitted by the cycle (empty if nothing changed).
     */
    public List<OpportunityEvent> runOnce() {
        synchronized (runLock) {
            if (!recovered) {
                recover();
            }
            // immutable views: config changes during the run cannot be seen
            DetectorProperties cfg = snapshotConfig();
            Instant now = clock.instant();
            QuoteSnapshot snapshot = store.snapshot(cfg.getQuoteTtl(), now);
            List<Opportunity> found = detector.detect(snapshot, cfg);

            List<OpportunityEvent> emitted;
            boolean firstRunAfterRecovery = !restoredActiveIds.isEmpty();
            if (firstRunAfterRecovery) {
                emitted = reconcileAfterRecovery(found, cfg, now);
                restoredActiveIds = Set.of();
            } else {
                emitted = tracker.reconcile(found, cfg.getMinProfitRate(),
                        cfg.getExitMargin(), cfg.getUpdateEventEpsilon(), now);
            }

            // events are durable before the checkpoint that marks completion
            for (OpportunityEvent event : emitted) {
                try {
                    journal.appendEvent(event);
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to journal event", e);
                }
            }
            long ingestSeq = ingester.lastIngestSeq();
            // Avoid flooding the journal with identical idle checkpoints: persist
            // when this cycle changed the opportunity set, when new quotes were
            // ingested since the last checkpoint, or periodically as a safety net.
            boolean shouldCheckpoint = !emitted.isEmpty()
                    || ingestSeq != ingestSeqAtLastCheckpoint
                    || now.toEpochMilli() - lastCheckpointAtMs >= CHECKPOINT_FLUSH_INTERVAL_MS;
            if (shouldCheckpoint) {
                try {
                    journal.appendCheckpoint(ingestSeq, snapshot.version(),
                            tracker.activeOpportunities().size());
                    ingestSeqAtLastCheckpoint = ingestSeq;
                    lastCheckpointAtMs = now.toEpochMilli();
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to write checkpoint", e);
                }
            }
            return emitted;
        }
    }

    /**
     * First reconciliation after recovery: opportunities active at crash are
     * refreshed silently (they were reported before - no duplicate), genuinely
     * new ones APPEAR, and pre-crash opportunities no longer valid are retracted.
     */
    private List<OpportunityEvent> reconcileAfterRecovery(List<Opportunity> found,
                                                          DetectorProperties cfg,
                                                          Instant now) {
        return tracker.reconcileAfterRecovery(found, restoredActiveIds,
                cfg.getMinProfitRate(), cfg.getExitMargin(), now);
    }

    /**
     * Produce one immutable copy of configuration for the run. Property setters
     * and this copy are both synchronized, so an update can never be observed
     * half-applied and every run is reproducible under the copy it took.
     */
    synchronized DetectorProperties snapshotConfig() {
        return properties.copy();
    }

    public OpportunityTracker getTracker() {
        return tracker;
    }
}
