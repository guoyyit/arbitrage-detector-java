package com.github.guoyyit.arbitragedetectorjava.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Auditable lifecycle event of an opportunity: it APPEARED, was refreshed
 * (UPDATED, still profitable), or DISAPPEARED (fell below the exit threshold).
 */
public final class OpportunityEvent {
    public enum Type { APPEARED, UPDATED, DISAPPEARED }

    private final Type type;
    private final Opportunity opportunity;
    private final Instant at;
    private final String reason;

    public OpportunityEvent(Type type, Opportunity opportunity, Instant at, String reason) {
        this.type = Objects.requireNonNull(type, "type");
        this.opportunity = Objects.requireNonNull(opportunity, "opportunity");
        this.at = Objects.requireNonNull(at, "at");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Type type() { return type; }
    public Opportunity opportunity() { return opportunity; }
    public Instant at() { return at; }
    public String reason() { return reason; }
}
