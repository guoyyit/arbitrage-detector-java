package com.github.guoyyit.arbitragedetectorjava.api;

import com.github.guoyyit.arbitragedetectorjava.config.ArbitrageProperties;
import com.github.guoyyit.arbitragedetectorjava.config.ConfigDto;
import com.github.guoyyit.arbitragedetectorjava.engine.DetectionEngine;
import com.github.guoyyit.arbitragedetectorjava.input.InMemoryQuotePipe;
import com.github.guoyyit.arbitragedetectorjava.model.Quote;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Query and control endpoints:
 * <ul>
 *   <li>GET  /api/opportunities - currently active opportunities</li>
 *   <li>GET  /api/events        - recent auditable lifecycle events</li>
 *   <li>GET  /api/quotes        - current valid quotes (point-in-time snapshot)</li>
 *   <li>POST /api/quotes        - inject a quote through the in-memory pipe</li>
 *   <li>GET  /api/config        - current configuration</li>
 *   <li>PUT  /api/config        - atomically update configuration for the next cycle</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ArbitrageController {

    private final DetectionEngine engine;
    private final InMemoryQuotePipe pipe;

    public ArbitrageController(DetectionEngine engine, InMemoryQuotePipe pipe) {
        this.engine = engine;
        this.pipe = pipe;
    }

    @GetMapping("/opportunities")
    public List<?> opportunities() {
        return engine.tracker().active();
    }

    @GetMapping("/events")
    public List<?> events() {
        return engine.auditLog();
    }

    @GetMapping("/quotes")
    public List<?> quotes() {
        ArbitrageProperties cfg = engine.currentConfig();
        long now = System.currentTimeMillis();
        return engine.quoteBook().snapshot(now, cfg.quoteTtlMillis()).all();
    }

    @PostMapping("/quotes")
    public String inject(@RequestBody Quote quote) {
        pipe.send(quote);
        return "queued";
    }

    @GetMapping("/config")
    public ConfigDto config() {
        return ConfigDto.of(engine.currentConfig());
    }

    @PutMapping("/config")
    public ConfigDto updateConfig(@RequestBody ConfigDto update) {
        engine.updateConfig(update.merged(engine.currentConfig()));
        return ConfigDto.of(engine.currentConfig());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
