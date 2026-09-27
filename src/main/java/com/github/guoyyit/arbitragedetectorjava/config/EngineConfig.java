package com.github.guoyyit.arbitragedetectorjava.config;

import com.github.guoyyit.arbitragedetectorjava.detect.ArbitrageDetector;
import com.github.guoyyit.arbitragedetectorjava.engine.DetectionEngine;
import com.github.guoyyit.arbitragedetectorjava.input.InMemoryQuotePipe;
import com.github.guoyyit.arbitragedetectorjava.input.SimulatedQuoteSource;
import com.github.guoyyit.arbitragedetectorjava.lifecycle.OpportunityTracker;
import com.github.guoyyit.arbitragedetectorjava.state.QuoteBook;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

/**
 * Spring wiring. Builds the detector stack from {@code arbitrage.*}
 * properties, starts the simulated feed and the detection loop when the
 * application is ready, and stops everything on shutdown.
 */
@Configuration
public class EngineConfig {

    private final InMemoryQuotePipe pipe = new InMemoryQuotePipe();
    private SimulatedQuoteSource source;
    private DetectionEngine engine;

    @Bean
    public ArbitrageProperties arbitrageProperties(Environment env) {
        ArbitrageProperties d = ArbitrageProperties.defaults();
        return new ArbitrageProperties(
                env.getProperty("arbitrage.fee-rate", Double.class, d.feeRate()),
                env.getProperty("arbitrage.slippage-rate", Double.class, d.slippageRate()),
                env.getProperty("arbitrage.min-profit", Double.class, d.minProfit()),
                env.getProperty("arbitrage.max-cycle-length", Integer.class, d.maxCycleLength()),
                env.getProperty("arbitrage.quote-ttl-millis", Long.class, d.quoteTtlMillis()),
                env.getProperty("arbitrage.detect-interval-millis", Long.class, d.detectIntervalMillis()),
                env.getProperty("arbitrage.min-volume", Double.class, d.minVolume()),
                env.getProperty("arbitrage.exit-hysteresis", Double.class, d.exitHysteresis()),
                env.getProperty("arbitrage.opportunity-ttl-millis", Long.class, d.opportunityTtlMillis()),
                env.getProperty("arbitrage.journal-dir", String.class, d.journalDir()));
    }

    @Bean
    public QuoteBook quoteBook() {
        return new QuoteBook();
    }

    @Bean
    public DetectionEngine detectionEngine(QuoteBook book, ArbitrageProperties props) {
        this.engine = new DetectionEngine(book, new ArbitrageDetector(),
                new OpportunityTracker(), props);
        return this.engine;
    }

    @Bean
    public InMemoryQuotePipe quotePipe() {
        return pipe;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startFeeds() {
        pipe.start(q -> engine.quoteBook().submit(q, System.currentTimeMillis(),
                engine.currentConfig().quoteTtlMillis()));
        source = new SimulatedQuoteSource(pipe::send, 42);
        source.start();
        engine.start();
    }

    @PreDestroy
    public void shutdown() {
        if (source != null) {
            source.stop();
        }
        pipe.stop();
        if (engine != null) {
            engine.stop();
        }
    }
}
