/*
 * ToddySoft Connect MCP Server — an MCP (Model Context Protocol) server exposing
 * Apache PLC4X industrial drivers as tools for AI assistant integration.
 * Copyright (C) 2026  ToddySoft GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.toddysoft.connect.java.tools.mcpserver.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TokenBucket} against a controllable time source, so that rate behaviour is
 * asserted exactly rather than by sleeping and hoping.
 */
class TokenBucketTest {

    /** A hand-advanced nanosecond clock — no test in this class ever sleeps. */
    private static final class FakeClock implements java.util.function.LongSupplier {
        private long nanos;

        @Override
        public long getAsLong() {
            return nanos;
        }

        void advanceMillis(long millis) {
            nanos += TimeUnit.MILLISECONDS.toNanos(millis);
        }
    }

    @Test
    void startsFullSoABurstIsAvailableImmediately() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(5.0, 10, clock);

        for (int i = 0; i < 10; i++) {
            assertTrue(bucket.tryConsume(), "token " + (i + 1) + " of the burst should be available");
        }
    }

    @Test
    void refusesOnceTheBurstIsExhausted() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(5.0, 10, clock);

        for (int i = 0; i < 10; i++) {
            bucket.tryConsume();
        }

        assertFalse(bucket.tryConsume(), "the 11th call within the same instant must be refused");
    }

    @Test
    void refillsAtTheConfiguredRate() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(5.0, 10, clock);
        for (int i = 0; i < 10; i++) {
            bucket.tryConsume();
        }

        // At 5 per second one token is worth 200ms.
        clock.advanceMillis(200);

        assertTrue(bucket.tryConsume(), "one token should have refilled after 200ms");
        assertFalse(bucket.tryConsume(), "but only one");
    }

    @Test
    void doesNotRefillBeyondTheBurst() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(5.0, 10, clock);
        bucket.tryConsume();

        // Long enough to refill far more than the burst.
        clock.advanceMillis(60_000);

        for (int i = 0; i < 10; i++) {
            assertTrue(bucket.tryConsume(), "token " + (i + 1) + " should be available");
        }
        assertFalse(bucket.tryConsume(), "the bucket must not accumulate beyond its burst");
    }

    @Test
    void reportsHowLongUntilTheNextTokenIsAvailable() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(5.0, 10, clock);
        for (int i = 0; i < 10; i++) {
            bucket.tryConsume();
        }

        // One token per 200ms, so a bucket emptied at t=0 has one again at t=200ms.
        assertEquals(200, bucket.retryAfterMillis());

        clock.advanceMillis(50);
        assertEquals(150, bucket.retryAfterMillis());
    }

    @Test
    void reportsZeroWaitWhileTokensRemain() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(5.0, 10, clock);

        assertEquals(0, bucket.retryAfterMillis());
    }

    @Test
    void refundReturnsAConsumedToken() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(5.0, 10, clock);
        for (int i = 0; i < 10; i++) {
            bucket.tryConsume();
        }
        assertFalse(bucket.tryConsume(), "precondition: the bucket is empty");

        bucket.refund();

        assertTrue(bucket.tryConsume(), "a refunded token must be usable again");
    }

    @Test
    void refundNeverPushesTheBucketAboveItsBurst() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(5.0, 10, clock);

        bucket.refund();

        for (int i = 0; i < 10; i++) {
            assertTrue(bucket.tryConsume(), "token " + (i + 1) + " should be available");
        }
        assertFalse(bucket.tryConsume(), "a refund on a full bucket must not create a token");
    }

    @Test
    void isIdleAndFullOnlyWhenUntouchedForTheGivenPeriod() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(5.0, 10, clock);
        long idleThreshold = TimeUnit.MINUTES.toNanos(5);

        bucket.tryConsume();
        assertFalse(bucket.isIdleAndFull(idleThreshold), "just used, and a token short");

        clock.advanceMillis(TimeUnit.MINUTES.toMillis(5) + 1);

        assertTrue(bucket.isIdleAndFull(idleThreshold), "refilled to full and untouched since");
    }
}
