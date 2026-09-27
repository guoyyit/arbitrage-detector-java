package com.github.guoyyit.arbitragedetectorjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "arbitrage.journal-path=target/test-data/api-journal.log",
                "arbitrage.quote-ttl=1h",
                "arbitrage.min-profit-rate=0.001",
                "arbitrage.max-cycle-length=2",
                "arbitrage.detection-interval=1h"
        })
class ApiIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private Map<String, Object> quoteJson(String market, double bid, double ask) {
        return Map.of(
                "market", market, "base", "BTC", "quote", "USDT",
                "bid", bid, "ask", ask, "bidQty", 5, "askQty", 5,
                "quoteTime", Instant.now().toString());
    }

    @Test
    void fullFlowOverHttp_quoteDetectInspectConfig() {
        // two markets with a profitable cross spread after costs
        ResponseEntity<Map> r1 = rest.postForEntity(url("/api/quotes"),
                new HttpEntity<>(quoteJson("market-a", 60000, 60010)), Map.class);
        assertEquals(200, r1.getStatusCode().value());
        assertEquals("ACCEPTED", r1.getBody().get("result"));

        ResponseEntity<Map> r2 = rest.postForEntity(url("/api/quotes"),
                new HttpEntity<>(quoteJson("market-b", 58900, 59000)), Map.class);
        assertEquals(200, r2.getStatusCode().value());
        assertEquals("ACCEPTED", r2.getBody().get("result"));

        // duplicate re-submission of identical book would be DUPLICATE if quoteTime
        // matched; here we just trigger a detection
        ResponseEntity<Map> detect = rest.postForEntity(url("/api/detect"),
                HttpEntity.EMPTY, Map.class);
        assertEquals(200, detect.getStatusCode().value());
        Number active = (Number) detect.getBody().get("activeCount");
        assertEquals(1, active.intValue());

        ResponseEntity<Object[]> opps =
                rest.getForEntity(url("/api/opportunities"), Object[].class);
        assertEquals(200, opps.getStatusCode().value());
        assertEquals(1, opps.getBody().length);

        ResponseEntity<Object[]> events =
                rest.getForEntity(url("/api/events"), Object[].class);
        assertEquals(1, events.getBody().length, "one APPEARED audit event");

        // config is readable
        ResponseEntity<Map> cfg = rest.getForEntity(url("/api/config"), Map.class);
        assertEquals(200, cfg.getStatusCode().value());
        assertEquals(2, ((Number) cfg.getBody().get("maxCycleLength")).intValue());

        // invalid config update must be rejected with 400 and errors, old values kept
        Map<String, Object> bad = new java.util.HashMap<>(quoteJson("ignored", 1, 1));
        bad.clear();
        bad.put("feeRate", 0.5);
        bad.put("slippageRate", 0.0005);
        bad.put("minProfitRate", 0.001);
        bad.put("maxCycleLength", 1);
        bad.put("quoteTtl", "PT1S");
        bad.put("detectionInterval", "PT1S");
        bad.put("minVolume", 0);
        bad.put("exitMargin", 0.0005);
        bad.put("updateEventEpsilon", 1.0e-9);
        ResponseEntity<Map> rejected = rest.exchange(url("/api/config"),
                HttpMethod.PUT, new HttpEntity<>(bad), Map.class);
        assertEquals(400, rejected.getStatusCode().value());
        assertNotNull(rejected.getBody().get("errors"));

        ResponseEntity<Map> cfgAfter = rest.getForEntity(url("/api/config"), Map.class);
        assertEquals(2, ((Number) cfgAfter.getBody().get("maxCycleLength")).intValue(),
                "rejected update must leave previous configuration in place");
    }
}
