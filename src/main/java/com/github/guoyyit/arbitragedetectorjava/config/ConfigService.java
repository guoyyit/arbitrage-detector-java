package com.github.guoyyit.arbitragedetectorjava.config;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates and atomically applies configuration changes.
 *
 * The candidate is validated as a whole first; only when every field is legal
 * are the setters invoked while holding the properties monitor (one indivisible
 * update), so a detection run can never observe a partially changed
 * configuration and results remain reproducible run by run.
 */
@Service
public class ConfigService {
    private final DetectorProperties properties;

    public ConfigService(DetectorProperties properties) {
        this.properties = properties;
    }

    public DetectorProperties properties() {
        return properties;
    }

    /** Validate then apply; returns an error list (empty on success). */
    public List<String> update(ConfigView desired) {
        List<String> errors = validate(desired);
        if (!errors.isEmpty()) {
            return errors;
        }
        synchronized (properties) {
            properties.setFeeRate(desired.feeRate());
            properties.setSlippageRate(desired.slippageRate());
            properties.setMinProfitRate(desired.minProfitRate());
            properties.setMaxCycleLength(desired.maxCycleLength());
            properties.setQuoteTtl(desired.quoteTtl());
            properties.setDetectionInterval(desired.detectionInterval());
            properties.setMinVolume(desired.minVolume());
            properties.setExitMargin(desired.exitMargin());
            properties.setUpdateEventEpsilon(desired.updateEventEpsilon());
            if (desired.journalPath() != null && !desired.journalPath().isBlank()) {
                properties.setJournalPath(desired.journalPath());
            }
        }
        return List.of();
    }

    /** Reject nonsensical values so they can never corrupt a detection. */
    public static List<String> validate(ConfigView c) {
        List<String> errors = new ArrayList<>();
        if (!Double.isFinite(c.feeRate()) || c.feeRate() < 0 || c.feeRate() >= 1) {
            errors.add("feeRate must be in [0,1)");
        }
        if (!Double.isFinite(c.slippageRate()) || c.slippageRate() < 0 || c.slippageRate() >= 1) {
            errors.add("slippageRate must be in [0,1)");
        }
        if (!Double.isFinite(c.minProfitRate()) || c.minProfitRate() < 0) {
            errors.add("minProfitRate must be >= 0");
        }
        if (c.maxCycleLength() < 2 || c.maxCycleLength() > 8) {
            errors.add("maxCycleLength must be in [2,8]");
        }
        if (c.quoteTtl() == null || c.quoteTtl().isNegative() || c.quoteTtl().isZero()) {
            errors.add("quoteTtl must be a positive duration");
        }
        if (c.detectionInterval() == null || c.detectionInterval().isNegative()
                || c.detectionInterval().isZero()) {
            errors.add("detectionInterval must be a positive duration");
        }
        if (!Double.isFinite(c.minVolume()) || c.minVolume() < 0) {
            errors.add("minVolume must be >= 0");
        }
        if (!Double.isFinite(c.exitMargin()) || c.exitMargin() < 0) {
            errors.add("exitMargin must be >= 0");
        }
        if (!Double.isFinite(c.updateEventEpsilon()) || c.updateEventEpsilon() < 0) {
            errors.add("updateEventEpsilon must be >= 0");
        }
        if (Double.isFinite(c.exitMargin()) && Double.isFinite(c.minProfitRate())
                && c.exitMargin() > c.minProfitRate()) {
            errors.add("exitMargin must not exceed minProfitRate (exit threshold would be negative)");
        }
        return errors;
    }

    /** JSON-friendly immutable view used by the configuration API. */
    public record ConfigView(double feeRate, double slippageRate, double minProfitRate,
                             int maxCycleLength, Duration quoteTtl, Duration detectionInterval,
                             double minVolume, double exitMargin, double updateEventEpsilon,
                             String journalPath) {
        public static ConfigView from(DetectorProperties p) {
            return new ConfigView(p.getFeeRate(), p.getSlippageRate(), p.getMinProfitRate(),
                    p.getMaxCycleLength(), p.getQuoteTtl(), p.getDetectionInterval(),
                    p.getMinVolume(), p.getExitMargin(), p.getUpdateEventEpsilon(),
                    p.getJournalPath());
        }

        public DetectorProperties toProperties() {
            DetectorProperties p = new DetectorProperties();
            p.setFeeRate(feeRate);
            p.setSlippageRate(slippageRate);
            p.setMinProfitRate(minProfitRate);
            p.setMaxCycleLength(maxCycleLength);
            p.setQuoteTtl(quoteTtl);
            p.setDetectionInterval(detectionInterval);
            p.setMinVolume(minVolume);
            p.setExitMargin(exitMargin);
            p.setUpdateEventEpsilon(updateEventEpsilon);
            if (journalPath != null) {
                p.setJournalPath(journalPath);
            }
            return p;
        }
    }
}
