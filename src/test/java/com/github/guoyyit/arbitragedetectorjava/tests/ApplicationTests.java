package com.github.guoyyit.arbitragedetectorjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Wiring smoke test: the whole component graph (store, ingester, journal,
 * detector, tracker, engine, scheduler, config and REST controller) must start
 * with the bound configuration.
 */
@SpringBootTest(properties = "arbitrage.journal-path=target/test-data/context-journal.log")
public class ApplicationTests {
    @Test
    public void contentLoad() {
    }
}
