package com.github.guoyyit.arbitragedetectorjava.domain;

import java.util.Objects;

/**
 * One leg of an arbitrage cycle: convert {@code from} currency into {@code to}
 * currency on {@code market} at the given executable price.
 */
public final class Hop {
    public enum Side { BUY, SELL }

    private final String market;
    private final String from;
    private final String to;
    private final Side side;
    private final double price;
    private final double maxQty;

    public Hop(String market, String from, String to, Side side, double price, double maxQty) {
        this.market = Objects.requireNonNull(market, "market");
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
        this.side = Objects.requireNonNull(side, "side");
        this.price = price;
        this.maxQty = maxQty;
    }

    public String market() { return market; }
    public String from() { return from; }
    public String to() { return to; }
    public Side side() { return side; }
    public double price() { return price; }
    public double maxQty() { return maxQty; }
}
