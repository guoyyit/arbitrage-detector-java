package com.github.guoyyit.arbitragedetectorjava.quote;

import com.github.guoyyit.arbitragedetectorjava.domain.Quote;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable, consistent view of the quote state at a point in time.
 * Detection always runs against one snapshot; quotes never change under it.
 */
public final class QuoteSnapshot {
    private final long version;
    private final Instant createdAt;
    private final Map<String, Quote> quotesByKey;

    public QuoteSnapshot(long version, Instant createdAt, Map<String, Quote> quotesByKey) {
        this.version = version;
        this.createdAt = createdAt;
        this.quotesByKey = Map.copyOf(quotesByKey);
    }

    public long version() { return version; }
    public Instant createdAt() { return createdAt; }

    /** All live quotes in a deterministic order (sorted by key). */
    public List<Quote> quotes() {
        return quotesByKey.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    public Quote get(String key) { return quotesByKey.get(key); }
    public int size() { return quotesByKey.size(); }
}
