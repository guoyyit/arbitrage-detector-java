package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.config.ArbitrageProperties;
import com.github.guoyyit.arbitragedetectorjava.detect.ArbitrageDetector;
import com.github.guoyyit.arbitragedetectorjava.engine.DetectionEngine;
import com.github.guoyyit.arbitragedetectorjava.lifecycle.OpportunityTracker;
import com.github.guoyyit.arbitragedetectorjava.model.OpportunityEvent;
import com.github.guoyyit.arbitragedetectorjava.model.Quote;
import com.github.guoyyit.arbitragedetectorjava.persistence.JournalStore;
import com.github.guoyyit.arbitragedetectorjava.state.QuoteBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crash / recovery: a restarted engine must not re-report opportunities
 * already journaled, must not miss opportunities that appear while it was
 * down, and must survive a torn journal tail from a crash mid-write.
 */
class RecoveryTest {

    private static final long NOW = 1_000_000L;
    private static final long TTL = 3_600_000L;

    @TempDir
    Path dir;

    private ArbitrageProperties props() {
        return new ArbitrageProperties(0.001, 0.0005, 0.001, 4,
                TTL, 1_000, 0.0001, 0.5, 30_000, dir.toString());
    }

    private static Quote q(String market, double bid, double ask, long seq, long ts) {
        return new Quote(market, "BTC", "USD", bid, ask, 10, 10, seq, ts);
    }

    private static void submitGap(QuoteBook book, long seq, long ts) {
        book.submit(q("m1", 100, 101, seq, ts), ts, TTL);
        book.submit(q("m2", 103, 104, seq, ts), ts, TTL);
    }

    @Test
    void journalRoundTripsEvents() {
        JournalStore store = new JournalStore(dir);
        QuoteBook book = new QuoteBook();
        OpportunityTracker tracker = new OpportunityTracker();
        ArbitrageProperties p = props();
        submitGap(book, 1, NOW);
        var snapshot = book.snapshot(NOW, TTL);
        var ops = new ArbitrageDetector().detect(snapshot, p);
        var events = tracker.update(ops, p, NOW);
        assertEquals(1, events.size());
        store.appendCycle(snapshot.version(), NOW, events);
        store.close();

        JournalStore reopened = new JournalStore(dir);
        List<OpportunityEvent> loaded = reopened.loadEvents();
        assertEquals(events, loaded);
        reopened.close();
    }

    @Test
    void tornTailIsDiscarded() throws Exception {
        JournalStore store = new JournalStore(dir);
        QuoteBook book = new QuoteBook();
        OpportunityTracker tracker = new OpportunityTracker();
        ArbitrageProperties p = props();
        submitGap(book, 1, NOW);
        var snapshot = book.snapshot(NOW, TTL);
        var events = tracker.update(new ArbitrageDetector().detect(snapshot, p), p, NOW);
        store.appendCycle(snapshot.version(), NOW, events);
        store.close();

        // Simulate a crash mid-write: append a partial JSON line.
        Path file = dir.resolve("opportunity-journal.log");
        Files.writeString(file, "{\"snapshotVersion\":99,\"timestamp\":1,\"events\":[{\"type\":\"APPE",
                StandardOpenOption.APPEND);

        JournalStore reopened = new JournalStore(dir);
        List<OpportunityEvent> loaded = reopened.loadEvents();
        assertEquals(1, loaded.size(), "torn tail must be skipped, complete cycle kept");
        assertEquals(OpportunityEvent.Type.APPEARED, loaded.get(0).type());
        reopened.close();
    }

    @Test
    void restartNeitherReReportsNorMisses() {
        ArbitrageProperties p = props();

        // --- First process lifetime ---
        QuoteBook book1 = new QuoteBook();
        DetectionEngine engine1 = new DetectionEngine(book1, new ArbitrageDetector(),
                new OpportunityTracker(), p);
        submitGap(book1, 1, NOW);
        var events1 = engine1.runOnce(NOW);
        assertEquals(1, events1.size());
        assertEquals(OpportunityEvent.Type.APPEARED, events1.get(0).type());
        engine1.stop(); // simulated shutdown (journal closed)

        // --- Restart: same journal dir, fresh book and tracker ---
        QuoteBook book2 = new QuoteBook();
        DetectionEngine engine2 = new DetectionEngine(book2, new ArbitrageDetector(),
                new OpportunityTracker(), p);
        // Active set recovered from the journal before any new quote arrives.
        assertEquals(1, engine2.tracker().activeCount());

        // The same quotes redelivered after restart must NOT re-report.
        submitGap(book2, 2, NOW + 1000);
        var events2 = engine2.runOnce(NOW + 1000);
        assertTrue(events2.stream().noneMatch(e -> e.type() == OpportunityEvent.Type.APPEARED),
                "already-reported opportunity must not be re-reported after restart");
        assertEquals(1, engine2.tracker().activeCount());

        // A NEW opportunity appearing after the restart must be reported.
        book2.submit(new Quote("m1", "ETH", "USD", 10, 10.1, 100, 100, 1, NOW + 2000), NOW + 2000, TTL);
        book2.submit(new Quote("m2", "ETH", "USD", 10.4, 10.5, 100, 100, 1, NOW + 2000), NOW + 2000, TTL);
        var events3 = engine2.runOnce(NOW + 2000);
        assertEquals(1, events3.stream()
                .filter(e -> e.type() == OpportunityEvent.Type.APPEARED).count());
        assertEquals(2, engine2.tracker().activeCount());
        engine2.stop();
    }

    @Test
    void opportunityAppearingWhileDownIsReportedAfterRecovery() {
        ArbitrageProperties p = props();

        QuoteBook book1 = new QuoteBook();
        DetectionEngine engine1 = new DetectionEngine(book1, new ArbitrageDetector(),
                new OpportunityTracker(), p);
        submitGap(book1, 1, NOW);
        engine1.runOnce(NOW);
        engine1.stop();

        // While "down", the BTC gap closes and an ETH gap opens.
        QuoteBook book2 = new QuoteBook();
        DetectionEngine engine2 = new DetectionEngine(book2, new ArbitrageDetector(),
                new OpportunityTracker(), p);
        book2.submit(q("m1", 100, 101, 2, NOW + 5000), NOW + 5000, TTL);
        book2.submit(q("m2", 100.5, 101.5, 2, NOW + 5000), NOW + 5000, TTL); // no gap
        book2.submit(new Quote("m1", "ETH", "USD", 10, 10.1, 100, 100, 1, NOW + 5000), NOW + 5000, TTL);
        book2.submit(new Quote("m2", "ETH", "USD", 10.4, 10.5, 100, 100, 1, NOW + 5000), NOW + 5000, TTL);

        var events = engine2.runOnce(NOW + 5000);
        // The new ETH opportunity is reported exactly once.
        assertEquals(1, events.stream()
                .filter(e -> e.type() == OpportunityEvent.Type.APPEARED).count());
        assertTrue(events.stream().anyMatch(e ->
                e.type() == OpportunityEvent.Type.APPEARED
                        && e.opportunity().signature().contains("ETH")));
        engine2.stop();
    }
}
