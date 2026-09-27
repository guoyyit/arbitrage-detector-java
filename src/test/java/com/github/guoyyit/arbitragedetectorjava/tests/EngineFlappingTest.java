package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.config.DetectorProperties;
import com.github.guoyyit.arbitragedetectorjava.detect.ArbitrageDetector;
import com.github.guoyyit.arbitragedetectorjava.domain.OpportunityEvent;
import com.github.guoyyit.arbitragedetectorjava.domain.Quote;
import com.github.guoyyit.arbitragedetectorjava.engine.DetectionEngine;
import com.github.guoyyit.arbitragedetectorjava.input.QuoteIngester;
import com.github.guoyyit.arbitragedetectorjava.opportunity.OpportunityTracker;
import com.github.guoyyit.arbitragedetectorjava.persistence.StateJournal;
import com.github.guoyyit.arbitragedetectorjava.quote.QuoteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EngineFlappingTest {

    @TempDir
    Path tmp;

    private record Stack(QuoteIngester ingester, DetectionEngine engine,
                         OpportunityTracker tracker, DetectorProperties props) {
    }

    private Stack build(Path f) {
        DetectorProperties p = new DetectorProperties();
        p.setFeeRate(0.001);
        p.setSlippageRate(0.0005);
        p.setMinProfitRate(0.002);
        p.setExitMargin(0.001);
        p.setUpdateEventEpsilon(0.0001); // ignore tiny rate moves in the audit log
        p.setMaxCycleLength(2);
        p.setQuoteTtl(Duration.ofHours(1));
        p.setJournalPath(f.toString());
        QuoteStore store = new QuoteStore();
        StateJournal journal = new StateJournal(f);
        QuoteIngester ingester = new QuoteIngester(store, p, journal, Clock.systemUTC());
        OpportunityTracker tracker = new OpportunityTracker();
        DetectionEngine engine = new DetectionEngine(store, new ArbitrageDetector(),
                tracker, journal, p, ingester, Clock.systemUTC());
        return new Stack(ingester, engine, tracker, p);
    }

    private void pushBook(Stack s, double bidA, double askB, Instant t) {
        s.ingester().ingest(new Quote("market-a", "BTC", "USDT", bidA, bidA + 10,
                5, 5, t, -1L), t);
        s.ingester().ingest(new Quote("market-b", "BTC", "USDT", askB - 10, askB,
                5, 5, t, -1L), t);
    }

    @Test
    void highFrequencyMicroWobbleDoesNotFlapOpportunity() throws Exception {
        Path f = tmp.resolve("flap.log");
        Stack s = build(f);
        Instant t = Instant.now();

        // clearly profitable book -> APPEARED
        pushBook(s, 60000, 59000, t);
        List<OpportunityEvent> first = s.engine().runOnce();
        assertEquals(1, first.size());
        assertEquals(OpportunityEvent.Type.APPEARED, first.get(0).type());
        String id = first.get(0).opportunity().id();

        // 40 detections with micro-wobbles around the same prices: the cycle
        // stays profitable the whole time but never crosses the exit band
        int appearOrDisappear = 0;
        int totalEvents = 0;
        for (int i = 1; i <= 40; i++) {
            double wiggle = (i % 2 == 0 ? 1 : -1) * 3.0; // +/- 3 USD
            pushBook(s, 60000 + wiggle, 59000 + wiggle * 0.5, t.plusMillis(i));
            List<OpportunityEvent> events = s.engine().runOnce();
            totalEvents += events.size();
            for (OpportunityEvent e : events) {
                if (e.type() != OpportunityEvent.Type.UPDATED) {
                    appearOrDisappear++;
                }
            }
        }
        assertTrue(s.tracker().isActive(id));
        assertEquals(0, appearOrDisappear,
                "micro-wobbles must not cause APPEARED/DISAPPEARED churn");
        // epsilon additionally keeps the audit trail small (moves are < 1e-4)
        assertEquals(0, totalEvents, "tiny rate moves should not even log UPDATED");

        // now a real adverse move through the exit band -> exactly one DISAPPEARED
        pushBook(s, 59100, 59050, t.plusMillis(100));
        List<OpportunityEvent> end = s.engine().runOnce();
        assertEquals(1, end.size());
        assertEquals(OpportunityEvent.Type.DISAPPEARED, end.get(0).type());
        assertEquals(id, end.get(0).opportunity().id());
        assertFalse(s.tracker().isActive(id));
    }

    @Test
    void expiredQuotesRemoveOpportunityWithExplainableEvent() throws Exception {
        Path f = tmp.resolve("ttl-engine.log");
        MutableClock clock = new MutableClock(Instant.parse("2026-09-28T10:00:00Z"));
        DetectorProperties p = new DetectorProperties();
        p.setFeeRate(0.001);
        p.setSlippageRate(0.0005);
        p.setMinProfitRate(0.001);
        p.setExitMargin(0.0005);
        p.setMaxCycleLength(2);
        p.setQuoteTtl(Duration.ofSeconds(1));
        p.setJournalPath(f.toString());
        QuoteStore store = new QuoteStore();
        StateJournal journal = new StateJournal(f);
        QuoteIngester ingester = new QuoteIngester(store, p, journal, clock);
        OpportunityTracker tracker = new OpportunityTracker();
        DetectionEngine engine = new DetectionEngine(store, new ArbitrageDetector(),
                tracker, journal, p, ingester, clock);

        Instant t = clock.instant();
        ingester.ingest(new Quote("market-a", "BTC", "USDT", 60000, 60010, 5, 5, t, -1L), t);
        ingester.ingest(new Quote("market-b", "BTC", "USDT", 58900, 59000, 5, 5, t, -1L), t);
        assertEquals(1, engine.runOnce().size());

        // advance the simulated clock past the TTL: snapshot empties and the
        // opportunity is retracted with an explanatory event
        clock.advance(Duration.ofSeconds(2));
        List<OpportunityEvent> events = engine.runOnce();
        assertTrue(events.stream().anyMatch(e -> e.type() == OpportunityEvent.Type.DISAPPEARED));
        assertTrue(events.stream().filter(e -> e.type() == OpportunityEvent.Type.DISAPPEARED)
                .findFirst().orElseThrow().reason().contains("expired quote or book changed"));
        journal.close();
    }

    /** Fixed-then-advancable UTC clock for deterministic time-based tests. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
