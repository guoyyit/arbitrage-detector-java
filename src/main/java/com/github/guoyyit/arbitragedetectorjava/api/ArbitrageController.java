package com.github.guoyyit.arbitragedetectorjava.api;

import com.github.guoyyit.arbitragedetectorjava.config.ConfigService;
import com.github.guoyyit.arbitragedetectorjava.domain.Opportunity;
import com.github.guoyyit.arbitragedetectorjava.domain.OpportunityEvent;
import com.github.guoyyit.arbitragedetectorjava.domain.Quote;
import com.github.guoyyit.arbitragedetectorjava.engine.DetectionEngine;
import com.github.guoyyit.arbitragedetectorjava.input.QuoteIngester;
import com.github.guoyyit.arbitragedetectorjava.opportunity.OpportunityTracker;
import com.github.guoyyit.arbitragedetectorjava.quote.QuoteStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Local-only HTTP surface used to feed simulated quotes, inspect opportunities
 * and the audit trail, trigger a detection run, and view/update configuration.
 * Bind to localhost only in {@code application.properties}; there is no
 * dependency on any external market service.
 */
@RestController
@RequestMapping("/api")
public class ArbitrageController {
    private final QuoteIngester ingester;
    private final OpportunityTracker tracker;
    private final DetectionEngine engine;
    private final ConfigService configService;

    public ArbitrageController(QuoteIngester ingester, OpportunityTracker tracker,
                               DetectionEngine engine, ConfigService configService) {
        this.ingester = ingester;
        this.tracker = tracker;
        this.engine = engine;
        this.configService = configService;
    }

    /** Accept one simulated market quote; returns the store's acceptance verdict. */
    @PostMapping("/quotes")
    public Map<String, Object> postQuote(@RequestBody QuoteDto dto) {
        Quote q = new Quote(dto.market(), dto.base(), dto.quote(),
                dto.bid(), dto.ask(), dto.bidQty(), dto.askQty(),
                dto.quoteTime() == null ? Instant.now() : dto.quoteTime(), -1L);
        QuoteStore.AcceptResult verdict = ingester.ingest(q);
        return Map.of("result", verdict.name(), "ingestSeq", ingester.lastIngestSeq());
    }

    /** Force one detection cycle immediately (the scheduler also runs these). */
    @PostMapping("/detect")
    public Map<String, Object> detect() {
        List<OpportunityEvent> emitted = engine.runOnce();
        return Map.of(
                "emittedEvents", emitted,
                "activeCount", tracker.activeOpportunities().size());
    }

    @GetMapping("/opportunities")
    public List<Opportunity> opportunities() {
        return tracker.activeOpportunities();
    }

    @GetMapping("/events")
    public List<OpportunityEvent> events() {
        return tracker.events();
    }

    @GetMapping("/config")
    public ConfigService.ConfigView getConfig() {
        return ConfigService.ConfigView.from(configService.properties());
    }

    @PutMapping("/config")
    public ResponseEntity<?> putConfig(@RequestBody ConfigService.ConfigView updated) {
        List<String> errors = configService.update(updated);
        if (!errors.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("errors", errors));
        }
        return ResponseEntity.ok(ConfigService.ConfigView.from(configService.properties()));
    }

    /** Wire DTO for quote submission. */
    public record QuoteDto(String market, String base, String quote,
                           double bid, double ask, double bidQty, double askQty,
                           Instant quoteTime) {
    }
}
