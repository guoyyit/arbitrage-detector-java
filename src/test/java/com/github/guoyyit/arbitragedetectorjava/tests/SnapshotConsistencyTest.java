package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.config.DetectorProperties;
import com.github.guoyyit.arbitragedetectorjava.domain.Opportunity;
import com.github.guoyyit.arbitragedetectorjava.domain.Quote;
import com.github.guoyyit.arbitragedetectorjava.detect.ArbitrageDetector;
import com.github.guoyyit.arbitragedetectorjava.quote.QuoteSnapshot;
import com.github.guoyyit.arbitragedetectorjava.quote.QuoteStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotConsistencyTest {

    private static Quote quote(String market, int i, Instant time, long seq) {
        return new Quote(market, "C" + i, "USD", 100 + i, 101 + i, 10, 10, time, seq);
    }

    @Test
    void concurrentUpdatesNeverProduceAnInconsistentSnapshot() throws Exception {
        QuoteStore store = new QuoteStore();
        Duration ttl = Duration.ofSeconds(60);
        Instant start = Instant.now();
        int markets = 8;
        int updatesPerMarket = 200;
        ExecutorService pool = Executors.newFixedThreadPool(markets);
        CountDownLatch done = new CountDownLatch(markets);
        AtomicLong seq = new AtomicLong();

        for (int m = 0; m < markets; m++) {
            final String market = "market-" + m;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < updatesPerMarket; i++) {
                        Instant t = start.plusMillis(seq.incrementAndGet());
                        store.accept(quote(market, i % 5, t, seq.get()), ttl, t);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        // reader thread: every snapshot must be self-consistent: exactly one
        // quote per key, and snapshot versions must never go backwards
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger reads = new AtomicInteger();
        Thread reader = new Thread(() -> {
            long lastVersion = -1;
            Set<String> seen = new HashSet<>();
            while (done.getCount() > 0 || reads.get() < 50) {
                Instant now = Instant.now();
                QuoteSnapshot snap = store.snapshot(ttl, now);
                try {
                    assertTrue(snap.version() >= lastVersion);
                    lastVersion = snap.version();
                    seen.clear();
                    for (Quote q : snap.quotes()) {
                        assertTrue(seen.add(q.key()),
                                "duplicate key inside one snapshot: " + q.key());
                        assertFalse(q.quoteTime().isBefore(now.minus(ttl)),
                                "expired quote leaked into snapshot");
                    }
                    reads.incrementAndGet();
                    if (done.getCount() == 0 && reads.get() >= 50) {
                        break;
                    }
                } catch (Throwable t) {
                    errors.add(t);
                    break;
                }
            }
        });
        reader.start();

        assertTrue(done.await(30, TimeUnit.SECONDS));
        reader.join(30000);
        pool.shutdown();
        assertTrue(errors.isEmpty(), () -> "snapshot inconsistency: " + errors);
        assertTrue(reads.get() >= 50);
    }

    @Test
    void detectionOnSnapshotIsIsolatedFromConcurrentUpdates() throws Exception {
        QuoteStore store = new QuoteStore();
        ArbitrageDetector detector = new ArbitrageDetector();
        DetectorProperties cfg = new DetectorProperties();
        cfg.setFeeRate(0.001);
        cfg.setSlippageRate(0.0005);
        cfg.setMinProfitRate(0.001);
        cfg.setMaxCycleLength(2);
        cfg.setQuoteTtl(Duration.ofSeconds(60));

        Instant t = Instant.now();
        // seed a profitable two-hop snapshot
        store.accept(new Quote("a", "BTC", "USDT", 60000, 60010, 2, 2, t, 1),
                Duration.ofSeconds(60), t);
        store.accept(new Quote("b", "BTC", "USDT", 58900, 59000, 2, 2, t, 2),
                Duration.ofSeconds(60), t);

        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        Thread mutator = new Thread(() -> {
            long s = 100;
            Instant now = Instant.now();
            for (int i = 0; i < 1000; i++) {
                Instant nt = now.plusMillis(i + 1L);
                store.accept(new Quote("a", "BTC", "USDT", 61000 + i, 61010 + i, 2, 2,
                        nt, s++), Duration.ofSeconds(60), nt);
            }
        });

        mutator.start();
        // run many detections; every result must be internally reproducible:
        // re-detecting on the SAME snapshot object gives the SAME list
        for (int i = 0; i < 200 && mutator.isAlive(); i++) {
            QuoteSnapshot snap = store.snapshot(Duration.ofSeconds(60), Instant.now());
            List<Opportunity> r1 = detector.detect(snap, cfg);
            List<Opportunity> r2 = detector.detect(snap, cfg);
            try {
                assertEquals(r1.size(), r2.size());
                for (int j = 0; j < r1.size(); j++) {
                    assertEquals(r1.get(j).id(), r2.get(j).id());
                    assertEquals(r1.get(j).netRate(), r2.get(j).netRate(), 0);
                }
            } catch (Throwable e) {
                errors.add(e);
                break;
            }
        }
        mutator.join();
        assertTrue(errors.isEmpty(), () -> "non-deterministic detection: " + errors);
    }
}
