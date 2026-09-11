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
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RateLimiter} — the two-bucket pacing that protects a single PLC from being
 * hammered, and the network from a fleet-wide sweep that is within every device's own limit.
 */
class RateLimiterTest {

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

    private static RateLimit limits(double deviceRate, int deviceBurst,
                                    double globalRate, int globalBurst) {
        RateLimit rateLimit = new RateLimit();
        rateLimit.setPerDevice(new GuardRailProperties.Bucket(deviceRate, deviceBurst));
        rateLimit.setGlobal(new GuardRailProperties.Bucket(globalRate, globalBurst));
        return rateLimit;
    }

    @Test
    void refusesAThirdCallToTheSameDeviceWhenItsBurstIsTwo() {
        RateLimiter limiter = new RateLimiter(limits(1.0, 2, 100.0, 100), new FakeClock());

        assertTrue(limiter.tryAcquireForDevice("s7://10.0.0.5").allowed());
        assertTrue(limiter.tryAcquireForDevice("s7://10.0.0.5").allowed());

        RateLimiter.Decision third = limiter.tryAcquireForDevice("s7://10.0.0.5");
        assertFalse(third.allowed());
        assertEquals(RateLimiter.Scope.DEVICE, third.scope());
        assertTrue(third.retryAfterMillis() > 0, "a refusal must say how long to wait");
    }

    @Test
    void oneBusyDeviceDoesNotAffectAnother() {
        RateLimiter limiter = new RateLimiter(limits(1.0, 2, 100.0, 100), new FakeClock());
        limiter.tryAcquireForDevice("s7://10.0.0.5");
        limiter.tryAcquireForDevice("s7://10.0.0.5");
        assertFalse(limiter.tryAcquireForDevice("s7://10.0.0.5").allowed(), "precondition");

        assertTrue(limiter.tryAcquireForDevice("s7://10.0.0.9").allowed(),
                "a second device has its own budget");
    }

    @Test
    void theGlobalBucketStopsAFleetSweepThatIsWithinEveryDevicesOwnLimit() {
        // Every device is asked once — inside its own generous limit — but the network is not.
        RateLimiter limiter = new RateLimiter(limits(100.0, 100, 1.0, 3), new FakeClock());

        assertTrue(limiter.tryAcquireForDevice("s7://10.0.0.1").allowed());
        assertTrue(limiter.tryAcquireForDevice("s7://10.0.0.2").allowed());
        assertTrue(limiter.tryAcquireForDevice("s7://10.0.0.3").allowed());

        RateLimiter.Decision fourth = limiter.tryAcquireForDevice("s7://10.0.0.4");
        assertFalse(fourth.allowed(), "the global bucket must bound aggregate traffic");
        assertEquals(RateLimiter.Scope.GLOBAL, fourth.scope());
    }

    @Test
    void aCallRefusedGloballyDoesNotSpendTheDevicesBudget() {
        RateLimiter limiter = new RateLimiter(limits(1.0, 2, 1.0, 1), new FakeClock());
        assertTrue(limiter.tryAcquireForDevice("s7://10.0.0.1").allowed(), "spends the global token");

        // Refused globally — the device bucket must be left as it was.
        assertFalse(limiter.tryAcquireForDevice("s7://10.0.0.5").allowed());

        // Once the global bucket refills, the device still has its full burst of two.
        FakeClock clock = new FakeClock();
        RateLimiter fresh = new RateLimiter(limits(1.0, 2, 100.0, 100), clock);
        assertTrue(fresh.tryAcquireForDevice("s7://10.0.0.5").allowed());
        assertTrue(fresh.tryAcquireForDevice("s7://10.0.0.5").allowed());
        assertFalse(fresh.tryAcquireForDevice("s7://10.0.0.5").allowed());
    }

    @Test
    void discoveryTakesOnlyAGlobalTokenBecauseThereIsNoDeviceYet() {
        RateLimiter limiter = new RateLimiter(limits(1.0, 1, 1.0, 2), new FakeClock());

        assertTrue(limiter.tryAcquireGlobal().allowed());
        assertTrue(limiter.tryAcquireGlobal().allowed());

        RateLimiter.Decision third = limiter.tryAcquireGlobal();
        assertFalse(third.allowed());
        assertEquals(RateLimiter.Scope.GLOBAL, third.scope());
    }

    @Test
    void urlsDifferingOnlyByPathOrQueryShareOneDeviceBudget() {
        RateLimiter limiter = new RateLimiter(limits(1.0, 2, 100.0, 100), new FakeClock());

        assertTrue(limiter.tryAcquireForDevice("s7://10.0.0.5?remote-rack=0").allowed());
        assertTrue(limiter.tryAcquireForDevice("s7://10.0.0.5?remote-rack=1").allowed());

        assertFalse(limiter.tryAcquireForDevice("s7://10.0.0.5").allowed(),
                "the same device is the same device whatever the parameters say");
    }

    @Test
    void differentPortsOnOneHostAreDifferentDevices() {
        RateLimiter limiter = new RateLimiter(limits(1.0, 1, 100.0, 100), new FakeClock());

        assertTrue(limiter.tryAcquireForDevice("modbus-tcp://10.0.0.5:502").allowed());

        assertTrue(limiter.tryAcquireForDevice("modbus-tcp://10.0.0.5:503").allowed(),
                "a second device behind the same address is still a second device");
    }

    @Test
    void transportPrefixedUrlsKeyOnTheSameDevice() {
        RateLimiter limiter = new RateLimiter(limits(1.0, 1, 100.0, 100), new FakeClock());

        assertTrue(limiter.tryAcquireForDevice("s7:tcp://10.0.0.5").allowed());

        assertFalse(limiter.tryAcquireForDevice("s7:tcp://10.0.0.5").allowed());
    }

    @Test
    void whenDisabledEverythingIsAllowed() {
        RateLimit rateLimit = limits(1.0, 1, 1.0, 1);
        rateLimit.setEnabled(false);
        RateLimiter limiter = new RateLimiter(rateLimit, new FakeClock());

        for (int i = 0; i < 50; i++) {
            assertTrue(limiter.tryAcquireForDevice("s7://10.0.0.5").allowed());
            assertTrue(limiter.tryAcquireGlobal().allowed());
        }
    }

    @Test
    void trackingIsBoundedSoManyDevicesCannotExhaustMemory() {
        RateLimit rateLimit = limits(100.0, 100, 1_000_000.0, 1_000_000);
        rateLimit.setMaxTrackedDevices(10);
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter(rateLimit, clock);

        for (int i = 0; i < 500; i++) {
            // Each device is contacted once and then never again, so its bucket refills to full.
            limiter.tryAcquireForDevice("s7://10.0.0." + i);
            clock.advanceMillis(1000);
        }

        assertTrue(limiter.trackedDeviceCount() <= 10,
                "tracked buckets must stay within the configured cap, was "
                        + limiter.trackedDeviceCount());
    }

    @Test
    void aDeviceStillWithinItsBurstIsNotEvictedWhileBusy() {
        RateLimit rateLimit = limits(0.001, 2, 1_000_000.0, 1_000_000);
        rateLimit.setMaxTrackedDevices(2);
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter(rateLimit, clock);

        // Spend the busy device's whole burst; its bucket is empty and must be remembered.
        limiter.tryAcquireForDevice("s7://10.0.0.1");
        limiter.tryAcquireForDevice("s7://10.0.0.1");

        // Push other devices through, over the cap.
        for (int i = 2; i < 20; i++) {
            limiter.tryAcquireForDevice("s7://10.0.0." + i);
        }

        assertFalse(limiter.tryAcquireForDevice("s7://10.0.0.1").allowed(),
                "the busy device's exhausted bucket must survive eviction of idle ones");
    }
}
