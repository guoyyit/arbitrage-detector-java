package com.github.guoyyit.arbitragedetectorjava.input;

import com.github.guoyyit.arbitragedetectorjava.model.Quote;

/**
 * A source of quotes. Implementations simulate market feeds locally
 * (loopback / in-memory pipe); no external services are used.
 */
public interface QuoteSource {

    /** Start producing quotes into the sink. */
    void start();

    /** Stop producing quotes. */
    void stop();

    /** Sink invoked by sources for every produced quote. */
    interface QuoteSink {
        void onQuote(Quote quote);
    }
}
