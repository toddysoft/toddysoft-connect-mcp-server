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

import org.apache.plc4x.java.utils.cache.CachedPlcConnectionManager;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpServerConfigurationTest {

    private McpServerConfiguration configuration = new McpServerConfiguration();

    @AfterEach
    void tearDown() {
        // Ensure cleanup runs without error even if beans weren't created
        assertDoesNotThrow(() -> configuration.cleanup());
    }

    @Test
    void cachedPlcConnectionManager_createsWithDefaultProperties() {
        McpServerProperties properties = new McpServerProperties();
        CachedPlcConnectionManager manager = configuration.cachedPlcConnectionManager(properties);

        assertNotNull(manager);
    }

    @Test
    void cachedPlcConnectionManager_createsWithCustomProperties() {
        McpServerProperties properties = new McpServerProperties();
        properties.getCache().setMaxIdleMinutes(10);
        properties.getCache().setMaxLeaseSeconds(120);

        CachedPlcConnectionManager manager = configuration.cachedPlcConnectionManager(properties);

        assertNotNull(manager);
    }

    @Test
    void auditLog_createsWithMcpServerSource() {
        AuditLog log = configuration.auditLog();

        assertNotNull(log);
        assertEquals("mcp-server", log.getSource());
    }

    @Test
    void cleanup_closesManagerAndLog() {
        McpServerProperties properties = new McpServerProperties();
        configuration.cachedPlcConnectionManager(properties);
        configuration.auditLog();

        // Should not throw
        assertDoesNotThrow(() -> configuration.cleanup());
    }

    @Test
    void cleanup_handlesNullBeansGracefully() {
        // Neither bean was created — cleanup should still succeed
        assertDoesNotThrow(() -> configuration.cleanup());
    }

}
