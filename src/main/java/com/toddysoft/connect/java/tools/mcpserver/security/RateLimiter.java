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

import com.toddysoft.connect.java.tools.mcpserver.security.GuardRailProperties.RateLimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Paces operations against two limits at once.
 *
 * <p>A <strong>per-device</strong> bucket protects one PLC: a controller has a finite request
 * budget, and exceeding it degrades the control task. A <strong>global</strong> bucket protects the
 * network: hundreds of devices each politely within their own limit still add up to more traffic
 * than the line can carry.</p>
 *
 * <p>An over-limit call is <strong>refused, never queued</strong>. Queuing converts a burst into
 * held threads and unbounded memory, and leaves the caller unable to tell slow from throttled.</p>
 */
public class RateLimiter {

    /** Which limit refused a call. */
    public enum Scope { DEVICE, GLOBAL }

    /**
     * The outcome of asking for permission.
     *
     * @param allowed          whether the operation may proceed
     * @param scope            which bucket refused; null when allowed
     * @param retryAfterMillis how long until a retry could succeed; zero when allowed
     */
    public record Decision(boolean allowed, Scope scope, long retryAfterMillis) {

        static Decision allow() {
            return new Decision(true, null, 0);
        }

        static Decision refuse(Scope scope, long retryAfterMillis) {
            return new Decision(false, scope, retryAfterMillis);
        }
    }

    private final RateLimit rateLimit;
    private final LongSupplier nanoTime;
    private final TokenBucket globalBucket;
    private final Map<String, TokenBucket> deviceBuckets = new ConcurrentHashMap<>();

    public RateLimiter(RateLimit rateLimit, LongSupplier nanoTime) {
        this.rateLimit = rateLimit;
        this.nanoTime = nanoTime;
        this.globalBucket = new TokenBucket(
                rateLimit.getGlobal().getRequestsPerSecond(),
                rateLimit.getGlobal().getBurst(),
                nanoTime);
    }

    /**
     * Asks permission to contact one device, which must satisfy both limits.
     *
     * <p>The device bucket is tried first and <strong>refunded</strong> if the global bucket then
     * refuses, so a call that never happened does not spend that device's budget.</p>
     */
    public Decision tryAcquireForDevice(String connectionUrl) {
        if (!rateLimit.isEnabled()) {
            return Decision.allow();
        }
        TokenBucket device = bucketFor(DeviceKey.of(connectionUrl));
        if (!device.tryConsume()) {
            return Decision.refuse(Scope.DEVICE, device.retryAfterMillis());
        }
        if (!globalBucket.tryConsume()) {
            device.refund();
            return Decision.refuse(Scope.GLOBAL, globalBucket.retryAfterMillis());
        }
        return Decision.allow();
    }

    /**
     * Asks permission for an operation with no single target — discovery, which is a broadcast
     * sweep rather than a conversation with one device, so only the global limit applies.
     */
    public Decision tryAcquireGlobal() {
        if (!rateLimit.isEnabled()) {
            return Decision.allow();
        }
        if (!globalBucket.tryConsume()) {
            return Decision.refuse(Scope.GLOBAL, globalBucket.retryAfterMillis());
        }
        return Decision.allow();
    }

    /** How many per-device buckets are currently held. Exposed so the bound can be asserted. */
    public int trackedDeviceCount() {
        return deviceBuckets.size();
    }

    private TokenBucket bucketFor(String key) {
        TokenBucket existing = deviceBuckets.get(key);
        if (existing != null) {
            return existing;
        }
        if (deviceBuckets.size() >= rateLimit.getMaxTrackedDevices()) {
            evictIdleBuckets();
        }
        return deviceBuckets.computeIfAbsent(key, k -> new TokenBucket(
                rateLimit.getPerDevice().getRequestsPerSecond(),
                rateLimit.getPerDevice().getBurst(),
                nanoTime));
    }

    /**
     * Drops buckets that have refilled to full and been untouched since — they are
     * indistinguishable from a bucket that was never created.
     *
     * <p>A bucket that is still short of tokens is <strong>never</strong> evicted, even when that
     * leaves the map above its cap: discarding it would hand that device a fresh burst, silently
     * removing the limit that is actively protecting it. The cap is therefore a target rather than
     * a hard ceiling, and it is only reachable above the cap while that many devices are genuinely
     * being talked to.</p>
     */
    private void evictIdleBuckets() {
        deviceBuckets.entrySet().removeIf(entry -> entry.getValue().isIdleAndFull(0));
    }

}
