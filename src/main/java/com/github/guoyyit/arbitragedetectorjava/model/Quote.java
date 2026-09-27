package com.github.guoyyit.arbitragedetectorjava.model;

/**
 * One market quote for a trading pair on one market.
 *
 * @param market     market / exchange identifier
 * @param base       base currency, e.g. "BTC"
 * @param quote      quote currency, e.g. "USD"
 * @param bid        best bid price (quote per base)
 * @param ask        best ask price (quote per base)
 * @param bidVolume  volume available at bid, in base units
 * @param askVolume  volume available at ask, in base units
 * @param sequence   per-source monotonically increasing sequence number
 * @param timestamp  source event time, epoch millis
 */
public record Quote(String market, String base, String quote,
                    double bid, double ask,
                    double bidVolume, double askVolume,
                    long sequence, long timestamp) {

    public Quote {
        if (market == null || market.isBlank()) throw new IllegalArgumentException("market");
        if (base == null || base.isBlank()) throw new IllegalArgumentException("base");
        if (quote == null || quote.isBlank()) throw new IllegalArgumentException("quote");
        if (bid <= 0 || ask <= 0) throw new IllegalArgumentException("prices must be positive");
        if (bid > ask) throw new IllegalArgumentException("bid must not exceed ask");
        if (bidVolume < 0 || askVolume < 0) throw new IllegalArgumentException("volumes must be >= 0");
    }

    /** Identity of the stream slot this quote updates. */
    public String key() {
        return market + ":" + base + "/" + quote;
    }
}
