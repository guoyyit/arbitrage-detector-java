package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.config.DetectorProperties;
import com.github.guoyyit.arbitragedetectorjava.domain.Hop;
import com.github.guoyyit.arbitragedetectorjava.domain.Opportunity;
import com.github.guoyyit.arbitragedetectorjava.domain.OpportunityEvent;
import com.github.guoyyit.arbitragedetectorjava.domain.Quote;
import com.github.guoyyit.arbitragedetectorjava.detect.ArbitrageDetector;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {

    @TempDir
    Path tmp;

    private DetectorProperties props(Path journal) {
        DetectorProperties p = new DetectorProperties();
        p.setFeeRate(0.001);
        p.setSlippageRate(0.0005);
        p.setMinProfitRate(0.001);
        p.setExitMargin(0.0005);
        p.setMaxCycleLength(2);
        p.setQuoteTtl(Duration.ofHours(1));
        p.setDetectionInterval(Duration.ofMinutes(1));
        p.setJournalPath(journal.toString());
        return p;
    }

    private Quote btcQuote(String market, double bid, double ask, Instant t) {
        return new Quote(market, "BTC", "USDT", bid, ask, 5, 5, t, -1L);
    }

    private record Stack(QuoteStore store, QuoteIngester ingester, StateJournal journal,
                         OpportunityTracker tracker, DetectionEngine engine) {
    }

    private Stack buildStack(Path journalFile, DetectorProperties p) {
        QuoteStore store = new QuoteStore();
        StateJournal journal = new StateJournal(journalFile);
        QuoteIngester ingester = new QuoteIngester(store, p, journal, Clock.systemUTC());
        OpportunityTracker tracker = new OpportunityTracker();
        DetectionEngine engine = new DetectionEngine(store, new ArbitrageDetector(),
                tracker, journal, p, ingester, Clock.systemUTC());
        return new Stack(store, ingester, journal, tracker, engine);
    }

    @Test
    void journalRoundTripsQuotesEventsAndCheckpoint() throws Exception {
        Path f = tmp.resolve("j.log");
        StateJournal j1 = new StateJournal(f);
        Instant t = Instant.now();
        j1.appendQuote(new Quote("m1", "BTC", "USDT", 1, 2, 5, 5, t, 1));
        Hop hop = new Hop("m1", "BTC", "USDT", Hop.Side.SELL, 1, 1);
        Opportunity op = new Opportunity("id-1", List.of(hop), 0.01, 0.008, 1, t, 7);
        j1.appendEvent(new OpportunityEvent(OpportunityEvent.Type.APPEARED, op, t, "test"));
        j1.appendCheckpoint(42, 7, 1);
        j1.close();

        // "restart": fresh journal instance over the same file
        StateJournal j2 = new StateJournal(f);
        StateJournal.RecoveryState rs = j2.recover();
        assertEquals(1, rs.quotes().size());
        assertEquals("m1", rs.quotes().get(0).market());
        assertEquals(1, rs.maxIngestSeq());
        assertEquals(42, rs.checkpointIngestSeq());
        assertEquals(7, rs.checkpointSnapshotVersion());
        assertEquals(1, rs.priorEvents().size());
        assertEquals("id-1", rs.priorEvents().get(0).opportunity().id());
        assertEquals(java.util.Set.of("id-1"), rs.activeIds());
        // recovery is idempotent
        StateJournal.RecoveryState again = j2.recover();
        assertEquals(1, again.quotes().size());
    }

    @Test
    void crashRecoveryDoesNotDuplicateOrMissOpportunities() throws Exception {
        Path f = tmp.resolve("engine.log");
        DetectorProperties p = props(f);
        Instant t = Instant.now();

        // --- first process lifetime ---
        Stack s1 = buildStack(f, p);
        s1.ingester.ingest(btcQuote("market-a", 60000, 60010, t));
        s1.ingester.ingest(btcQuote("market-b", 58900, 59000, t));
        List<OpportunityEvent> run1 = s1.engine.runOnce();
        assertEquals(1, run1.size());
        assertEquals(OpportunityEvent.Type.APPEARED, run1.get(0).type());
        String cycleId = run1.get(0).opportunity().id();
        assertEquals(2, s1.ingester.lastIngestSeq());

        // a new, different profitable cycle emerges but detection never runs
        // before the crash (interruption window)
        s1.ingester.ingest(new Quote("market-c", "ETH", "USDT", 60000, 60010, 5, 5,
                t, -1L));
        s1.ingester.ingest(new Quote("market-d", "ETH", "USDT", 58900, 59000, 5, 5,
                t, -1L));
        assertEquals(4, s1.ingester.lastIngestSeq());

        // --- simulated crash + restart: brand-new components, same journal ---
        Stack s2 = buildStack(f, p);
        s2.engine.recover(); // @PostConstruct in a real boot
        assertEquals(4, s2.ingester.lastIngestSeq(),
                "ingest sequence must be restored and never move backwards");

        List<OpportunityEvent> run2 = s2.engine.runOnce();
        long appearedBtc = run2.stream()
                .filter(e -> e.type() == OpportunityEvent.Type.APPEARED
                        && e.opportunity().id().equals(cycleId))
                .count();
        assertEquals(0, appearedBtc,
                "already-reported opportunity must not be reported again");
        long appearedEth = run2.stream()
                .filter(e -> e.type() == OpportunityEvent.Type.APPEARED
                        && e.opportunity().id().contains("ETH"))
                .count();
        assertEquals(1, appearedEth,
                "opportunity emerging during interruption must be reported once");

        // active set holds both, and audit history contains prior APPEARED
        assertEquals(2, s2.tracker.activeOpportunities().size());
        assertTrue(s2.tracker.events().stream()
                .anyMatch(e -> e.type() == OpportunityEvent.Type.APPEARED
                        && e.opportunity().id().equals(cycleId)));

        // a further restart must stay idempotent
        Stack s3 = buildStack(f, p);
        s3.engine.recover();
        List<OpportunityEvent> run3 = s3.engine.runOnce();
        assertTrue(run3.stream().noneMatch(e -> e.type() == OpportunityEvent.Type.APPEARED),
                "second restart must not re-report any opportunity");
        assertEquals(2, s3.tracker.activeOpportunities().size());
    }

    @Test
    void opportunityThatDisappearedBeforeCrashIsNotRestoredAsActive() throws Exception {
        Path f = tmp.resolve("gone.log");
        DetectorProperties p = props(f);
        Instant t = Instant.now();
        Stack s1 = buildStack(f, p);
        s1.ingester.ingest(btcQuote("market-a", 60000, 60010, t));
        s1.ingester.ingest(btcQuote("market-b", 58900, 59000, t));
        List<OpportunityEvent> first = s1.engine.runOnce();
        String id = first.get(0).opportunity().id();

        // book converges: opportunity drops below exit threshold and is retracted
        s1.ingester.ingest(btcQuote("market-a", 59001, 59002, t.plusMillis(10)));
        List<OpportunityEvent> second = s1.engine.runOnce();
        assertTrue(second.stream().anyMatch(e ->
                e.type() == OpportunityEvent.Type.DISAPPEARED && e.opportunity().id().equals(id)));
        assertFalse(s1.tracker.isActive(id));

        // crash + restart: it must not come back to life
        Stack s2 = buildStack(f, p);
        s2.engine.recover();
        s2.engine.runOnce();
        assertFalse(s2.tracker.isActive(id));
    }
}
