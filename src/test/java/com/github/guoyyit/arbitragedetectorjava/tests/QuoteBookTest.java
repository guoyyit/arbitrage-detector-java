package com.github.guoyyit.arbitragedetectorjava.tests;

import com.github.guoyyit.arbitragedetectorjava.model.Quote;
import com.github.guoyyit.arbitragedetectorjava.state.QuoteBook;
import com.github.guoyyit.arbitragedetectorjava.state.QuoteSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuoteBookTest {

    private static final long NOW = 10_000L;
    private static final long TTL = 5_000L;

    private Quote q(long seq, long ts) {
        return new Quote("m1", "BTC", "USD", 100, 101, 10, 10, seq, ts);
    }

    @Test
    void acceptsFreshQuote() {
        QuoteBook book = new QuoteBook();
        assertEquals(QuoteBook.SubmitResult.ACCEPTED, book.submit(q(1, NOW), NOW, TTL));
        assertEquals(100, book.snapshot(NOW, TTL).all().get(0).bid(), 0);
    }

    @Test
    void rejectsOutOfOrderQuoteBySequence() {
        QuoteBook book = new QuoteBook();
        book.submit(q(2, NOW), NOW, TTL);
        // Older sequence arriving later: even with a newer timestamp it must be ignored.
        QuoteBook.SubmitResult r = book.submit(q(1, NOW + 100), NOW + 100, TTL);
        assertEquals(QuoteBook.SubmitResult.STALE_SEQUENCE, r);
        QuoteSnapshot snap = book.snapshot(NOW + 100, TTL);
        assertEquals(1, snap.all().size());
        assertEquals(2, snap.all().get(0).sequence());
    }

    @Test
    void rejectsDuplicate() {
        QuoteBook book = new QuoteBook();
        assertEquals(QuoteBook.SubmitResult.ACCEPTED, book.submit(q(1, NOW), NOW, TTL));
        assertEquals(QuoteBook.SubmitResult.DUPLICATE, book.submit(q(1, NOW), NOW, TTL));
        // Same sequence replayed at a later wall-clock time is still a duplicate.
        assertEquals(QuoteBook.SubmitResult.DUPLICATE,
                book.submit(q(1, NOW + 50), NOW + 50, TTL));
        assertEquals(1, book.snapshot(NOW + 50, TTL).all().size());
    }

    @Test
    void rejectsLateExpiredQuote() {
        QuoteBook book = new QuoteBook();
        // Quote whose validity window has already elapsed at arrival.
        assertEquals(QuoteBook.SubmitResult.EXPIRED,
                book.submit(q(1, NOW - TTL - 1), NOW, TTL));
        assertEquals(0, book.size());
    }

    @Test
    void expiredQuoteExcludedFromSnapshotAndPurged() {
        QuoteBook book = new QuoteBook();
        book.submit(q(1, NOW), NOW, TTL);
        // Still valid exactly at now + ttl? timestamp + ttl > now required.
        assertEquals(1, book.snapshot(NOW + TTL - 1, TTL).all().size());
        assertEquals(0, book.snapshot(NOW + TTL, TTL).all().size());
        assertEquals(0, book.snapshot(NOW + TTL + 100, TTL).all().size());
        assertEquals(1, book.purgeExpired(NOW + TTL + 100, TTL));
        assertEquals(0, book.size());
    }

    @Test
    void newerSequenceReplacesSlotAndAdvancesVersion() {
        QuoteBook book = new QuoteBook();
        book.submit(q(1, NOW), NOW, TTL);
        long v1 = book.snapshot(NOW, TTL).version();
        book.submit(q(2, NOW + 100), NOW + 100, TTL);
        QuoteSnapshot snap = book.snapshot(NOW + 100, TTL);
        assertEquals(2, snap.all().get(0).sequence());
        assertTrue(snap.version() > v1);
    }

    @Test
    void snapshotIsImmutable() {
        QuoteBook book = new QuoteBook();
        book.submit(q(1, NOW), NOW, TTL);
        QuoteSnapshot snap = book.snapshot(NOW, TTL);
        book.submit(q(2, NOW + 100), NOW + 100, TTL);
        // The older snapshot view is unaffected by later updates.
        assertEquals(1, snap.all().get(0).sequence());
        assertEquals(NOW, snap.createdAt());
    }
}
