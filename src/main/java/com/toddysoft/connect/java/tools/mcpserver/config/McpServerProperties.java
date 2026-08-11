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
package com.toddysoft.connect.java.tools.mcpserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the ToddySoft Connect MCP Server.
 *
 * <p>These properties are bound from the {@code toddysoft.mcp} prefix in application.yml
 * and control operation timeouts and connection cache behavior.</p>
 */
@ConfigurationProperties(prefix = "toddysoft.mcp")
public class McpServerProperties {

    /** Default timeout in seconds for read, write, and browse operations. */
    private int timeoutSeconds = 30;

    /** Timeout in seconds for device discovery operations. */
    private int discoveryTimeoutSeconds = 30;

    /** Connection cache settings. */
    private Cache cache = new Cache();

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getDiscoveryTimeoutSeconds() {
        return discoveryTimeoutSeconds;
    }

    public void setDiscoveryTimeoutSeconds(int discoveryTimeoutSeconds) {
        this.discoveryTimeoutSeconds = discoveryTimeoutSeconds;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    /**
     * Nested configuration for the connection cache pool settings.
     */
    public static class Cache {

        /** Maximum idle time in minutes before a cached connection is closed. */
        private int maxIdleMinutes = 5;

        /** Maximum lease time in seconds before a connection is forcibly returned to the pool. */
        private int maxLeaseSeconds = 60;

        public int getMaxIdleMinutes() {
            return maxIdleMinutes;
        }

        public void setMaxIdleMinutes(int maxIdleMinutes) {
            this.maxIdleMinutes = maxIdleMinutes;
        }

        public int getMaxLeaseSeconds() {
            return maxLeaseSeconds;
        }

        public void setMaxLeaseSeconds(int maxLeaseSeconds) {
            this.maxLeaseSeconds = maxLeaseSeconds;
        }
    }

}
