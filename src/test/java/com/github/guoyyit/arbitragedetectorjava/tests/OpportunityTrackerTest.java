package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.config.ArbitrageProperties;
import com.github.guoyyit.arbitragedetectorjava.lifecycle.OpportunityTracker;
import com.github.guoyyit.arbitragedetectorjava.model.ArbitrageOpportunity;
import com.github.guoyyit.arbitragedetectorjava.model.Hop;
import com.github.guoyyit.arbitragedetectorjava.model.OpportunityEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Flicker suppression: hysteresis band + grace TTL must stop an
 * opportunity from repeatedly appearing and disappearing under tiny
 * high-frequency fluctuations, while every transition is explained.
 */
class OpportunityTrackerTest {

    private ArbitrageProperties props() {
        // enter 0.01, exit 0.005, grace 3 s
        return new ArbitrageProperties(0.001, 0.0005, 0.01, 4,
                60_000, 1_000, 0.0001, 0.5, 3_000, "data");
    }

    private ArbitrageOpportunity op(String sig, double net, long snapshotVersion) {
        List<Hop> hops = List.of(
                new Hop("m2", "BTC", "USD", 103, 10),
                new Hop("m1", "USD", "BTC", 1.0 / 101, 1010));
        return new ArbitrageOpportunity(sig, hops, net + 0.003, net,
                10, snapshotVersion, snapshotVersion * 1000L);
    }

    @Test
    void hysteresisAndGraceTtlSuppressFlicker() {
        OpportunityTracker tracker = new OpportunityTracker();
        ArbitrageProperties p = props();

        var e1 = tracker.update(List.of(op("S", 0.020, 1)), p, 0);
        assertEquals(1, e1.size());
        assertEquals(OpportunityEvent.Type.APPEARED, e1.get(0).type());

        // Dips between exit (0.005) and enter (0.01): stays active, no event.
        assertTrue(tracker.update(List.of(op("S", 0.007, 2)), p, 100).isEmpty());
        assertEquals(1, tracker.activeCount());

        // Even below the exit threshold, the grace TTL keeps it alive.
        assertTrue(tracker.update(List.of(op("S", 0.004, 3)), p, 200).isEmpty());
        assertEquals(1, tracker.activeCount());

        // Recovers above enter threshold -> REFRESHED, never a duplicate APPEARED.
        var e4 = tracker.update(List.of(op("S", 0.020, 4)), p, 300);
        assertEquals(1, e4.size());
        assertEquals(OpportunityEvent.Type.REFRESHED, e4.get(0).type());
        assertEquals(1, tracker.activeCount());
    }

    @Test
    void highFrequencyNoiseAroundEnterThresholdNeverToggles() {
        OpportunityTracker tracker = new OpportunityTracker();
        ArbitrageProperties p = props();

        tracker.update(List.of(op("S", 0.011, 1)), p, 0);
        int appeared = 1;
        int disappeared = 0;
        // 20 cycles jittering right around the enter threshold, always above exit.
        for (int i = 1; i <= 20; i++) {
            double net = 0.01 + (i % 2 == 0 ? 0.001 : -0.001);
            var events = tracker.update(List.of(op("S", net, i + 1)), p, i * 100L);
            for (OpportunityEvent e : events) {
                if (e.type() == OpportunityEvent.Type.APPEARED) appeared++;
                if (e.type() == OpportunityEvent.Type.DISAPPEARED) disappeared++;
            }
        }
        assertEquals(1, appeared, "only one APPEARED despite noise");
        assertEquals(0, disappeared, "no DISAPPEARED while above exit threshold");
        assertEquals(1, tracker.activeCount());
    }

    @Test
    void disappearsAfterGraceTtlAndMayReappear() {
        OpportunityTracker tracker = new OpportunityTracker();
        ArbitrageProperties p = props();

        tracker.update(List.of(op("S", 0.020, 1)), p, 0);
        // Not detected for more than the grace TTL.
        var e2 = tracker.update(List.of(), p, 4_000);
        assertEquals(1, e2.size());
        assertEquals(OpportunityEvent.Type.DISAPPEARED, e2.get(0).type());
        assertNotNull(e2.get(0).reason());
        assertEquals(0, tracker.activeCount());

        // A later profitable cycle is a genuine new APPEARED.
        var e3 = tracker.update(List.of(op("S", 0.020, 5)), p, 4_000);
        assertEquals(1, e3.size());
        assertEquals(OpportunityEvent.Type.APPEARED, e3.get(0).type());
    }

    @Test
    void unknownOpportunityBelowEnterThresholdIsIgnored() {
        OpportunityTracker tracker = new OpportunityTracker();
        ArbitrageProperties p = props();
        var events = tracker.update(List.of(op("S", 0.007, 1)), p, 0);
        assertTrue(events.isEmpty());
        assertEquals(0, tracker.activeCount());
    }

    @Test
    void restoreRebuildsActiveSetFromEvents() {
        OpportunityTracker tracker = new OpportunityTracker();
        ArbitrageProperties p = props();
        var appeared = tracker.update(List.of(op("A", 0.02, 1)), p, 0);
        tracker.update(List.of(op("A", 0.02, 2), op("B", 0.03, 2)), p, 100);
        var goneB = tracker.update(List.of(op("A", 0.02, 40)), p, 4_000);
        // Only B disappears (undetected past grace TTL); A is still detected.
        assertEquals(1, goneB.size());
        assertEquals(OpportunityEvent.Type.DISAPPEARED, goneB.get(0).type());
        assertEquals("B", goneB.get(0).opportunity().signature());

        List<OpportunityEvent> journal = new java.util.ArrayList<>(appeared);
        // Simulate replaying only A-APPEARED + B-APPEARED + B-DISAPPEARED (event sourcing).
        OpportunityTracker rebuilt = new OpportunityTracker();
        rebuilt.restore(journal);
        assertEquals(1, rebuilt.activeCount());
        assertEquals("A", rebuilt.active().get(0).signature());
    }
}
