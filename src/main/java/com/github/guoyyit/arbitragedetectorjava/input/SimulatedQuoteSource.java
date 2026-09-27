package com.github.guoyyit.arbitragedetectorjava.input;

import com.github.guoyyit.arbitragedetectorjava.model.Quote;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local simulated market feed. Random-walks mid prices for a fixed
 * universe of markets and pairs on a background thread. Per-market price
 * offsets drift independently, so cross-market gaps (and thus arbitrage
 * opportunities) appear and disappear naturally. Purely in-process: no
 * network, no external service.
 */
public class SimulatedQuoteSource implements QuoteSource {

    private record Slot(String market, String base, String quote, double mid) {
    }

    private final QuoteSink sink;
    private final Random random;
    private final long tickMillis;
    private final List<Slot> slots;
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private volatile Thread thread;
    private volatile boolean running;

    public SimulatedQuoteSource(QuoteSink sink, long seed) {
        this(sink, seed, 200);
    }

    public SimulatedQuoteSource(QuoteSink sink, long seed, long tickMillis) {
        this.sink = sink;
        this.random = new Random(seed);
        this.tickMillis = tickMillis;
        this.slots = List.of(
                new Slot("m1", "BTC", "USD", 50_000),
                new Slot("m2", "BTC", "USD", 50_000),
                new Slot("m3", "BTC", "USD", 50_000),
                new Slot("m1", "ETH", "USD", 3_000),
                new Slot("m2", "ETH", "USD", 3_000),
                new Slot("m3", "ETH", "USD", 3_000),
                new Slot("m1", "EUR", "USD", 1.10),
                new Slot("m2", "EUR", "USD", 1.10));
    }

    @Override
    public void start() {
        running = true;
        thread = new Thread(this::run, "simulated-quote-source");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        double[] drift = new double[slots.size()];
        while (running) {
            for (int i = 0; i < slots.size(); i++) {
                Slot s = slots.get(i);
                // Per-slot random walk; occasionally a larger jump creates gaps.
                drift[i] += (random.nextDouble() - 0.5) * 0.001;
                drift[i] = Math.max(-0.01, Math.min(0.01, drift[i]));
                double mid = s.mid() * (1 + drift[i]);
                double spread = mid * 0.0005;
                double bid = mid - spread;
                double ask = mid + spread;
                double vol = 1 + 9 * random.nextDouble();
                long seq = sequences.computeIfAbsent(
                        s.market() + ":" + s.base() + "/" + s.quote(),
                        k -> new AtomicLong()).incrementAndGet();
                sink.onQuote(new Quote(s.market(), s.base(), s.quote(),
                        bid, ask, vol, vol, seq, System.currentTimeMillis()));
            }
            try {
                Thread.sleep(tickMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
