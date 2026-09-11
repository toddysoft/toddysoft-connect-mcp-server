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
package com.toddysoft.connect.java.tools.mcpserver.tools;

import com.toddysoft.connect.java.tools.mcpserver.config.McpServerProperties;
import com.toddysoft.connect.java.tools.mcpserver.security.GuardRailProperties;
import com.toddysoft.connect.java.tools.mcpserver.security.OperationGuard;
import com.toddysoft.connect.java.tools.mcpserver.security.RateLimiter;
import org.apache.plc4x.java.api.PlcDriverManager;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.cache.PlcConnectionCache;
import org.junit.jupiter.api.BeforeEach;
import com.toddysoft.connect.java.tools.mcpserver.security.TestGuards;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests that each tool honours the guard-rails, using a real {@link OperationGuard} rather than a
 * mocked one — the point of these tests is the behaviour an operator gets, not that a collaborator
 * was called.
 *
 * <p>Each refusal also asserts that the device was never contacted. A guard-rail that reports a
 * refusal after the packet has gone out is not a guard-rail.</p>
 */
@ExtendWith(MockitoExtension.class)
class ToolGuardRailTest {

    @Mock
    private PlcConnectionCache connectionCache;

    @Mock
    private PlcDriverManager driverManager;

    @Mock
    private AuditLog auditLog;

    private GuardRailProperties guardRails;
    private McpServerProperties properties;

    @BeforeEach
    void setUp() {
        guardRails = new GuardRailProperties();
        properties = new McpServerProperties();
    }

    private OperationGuard guard() {
        return new OperationGuard(guardRails, new RateLimiter(guardRails.getRateLimit(), () -> 0L), auditLog);
    }

    private static Map<String, Object> onlyEntry(List<Map<String, Object>> results) {
        assertEquals(1, results.size(), "a refusal is a single entry, was: " + results);
        return results.get(0);
    }

    @Test
    void writeIsRefusedByDefaultAndNeverReachesTheDevice() throws Exception {
        WriteTool tool = new WriteTool(connectionCache, properties, auditLog, guard(), TestGuards.redactor());

        Map<String, Object> refusal = onlyEntry(tool.writeTags("s7://10.0.0.5", Map.of("%M0.0:BOOL", true)));

        assertEquals("WRITES_DISABLED", refusal.get("reason"));
        assertTrue(((String) refusal.get("error")).contains("disabled"));
        verify(connectionCache, never()).getConnection(anyString());
    }

    @Test
    void writeProceedsOnceEnabledAndAllowlisted() throws Exception {
        guardRails.getWrites().setEnabled(true);
        guardRails.getWrites().setAllow(Map.of("*", List.of("**")));
        WriteTool tool = new WriteTool(connectionCache, properties, auditLog, guard(), TestGuards.redactor());
        when(connectionCache.getConnection(anyString())).thenThrow(new IllegalStateException("reached the device"));

        List<Map<String, Object>> results = tool.writeTags("s7://10.0.0.5", Map.of("%M0.0:BOOL", true));

        // The guard let it through; the connection failure beyond it is this test's success signal.
        assertTrue(results.toString().contains("reached the device"), "was: " + results);
    }

    @Test
    void aTagOutsideTheAllowlistRefusesTheWholeCallAndWritesNothing() throws Exception {
        guardRails.getWrites().setEnabled(true);
        guardRails.getWrites().setAllow(Map.of("10.0.0.5", List.of("%DB10.*")));
        WriteTool tool = new WriteTool(connectionCache, properties, auditLog, guard(), TestGuards.redactor());

        Map<String, Object> refusal = onlyEntry(tool.writeTags("s7://10.0.0.5", Map.of(
                "%DB10.DBW0:INT", 42,
                "%DB11.DBW0:INT", 1)));

        assertEquals("TAG_NOT_ALLOWED", refusal.get("reason"));
        assertTrue(((String) refusal.get("error")).contains("%DB11.DBW0:INT"), refusal.toString());
        // The permitted tag in the same call must not have been written either.
        verify(connectionCache, never()).getConnection(anyString());
    }

    @Test
    void discoveryIsRefusedByDefaultAndNeverScans() throws Exception {
        DiscoveryTool tool = new DiscoveryTool(driverManager, properties, auditLog, guard());

        Map<String, Object> refusal = onlyEntry(tool.discoverDevices("s7"));

        assertEquals("DISCOVERY_DISABLED", refusal.get("reason"));
        verifyNoInteractions(driverManager);
    }

    @Test
    void discoveryOfANonAllowlistedProtocolIsRefusedBeforeAnyPacket() throws Exception {
        guardRails.getDiscovery().setEnabled(true);
        guardRails.getDiscovery().setProtocols(List.of("modbus-tcp"));
        DiscoveryTool tool = new DiscoveryTool(driverManager, properties, auditLog, guard());

        Map<String, Object> refusal = onlyEntry(tool.discoverDevices("s7"));

        assertEquals("PROTOCOL_NOT_ALLOWED", refusal.get("reason"));
        verifyNoInteractions(driverManager);
    }

    @Test
    void anUnrestrictedSweepScansOnlyAllowlistedProtocols() throws Exception {
        guardRails.getDiscovery().setEnabled(true);
        guardRails.getDiscovery().setProtocols(List.of("modbus-tcp"));
        DiscoveryTool tool = new DiscoveryTool(driverManager, properties, auditLog, guard());
        when(driverManager.getDriver("modbus-tcp")).thenThrow(new IllegalStateException("scanned modbus-tcp"));

        tool.discoverDevices(null);

        // Only the allowlisted driver is ever looked up — never a fan-out across the classpath.
        verify(driverManager).getDriver("modbus-tcp");
        verify(driverManager, never()).getProtocolCodes();
    }

    @Test
    void readIsRateLimitedAndSaysHowLongToWait() throws Exception {
        guardRails.getRateLimit().setPerDevice(new GuardRailProperties.Bucket(1.0, 1));
        ReadTool tool = new ReadTool(connectionCache, properties, auditLog, guard(), TestGuards.redactor());
        when(connectionCache.getConnection(anyString())).thenThrow(new IllegalStateException("first call got through"));

        tool.readTags("s7://10.0.0.5", List.of("%M0.0:BOOL"));
        Map<String, Object> refusal = onlyEntry(tool.readTags("s7://10.0.0.5", List.of("%M0.0:BOOL")));

        assertEquals("RATE_LIMITED", refusal.get("reason"));
        assertTrue(((Number) refusal.get("retryAfterMillis")).longValue() > 0,
                "the model needs to know how long to back off, was: " + refusal);
        verify(connectionCache, times(1)).getConnection(anyString());
    }

    @Test
    void browseIsRateLimitedOnTheSameDeviceBudgetAsRead() throws Exception {
        guardRails.getRateLimit().setPerDevice(new GuardRailProperties.Bucket(1.0, 1));
        OperationGuard shared = guard();
        ReadTool readTool = new ReadTool(connectionCache, properties, auditLog, shared, TestGuards.redactor());
        BrowseTool browseTool = new BrowseTool(connectionCache, properties, auditLog, shared, TestGuards.redactor());
        when(connectionCache.getConnection(anyString())).thenThrow(new IllegalStateException("got through"));

        readTool.readTags("s7://10.0.0.5", List.of("%M0.0:BOOL"));
        Map<String, Object> refusal = onlyEntry(browseTool.browseTags("s7://10.0.0.5", "*"));

        assertEquals("RATE_LIMITED", refusal.get("reason"),
                "reads and browses of one device share that device's budget");
    }
}
