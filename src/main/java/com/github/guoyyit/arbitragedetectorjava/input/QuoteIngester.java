package com.github.guoyyit.arbitragedetectorjava.input;

import com.github.guoyyit.arbitragedetectorjava.config.DetectorProperties;
import com.github.guoyyit.arbitragedetectorjava.domain.Quote;
import com.github.guoyyit.arbitragedetectorjava.persistence.StateJournal;
import com.github.guoyyit.arbitragedetectorjava.quote.QuoteStore;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single ingress point for quotes from any number of simulated market feeds.
 *
 * It stamps every incoming quote with a monotonically increasing ingest
 * sequence (arrival order) and delegates acceptance to the {@link QuoteStore},
 * which enforces out-of-order / duplicate / late / expiry rules. Quotes that
 * are actually accepted are durably appended to the write-ahead journal before
 * the accept result is returned, so a crash cannot lose an accepted quote.
 */
@Component
public class QuoteIngester {
    private final QuoteStore store;
    private final DetectorProperties properties;
    private final StateJournal journal;
    private final Clock clock;
    private final AtomicLong seq = new AtomicLong(0);

    @org.springframework.beans.factory.annotation.Autowired
    public QuoteIngester(QuoteStore store, DetectorProperties properties, StateJournal journal) {
        this(store, properties, journal, Clock.systemUTC());
    }

    public QuoteIngester(QuoteStore store, DetectorProperties properties,
                         StateJournal journal, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.journal = journal;
        this.clock = clock;
    }

    /** Stamp and ingest a quote already carrying a market-assigned quoteTime. */
    public QuoteStore.AcceptResult ingest(Quote raw) {
        return ingest(raw, clock.instant());
    }

    public QuoteStore.AcceptResult ingest(Quote raw, Instant now) {
        long s = seq.incrementAndGet();
        Quote stamped = new Quote(raw.market(), raw.base(), raw.quote(),
                raw.bid(), raw.ask(), raw.bidQty(), raw.askQty(), raw.quoteTime(), s);
        QuoteStore.AcceptResult result = store.accept(stamped, properties.getQuoteTtl(), now);
        if (result == QuoteStore.AcceptResult.ACCEPTED) {
            try {
                journal.appendQuote(stamped);
            } catch (Exception e) {
                throw new UncheckedIOException("failed to journal accepted quote", e instanceof java.io.IOException ? (java.io.IOException) e : new java.io.IOException(e));
            }
        }
        return result;
    }

    public long lastIngestSeq() { return seq.get(); }

    /** Restore sequence after recovery so it never moves backwards. */
    public void restoreSeq(long value) {
        seq.updateAndGet(current -> Math.max(current, value));
    }
}
