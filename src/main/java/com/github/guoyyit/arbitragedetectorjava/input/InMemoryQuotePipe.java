package com.github.guoyyit.arbitragedetectorjava.input;

import com.github.guoyyit.arbitragedetectorjava.model.Quote;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-memory pipe: producers {@link #send} quotes without blocking, one
 * consumer thread drains the queue into the sink in FIFO order. This is
 * the loopback transport used to simulate market feeds in-process.
 */
public class InMemoryQuotePipe {

    private final BlockingQueue<Quote> queue = new LinkedBlockingQueue<>();
    private volatile Thread consumer;
    private volatile boolean running;

    /** Non-blocking; safe to call from any thread. */
    public void send(Quote quote) {
        queue.offer(quote);
    }

    public void start(QuoteSource.QuoteSink sink) {
        running = true;
        consumer = new Thread(() -> {
            while (running || !queue.isEmpty()) {
                try {
                    Quote q = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (q != null) {
                        sink.onQuote(q);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "quote-pipe-consumer");
        consumer.setDaemon(true);
        consumer.start();
    }

    public void stop() {
        running = false;
        if (consumer != null) {
            consumer.interrupt();
        }
    }
}
