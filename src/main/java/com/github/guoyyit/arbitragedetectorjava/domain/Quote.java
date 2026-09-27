package com.github.guoyyit.arbitragedetectorjava.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A single market quote for a trading pair on a given market.
 * Immutable. {@code ingestSeq} is assigned by the ingester in arrival order and
 * is used for deterministic ordering and recovery.
 */
public final class Quote {
    private final String market;
    private final String base;
    private final String quote;
    private final double bid;
    private final double ask;
    private final double bidQty;
    private final double askQty;
    private final Instant quoteTime;
    private final long ingestSeq;

    public Quote(String market, String base, String quote,
                 double bid, double ask, double bidQty, double askQty,
                 Instant quoteTime, long ingestSeq) {
        this.market = Objects.requireNonNull(market, "market");
        this.base = Objects.requireNonNull(base, "base");
        this.quote = Objects.requireNonNull(quote, "quote");
        this.bid = bid;
        this.ask = ask;
        this.bidQty = bidQty;
        this.askQty = askQty;
        this.quoteTime = Objects.requireNonNull(quoteTime, "quoteTime");
        this.ingestSeq = ingestSeq;
    }

    public String market() { return market; }
    public String base() { return base; }
    public String quote() { return quote; }
    public double bid() { return bid; }
    public double ask() { return ask; }
    public double bidQty() { return bidQty; }
    public double askQty() { return askQty; }
    public Instant quoteTime() { return quoteTime; }
    public long ingestSeq() { return ingestSeq; }

    /** Canonical key identifying the (market, pair) this quote belongs to. */
    public String key() { return market + ":" + base + "/" + quote; }
}
