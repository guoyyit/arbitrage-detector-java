package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.config.DetectorProperties;
import com.github.guoyyit.arbitragedetectorjava.domain.Quote;
import com.github.guoyyit.arbitragedetectorjava.input.InMemoryQuoteSource;
import com.github.guoyyit.arbitragedetectorjava.input.QuoteIngester;
import com.github.guoyyit.arbitragedetectorjava.persistence.StateJournal;
import com.github.guoyyit.arbitragedetectorjava.quote.QuoteSnapshot;
import com.github.guoyyit.arbitragedetectorjava.quote.QuoteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryQuotePipeTest {

    @TempDir
    Path tmp;

    private DetectorProperties props(Path journal) {
        DetectorProperties p = new DetectorProperties();
        p.setQuoteTtl(Duration.ofSeconds(5));
        p.setJournalPath(journal.toString());
        return p;
    }

    private Quote quote(String market, double bid, Instant quoteTime) {
        return new Quote(market, "BTC", "USDT", bid, bid + 10, 5, 5, quoteTime, -1L);
    }

    @Test
    void concurrentPushFromManyFeedsDeliversEveryAcceptedQuote() throws Exception {
        Path f = tmp.resolve("pipe.log");
        DetectorProperties p = props(f);
        QuoteStore store = new QuoteStore();
        StateJournal journal = new StateJournal(f);
        QuoteIngester ingester = new QuoteIngester(store, p, journal);
        InMemoryQuoteSource source = new InMemoryQuoteSource(ingester, 4);

        int feeds = 8;
        int perFeed = 100;
        CountDownLatch latch = new CountDownLatch(feeds);
        Instant base = Instant.now();
        List<Thread> producers = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        for (int m = 0; m < feeds; m++) {
            String market = "market-" + m;
            Thread th = new Thread(() -> {
                try {
                    for (int i = 0; i < perFeed; i++) {
                        Instant t = base.plusMillis(clock.incrementAndGet());
                        source.push(quote(market, 100 + i, t));
                    }
                } finally {
                    latch.countDown();
                }
            });
            producers.add(th);
            th.start();
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        source.drain(10, TimeUnit.SECONDS);

        QuoteSnapshot snap = store.snapshot(p.getQuoteTtl(), Instant.now());
        assertEquals(feeds, snap.size(), "one latest quote per market feed");
        assertEquals(feeds * perFeed, ingester.lastIngestSeq(),
                "every pushed quote received a monotonic ingest sequence");
        source.shutdown();
        journal.close();
    }

    @Test
    void outOfOrderDuplicateAndLateQuotesHandledEndToEnd() throws Exception {
        Path f = tmp.resolve("order.log");
        DetectorProperties p = props(f);
        QuoteStore store = new QuoteStore();
        StateJournal journal = new StateJournal(f);
        QuoteIngester ingester = new QuoteIngester(store, p, journal);
        InMemoryQuoteSource source = new InMemoryQuoteSource(ingester, 2);
        Instant t0 = Instant.now();

        Quote q1 = quote("m", 100, t0);
        Quote q1Dup = quote("m", 100, t0);
        Quote q2 = quote("m", 120, t0.plusMillis(200));
        Quote stale = quote("m", 90, t0.minusMillis(100));
        Quote lateButNewer = quote("m", 130, t0.plusMillis(300));

        // duplicate of the currently-stored quote is recognized as DUPLICATE
        assertEquals(QuoteStore.AcceptResult.ACCEPTED, ingester.ingest(q1, t0.plusMillis(50)));
        assertEquals(QuoteStore.AcceptResult.DUPLICATE,
                ingester.ingest(q1Dup, t0.plusMillis(60)));
        // newer quote supersedes q1
        assertEquals(QuoteStore.AcceptResult.ACCEPTED, ingester.ingest(q2, t0.plusMillis(250)));
        // both an even older quote and the now-superseded q1 arrive out of order
        assertEquals(QuoteStore.AcceptResult.STALE, ingester.ingest(stale, t0.plusMillis(260)));
        assertEquals(QuoteStore.AcceptResult.STALE, ingester.ingest(q1, t0.plusMillis(270)));
        // arrives late in wall-clock terms but carries the newest quoteTime
        assertEquals(QuoteStore.AcceptResult.ACCEPTED,
                ingester.ingest(lateButNewer, t0.plusMillis(900)));

        QuoteSnapshot snap = store.snapshot(p.getQuoteTtl(), t0.plusMillis(900));
        assertEquals(130, snap.get("m:BTC/USDT").bid(), 1e-9);
        source.shutdown();
        journal.close();
    }

    @Test
    void expiredQuotesAreExcludedFromSnapshotsButPipeKeepsRunning() throws Exception {
        Path f = tmp.resolve("ttl.log");
        DetectorProperties p = props(f);
        QuoteStore store = new QuoteStore();
        StateJournal journal = new StateJournal(f);
        QuoteIngester ingester = new QuoteIngester(store, p, journal);
        Instant t0 = Instant.now();

        ingester.ingest(quote("old-mkt", 100, t0.minusSeconds(6)), t0);
        ingester.ingest(quote("fresh-mkt", 100, t0), t0);

        QuoteSnapshot snap = store.snapshot(p.getQuoteTtl(), t0);
        assertEquals(1, snap.size());
        assertNotNull(snap.get("fresh-mkt:BTC/USDT"));
        assertNull(snap.get("old-mkt:BTC/USDT"));
        journal.close();
    }

    @Test
    void asyncDeliveryAssignsMonotonicSequences() throws Exception {
        Path f = tmp.resolve("seq.log");
        DetectorProperties p = props(f);
        QuoteStore store = new QuoteStore();
        StateJournal journal = new StateJournal(f);
        QuoteIngester ingester = new QuoteIngester(store, p, journal);
        InMemoryQuoteSource source = new InMemoryQuoteSource(ingester, 3);

        Instant t0 = Instant.now();
        List<Quote> pushed = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 300; i++) {
            Quote q = new Quote("m" + (i % 4), "BTC", "USDT",
                    100 + i, 110 + i, 5, 5, t0.plusMillis(i), -1L);
            pushed.add(q);
            source.push(q);
        }
        source.drain(10, TimeUnit.SECONDS);
        assertEquals(300, ingester.lastIngestSeq());
        source.shutdown();
        journal.close();
    }
}
