package com.github.guoyyit.arbitragedetectorjava.config;

/**
 * Immutable detector configuration. The engine swaps the whole instance
 * atomically, so a config change never applies mid-detection.
 */
public final class ArbitrageProperties {

    private final double feeRate;
    private final double slippageRate;
    private final double minProfit;
    private final int maxCycleLength;
    private final long quoteTtlMillis;
    private final long detectIntervalMillis;
    private final double minVolume;
    private final double exitHysteresis;
    private final long opportunityTtlMillis;
    private final String journalDir;

    public ArbitrageProperties(double feeRate, double slippageRate, double minProfit,
                               int maxCycleLength, long quoteTtlMillis,
                               long detectIntervalMillis, double minVolume,
                               double exitHysteresis, long opportunityTtlMillis,
                               String journalDir) {
        this.feeRate = feeRate;
        this.slippageRate = slippageRate;
        this.minProfit = minProfit;
        this.maxCycleLength = maxCycleLength;
        this.quoteTtlMillis = quoteTtlMillis;
        this.detectIntervalMillis = detectIntervalMillis;
        this.minVolume = minVolume;
        this.exitHysteresis = exitHysteresis;
        this.opportunityTtlMillis = opportunityTtlMillis;
        this.journalDir = journalDir;
    }

    public static ArbitrageProperties defaults() {
        return new ArbitrageProperties(
                0.001,   // feeRate: 0.1% per hop
                0.0005,  // slippageRate: 0.05% per hop
                0.001,   // minProfit: 0.1% net
                4,       // maxCycleLength
                5_000,   // quoteTtlMillis
                1_000,   // detectIntervalMillis
                0.0001,  // minVolume (start currency units)
                0.5,     // exitHysteresis: exit at minProfit * (1 - 0.5)
                30_000,  // opportunityTtlMillis
                "data"); // journalDir
    }

    public double feeRate() { return feeRate; }
    public double slippageRate() { return slippageRate; }
    public double minProfit() { return minProfit; }
    public int maxCycleLength() { return maxCycleLength; }
    public long quoteTtlMillis() { return quoteTtlMillis; }
    public long detectIntervalMillis() { return detectIntervalMillis; }
    public double minVolume() { return minVolume; }
    public double exitHysteresis() { return exitHysteresis; }
    public long opportunityTtlMillis() { return opportunityTtlMillis; }
    public String journalDir() { return journalDir; }
}
