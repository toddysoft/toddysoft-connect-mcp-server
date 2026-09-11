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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Guard-rail settings, bound from {@code toddysoft.mcp.security}.
 *
 * <p>The defaults here are the safe posture: writes and discovery are refused, and rate limiting
 * is on. An operator opts in to risk rather than opting out of it, because a guard-rail that
 * defaults off only protects the people who already knew they needed it.</p>
 */
public class GuardRailProperties {

    private Writes writes = new Writes();
    private Discovery discovery = new Discovery();
    private RateLimit rateLimit = new RateLimit();

    public Writes getWrites() {
        return writes;
    }

    public void setWrites(Writes writes) {
        this.writes = writes;
    }

    public Discovery getDiscovery() {
        return discovery;
    }

    public void setDiscovery(Discovery discovery) {
        this.discovery = discovery;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    /**
     * Fails fast on a configuration that cannot mean what it says.
     *
     * <p>Called during startup, because every one of these mistakes otherwise produces a server
     * that runs happily while enforcing something other than what was written down — the worst
     * outcome for a guard-rail.</p>
     *
     * @throws IllegalStateException describing the offending setting
     */
    public void validate() {
        if (writes.isEnabled() && writes.getAllow().isEmpty()) {
            throw new IllegalStateException(
                    "toddysoft.mcp.security.writes.enabled is true but writes.allow is empty, so "
                            + "nothing is writable. List the permitted tag patterns per device, use "
                            + "'**' to permit every address deliberately, or disable writes.");
        }
        if (writes.isEnabled()) {
            for (Map.Entry<String, List<String>> rule : writes.getAllow().entrySet()) {
                for (String pattern : rule.getValue()) {
                    if ("*".equals(pattern)) {
                        throw new IllegalStateException(
                                "toddysoft.mcp.security.writes.allow['" + rule.getKey() + "'] contains "
                                        + "the pattern '*'. Use '**' to permit every address, so that "
                                        + "'everything' is never one keystroke away from a narrow rule.");
                    }
                }
            }
        }
        if (discovery.isEnabled() && discovery.getProtocols().isEmpty()) {
            throw new IllegalStateException(
                    "toddysoft.mcp.security.discovery.enabled is true but no protocols are "
                            + "allowlisted. List the protocols permitted to scan, or disable discovery.");
        }
        if (!rateLimit.isEnabled()) {
            // Nothing reads the limits, so a stale value is not worth refusing startup over.
            return;
        }
        validateBucket("per-device", rateLimit.getPerDevice());
        validateBucket("global", rateLimit.getGlobal());
        if (rateLimit.getMaxTrackedDevices() <= 0) {
            throw new IllegalStateException(
                    "toddysoft.mcp.security.rate-limit.max-tracked-devices must be positive, was "
                            + rateLimit.getMaxTrackedDevices() + ".");
        }
    }

    private static void validateBucket(String name, Bucket bucket) {
        if (bucket.getRequestsPerSecond() <= 0) {
            throw new IllegalStateException("toddysoft.mcp.security.rate-limit." + name
                    + ".requests-per-second must be positive, was " + bucket.getRequestsPerSecond()
                    + ". To switch pacing off, set rate-limit.enabled=false.");
        }
        if (bucket.getBurst() < bucket.getRequestsPerSecond()) {
            throw new IllegalStateException("toddysoft.mcp.security.rate-limit." + name
                    + ".burst (" + bucket.getBurst() + ") is below its requests-per-second ("
                    + bucket.getRequestsPerSecond() + "), so the rate could never be reached.");
        }
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    /** Whether the server may write to a device at all, and at which addresses. */
    public static class Writes {

        /** Off by default: a write changes physical state, so it is opted into. */
        private boolean enabled = false;

        /**
         * Writable tag addresses, keyed by device ({@code host[:port]}, or {@code *} for any).
         *
         * <p>Required when writes are enabled — an empty map is refused at startup rather than
         * treated as either "everything" or "nothing", because deleting the last rule must not
         * silently change the policy in either direction. {@code **} is the one way to say every
         * address.</p>
         */
        private Map<String, List<String>> allow = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, List<String>> getAllow() {
            return allow;
        }

        public void setAllow(Map<String, List<String>> allow) {
            this.allow = allow;
        }
    }

    /** Whether the server may scan the network, and with which protocols. */
    public static class Discovery {

        /** Off by default: discovery is broadcast traffic, restricted on many plant networks. */
        private boolean enabled = false;

        /**
         * Protocol codes permitted to scan. Must be non-empty when discovery is enabled — an
         * enabled discovery with nothing allowlisted is far more likely a mistake than an intent.
         */
        private List<String> protocols = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getProtocols() {
            return protocols;
        }

        public void setProtocols(List<String> protocols) {
            this.protocols = protocols;
        }
    }

    /** Two buckets: one protecting a single PLC, one protecting the network as a whole. */
    public static class RateLimit {

        private boolean enabled = true;
        private Bucket perDevice = new Bucket(5.0, 10);
        private Bucket global = new Bucket(20.0, 40);

        /** Upper bound on per-device buckets held at once, so the tracking cannot itself leak. */
        private int maxTrackedDevices = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Bucket getPerDevice() {
            return perDevice;
        }

        public void setPerDevice(Bucket perDevice) {
            this.perDevice = perDevice;
        }

        public Bucket getGlobal() {
            return global;
        }

        public void setGlobal(Bucket global) {
            this.global = global;
        }

        public int getMaxTrackedDevices() {
            return maxTrackedDevices;
        }

        public void setMaxTrackedDevices(int maxTrackedDevices) {
            this.maxTrackedDevices = maxTrackedDevices;
        }
    }

    /** A sustained rate plus the burst that may be spent at once. */
    public static class Bucket {

        private double requestsPerSecond;
        private int burst;

        public Bucket() {
        }

        public Bucket(double requestsPerSecond, int burst) {
            this.requestsPerSecond = requestsPerSecond;
            this.burst = burst;
        }

        public double getRequestsPerSecond() {
            return requestsPerSecond;
        }

        public void setRequestsPerSecond(double requestsPerSecond) {
            this.requestsPerSecond = requestsPerSecond;
        }

        public int getBurst() {
            return burst;
        }

        public void setBurst(int burst) {
            this.burst = burst;
        }
    }
}
