package com.github.guoyyit.arbitragedetectorjava.input;

import com.github.guoyyit.arbitragedetectorjava.domain.Quote;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Simulates market data feeds over an in-memory pipe - a local loopback with no
 * network or external service. Any number of producer threads can call
 * {@link #push(Quote)} concurrently; calls may intentionally be made out of
 * order, duplicated or delayed to exercise the ingester's correctness rules.
 *
 * Delivery is asynchronous through a bounded thread pool; tests use
 * {@link #drain(long, TimeUnit)} to wait until every pushed quote has been
 * processed before asserting. {@link #pushSync(Quote)} delivers on the calling
 * thread when a deterministic ordering is required.
 */
public class InMemoryQuoteSource {
    private final QuoteIngester ingester;
    private final ExecutorService executor;
    private final List<Future<?>> pending = new ArrayList<>();

    public InMemoryQuoteSource(QuoteIngester ingester) {
        this(ingester, 2);
    }

    public InMemoryQuoteSource(QuoteIngester ingester, int workerThreads) {
        this.ingester = ingester;
        this.executor = Executors.newFixedThreadPool(Math.max(1, workerThreads),
                r -> {
                    Thread t = new Thread(r, "quote-pipe");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** Enqueue one quote for asynchronous delivery into the ingester. */
    public synchronized Future<?> push(Quote quote) {
        Future<?> f = executor.submit(() -> ingester.ingest(quote));
        pending.add(f);
        return f;
    }

    /** Enqueue many quotes; the pool may deliver them concurrently/out of order. */
    public void pushAll(List<Quote> quotes) {
        for (Quote q : quotes) {
            push(q);
        }
    }

    /** Deliver on the calling thread, preserving caller-enforced ordering. */
    public void pushSync(Quote quote) {
        ingester.ingest(quote);
    }

    /** Block until all pushed quotes have been ingested. */
    public void drain(long timeout, TimeUnit unit) throws InterruptedException {
        List<Future<?>> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(pending);
        }
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        for (Future<?> f : snapshot) {
            long remaining = deadlineNanos - System.nanoTime();
            try {
                f.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
            } catch (Exception e) {
                throw new RuntimeException("quote delivery failed", e);
            }
        }
        synchronized (this) {
            pending.removeAll(snapshot);
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
