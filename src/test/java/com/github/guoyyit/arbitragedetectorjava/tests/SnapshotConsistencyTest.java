package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.config.ArbitrageProperties;
import com.github.guoyyit.arbitragedetectorjava.detect.ArbitrageDetector;
import com.github.guoyyit.arbitragedetectorjava.model.ArbitrageOpportunity;
import com.github.guoyyit.arbitragedetectorjava.model.Quote;
import com.github.guoyyit.arbitragedetectorjava.state.QuoteBook;
import com.github.guoyyit.arbitragedetectorjava.state.QuoteSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent writers must never tear a detection: every detection runs on
 * one immutable point-in-time snapshot, and re-detecting on that snapshot
 * after more updates must reproduce the identical result.
 */
class SnapshotConsistencyTest {

    private static final long NOW = 1_000_000L;
    private static final long TTL = 3_600_000L;

    private final ArbitrageProperties props = new ArbitrageProperties(
            0.001, 0.0005, 0.0005, 3, TTL, 100, 0.0001, 0.5, 30_000, "data");

    @Test
    void detectionIsConsistentAndReproducibleUnderConcurrentUpdates() throws Exception {
        QuoteBook book = new QuoteBook();
        ArbitrageDetector detector = new ArbitrageDetector();
        int writers = 4;
        int quotesPerWriter = 300;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (int w = 0; w < writers; w++) {
            final String market = "m" + w;
            final int writerId = w;
            Thread t = new Thread(() -> {
                await(start);
                for (int i = 1; i <= quotesPerWriter; i++) {
                    final int seq = i;
                    double mid = 100 + writerId + seq * 0.001;
                    book.submit(new Quote(market, "BTC", "USD",
                            mid - 0.05, mid + 0.05, 10, 10, seq, NOW + seq), NOW + seq, TTL);
                    book.submit(new Quote(market, "ETH", "USD",
                            mid / 10 - 0.005, mid / 10 + 0.005, 100, 100, seq, NOW + seq), NOW + seq, TTL);
                }
            });
            t.start();
            threads.add(t);
        }

        List<QuoteSnapshot> snapshots = new ArrayList<>();
        List<List<ArbitrageOpportunity>> firstPass = new ArrayList<>();
        Thread reader = new Thread(() -> {
            await(start);
            long lastVersion = -1;
            for (int i = 0; i < 200; i++) {
                QuoteSnapshot snap = book.snapshot(NOW + quotesPerWriter, TTL);
                assertTrue(snap.version() >= lastVersion, "versions must be monotonic");
                lastVersion = snap.version();
                List<ArbitrageOpportunity> result = detector.detect(snap, props);
                // Same snapshot, same config: identical result, immediately.
                assertEquals(result, detector.detect(snap, props),
                        "detection on one snapshot must be deterministic");
                snapshots.add(snap);
                firstPass.add(result);
            }
        });
        reader.start();

        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        reader.join();

        // After all writers finished, re-detecting on every historical
        // snapshot must reproduce exactly what was detected at the time.
        for (int i = 0; i < snapshots.size(); i++) {
            assertEquals(firstPass.get(i), detector.detect(snapshots.get(i), props),
                    "snapshot " + i + " no longer reproduces its original result");
        }

        // Final state contains the last quote of every slot.
        QuoteSnapshot last = book.snapshot(NOW + quotesPerWriter, TTL);
        assertEquals(writers * 2, last.all().size());
        for (Quote q : last.all()) {
            assertEquals(quotesPerWriter, q.sequence());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }
}
