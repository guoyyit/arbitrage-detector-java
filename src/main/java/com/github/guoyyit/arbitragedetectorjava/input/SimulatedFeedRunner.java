package com.github.guoyyit.arbitragedetectorjava.input;

import com.github.guoyyit.arbitragedetectorjava.domain.Quote;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional demo feed: pushes synthetic quotes for a handful of markets through
 * the same in-memory pipe as real simulated feeds. Disabled by default; enable
 * with {@code arbitrage.simulated-feed.enabled=true}. Everything stays local.
 */
@Component
@ConditionalOnProperty(name = "arbitrage.simulated-feed.enabled", havingValue = "true")
public class SimulatedFeedRunner {
    private final InMemoryQuoteSource source;
    private final ScheduledExecutorService executor;
    private final AtomicLong tick = new AtomicLong();

    public SimulatedFeedRunner(QuoteIngester ingester) {
        this.source = new InMemoryQuoteSource(ingester, 2);
        this.executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "sim-feed");
            t.setDaemon(true);
            return t;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        executor.scheduleAtFixedRate(this::pushMarketA, 0, 250, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(this::pushMarketB, 120, 250, TimeUnit.MILLISECONDS);
    }

    private void pushMarketA() {
        long t = tick.incrementAndGet();
        double wiggle = 1.0 + 0.0008 * Math.sin(t / 7.0);
        Instant now = Instant.now();
        source.pushSync(new Quote("market-a", "BTC", "USDT",
                60000 * wiggle, 60010 * wiggle, 2.0, 2.5, now, -1L));
        source.pushSync(new Quote("market-a", "ETH", "USDT",
                3000 * wiggle, 3001 * wiggle, 20.0, 20.0, now, -1L));
    }

    private void pushMarketB() {
        long t = tick.get();
        double wiggle = 1.0 + 0.0008 * Math.cos(t / 5.0);
        Instant now = Instant.now();
        source.pushSync(new Quote("market-b", "BTC", "USDT",
                60020 * wiggle, 60030 * wiggle, 1.5, 1.8, now, -1L));
        source.pushSync(new Quote("market-b", "ETH", "BTC",
                0.0498 * wiggle, 0.0502 * wiggle, 100, 100, now, -1L));
    }

    public void shutdown() {
        executor.shutdownNow();
        source.shutdown();
    }

    public Duration interval() {
        return Duration.ofMillis(250);
    }
}
