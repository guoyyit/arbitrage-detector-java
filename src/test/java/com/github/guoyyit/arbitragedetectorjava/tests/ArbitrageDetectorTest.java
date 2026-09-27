package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.config.DetectorProperties;
import com.github.guoyyit.arbitragedetectorjava.domain.Hop;
import com.github.guoyyit.arbitragedetectorjava.domain.Opportunity;
import com.github.guoyyit.arbitragedetectorjava.domain.Quote;
import com.github.guoyyit.arbitragedetectorjava.detect.ArbitrageDetector;
import com.github.guoyyit.arbitragedetectorjava.quote.QuoteSnapshot;
import com.github.guoyyit.arbitragedetectorjava.quote.QuoteStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArbitrageDetectorTest {
    private final ArbitrageDetector detector = new ArbitrageDetector();
    private final Instant now = Instant.parse("2026-09-28T10:00:00Z");

    private DetectorProperties config() {
        DetectorProperties p = new DetectorProperties();
        p.setFeeRate(0.001);
        p.setSlippageRate(0.0005);
        p.setMinProfitRate(0.001);
        p.setMaxCycleLength(3);
        p.setQuoteTtl(Duration.ofSeconds(30));
        p.setMinVolume(0);
        p.setExitMargin(0.0005);
        return p;
    }

    private Quote q(String market, String base, String quote, double bid, double ask,
                    double bq, double aq, Instant time) {
        return new Quote(market, base, quote, bid, ask, bq, aq, time, 1);
    }

    private QuoteSnapshot snapshot(Quote... quotes) {
        Map<String, Quote> map = new LinkedHashMap<>();
        long seq = 1;
        for (Quote quote : quotes) {
            Quote stamped = new Quote(quote.market(), quote.base(), quote.quote(),
                    quote.bid(), quote.ask(), quote.bidQty(), quote.askQty(),
                    quote.quoteTime(), seq++);
            map.put(stamped.key(), stamped);
        }
        return new QuoteSnapshot(1L, now, map);
    }

    @Test
    void detectsProfitableTwoHopCycleAcrossMarkets() {
        // sell BTC on market-a at 60000, buy BTC on market-b at 59000
        QuoteSnapshot snap = snapshot(
                q("market-a", "BTC", "USDT", 60000, 60010, 2, 2, now),
                q("market-b", "BTC", "USDT", 58900, 59000, 2, 2, now));
        List<Opportunity> result = detector.detect(snap, config());
        assertEquals(1, result.size());
        Opportunity op = result.get(0);
        assertEquals(2, op.hops().size());
        assertTrue(op.netRate() > 0.001,
                "net rate after fees+slippage must exceed threshold: " + op.netRate());
        // expected: 60000/59000 * (0.999*0.9995)^2 - 1
        double expected = 60000.0 / 59000.0 * Math.pow(0.999 * 0.9995, 2) - 1.0;
        assertEquals(expected, op.netRate(), 1e-9);
        // hop identity: sell on a, buy on b
        assertEquals(Hop.Side.SELL, op.hops().get(0).side());
        assertEquals("market-a", op.hops().get(0).market());
        assertEquals(Hop.Side.BUY, op.hops().get(1).side());
        assertEquals("market-b", op.hops().get(1).market());
    }

    @Test
    void noOpportunityWhenPricesDoNotCoverFees() {
        // gross edge ~0.08% but two hops cost ~0.3% -> net negative
        QuoteSnapshot snap = snapshot(
                q("market-a", "BTC", "USDT", 60000, 60020, 2, 2, now),
                q("market-b", "BTC", "USDT", 59950, 59970, 2, 2, now));
        assertTrue(detector.detect(snap, config()).isEmpty());
    }

    @Test
    void detectsProfitableThreeHopTriangle() {
        // BTC->USDT 60000; USDT->ETH at ask 2990; ETH->BTC at bid 0.0505
        QuoteSnapshot snap = snapshot(
                q("m", "BTC", "USDT", 60000, 60010, 10, 10, now),
                q("m", "ETH", "USDT", 2980, 2990, 100, 100, now),
                q("m", "ETH", "BTC", 0.0504, 0.0506, 100, 100, now));
        DetectorProperties cfg = config();
        List<Opportunity> three = detector.detect(snap, cfg);
        assertFalse(three.isEmpty());
        assertTrue(three.stream().anyMatch(o -> o.hops().size() == 3),
                "expected a 3-hop triangular opportunity");

        // cap cycle length at 2 -> triangle must disappear
        cfg.setMaxCycleLength(2);
        assertTrue(detector.detect(snap, cfg).stream()
                .noneMatch(o -> o.hops().size() == 3));
    }

    @Test
    void minVolumeFiltersThinBooks() {
        QuoteSnapshot snap = snapshot(
                q("market-a", "BTC", "USDT", 60000, 60010, 0.01, 0.01, now),
                q("market-b", "BTC", "USDT", 58900, 59000, 0.01, 0.01, now));
        DetectorProperties cfg = config();
        assertFalse(detector.detect(snap, cfg).isEmpty());
        cfg.setMinVolume(1.0); // need 1 BTC executable
        assertTrue(detector.detect(snap, cfg).isEmpty(),
                "thin book must be filtered out by minimum volume");
    }

    @Test
    void detectionIsDeterministicForSameSnapshot() {
        QuoteSnapshot snap = snapshot(
                q("market-a", "BTC", "USDT", 60000, 60010, 2, 2, now),
                q("market-b", "BTC", "USDT", 58900, 59000, 2, 2, now),
                q("m", "ETH", "USDT", 2980, 2990, 100, 100, now),
                q("m", "ETH", "BTC", 0.0504, 0.0506, 100, 100, now));
        DetectorProperties cfg = config();
        List<Opportunity> r1 = detector.detect(snap, cfg);
        List<Opportunity> r2 = detector.detect(snap, cfg);
        assertEquals(r1.size(), r2.size());
        for (int i = 0; i < r1.size(); i++) {
            assertEquals(r1.get(i).id(), r2.get(i).id());
            assertEquals(r1.get(i).netRate(), r2.get(i).netRate(), 0);
        }
        // sorted by descending net rate
        for (int i = 1; i < r1.size(); i++) {
            assertTrue(r1.get(i - 1).netRate() >= r1.get(i).netRate());
        }
    }

    @Test
    void rotatedCycleIsEmittedExactlyOnce() {
        // only one triangle exists in this book; it must not appear under 3
        // different start currencies as 3 duplicates
        QuoteSnapshot snap = snapshot(
                q("m", "BTC", "USDT", 60000, 60010, 10, 10, now),
                q("m", "ETH", "USDT", 2980, 2990, 100, 100, now),
                q("m", "ETH", "BTC", 0.0504, 0.0506, 100, 100, now));
        List<Opportunity> result = detector.detect(snap, config());
        long threeHops = result.stream().filter(o -> o.hops().size() == 3).count();
        assertEquals(1, threeHops, "one structural cycle -> one opportunity");
    }

    @Test
    void structuralIdIsStableAcrossSnapshots() {
        Quote a = q("market-a", "BTC", "USDT", 60000, 60010, 2, 2, now);
        Quote b = q("market-b", "BTC", "USDT", 58900, 59000, 2, 2, now);
        List<Opportunity> r1 = detector.detect(snapshot(a, b), config());
        Quote a2 = new Quote("market-a", "BTC", "USDT", 60100, 60110, 2, 2,
                now.plusMillis(100), 3);
        Quote b2 = new Quote("market-b", "BTC", "USDT", 58900, 59000, 2, 2,
                now.plusMillis(100), 4);
        List<Opportunity> r2 = detector.detect(snapshot(a2, b2), config());
        assertEquals(1, r1.size());
        assertEquals(1, r2.size());
        assertEquals(r1.get(0).id(), r2.get(0).id(),
                "same markets/pairs/sides -> same identity despite price change");
    }
}
