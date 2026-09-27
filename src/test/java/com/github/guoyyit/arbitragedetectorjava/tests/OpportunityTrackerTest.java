package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.domain.Hop;
import com.github.guoyyit.arbitragedetectorjava.domain.Opportunity;
import com.github.guoyyit.arbitragedetectorjava.domain.OpportunityEvent;
import com.github.guoyyit.arbitragedetectorjava.opportunity.OpportunityTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpportunityTrackerTest {
    private OpportunityTracker tracker;
    private static final double MIN = 0.002;
    private static final double MARGIN = 0.001; // exit at 0.001
    private final Instant t0 = Instant.parse("2026-09-28T10:00:00Z");

    @BeforeEach
    void setUp() {
        tracker = new OpportunityTracker();
    }

    private List<OpportunityEvent> rec(List<Opportunity> found, Instant now) {
        return tracker.reconcile(found, MIN, MARGIN, 0.0, now);
    }

    private Opportunity opp(String id, double netRate) {
        Hop hop = new Hop("m", "BTC", "USDT", Hop.Side.SELL, 1, 1);
        return new Opportunity(id, List.of(hop), netRate + 0.001, netRate,
                1, t0, 1);
    }

    @Test
    void appearsWhenAboveEntryThreshold() {
        List<OpportunityEvent> events =
                rec(List.of(opp("X", 0.003)), t0);
        assertEquals(1, events.size());
        assertEquals(OpportunityEvent.Type.APPEARED, events.get(0).type());
        assertTrue(events.get(0).reason().contains("entry threshold"));
    }

    @Test
    void hysteresisSuppressesFlappingInTheBand() {
        // appears at 0.003
        rec(List.of(opp("X", 0.003)), t0);

        // dips to 0.0015: below entry (0.002) but above exit (0.001) -> stays
        List<OpportunityEvent> e1 = rec(List.of(opp("X", 0.0015)), t0.plusMillis(1));
        assertTrue(e1.stream().anyMatch(e -> e.type() == OpportunityEvent.Type.UPDATED),
                "movement inside the hysteresis band keeps the opportunity active");
        assertTrue(tracker.isActive("X"));

        // recovers above entry -> still no new APPEARED (no flapping)
        List<OpportunityEvent> e2 = rec(List.of(opp("X", 0.0025)), t0.plusMillis(2));
        assertTrue(e2.stream().noneMatch(e -> e.type() == OpportunityEvent.Type.APPEARED));
        assertTrue(tracker.isActive("X"));
    }

    @Test
    void disappearsOnlyAtOrBelowExitThreshold() {
        rec(List.of(opp("X", 0.003)), t0);
        // at exactly the exit threshold -> disappears
        List<OpportunityEvent> events = rec(List.of(opp("X", 0.0010)), t0.plusMillis(1));
        assertEquals(1, events.size());
        assertEquals(OpportunityEvent.Type.DISAPPEARED, events.get(0).type());
        assertTrue(events.get(0).reason().contains("exit threshold"));
        assertFalse(tracker.isActive("X"));

        // re-entering above the entry threshold emits a fresh APPEARED
        List<OpportunityEvent> again = rec(List.of(opp("X", 0.0031)), t0.plusMillis(2));
        assertEquals(OpportunityEvent.Type.APPEARED, again.get(0).type());
    }

    @Test
    void disappearsWhenCycleVanishesFromSnapshot() {
        rec(List.of(opp("X", 0.003), opp("Y", 0.004)), t0);
        List<OpportunityEvent> events =
                rec(List.of(opp("Y", 0.004)), t0.plusMillis(1));
        assertEquals(1, events.size());
        assertEquals(OpportunityEvent.Type.DISAPPEARED, events.get(0).type());
        assertEquals("X", events.get(0).opportunity().id());
        assertTrue(events.get(0).reason().contains("expired quote or book changed"));
    }

    @Test
    void auditTrailPreservesEveryLifecycleChangeInOrder() {
        rec(List.of(opp("X", 0.003)), t0);
        rec(List.of(opp("X", 0.0031)), t0.plusMillis(1));
        rec(List.of(), t0.plusMillis(2));
        List<OpportunityEvent> all = tracker.events();
        assertEquals(3, all.size());
        assertEquals(OpportunityEvent.Type.APPEARED, all.get(0).type());
        assertEquals(OpportunityEvent.Type.UPDATED, all.get(1).type());
        assertEquals(OpportunityEvent.Type.DISAPPEARED, all.get(2).type());
        // every event is explainable
        for (OpportunityEvent e : all) {
            assertNotNull(e.reason());
            assertFalse(e.reason().isBlank());
            assertNotNull(e.at());
        }
    }
}
