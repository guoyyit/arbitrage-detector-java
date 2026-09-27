package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.config.ArbitrageProperties;
import com.github.guoyyit.arbitragedetectorjava.detect.ArbitrageDetector;
import com.github.guoyyit.arbitragedetectorjava.engine.DetectionEngine;
import com.github.guoyyit.arbitragedetectorjava.lifecycle.OpportunityTracker;
import com.github.guoyyit.arbitragedetectorjava.model.OpportunityEvent;
import com.github.guoyyit.arbitragedetectorjava.model.Quote;
import com.github.guoyyit.arbitragedetectorjava.state.QuoteBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Configuration changes apply atomically between cycles: a cycle always
 * runs entirely on one config, and a given quote history + config history
 * always reproduces the identical event stream.
 */
class EngineConfigChangeTest {

    private static final long NOW = 1_000_000L;
    private static final long TTL = 3_600_000L;

    @TempDir
    Path dir;

    private ArbitrageProperties props(double minProfit, int maxCycleLength) {
        return props(minProfit, maxCycleLength, dir);
    }

    private ArbitrageProperties props(double minProfit, int maxCycleLength, Path journalDir) {
        return new ArbitrageProperties(0.001, 0.0005, minProfit, maxCycleLength,
                TTL, 1_000, 0.0001, 0.5, 30_000, journalDir.toString());
    }

    private static void submitSmallGap(QuoteBook book, long seq, long ts) {
        // net return ~0.69%: below 1% minProfit, above 0.5%.
        book.submit(new Quote("m1", "BTC", "USD", 100, 101, 10, 10, seq, ts), ts, TTL);
        book.submit(new Quote("m2", "BTC", "USD", 102, 103, 10, 10, seq, ts), ts, TTL);
    }

    @Test
    void configChangeAppliesCleanlyOnNextCycle() {
        QuoteBook book = new QuoteBook();
        DetectionEngine engine = new DetectionEngine(book, new ArbitrageDetector(),
                new OpportunityTracker(), props(0.01, 4));
        submitSmallGap(book, 1, NOW);

        // With minProfit 1% the ~0.69% gap is not an opportunity.
        assertTrue(engine.runOnce(NOW).isEmpty());
        assertEquals(0, engine.tracker().activeCount());

        // Lower the threshold: the very next cycle reports it.
        engine.updateConfig(props(0.005, 4));
        var events = engine.runOnce(NOW + 1000);
        assertEquals(1, events.size());
        assertEquals(OpportunityEvent.Type.APPEARED, events.get(0).type());

        // Raise it again: no duplicate APPEARED, opportunity stays tracked
        // (hysteresis band follows the new config).
        engine.updateConfig(props(0.01, 4));
        assertTrue(engine.runOnce(NOW + 2000).stream()
                .noneMatch(e -> e.type() == OpportunityEvent.Type.APPEARED));
        engine.stop();
    }

    @Test
    void maxCycleLengthChangeTakesEffectImmediately() {
        QuoteBook book = new QuoteBook();
        DetectionEngine engine = new DetectionEngine(book, new ArbitrageDetector(),
                new OpportunityTracker(), props(0.001, 2));
        // Only a 3-hop cycle exists in this market.
        book.submit(new Quote("m1", "EUR", "USD", 1.25, 1.26, 1000, 1000, 1, NOW), NOW, TTL);
        book.submit(new Quote("m2", "EUR", "JPY", 160, 161, 1000, 1000, 1, NOW), NOW, TTL);
        book.submit(new Quote("m3", "JPY", "USD", 0.0068, 0.0069, 1_000_000, 1_000_000, 1, NOW), NOW, TTL);

        assertTrue(engine.runOnce(NOW).isEmpty(), "3-hop cycle must be ignored at maxCycleLength=2");

        engine.updateConfig(props(0.001, 3));
        var events = engine.runOnce(NOW + 1000);
        assertEquals(1, events.size());
        assertEquals(3, events.get(0).opportunity().hops().size());
        engine.stop();
    }

    @Test
    void sameHistoryReproducesIdenticalEventStream() {
        // Each replay starts from a fresh journal dir; suppression of
        // re-reporting over an existing journal is covered by RecoveryTest.
        List<List<OpportunityEvent>> run1 = scriptedRun(dir.resolve("run1"));
        List<List<OpportunityEvent>> run2 = scriptedRun(dir.resolve("run2"));
        assertEquals(run1, run2, "same quote+config history must reproduce identical events");
    }

    private List<List<OpportunityEvent>> scriptedRun(Path journalDir) {
        QuoteBook book = new QuoteBook();
        ArbitrageProperties start = new ArbitrageProperties(0.001, 0.0005, 0.01, 4,
                TTL, 1_000, 0.0001, 0.5, 30_000, journalDir.toString());
        DetectionEngine engine = new DetectionEngine(book, new ArbitrageDetector(),
                new OpportunityTracker(), start);
        List<List<OpportunityEvent>> all = new java.util.ArrayList<>();
        submitSmallGap(book, 1, NOW);
        all.add(engine.runOnce(NOW));
        engine.updateConfig(props(0.005, 4, journalDir));
        submitSmallGap(book, 2, NOW + 1000);
        all.add(engine.runOnce(NOW + 1000));
        submitSmallGap(book, 3, NOW + 2000);
        all.add(engine.runOnce(NOW + 2000));
        engine.stop();
        return all;
    }
}
