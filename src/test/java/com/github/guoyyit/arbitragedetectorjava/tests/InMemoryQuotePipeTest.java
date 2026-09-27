package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.input.InMemoryQuotePipe;
import com.github.guoyyit.arbitragedetectorjava.input.QuoteSource;
import com.github.guoyyit.arbitragedetectorjava.model.Quote;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The in-memory pipe must preserve FIFO order and drain all quotes. */
class InMemoryQuotePipeTest {

    private static Quote quote(long seq) {
        return new Quote("m1", "BTC", "USD", 100, 101, 10, 10, seq, seq);
    }

    @Test
    void deliversInOrder() throws Exception {
        InMemoryQuotePipe pipe = new InMemoryQuotePipe();
        List<Quote> received = Collections.synchronizedList(new ArrayList<>());
        QuoteSource.QuoteSink sink = q -> {
            received.add(q);
            try { Thread.sleep(2); } catch (InterruptedException ignored) { }
        };
        pipe.start(sink);
        for (long i = 1; i <= 200; i++) {
            pipe.send(quote(i));
        }
        long deadline = System.currentTimeMillis() + 5_000;
        while (received.size() < 200 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        pipe.stop();
        assertEquals(200, received.size());
        for (int i = 0; i < 200; i++) {
            assertEquals(i + 1, received.get(i).sequence(), "FIFO order broken at " + i);
        }
    }
}
