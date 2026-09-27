package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.config.ArbitrageProperties;
import com.github.guoyyit.arbitragedetectorjava.detect.ArbitrageDetector;
import com.github.guoyyit.arbitragedetectorjava.model.ArbitrageOpportunity;
import com.github.guoyyit.arbitragedetectorjava.model.Quote;
import com.github.guoyyit.arbitragedetectorjava.state.QuoteBook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArbitrageDetectorTest {

    private static final long NOW = 1_000_000L;

    private ArbitrageProperties props() {
        return new ArbitrageProperties(0.001, 0.0005, 0.001, 4, 60_000, 1_000,
                0.0001, 0.5, 30_000, "data");
    }

    private Quote q(String market, String base, String quote, double bid, double ask,
                    double bidVol, double askVol, long seq) {
        return new Quote(market, base, quote, bid, ask, bidVol, askVol, seq, NOW);
    }

    @Test
    void detectsTwoHopCrossMarketCycle() {
        QuoteBook book = new QuoteBook();
        // BTC/USD on two markets with a 2% price gap.
        book.submit(q("m1", "BTC", "USD", 100, 101, 10, 10, 1), NOW, 60_000);
        book.submit(q("m2", "BTC", "USD", 103, 104, 10, 10, 1), NOW, 60_000);
        var snapshot = book.snapshot(NOW, 60_000);

        List<ArbitrageOpportunity> ops = new ArbitrageDetector().detect(snapshot, props());
        assertEquals(1, ops.size());
        ArbitrageOpportunity op = ops.get(0);
        assertEquals(2, op.hops().size());
        // Cycle starts at BTC (smallest currency): sell BTC on m2 at 103, buy back on m1 at 101.
        assertEquals("m2", op.hops().get(0).market());
        assertEquals("BTC", op.hops().get(0).from());
        assertEquals("USD", op.hops().get(0).to());
        assertEquals("m1", op.hops().get(1).market());
        // net = 103*(1-f)*(1-s) * (1/101)*(1-f)*(1-s) - 1
        double c = (1 - 0.001) * (1 - 0.0005);
        double net = (1.0 / 101) * c * 103 * c - 1;
        assertEquals(net, op.netReturn(), 1e-9);
        assertTrue(op.netReturn() > props().minProfit());
    }

    @Test
    void detectsThreeHopCycle() {
        QuoteBook book = new QuoteBook();
        // USD -> EUR -> JPY -> USD loop with ~1% gross edge.
        book.submit(q("m1", "EUR", "USD", 1.10, 1.11, 1000, 1000, 1), NOW, 60_000);
        book.submit(q("m2", "EUR", "JPY", 160.0, 161.0, 1000, 1000, 1), NOW, 60_000);
        book.submit(q("m3", "JPY", "USD", 0.0068, 0.0069, 1_000_000, 1_000_000, 1), NOW, 60_000);
        var snapshot = book.snapshot(NOW, 60_000);

        List<ArbitrageOpportunity> ops = new ArbitrageDetector().detect(snapshot, props());
        // USD->EUR (1/1.11), EUR->JPY (160), JPY->USD (0.0068): gross = 160*0.0068/1.11 = 0.980 -> loss.
        // Reverse: USD->JPY (1/0.0069), JPY->EUR (1/161), EUR->USD (1.10): gross = 1.10/(0.0069*161) = 0.9903 -> loss.
        // No profitable cycle expected with these symmetric-ish numbers.
        assertEquals(0, ops.size());

        // Make EUR/USD bid clearly profitable for USD->JPY->EUR->USD.
        QuoteBook book2 = new QuoteBook();
        book2.submit(q("m1", "EUR", "USD", 1.25, 1.26, 1000, 1000, 1), NOW, 60_000);
        book2.submit(q("m2", "EUR", "JPY", 160.0, 161.0, 1000, 1000, 1), NOW, 60_000);
        book2.submit(q("m3", "JPY", "USD", 0.0068, 0.0069, 1_000_000, 1_000_000, 1), NOW, 60_000);
        var snap2 = book2.snapshot(NOW, 60_000);
        List<ArbitrageOpportunity> ops2 = new ArbitrageDetector().detect(snap2, props());
        assertEquals(1, ops2.size());
        assertEquals(3, ops2.get(0).hops().size());
        // gross = (1/0.0069) * (1/161) * 1.25 - 1 = 1.1254 - 1
        double gross = 1.25 / (0.0069 * 161) - 1;
        assertEquals(gross, ops2.get(0).grossReturn(), 1e-9);
    }

    @Test
    void respectsMaxCycleLengthAndMinVolume() {
        QuoteBook book = new QuoteBook();
        book.submit(q("m1", "BTC", "USD", 100, 101, 10, 10, 1), NOW, 60_000);
        book.submit(q("m2", "BTC", "USD", 103, 104, 10, 10, 1), NOW, 60_000);
        var snapshot = book.snapshot(NOW, 60_000);

        // maxCycleLength = 2 still allows the 2-hop cycle.
        var p2 = new ArbitrageProperties(0.001, 0.0005, 0.001, 2, 60_000, 1_000, 0.0001, 0.5, 30_000, "d");
        assertEquals(1, new ArbitrageDetector().detect(snapshot, p2).size());

        // Huge minVolume filters it out (capacity is 10 BTC / ~1010 USD).
        var pv = new ArbitrageProperties(0.001, 0.0005, 0.001, 2, 60_000, 1_000, 1_000_000, 0.5, 30_000, "d");
        assertEquals(0, new ArbitrageDetector().detect(snapshot, pv).size());

        // Huge minProfit filters it out.
        var pp = new ArbitrageProperties(0.001, 0.0005, 0.5, 2, 60_000, 1_000, 0.0001, 0.5, 30_000, "d");
        assertEquals(0, new ArbitrageDetector().detect(snapshot, pp).size());
    }

    @Test
    void deterministicAcrossRuns() {
        QuoteBook book = new QuoteBook();
        book.submit(q("m1", "BTC", "USD", 100, 101, 10, 10, 1), NOW, 60_000);
        book.submit(q("m2", "BTC", "USD", 103, 104, 10, 10, 1), NOW, 60_000);
        book.submit(q("m1", "ETH", "USD", 10, 10.1, 100, 100, 2), NOW, 60_000);
        book.submit(q("m2", "ETH", "USD", 10.4, 10.5, 100, 100, 2), NOW, 60_000);
        var snapshot = book.snapshot(NOW, 60_000);

        var d = new ArbitrageDetector();
        List<ArbitrageOpportunity> a = d.detect(snapshot, props());
        List<ArbitrageOpportunity> b = d.detect(snapshot, props());
        assertEquals(a, b);
        assertEquals(2, a.size());
    }
}
