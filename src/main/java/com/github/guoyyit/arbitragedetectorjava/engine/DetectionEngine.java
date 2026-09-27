package com.github.guoyyit.arbitragedetectorjava.engine;

import com.github.guoyyit.arbitragedetectorjava.config.ArbitrageProperties;
import com.github.guoyyit.arbitragedetectorjava.detect.ArbitrageDetector;
import com.github.guoyyit.arbitragedetectorjava.lifecycle.OpportunityTracker;
import com.github.guoyyit.arbitragedetectorjava.model.ArbitrageOpportunity;
import com.github.guoyyit.arbitragedetectorjava.model.OpportunityEvent;
import com.github.guoyyit.arbitragedetectorjava.persistence.JournalStore;
import com.github.guoyyit.arbitragedetectorjava.state.QuoteBook;
import com.github.guoyyit.arbitragedetectorjava.state.QuoteSnapshot;

import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the detection loop:
 * purge expired -&gt; snapshot -&gt; detect -&gt; track -&gt; journal.
 *
 * <p>One cycle runs at a time (single-threaded scheduler, self re-arming
 * so interval changes apply between cycles). Every cycle reads the config
 * exactly once and detects on exactly one immutable snapshot, so a config
 * change or concurrent quote update never affects a cycle in flight.
 *
 * <p>Recovery: on construction the journal is replayed into the tracker,
 * restoring the active opportunity set; a torn last cycle is discarded and
 * simply re-detected, so restarts neither duplicate nor miss reports.
 */
public class DetectionEngine {

    private static final int AUDIT_LOG_CAPACITY = 1000;

    private final QuoteBook book;
    private final ArbitrageDetector detector;
    private final OpportunityTracker tracker;
    private final JournalStore journal;
    private final AtomicReference<ArbitrageProperties> config;
    private final Queue<OpportunityEvent> auditLog = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "detection-engine");
                t.setDaemon(true);
                return t;
            });
    private volatile boolean running;

    public DetectionEngine(QuoteBook book, ArbitrageDetector detector,
                           OpportunityTracker tracker, ArbitrageProperties config) {
        this.book = book;
        this.detector = detector;
        this.tracker = tracker;
        this.config = new AtomicReference<>(config);
        this.journal = new JournalStore(Path.of(config.journalDir()));
        recover();
    }

    private void recover() {
        List<OpportunityEvent> events = journal.loadEvents();
        tracker.restore(events);
        auditLog.addAll(events);
        trimAuditLog();
    }

    /** Execute exactly one detection cycle. Exposed for tests. */
    public List<OpportunityEvent> runOnce(long nowMillis) {
        ArbitrageProperties cfg = config.get();
        book.purgeExpired(nowMillis, cfg.quoteTtlMillis());
        QuoteSnapshot snapshot = book.snapshot(nowMillis, cfg.quoteTtlMillis());
        // Detect with the exit threshold so the tracker can apply hysteresis.
        ArbitrageProperties detectionCfg = withMinProfit(cfg,
                cfg.minProfit() * (1.0 - cfg.exitHysteresis()));
        List<ArbitrageOpportunity> candidates = detector.detect(snapshot, detectionCfg);
        List<OpportunityEvent> events = tracker.update(candidates, cfg, nowMillis);
        journal.appendCycle(snapshot.version(), nowMillis, events);
        auditLog.addAll(events);
        trimAuditLog();
        return events;
    }

    /** Start the self-re-arming detection loop. */
    public void start() {
        running = true;
        scheduler.execute(this::loop);
    }

    private void loop() {
        if (!running) {
            return;
        }
        try {
            runOnce(System.currentTimeMillis());
        } catch (RuntimeException e) {
            // A failing cycle must not kill the loop; next cycle retries.
            e.printStackTrace();
        }
        long interval = config.get().detectIntervalMillis();
        scheduler.schedule(this::loop, interval, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
        scheduler.shutdownNow();
        journal.close();
    }

    /** Atomically swap the configuration; takes effect on the next cycle. */
    public void updateConfig(ArbitrageProperties newConfig) {
        config.set(newConfig);
    }

    public ArbitrageProperties currentConfig() {
        return config.get();
    }

    public QuoteBook quoteBook() {
        return book;
    }

    public OpportunityTracker tracker() {
        return tracker;
    }

    public List<OpportunityEvent> auditLog() {
        return List.copyOf(auditLog);
    }

    private void trimAuditLog() {
        while (auditLog.size() > AUDIT_LOG_CAPACITY) {
            auditLog.poll();
        }
    }

    private static ArbitrageProperties withMinProfit(ArbitrageProperties c, double minProfit) {
        return new ArbitrageProperties(c.feeRate(), c.slippageRate(), minProfit,
                c.maxCycleLength(), c.quoteTtlMillis(), c.detectIntervalMillis(),
                c.minVolume(), c.exitHysteresis(), c.opportunityTtlMillis(), c.journalDir());
    }
}
