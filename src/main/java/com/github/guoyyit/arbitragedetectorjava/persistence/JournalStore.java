package com.github.guoyyit.arbitragedetectorjava.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.arbitragedetectorjava.model.OpportunityEvent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only journal for crash recovery.
 *
 * <p>Each detection cycle is journaled as ONE line containing its snapshot
 * version and the full batch of lifecycle events it produced. Writing the
 * batch as a single line keeps a cycle atomic: a crash can at worst leave a
 * torn last line, which is detected and skipped on recovery. The cycle is
 * then re-detected from the live quote book, so no opportunity is missed,
 * and already-journaled APPEARED events suppress duplicate reporting.
 *
 * <p>Recovery replays every complete line in order; the resulting event
 * stream is folded into the tracker via
 * {@code OpportunityTracker.restore}.
 */
public class JournalStore {

    /** One journaled detection cycle. */
    public record CycleRecord(long snapshotVersion, long timestamp,
                              List<OpportunityEvent> events) {
    }

    private final Path journalFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private BufferedWriter writer;

    public JournalStore(Path journalDir) {
        try {
            Files.createDirectories(journalDir);
            this.journalFile = journalDir.resolve("opportunity-journal.log");
            this.writer = Files.newBufferedWriter(journalFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open journal in " + journalDir, e);
        }
    }

    /** Atomically journal one completed detection cycle. */
    public synchronized void appendCycle(long snapshotVersion, long timestamp,
                                         List<OpportunityEvent> events) {
        try {
            writer.write(mapper.writeValueAsString(
                    new CycleRecord(snapshotVersion, timestamp, events)));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("journal append failed", e);
        }
    }

    /** All events from all complete cycles, in journal order. */
    public synchronized List<OpportunityEvent> loadEvents() {
        List<OpportunityEvent> events = new ArrayList<>();
        if (!Files.exists(journalFile)) {
            return events;
        }
        try (BufferedReader reader = Files.newBufferedReader(journalFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    CycleRecord record = mapper.readValue(line, CycleRecord.class);
                    if (record.events() != null) {
                        events.addAll(record.events());
                    }
                } catch (IOException parseFailure) {
                    // Torn tail from a crash mid-write: ignore the incomplete cycle.
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("journal load failed", e);
        }
        return events;
    }

    public synchronized void close() {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("journal close failed", e);
        }
    }
}
