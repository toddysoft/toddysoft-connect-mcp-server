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

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * A token bucket: {@code burst} tokens available at once, refilling at {@code ratePerSecond}.
 *
 * <p>The bucket starts full, so a caller that has been idle may spend its whole burst at once and
 * is then paced at the configured rate. Time is supplied rather than read from the system clock,
 * which is what lets the tests assert rate behaviour exactly instead of sleeping.</p>
 *
 * <p>Every method is synchronized. The contended path is a few arithmetic operations, and a
 * correct bucket under concurrency is worth far more here than a lock-free one.</p>
 */
final class TokenBucket {

    private final double ratePerSecond;
    private final int burst;
    private final LongSupplier nanoTime;

    private double tokens;
    private long lastRefillNanos;
    private long lastUseNanos;

    TokenBucket(double ratePerSecond, int burst, LongSupplier nanoTime) {
        this.ratePerSecond = ratePerSecond;
        this.burst = burst;
        this.nanoTime = nanoTime;
        this.tokens = burst;
        this.lastRefillNanos = nanoTime.getAsLong();
        this.lastUseNanos = this.lastRefillNanos;
    }

    /**
     * Takes one token if any is available.
     *
     * @return true when a token was taken and the operation may proceed
     */
    synchronized boolean tryConsume() {
        refill();
        lastUseNanos = nanoTime.getAsLong();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /**
     * How long until the next token becomes available, zero when one is available now. Reported to
     * the caller so a refused request can be retried deliberately rather than blindly.
     */
    synchronized long retryAfterMillis() {
        refill();
        if (tokens >= 1.0) {
            return 0;
        }
        double secondsNeeded = (1.0 - tokens) / ratePerSecond;
        return (long) Math.ceil(secondsNeeded * 1000.0);
    }

    /**
     * Returns a token taken by a call that was then refused elsewhere, so a rejected operation does
     * not spend budget it never used. Never takes the bucket above its burst.
     */
    synchronized void refund() {
        refill();
        tokens = Math.min(burst, tokens + 1.0);
    }

    /**
     * Whether this bucket has been back at full for at least {@code idleNanos} — the condition for
     * evicting it, since a full, untouched bucket is indistinguishable from a fresh one.
     */
    synchronized boolean isIdleAndFull(long idleNanos) {
        refill();
        return tokens >= burst && (nanoTime.getAsLong() - lastUseNanos) >= idleNanos;
    }

    private void refill() {
        long now = nanoTime.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        double refilled = (elapsed / (double) TimeUnit.SECONDS.toNanos(1)) * ratePerSecond;
        tokens = Math.min(burst, tokens + refilled);
        lastRefillNanos = now;
    }
}
