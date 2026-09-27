package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.config.ConfigService;
import com.github.guoyyit.arbitragedetectorjava.config.DetectorProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigServiceTest {

    private ConfigService.ConfigView view(DetectorProperties p) {
        return ConfigService.ConfigView.from(p);
    }

    @Test
    void validUpdateAppliesAtomically() {
        DetectorProperties p = new DetectorProperties();
        ConfigService svc = new ConfigService(p);
        ConfigService.ConfigView desired = new ConfigService.ConfigView(
                0.002, 0.001, 0.003, 4, Duration.ofSeconds(10), Duration.ofMillis(100),
                1.5, 0.001, 1.0e-9, "x.log");
        List<String> errors = svc.update(desired);
        assertTrue(errors.isEmpty());
        assertEquals(0.002, p.getFeeRate(), 0);
        assertEquals(4, p.getMaxCycleLength());
        assertEquals(Duration.ofSeconds(10), p.getQuoteTtl());
        assertEquals(1.5, p.getMinVolume(), 0);
    }

    @Test
    void invalidUpdateIsRejectedAndLeavesPreviousValuesUntouched() {
        DetectorProperties p = new DetectorProperties();
        ConfigService svc = new ConfigService(p);
        ConfigService.ConfigView bad = new ConfigService.ConfigView(
                1.5, 0.001, 0.003, 1, Duration.ofSeconds(-1), Duration.ZERO,
                -1, 0.01, 1.0e-9, null);
        List<String> errors = svc.update(bad);
        assertFalse(errors.isEmpty(), "multiple validation errors should be reported");
        // every field keeps its previous value - no partial application
        assertEquals(0.001, p.getFeeRate(), 0);
        assertEquals(3, p.getMaxCycleLength());
        assertEquals(Duration.ofSeconds(5), p.getQuoteTtl());
    }

    @Test
    void exitMarginCannotExceedMinProfitRate() {
        DetectorProperties p = new DetectorProperties();
        ConfigService svc = new ConfigService(p);
        ConfigService.ConfigView candidate = new ConfigService.ConfigView(
                0.001, 0.0005, 0.001, 3, Duration.ofSeconds(5), Duration.ofMillis(200),
                0, 0.002, 1.0e-9, null);
        List<String> errors = svc.update(candidate);
        assertTrue(errors.stream().anyMatch(e -> e.contains("exitMargin")));
    }

    @Test
    void snapshotCopyIsIndependentOfLaterMutation() {
        DetectorProperties p = new DetectorProperties();
        DetectorProperties snap = p.copy();
        p.setMinProfitRate(0.9);
        p.setMaxCycleLength(8);
        assertNotEquals(0.9, snap.getMinProfitRate(), 0);
        assertEquals(3, snap.getMaxCycleLength());
    }
}
