package com.github.guoyyit.arbitragedetectorjava.engine;

import com.github.guoyyit.arbitragedetectorjava.config.DetectorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link DetectionEngine#runOnce()} periodically using a single worker
 * thread, so two detections can never overlap. The task re-reads the interval
 * after every run and reschedules itself, hence a config change takes effect on
 * the next boundary and never interrupts a detection halfway: the in-flight run
 * always completes with the snapshot and config copy it started with.
 */
@Component
public class DetectionScheduler {
    private static final Logger log = LoggerFactory.getLogger(DetectionScheduler.class);

    private final DetectionEngine engine;
    private final DetectorProperties properties;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> next;
    private volatile boolean enabled = true;

    public DetectionScheduler(DetectionEngine engine, DetectorProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "detection-loop");
            t.setDaemon(true);
            return t;
        });
        scheduleNext(0);
    }

    private synchronized void scheduleNext(long delayMillis) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        long delay = delayMillis;
        if (delay < 0) {
            delay = properties.getDetectionInterval().toMillis();
        }
        next = executor.schedule(this::tick, delay, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        if (!enabled) {
            scheduleNext(properties.getDetectionInterval().toMillis());
            return;
        }
        try {
            engine.runOnce();
        } catch (RuntimeException e) {
            // a failed run must not kill the loop; state is unchanged and the
            // next run retries against a fresh snapshot
            log.warn("detection run failed: {}", e.getMessage(), e);
        }
        scheduleNext(properties.getDetectionInterval().toMillis());
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized void shutdown() {
        if (next != null) {
            next.cancel(false);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
