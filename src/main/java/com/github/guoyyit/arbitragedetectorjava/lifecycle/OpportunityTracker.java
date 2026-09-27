package com.github.guoyyit.arbitragedetectorjava.lifecycle;

import com.github.guoyyit.arbitragedetectorjava.config.ArbitrageProperties;
import com.github.guoyyit.arbitragedetectorjava.model.ArbitrageOpportunity;
import com.github.guoyyit.arbitragedetectorjava.model.OpportunityEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stabilizes the opportunity set across detection cycles and emits
 * auditable lifecycle events.
 *
 * <p>Flicker suppression uses two mechanisms:
 * <ul>
 *   <li><b>Hysteresis:</b> a new opportunity must exceed
 *       {@code minProfit} to appear, but an active one only disappears
 *       after falling below {@code minProfit * (1 - exitHysteresis)}.</li>
 *   <li><b>Grace TTL:</b> an active opportunity that is momentarily not
 *       detected survives until {@code opportunityTtlMillis} has passed
 *       since it was last detected.</li>
 * </ul>
 *
 * <p>Events: APPEARED (crossed enter threshold), REFRESHED (active,
 * re-crossed enter threshold after dipping below it), DISAPPEARED
 * (not detected for longer than the grace TTL).
 *
 * <p>State can be rebuilt by replaying the journal via {@link #restore}.
 * All public methods are synchronized; the engine calls {@link #update}
 * from a single thread while query endpoints may read concurrently.
 */
public class OpportunityTracker {

    private static final class Tracked {
        ArbitrageOpportunity opportunity;
        long lastSeenMillis;
        boolean hot = true; // currently above the enter threshold

        Tracked(ArbitrageOpportunity opportunity, long lastSeenMillis) {
            this.opportunity = opportunity;
            this.lastSeenMillis = lastSeenMillis;
        }
    }

    private final Map<String, Tracked> active = new LinkedHashMap<>();

    /**
     * Fold one detection cycle into the tracked set.
     *
     * @param candidates opportunities whose net return exceeds the
     *                   <i>exit</i> threshold (the engine runs the
     *                   detector with that lowered bound)
     * @return lifecycle events produced by this cycle, in stable order
     */
    public synchronized List<OpportunityEvent> update(List<ArbitrageOpportunity> candidates,
                                                      ArbitrageProperties config, long nowMillis) {
        List<OpportunityEvent> events = new ArrayList<>();
        double enter = config.minProfit();
        double exit = enter * (1.0 - config.exitHysteresis());

        for (ArbitrageOpportunity op : candidates) {
            Tracked t = active.get(op.signature());
            boolean aboveEnter = op.netReturn() > enter;
            if (t == null) {
                if (aboveEnter) {
                    active.put(op.signature(), new Tracked(op, nowMillis));
                    events.add(new OpportunityEvent(OpportunityEvent.Type.APPEARED, op,
                            "net return %.6f exceeded enter threshold %.6f"
                                    .formatted(op.netReturn(), enter), nowMillis));
                }
                // Below enter threshold and unknown: ignore (hysteresis).
                continue;
            }
            t.opportunity = op;
            t.lastSeenMillis = nowMillis;
            if (aboveEnter && !t.hot) {
                t.hot = true;
                events.add(new OpportunityEvent(OpportunityEvent.Type.REFRESHED, op,
                        "net return %.6f rose back above enter threshold %.6f"
                                .formatted(op.netReturn(), enter), nowMillis));
            } else if (!aboveEnter) {
                t.hot = false; // dip tolerated while above exit threshold
            }
        }

        var it = active.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Tracked t = e.getValue();
            if (nowMillis - t.lastSeenMillis > config.opportunityTtlMillis()) {
                it.remove();
                events.add(new OpportunityEvent(OpportunityEvent.Type.DISAPPEARED, t.opportunity,
                        "not detected for %d ms; last net return %.6f fell below exit threshold %.6f or volume collapsed"
                                .formatted(nowMillis - t.lastSeenMillis,
                                        t.opportunity.netReturn(), exit), nowMillis));
            }
        }
        return List.copyOf(events);
    }

    /** Rebuild state from journaled events after a restart. */
    public synchronized void restore(List<OpportunityEvent> events) {
        for (OpportunityEvent e : events) {
            switch (e.type()) {
                case APPEARED, REFRESHED -> {
                    Tracked t = new Tracked(e.opportunity(), e.timestamp());
                    t.hot = true;
                    active.put(e.opportunity().signature(), t);
                }
                case DISAPPEARED -> active.remove(e.opportunity().signature());
            }
        }
    }

    /** Signatures reported but not yet disappeared; used to suppress re-reporting. */
    public synchronized List<ArbitrageOpportunity> active() {
        return active.values().stream().map(t -> t.opportunity).toList();
    }

    public synchronized int activeCount() {
        return active.size();
    }
}
