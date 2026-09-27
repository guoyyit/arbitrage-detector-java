package com.github.guoyyit.arbitragedetectorjava.state;

import com.github.guoyyit.arbitragedetectorjava.model.Quote;

import java.util.List;
import java.util.Map;

/**
 * Immutable, consistent view of the quote book at one point in time.
 * Detection runs only on snapshots, never on the live book.
 */
public final class QuoteSnapshot {

    private final Map<String, Quote> quotes;
    private final long version;
    private final long createdAt;

    public QuoteSnapshot(Map<String, Quote> quotes, long version, long createdAt) {
        this.quotes = quotes;
        this.version = version;
        this.createdAt = createdAt;
    }

    public Map<String, Quote> quotes() { return quotes; }
    public long version() { return version; }
    public long createdAt() { return createdAt; }

    public List<Quote> all() { return List.copyOf(quotes.values()); }
}
