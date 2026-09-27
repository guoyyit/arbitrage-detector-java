package com.github.guoyyit.arbitragedetectorjava.model;

/**
 * Auditable lifecycle transition of an arbitrage opportunity.
 *
 * @param type        transition kind
 * @param opportunity the opportunity state at transition time
 * @param reason      human-readable explanation of the transition
 * @param timestamp   wall-clock time of the transition, epoch millis
 */
public record OpportunityEvent(Type type, ArbitrageOpportunity opportunity,
                               String reason, long timestamp) {

    public enum Type {
        /** Opportunity crossed the enter threshold and was reported. */
        APPEARED,
        /** Still active; metrics refreshed by a newer snapshot. */
        REFRESHED,
        /** Dropped below the exit threshold or expired. */
        DISAPPEARED
    }
}
