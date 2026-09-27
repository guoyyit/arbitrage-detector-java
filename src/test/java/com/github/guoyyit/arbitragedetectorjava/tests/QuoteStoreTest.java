package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.domain.Quote;
import com.github.guoyyit.arbitragedetectorjava.quote.QuoteSnapshot;
import com.github.guoyyit.arbitragedetectorjava.quote.QuoteStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class QuoteStoreTest {
    private QuoteStore store;
    private final Instant t0 = Instant.parse("2026-09-28T10:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(5);

    @BeforeEach
    void setUp() {
        store = new QuoteStore();
    }

    private Quote quote(String market, String pair, double bid, double ask,
                        double bq, double aq, Instant time, long seq) {
        String[] p = pair.split("/");
        return new Quote(market, p[0], p[1], bid, ask, bq, aq, time, seq);
    }

    @Test
    void acceptsFreshQuote() {
        Quote q = quote("m1", "BTC/USDT", 100, 101, 1, 1, t0, 1);
        assertEquals(QuoteStore.AcceptResult.ACCEPTED, store.accept(q, TTL, t0));
        assertEquals(1, store.snapshot(TTL, t0).size());
    }

    @Test
    void rejectsDuplicateQuote() {
        Quote q1 = quote("m1", "BTC/USDT", 100, 101, 1, 1, t0, 1);
        Quote q2 = quote("m1", "BTC/USDT", 100, 101, 1, 1, t0, 2);
        assertEquals(QuoteStore.AcceptResult.ACCEPTED, store.accept(q1, TTL, t0));
        assertEquals(QuoteStore.AcceptResult.DUPLICATE, store.accept(q2, TTL, t0));
        assertEquals(1, store.version(), "duplicate must not bump version");
    }

    @Test
    void rejectsOutOfOrderQuoteAsStale() {
        // newer arrives first, older (out-of-order) arrives later
        Quote newer = quote("m1", "BTC/USDT", 102, 103, 1, 1, t0.plusMillis(200), 2);
        Quote older = quote("m1", "BTC/USDT", 100, 101, 1, 1, t0, 1);
        assertEquals(QuoteStore.AcceptResult.ACCEPTED, store.accept(newer, TTL, t0.plusMillis(300)));
        assertEquals(QuoteStore.AcceptResult.STALE, store.accept(older, TTL, t0.plusMillis(300)));
        QuoteSnapshot snap = store.snapshot(TTL, t0.plusMillis(300));
        assertEquals(102, snap.get("m1:BTC/USDT").bid(), 1e-9);
    }

    @Test
    void acceptsLateButStillNewerQuote() {
        // a quote arriving late is fine as long as its quoteTime is the newest
        Quote q1 = quote("m1", "BTC/USDT", 100, 101, 1, 1, t0, 1);
        Quote late = quote("m1", "BTC/USDT", 104, 105, 1, 1, t0.plusMillis(100), 5);
        store.accept(q1, TTL, t0.plusMillis(50));
        assertEquals(QuoteStore.AcceptResult.ACCEPTED,
                store.accept(late, TTL, t0.plusMillis(4000)));
        assertEquals(104, store.snapshot(TTL, t0.plusMillis(4000))
                .get("m1:BTC/USDT").bid(), 1e-9);
    }

    @Test
    void rejectsQuoteAlreadyExpiredAtArrival() {
        Quote expired = quote("m1", "BTC/USDT", 100, 101, 1, 1,
                t0.minusSeconds(6), 1);
        assertEquals(QuoteStore.AcceptResult.EXPIRED, store.accept(expired, TTL, t0));
        assertEquals(0, store.snapshot(TTL, t0).size());
    }

    @Test
    void expiredQuoteDisappearsFromLaterSnapshots() {
        Quote q = quote("m1", "BTC/USDT", 100, 101, 1, 1, t0, 1);
        store.accept(q, TTL, t0);
        assertEquals(1, store.snapshot(TTL, t0.plusSeconds(4)).size());
        assertEquals(0, store.snapshot(TTL, t0.plusSeconds(6)).size(),
                "quotes past their TTL must be excluded");
        // but expiry at read time does not delete history-version accounting
        assertEquals(1, store.version());
    }

    @Test
    void differentMarketsOrPairsAreIndependent() {
        store.accept(quote("m1", "BTC/USDT", 1, 2, 1, 1, t0, 1), TTL, t0);
        store.accept(quote("m2", "BTC/USDT", 1, 2, 1, 1, t0, 2), TTL, t0);
        store.accept(quote("m1", "ETH/USDT", 1, 2, 1, 1, t0, 3), TTL, t0);
        assertEquals(3, store.snapshot(TTL, t0).size());
    }

    @Test
    void versionIncreasesOncePerAcceptedQuote() {
        Quote q1 = quote("m1", "BTC/USDT", 1, 2, 1, 1, t0, 1);
        Quote dup = quote("m1", "BTC/USDT", 1, 2, 1, 1, t0, 2);
        Quote q2 = quote("m1", "BTC/USDT", 3, 4, 1, 1, t0.plusMillis(10), 3);
        store.accept(q1, TTL, t0);
        store.accept(dup, TTL, t0);
        store.accept(q2, TTL, t0);
        assertEquals(2, store.version());
    }
}
