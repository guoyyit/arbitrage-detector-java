package com.github.guoyyit.arbitragedetectorjava.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A detected arbitrage cycle with economics. {@code id} is a deterministic
 * identity derived from the cycle structure (market+pair+side per hop), so the
 * same cycle maps to the same id across detections and restarts.
 */
public final class Opportunity {
    private final String id;
    private final List<Hop> hops;
    private final double grossRate;
    private final double netRate;
    private final double maxVolume;
    private final Instant detectedAt;
    private final long snapshotVersion;

    public Opportunity(String id, List<Hop> hops, double grossRate, double netRate,
                       double maxVolume, Instant detectedAt, long snapshotVersion) {
        this.id = Objects.requireNonNull(id, "id");
        this.hops = List.copyOf(hops);
        this.grossRate = grossRate;
        this.netRate = netRate;
        this.maxVolume = maxVolume;
        this.detectedAt = Objects.requireNonNull(detectedAt, "detectedAt");
        this.snapshotVersion = snapshotVersion;
    }

    public String id() { return id; }
    public List<Hop> hops() { return hops; }
    public double grossRate() { return grossRate; }
    public double netRate() { return netRate; }
    public double maxVolume() { return maxVolume; }
    public Instant detectedAt() { return detectedAt; }
    public long snapshotVersion() { return snapshotVersion; }
}
