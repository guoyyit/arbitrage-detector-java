package com.github.guoyyit.arbitragedetectorjava.state;

import com.github.guoyyit.arbitragedetectorjava.model.Quote;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe store of the latest quote per (market, pair) slot.
 *
 * <p>Ordering rule: each source numbers its quotes with a strictly
 * increasing sequence per slot. A quote whose sequence is lower than the
 * stored one is stale (out of order); equal is a duplicate. A quote whose
 * validity window has already passed at arrival is late/expired. None of
 * these mutate the book, so they can never corrupt detection.
 *
 * <p>Consistency rule: updates run under a write lock, snapshots under a
 * read lock, so every snapshot is a true point-in-time view of the book.
 */
public class QuoteBook {

    public enum SubmitResult { ACCEPTED, STALE_SEQUENCE, DUPLICATE, EXPIRED }

    private final ConcurrentHashMap<String, Quote> slots = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong version = new AtomicLong();

    /**
     * Submit one quote.
     *
     * @param nowMillis  current time used for the expiry check
     * @param ttlMillis  quote time-to-live; a quote is valid while
     *                   {@code timestamp + ttlMillis > now}
     */
    public SubmitResult submit(Quote quote, long nowMillis, long ttlMillis) {
        if (isExpired(quote, nowMillis, ttlMillis)) {
            return SubmitResult.EXPIRED;
        }
        lock.writeLock().lock();
        try {
            SubmitResult[] result = new SubmitResult[1];
            slots.compute(quote.key(), (k, old) -> {
                if (old != null && quote.sequence() <= old.sequence()) {
                    result[0] = quote.sequence() == old.sequence()
                            ? SubmitResult.DUPLICATE : SubmitResult.STALE_SEQUENCE;
                    return old;
                }
                result[0] = SubmitResult.ACCEPTED;
                version.incrementAndGet();
                return quote;
            });
            return result[0];
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Point-in-time consistent snapshot; expired quotes are excluded. */
    public QuoteSnapshot snapshot(long nowMillis, long ttlMillis) {
        lock.readLock().lock();
        try {
            Map<String, Quote> copy = new TreeMap<>();
            for (Map.Entry<String, Quote> e : slots.entrySet()) {
                if (!isExpired(e.getValue(), nowMillis, ttlMillis)) {
                    copy.put(e.getKey(), e.getValue());
                }
            }
            return new QuoteSnapshot(java.util.Collections.unmodifiableMap(copy),
                    version.get(), nowMillis);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Remove expired slots so the live book does not grow unbounded. */
    public int purgeExpired(long nowMillis, long ttlMillis) {
        lock.writeLock().lock();
        try {
            List<String> dead = new ArrayList<>();
            for (Map.Entry<String, Quote> e : slots.entrySet()) {
                if (isExpired(e.getValue(), nowMillis, ttlMillis)) {
                    dead.add(e.getKey());
                }
            }
            dead.forEach(slots::remove);
            return dead.size();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return slots.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private static boolean isExpired(Quote q, long nowMillis, long ttlMillis) {
        return q.timestamp() + ttlMillis <= nowMillis;
    }
}
