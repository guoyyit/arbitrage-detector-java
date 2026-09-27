package com.github.guoyyit.arbitragedetectorjava.config;

/**
 * Mutable view of {@link ArbitrageProperties} for the REST API.
 * Null fields in a PUT body mean "keep current value".
 */
public class ConfigDto {
    private Double feeRate;
    private Double slippageRate;
    private Double minProfit;
    private Integer maxCycleLength;
    private Long quoteTtlMillis;
    private Long detectIntervalMillis;
    private Double minVolume;
    private Double exitHysteresis;
    private Long opportunityTtlMillis;
    private String journalDir;

    public static ConfigDto of(ArbitrageProperties c) {
        ConfigDto d = new ConfigDto();
        d.feeRate = c.feeRate();
        d.slippageRate = c.slippageRate();
        d.minProfit = c.minProfit();
        d.maxCycleLength = c.maxCycleLength();
        d.quoteTtlMillis = c.quoteTtlMillis();
        d.detectIntervalMillis = c.detectIntervalMillis();
        d.minVolume = c.minVolume();
        d.exitHysteresis = c.exitHysteresis();
        d.opportunityTtlMillis = c.opportunityTtlMillis();
        d.journalDir = c.journalDir();
        return d;
    }

    public ArbitrageProperties merged(ArbitrageProperties c) {
        validate();
        return new ArbitrageProperties(
                feeRate != null ? feeRate : c.feeRate(),
                slippageRate != null ? slippageRate : c.slippageRate(),
                minProfit != null ? minProfit : c.minProfit(),
                maxCycleLength != null ? maxCycleLength : c.maxCycleLength(),
                quoteTtlMillis != null ? quoteTtlMillis : c.quoteTtlMillis(),
                detectIntervalMillis != null ? detectIntervalMillis : c.detectIntervalMillis(),
                minVolume != null ? minVolume : c.minVolume(),
                exitHysteresis != null ? exitHysteresis : c.exitHysteresis(),
                opportunityTtlMillis != null ? opportunityTtlMillis : c.opportunityTtlMillis(),
                journalDir != null ? journalDir : c.journalDir());
    }

    private void validate() {
        if (feeRate != null && (feeRate < 0 || feeRate >= 1)) throw new IllegalArgumentException("feeRate must be in [0,1)");
        if (slippageRate != null && (slippageRate < 0 || slippageRate >= 1)) throw new IllegalArgumentException("slippageRate must be in [0,1)");
        if (minProfit != null && minProfit < 0) throw new IllegalArgumentException("minProfit must be >= 0");
        if (maxCycleLength != null && (maxCycleLength < 2 || maxCycleLength > 8)) throw new IllegalArgumentException("maxCycleLength must be in [2,8]");
        if (quoteTtlMillis != null && quoteTtlMillis <= 0) throw new IllegalArgumentException("quoteTtlMillis must be > 0");
        if (detectIntervalMillis != null && detectIntervalMillis <= 0) throw new IllegalArgumentException("detectIntervalMillis must be > 0");
        if (minVolume != null && minVolume < 0) throw new IllegalArgumentException("minVolume must be >= 0");
        if (exitHysteresis != null && (exitHysteresis < 0 || exitHysteresis >= 1)) throw new IllegalArgumentException("exitHysteresis must be in [0,1)");
        if (opportunityTtlMillis != null && opportunityTtlMillis < 0) throw new IllegalArgumentException("opportunityTtlMillis must be >= 0");
    }

    public Double getFeeRate() { return feeRate; }
    public void setFeeRate(Double v) { this.feeRate = v; }
    public Double getSlippageRate() { return slippageRate; }
    public void setSlippageRate(Double v) { this.slippageRate = v; }
    public Double getMinProfit() { return minProfit; }
    public void setMinProfit(Double v) { this.minProfit = v; }
    public Integer getMaxCycleLength() { return maxCycleLength; }
    public void setMaxCycleLength(Integer v) { this.maxCycleLength = v; }
    public Long getQuoteTtlMillis() { return quoteTtlMillis; }
    public void setQuoteTtlMillis(Long v) { this.quoteTtlMillis = v; }
    public Long getDetectIntervalMillis() { return detectIntervalMillis; }
    public void setDetectIntervalMillis(Long v) { this.detectIntervalMillis = v; }
    public Double getMinVolume() { return minVolume; }
    public void setMinVolume(Double v) { this.minVolume = v; }
    public Double getExitHysteresis() { return exitHysteresis; }
    public void setExitHysteresis(Double v) { this.exitHysteresis = v; }
    public Long getOpportunityTtlMillis() { return opportunityTtlMillis; }
    public void setOpportunityTtlMillis(Long v) { this.opportunityTtlMillis = v; }
    public String getJournalDir() { return journalDir; }
    public void setJournalDir(String v) { this.journalDir = v; }
}
