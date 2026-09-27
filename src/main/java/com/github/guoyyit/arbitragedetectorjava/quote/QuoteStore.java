package com.github.guoyyit.arbitragedetectorjava.quote;

import com.github.guoyyit.arbitragedetectorjava.domain.Quote;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe store of the latest accepted quote per (market, pair) key.
 *
 * Acceptance rules (never mutate state on rejection):
 * <ul>
 *   <li>{@code EXPIRED} - quoteTime is older than the configured TTL at arrival.</li>
 *   <li>{@code STALE}   - a quote with a strictly newer quoteTime is already stored
 *                         for the same key (out-of-order arrival).</li>
 *   <li>{@code DUPLICATE}- an identical quote (same quoteTime and book) is stored.</li>
 *   <li>{@code ACCEPTED} - otherwise it replaces the previous quote for the key.</li>
 * </ul>
 *
 * A read/write lock makes snapshot atomic with respect to updates: a snapshot
 * either contains an update entirely or not, never a mix across keys. The store
 * version increases exactly once per accepted quote, so every snapshot is
 * uniquely identified and results are reproducible.
 */
@Component
public class QuoteStore {
    private final Map<String, Quote> latest = new HashMap<>();
    private final AtomicLong version = new AtomicLong(0);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public enum AcceptResult { ACCEPTED, DUPLICATE, STALE, EXPIRED }

    /** Validate and accept a quote. {@code now} is injected for deterministic tests. */
    public AcceptResult accept(Quote quote, Duration ttl, Instant now) {
        if (quote.quoteTime().isBefore(now.minus(ttl))) {
            return AcceptResult.EXPIRED;
        }
        lock.writeLock().lock();
        try {
            Quote current = latest.get(quote.key());
            if (current != null) {
                if (quote.quoteTime().isBefore(current.quoteTime())) {
                    return AcceptResult.STALE;
                }
                if (isDuplicate(current, quote)) {
                    return AcceptResult.DUPLICATE;
                }
            }
            latest.put(quote.key(), quote);
            version.incrementAndGet();
            return AcceptResult.ACCEPTED;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Restore a quote from the write-ahead journal during recovery. Bypasses
     * ordering/expiry re-validation (the quote was validated before it was
     * journaled); TTL is re-applied later when a snapshot is taken.
     */
    public void restore(Quote quote) {
        lock.writeLock().lock();
        try {
            Quote current = latest.get(quote.key());
            if (current == null || !quote.quoteTime().isBefore(current.quoteTime())) {
                if (current == null || !isDuplicate(current, quote)) {
                    latest.put(quote.key(), quote);
                    version.incrementAndGet();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Take a consistent snapshot. Quotes past their TTL at {@code now} are
     * excluded. The copy happens entirely under the read lock, so concurrent
     * accept() calls cannot be observed partially.
     */
    public QuoteSnapshot snapshot(Duration ttl, Instant now) {
        lock.readLock().lock();
        try {
            Map<String, Quote> live = new HashMap<>(latest.size() * 2);
            Instant cutoff = now.minus(ttl);
            for (Map.Entry<String, Quote> e : latest.entrySet()) {
                if (!e.getValue().quoteTime().isBefore(cutoff)) {
                    live.put(e.getKey(), e.getValue());
                }
            }
            return new QuoteSnapshot(version.get(), now, live);
        } finally {
            lock.readLock().unlock();
        }
    }

    public long version() { return version.get(); }

    public int size() {
        lock.readLock().lock();
        try {
            return latest.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            latest.clear();
            version.set(0);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static boolean isDuplicate(Quote a, Quote b) {
        return a.quoteTime().equals(b.quoteTime())
                && Double.compare(a.bid(), b.bid()) == 0
                && Double.compare(a.ask(), b.ask()) == 0
                && Double.compare(a.bidQty(), b.bidQty()) == 0
                && Double.compare(a.askQty(), b.askQty()) == 0;
    }
}
