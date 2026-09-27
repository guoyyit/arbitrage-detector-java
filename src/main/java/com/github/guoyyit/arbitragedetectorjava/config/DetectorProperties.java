package com.github.guoyyit.arbitragedetectorjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Runtime detector configuration bound from {@code arbitrage.*} properties.
 *
 * All accessors/mutators are synchronized and detection runs take one
 * {@link #copy()} under the same monitor, so a multi-field update can never be
 * observed half-applied: each run executes entirely against either the old or
 * the new values. Updates are validated by {@code ConfigService}; an invalid
 * update is rejected and leaves the previous configuration untouched.
 */
@Component
@ConfigurationProperties(prefix = "arbitrage")
public class DetectorProperties {
    /** Taker fee rate per hop, e.g. 0.001 = 0.1%. */
    private double feeRate = 0.001;
    /** Slippage rate per hop applied against the taker, e.g. 0.0005. */
    private double slippageRate = 0.0005;
    /** Minimum net rate (after fees/slippage) for an opportunity to be valid. */
    private double minProfitRate = 0.001;
    /** Maximum number of hops in a cycle (>= 2). */
    private int maxCycleLength = 3;
    /** Quotes older than this are excluded from snapshots. */
    private Duration quoteTtl = Duration.ofSeconds(5);
    /** Detection scheduling interval. */
    private Duration detectionInterval = Duration.ofMillis(200);
    /** Minimum executable start-currency volume for an opportunity to be valid. */
    private double minVolume = 0.0;
    /** Hysteresis: an active opportunity stays active until net rate drops below minProfitRate - exitMargin. */
    private double exitMargin = 0.0005;
    /** Minimum |delta netRate| that warrants an UPDATED audit event (0 = record every detection). */
    private double updateEventEpsilon = 1.0e-9;
    /** Journal file for crash recovery. */
    private String journalPath = "data/journal.log";

    public synchronized double getFeeRate() { return feeRate; }
    public synchronized void setFeeRate(double feeRate) { this.feeRate = feeRate; }
    public synchronized double getSlippageRate() { return slippageRate; }
    public synchronized void setSlippageRate(double slippageRate) { this.slippageRate = slippageRate; }
    public synchronized double getMinProfitRate() { return minProfitRate; }
    public synchronized void setMinProfitRate(double minProfitRate) { this.minProfitRate = minProfitRate; }
    public synchronized int getMaxCycleLength() { return maxCycleLength; }
    public synchronized void setMaxCycleLength(int maxCycleLength) { this.maxCycleLength = maxCycleLength; }
    public synchronized Duration getQuoteTtl() { return quoteTtl; }
    public synchronized void setQuoteTtl(Duration quoteTtl) { this.quoteTtl = quoteTtl; }
    public synchronized Duration getDetectionInterval() { return detectionInterval; }
    public synchronized void setDetectionInterval(Duration detectionInterval) { this.detectionInterval = detectionInterval; }
    public synchronized double getMinVolume() { return minVolume; }
    public synchronized void setMinVolume(double minVolume) { this.minVolume = minVolume; }
    public synchronized double getExitMargin() { return exitMargin; }
    public synchronized void setExitMargin(double exitMargin) { this.exitMargin = exitMargin; }
    public synchronized double getUpdateEventEpsilon() { return updateEventEpsilon; }
    public synchronized void setUpdateEventEpsilon(double updateEventEpsilon) { this.updateEventEpsilon = updateEventEpsilon; }
    public synchronized String getJournalPath() { return journalPath; }
    public synchronized void setJournalPath(String journalPath) { this.journalPath = journalPath; }

    /** Immutable deep copy so a detection run sees one consistent configuration. */
    public synchronized DetectorProperties copy() {
        DetectorProperties p = new DetectorProperties();
        p.feeRate = feeRate;
        p.slippageRate = slippageRate;
        p.minProfitRate = minProfitRate;
        p.maxCycleLength = maxCycleLength;
        p.quoteTtl = quoteTtl;
        p.detectionInterval = detectionInterval;
        p.minVolume = minVolume;
        p.exitMargin = exitMargin;
        p.updateEventEpsilon = updateEventEpsilon;
        p.journalPath = journalPath;
        return p;
    }
}
