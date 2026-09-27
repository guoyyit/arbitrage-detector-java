package com.github.guoyyit.arbitragedetectorjava.opportunity;

import com.github.guoyyit.arbitragedetectorjava.domain.Opportunity;
import com.github.guoyyit.arbitragedetectorjava.domain.OpportunityEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks the active opportunity set across successive detections.
 *
 * Flapping suppression uses hysteresis with two thresholds:
 * <ul>
 *   <li>a cycle <em>appears</em> only when its net rate is strictly above the
 *       entry threshold {@code minProfitRate};</li>
 *   <li>an active cycle <em>disappears</em> only when its net rate falls to or
 *       below {@code minProfitRate - exitMargin} (or it is no longer present in
 *       the snapshot). Small high-frequency oscillations between the two
 *       thresholds therefore neither emit nor retract an opportunity.</li>
 * </ul>
 * Every set change is emitted as an {@link OpportunityEvent} with a reason, and
 * all events are retained in order as the audit trail. All state changes are
 * synchronized so detections on one thread and API reads on another never see a
 * half-reconciled set.
 */
@Component
public class OpportunityTracker {
    private final Map<String, Opportunity> active = new LinkedHashMap<>();
    private final List<OpportunityEvent> events = new ArrayList<>();

    /**
     * Reconcile the opportunities found in one detection against the active set.
     *
     * @param found opportunities found in the current snapshot (already above the
     *              entry threshold, in deterministic order)
     * @param updateEventEpsilon minimum |delta netRate| for an UPDATED audit
     *                           event (tiny high-frequency wiggles are still
     *                           tracked but do not spam the audit trail)
     * @return lifecycle events emitted by this reconciliation
     */
    public synchronized List<OpportunityEvent> reconcile(List<Opportunity> found,
                                                         double minProfitRate,
                                                         double exitMargin,
                                                         double updateEventEpsilon,
                                                         Instant now) {
        double exitThreshold = minProfitRate - exitMargin;
        List<OpportunityEvent> emitted = new ArrayList<>();

        // index current findings; first occurrence wins, but ids are structural
        Map<String, Opportunity> foundById = new LinkedHashMap<>();
        for (Opportunity o : found) {
            foundById.putIfAbsent(o.id(), o);
        }

        // 1) classify every found id first, so an id never emits both UPDATED
        //    and DISAPPEARED in the same reconciliation
        java.util.List<String> toDisappear = new ArrayList<>();
        for (Map.Entry<String, Opportunity> e : foundById.entrySet()) {
            Opportunity fresh = e.getValue();
            if (fresh.netRate() <= exitThreshold) {
                toDisappear.add(e.getKey());
            }
        }
        for (Opportunity current : new ArrayList<>(active.values())) {
            if (!foundById.containsKey(current.id())) {
                toDisappear.add(current.id());
            }
        }

        // 2) apply + emit appearances / updates / disappearances
        for (String id : toDisappear) {
            Opportunity gone = active.remove(id);
            Opportunity fresh = foundById.get(id);
            String reason;
            if (fresh != null) {
                reason = String.format(
                        "net rate %.6f fell to/below exit threshold %.6f",
                        fresh.netRate(), exitThreshold);
                gone = fresh;
            } else {
                reason = "no profitable cycle with this identity in the current "
                        + "snapshot (expired quote or book changed)";
            }
            emitted.add(addEvent(OpportunityEvent.Type.DISAPPEARED, gone, now, reason));
        }
        for (Opportunity o : foundById.values()) {
            if (toDisappear.contains(o.id())) {
                continue;
            }
            Opportunity previous = active.get(o.id());
            active.put(o.id(), o);
            if (previous == null) {
                emitted.add(addEvent(OpportunityEvent.Type.APPEARED, o, now,
                        String.format("net rate %.6f above entry threshold %.6f",
                                o.netRate(), minProfitRate)));
            } else if (Math.abs(o.netRate() - previous.netRate()) > updateEventEpsilon) {
                emitted.add(addEvent(OpportunityEvent.Type.UPDATED, o, now,
                        String.format("still active; net rate %.6f -> %.6f",
                                previous.netRate(), o.netRate())));
            }
        }
        return emitted;
    }

    /**
     * First reconciliation after a crash.
     * <ul>
     *   <li>ids in {@code restoredActiveIds} that are found again are refreshed
     *       silently - they were reported before the crash, so no duplicate
     *       APPEARED is emitted;</li>
     *   <li>restored ids found at/below the exit threshold, or absent from the
     *       snapshot, emit DISAPPEARED;</li>
     *   <li>found ids that were not active at crash emit APPEARED (opportunities
     *       that emerged during the interruption are not missed).</li>
     * </ul>
     */
    public synchronized List<OpportunityEvent> reconcileAfterRecovery(
            List<Opportunity> found, Set<String> restoredActiveIds,
            double minProfitRate, double exitMargin, Instant now) {
        double exitThreshold = minProfitRate - exitMargin;
        List<OpportunityEvent> emitted = new ArrayList<>();
        Map<String, Opportunity> foundById = new LinkedHashMap<>();
        for (Opportunity o : found) {
            foundById.putIfAbsent(o.id(), o);
        }

        for (Opportunity o : foundById.values()) {
            if (restoredActiveIds.contains(o.id())) {
                if (o.netRate() <= exitThreshold) {
                    active.remove(o.id());
                    emitted.add(addEvent(OpportunityEvent.Type.DISAPPEARED, o, now,
                            String.format("after recovery net rate %.6f is at/below "
                                    + "exit threshold %.6f", o.netRate(), exitThreshold)));
                } else {
                    active.put(o.id(), o); // silent refresh
                }
            } else if (o.netRate() > minProfitRate) {
                active.put(o.id(), o);
                emitted.add(addEvent(OpportunityEvent.Type.APPEARED, o, now,
                        String.format("new after recovery; net rate %.6f above "
                                + "entry threshold %.6f", o.netRate(), minProfitRate)));
            }
        }
        for (String id : restoredActiveIds) {
            if (!foundById.containsKey(id) && active.containsKey(id)) {
                Opportunity gone = active.remove(id);
                emitted.add(addEvent(OpportunityEvent.Type.DISAPPEARED, gone, now,
                        "absent from the first snapshot after recovery"));
            }
        }
        return emitted;
    }

    /**
     * the given active ids are accepted as previously reported so they are not
     * reported twice, and the prior journal events become the audit history.
     */
    public synchronized void restore(List<OpportunityEvent> priorEvents,
                                     Set<String> activeIds,
                                     List<Opportunity> knownOpportunities) {
        events.clear();
        active.clear();
        events.addAll(priorEvents);
        Map<String, Opportunity> byId = new LinkedHashMap<>();
        for (OpportunityEvent e : priorEvents) {
            if (e.type() != OpportunityEvent.Type.DISAPPEARED) {
                byId.put(e.opportunity().id(), e.opportunity());
            } else {
                byId.remove(e.opportunity().id());
            }
        }
        for (Opportunity o : knownOpportunities) {
            byId.put(o.id(), o);
        }
        for (String id : activeIds) {
            Opportunity o = byId.get(id);
            if (o != null) {
                active.put(id, o);
            }
        }
    }

    /**
     * Replace the payload for an already-restored active id with the freshest
     * object produced by the first post-recovery detection, without emitting a
     * new event (it was already reported before the crash).
     */
    public synchronized void replaceIfActive(Opportunity o) {
        if (active.containsKey(o.id())) {
            active.put(o.id(), o);
        }
    }

    public synchronized boolean isActive(String id) {
        return active.containsKey(id);
    }

    public synchronized List<Opportunity> activeOpportunities() {
        return List.copyOf(active.values());
    }

    public synchronized List<OpportunityEvent> events() {
        return List.copyOf(events);
    }

    public synchronized void clear() {
        active.clear();
        events.clear();
    }

    private OpportunityEvent addEvent(OpportunityEvent.Type type, Opportunity o,
                                      Instant now, String reason) {
        OpportunityEvent event = new OpportunityEvent(type, o, now, reason);
        events.add(event);
        return event;
    }
}
