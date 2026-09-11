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
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.auditlog.api.AuditLogEventType;
import org.apache.plc4x.java.api.PlcDriver;
import org.apache.plc4x.java.api.PlcDriverManager;
import org.apache.plc4x.java.api.messages.PlcDiscoveryItem;
import org.apache.plc4x.java.api.messages.PlcDiscoveryItemHandler;
import org.apache.plc4x.java.api.messages.PlcDiscoveryRequest;
import org.apache.plc4x.java.api.messages.PlcDiscoveryResponse;
import org.apache.plc4x.java.api.metadata.PlcDriverMetadata;
import org.apache.plc4x.java.api.value.PlcValue;
import com.toddysoft.connect.java.tools.mcpserver.security.TestGuards;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DiscoveryTool} using a mock {@link PlcDriverManager}
 * injected via the package-private constructor.
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryToolTest {

    @Mock
    private PlcDriverManager driverManager;

    @Mock
    private McpServerProperties properties;

    @Mock
    private AuditLog auditLog;

    private DiscoveryTool tool;

    @BeforeEach
    void setUp() {
        // Use the package-private constructor to inject the mock driver manager.
        // Every protocol this class exercises is allowlisted; the guard-rails themselves
        // are covered by ToolGuardRailTest.
        tool = new DiscoveryTool(driverManager, properties, auditLog,
                TestGuards.permissive("s7", "modbus", "modbus-tcp", "bad"));
    }

    /**
     * When a specific protocolCode is given and the driver supports discovery,
     * the handler should be invoked with discovery items that appear in the results.
     */
    @Test
    void discoverDevices_specificProtocol_returnsDiscoveredItems() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getDiscoveryTimeoutSeconds()).thenReturn(5);

        // Set up driver with discovery support.
        PlcDriver driver = mock(PlcDriver.class);
        PlcDriverMetadata metadata = mock(PlcDriverMetadata.class);
        when(metadata.isDiscoverySupported()).thenReturn(true);
        when(driver.getMetadata()).thenReturn(metadata);
        when(driverManager.getDriver("s7")).thenReturn(driver);

        // Set up the discovery request builder chain.
        PlcDiscoveryRequest.Builder builder = mock(PlcDiscoveryRequest.Builder.class);
        PlcDiscoveryRequest request = mock(PlcDiscoveryRequest.class);
        when(driver.discoveryRequestBuilder()).thenReturn(builder);
        when(builder.addQuery("all", "*")).thenReturn(builder);
        when(builder.build()).thenReturn(request);

        // Create a mock discovery item with no attributes.
        PlcDiscoveryItem item = createMockItem("s7://192.168.1.1", "s7", "tcp",
                "192.168.1.1", "Siemens S7-1500", null);

        // When executeWithHandler is called, capture the handler, invoke it, and return a completed future.
        PlcDiscoveryResponse response = mock(PlcDiscoveryResponse.class);
        when(request.executeWithHandler(any(PlcDiscoveryItemHandler.class))).thenAnswer(invocation -> {
            PlcDiscoveryItemHandler handler = invocation.getArgument(0);
            handler.handle(item);
            return CompletableFuture.completedFuture(response);
        });

        List<Map<String, Object>> results = tool.discoverDevices("s7");

        assertEquals(1, results.size());
        Map<String, Object> entry = results.get(0);
        assertEquals("s7://192.168.1.1", entry.get("connectionUrl"));
        assertEquals("s7", entry.get("protocolCode"));
        assertEquals("tcp", entry.get("transportCode"));
        assertEquals("192.168.1.1", entry.get("transportUrl"));
        assertEquals("Siemens S7-1500", entry.get("name"));
        assertFalse(entry.containsKey("attributes"), "No attributes key expected when attributes are null");
    }

    /**
     * When the driver does not support discovery, the result should contain
     * an error entry describing the lack of discovery support.
     */
    @Test
    void discoverDevices_discoveryNotSupported_returnsError() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getDiscoveryTimeoutSeconds()).thenReturn(5);

        PlcDriver driver = mock(PlcDriver.class);
        PlcDriverMetadata metadata = mock(PlcDriverMetadata.class);
        when(metadata.isDiscoverySupported()).thenReturn(false);
        when(driver.getMetadata()).thenReturn(metadata);
        when(driverManager.getDriver("modbus-tcp")).thenReturn(driver);

        List<Map<String, Object>> results = tool.discoverDevices("modbus-tcp");

        assertEquals(1, results.size());
        assertTrue(results.get(0).containsKey("error"));
        String error = (String) results.get(0).get("error");
        assertTrue(error.contains("modbus-tcp"), "Error should mention the protocol code");
        assertTrue(error.contains("does not support discovery"), "Error should explain the reason");
    }

    /**
     * When protocolCode is null, the tool scans the allowlisted protocols — never every driver on
     * the classpath — and runs discovery for each that supports it.
     */
    @Test
    void discoverDevices_nullProtocol_scansTheAllowlistedProtocols() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getDiscoveryTimeoutSeconds()).thenReturn(5);

        // Two allowlisted drivers: s7 supports discovery, modbus does not.
        DiscoveryTool twoProtocols = new DiscoveryTool(driverManager, properties, auditLog,
                TestGuards.permissive("s7", "modbus"));

        PlcDriver s7Driver = mock(PlcDriver.class);
        PlcDriverMetadata s7Meta = mock(PlcDriverMetadata.class);
        when(s7Meta.isDiscoverySupported()).thenReturn(true);
        when(s7Driver.getMetadata()).thenReturn(s7Meta);
        when(driverManager.getDriver("s7")).thenReturn(s7Driver);

        PlcDriver modbusDriver = mock(PlcDriver.class);
        PlcDriverMetadata modbusMeta = mock(PlcDriverMetadata.class);
        when(modbusMeta.isDiscoverySupported()).thenReturn(false);
        when(modbusDriver.getMetadata()).thenReturn(modbusMeta);
        when(driverManager.getDriver("modbus")).thenReturn(modbusDriver);

        // Set up s7 discovery.
        PlcDiscoveryRequest.Builder builder = mock(PlcDiscoveryRequest.Builder.class);
        PlcDiscoveryRequest request = mock(PlcDiscoveryRequest.class);
        when(s7Driver.discoveryRequestBuilder()).thenReturn(builder);
        when(builder.addQuery("all", "*")).thenReturn(builder);
        when(builder.build()).thenReturn(request);

        PlcDiscoveryItem item = createMockItem("s7://10.0.0.1", "s7", "tcp",
                "10.0.0.1", "PLC-1", null);
        PlcDiscoveryResponse response = mock(PlcDiscoveryResponse.class);
        when(request.executeWithHandler(any(PlcDiscoveryItemHandler.class))).thenAnswer(invocation -> {
            PlcDiscoveryItemHandler handler = invocation.getArgument(0);
            handler.handle(item);
            return CompletableFuture.completedFuture(response);
        });

        List<Map<String, Object>> results = twoProtocols.discoverDevices(null);

        // Only the s7 item should appear; modbus was skipped.
        assertEquals(1, results.size());
        assertEquals("s7://10.0.0.1", results.get(0).get("connectionUrl"));
    }

    /**
     * When the driver manager throws an exception (e.g., unknown protocol),
     * the result should contain an error entry.
     */
    @Test
    void discoverDevices_exceptionThrown_returnsError() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(properties.getDiscoveryTimeoutSeconds()).thenReturn(5);
        when(driverManager.getDriver("bad")).thenThrow(new RuntimeException("No driver found"));

        List<Map<String, Object>> results = tool.discoverDevices("bad");

        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(m -> m.containsKey("error")));
        String error = (String) results.stream()
                .filter(m -> m.containsKey("error"))
                .findFirst().get().get("error");
        assertTrue(error.contains("No driver found"));
    }

    /**
     * An unrestricted sweep with nothing allowlisted is refused, rather than quietly returning
     * nothing — an empty result would read as "no devices found", which is a different claim.
     */
    @Test
    void discoverDevices_nothingAllowlisted_isRefused() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        DiscoveryTool restricted = new DiscoveryTool(driverManager, properties, auditLog,
                TestGuards.permissive());

        List<Map<String, Object>> results = restricted.discoverDevices(null);

        assertEquals(1, results.size());
        assertEquals("PROTOCOL_NOT_ALLOWED", results.get(0).get("reason"));
        verifyNoInteractions(driverManager);
    }

    /**
     * Verifies that the audit log is invoked for both request and response events.
     */
    @Test
    void discoverDevices_auditLogEnabled_logsRequestAndResponse() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(properties.getDiscoveryTimeoutSeconds()).thenReturn(5);

        // One allowlisted driver that can discover, finding nothing: a complete, quiet sweep.
        PlcDriver driver = mock(PlcDriver.class);
        PlcDriverMetadata metadata = mock(PlcDriverMetadata.class);
        when(metadata.isDiscoverySupported()).thenReturn(true);
        when(driver.getMetadata()).thenReturn(metadata);
        PlcDiscoveryRequest.Builder builder = mock(PlcDiscoveryRequest.Builder.class);
        PlcDiscoveryRequest request = mock(PlcDiscoveryRequest.class);
        when(driver.discoveryRequestBuilder()).thenReturn(builder);
        when(builder.addQuery("all", "*")).thenReturn(builder);
        when(builder.build()).thenReturn(request);
        // executeWithHandler is declared with a wildcard return, so answer rather than thenReturn.
        when(request.executeWithHandler(any(PlcDiscoveryItemHandler.class)))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(mock(PlcDiscoveryResponse.class)));
        when(driverManager.getDriver("modbus")).thenReturn(driver);
        DiscoveryTool single = new DiscoveryTool(driverManager, properties, auditLog,
                TestGuards.permissive("modbus"));

        single.discoverDevices(null);

        // Request log should mention "for all protocols".
        verify(auditLog).write(eq(AuditLogEventType.API_REQUEST),
                contains("for all protocols"));
        verify(auditLog).write(eq(AuditLogEventType.API_RESPONSE),
                contains("returned 0 items"), any());
    }

    /**
     * Verifies that when a specific protocol is given, the audit log request
     * mentions that protocol.
     */
    @Test
    void discoverDevices_auditLogEnabled_logsRequestWithProtocol() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(properties.getDiscoveryTimeoutSeconds()).thenReturn(5);

        PlcDriver driver = mock(PlcDriver.class);
        PlcDriverMetadata metadata = mock(PlcDriverMetadata.class);
        when(metadata.isDiscoverySupported()).thenReturn(false);
        when(driver.getMetadata()).thenReturn(metadata);
        when(driverManager.getDriver("s7")).thenReturn(driver);

        tool.discoverDevices("s7");

        verify(auditLog).write(eq(AuditLogEventType.API_REQUEST),
                contains("for protocol: s7"));
    }

    /**
     * Verifies that no audit log events are written when the log is disabled.
     */
    @Test
    void discoverDevices_auditLogDisabled_doesNotLog() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getDiscoveryTimeoutSeconds()).thenReturn(5);

        PlcDriver driver = mock(PlcDriver.class);
        PlcDriverMetadata metadata = mock(PlcDriverMetadata.class);
        when(metadata.isDiscoverySupported()).thenReturn(false);
        when(driver.getMetadata()).thenReturn(metadata);
        when(driverManager.getDriver("modbus")).thenReturn(driver);
        DiscoveryTool single = new DiscoveryTool(driverManager, properties, auditLog,
                TestGuards.permissive("modbus"));

        single.discoverDevices(null);

        verify(auditLog, never()).write(any(AuditLogEventType.class), anyString());
        verify(auditLog, never()).write(any(AuditLogEventType.class), anyString(), any());
    }

    /**
     * Verifies that discovery items with non-empty attributes have their
     * attributes converted via PlcValueConverter and included in the result map.
     */
    @Test
    void discoverDevices_itemWithAttributes_attributesConverted() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getDiscoveryTimeoutSeconds()).thenReturn(5);

        PlcDriver driver = mock(PlcDriver.class);
        PlcDriverMetadata metadata = mock(PlcDriverMetadata.class);
        when(metadata.isDiscoverySupported()).thenReturn(true);
        when(driver.getMetadata()).thenReturn(metadata);
        when(driverManager.getDriver("s7")).thenReturn(driver);

        PlcDiscoveryRequest.Builder builder = mock(PlcDiscoveryRequest.Builder.class);
        PlcDiscoveryRequest request = mock(PlcDiscoveryRequest.class);
        when(driver.discoveryRequestBuilder()).thenReturn(builder);
        when(builder.addQuery("all", "*")).thenReturn(builder);
        when(builder.build()).thenReturn(request);

        // Create an item with attributes containing a STRING PlcValue.
        PlcValue firmwareValue = mock(PlcValue.class);
        when(firmwareValue.isNull()).thenReturn(false);
        when(firmwareValue.getPlcValueType()).thenReturn(org.apache.plc4x.java.api.types.PlcValueType.STRING);
        when(firmwareValue.getString()).thenReturn("V2.1.0");

        Map<String, PlcValue> attrs = new LinkedHashMap<>();
        attrs.put("firmware", firmwareValue);

        PlcDiscoveryItem item = createMockItem("s7://10.0.0.5", "s7", "tcp",
                "10.0.0.5", "PLC-FW", attrs);

        PlcDiscoveryResponse response = mock(PlcDiscoveryResponse.class);
        when(request.executeWithHandler(any(PlcDiscoveryItemHandler.class))).thenAnswer(invocation -> {
            PlcDiscoveryItemHandler handler = invocation.getArgument(0);
            handler.handle(item);
            return CompletableFuture.completedFuture(response);
        });

        List<Map<String, Object>> results = tool.discoverDevices("s7");

        assertEquals(1, results.size());
        Map<String, Object> entry = results.get(0);
        assertTrue(entry.containsKey("attributes"), "Attributes key should be present");
        @SuppressWarnings("unchecked")
        Map<String, Object> convertedAttrs = (Map<String, Object>) entry.get("attributes");
        assertEquals("V2.1.0", convertedAttrs.get("firmware"));
    }

    /**
     * Verifies that discovery items with null attributes do not have an
     * "attributes" key in the result map.
     */
    @Test
    void discoverDevices_itemWithNullAttributes_noAttributesKey() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getDiscoveryTimeoutSeconds()).thenReturn(5);

        PlcDriver driver = mock(PlcDriver.class);
        PlcDriverMetadata metadata = mock(PlcDriverMetadata.class);
        when(metadata.isDiscoverySupported()).thenReturn(true);
        when(driver.getMetadata()).thenReturn(metadata);
        when(driverManager.getDriver("s7")).thenReturn(driver);

        PlcDiscoveryRequest.Builder builder = mock(PlcDiscoveryRequest.Builder.class);
        PlcDiscoveryRequest request = mock(PlcDiscoveryRequest.class);
        when(driver.discoveryRequestBuilder()).thenReturn(builder);
        when(builder.addQuery("all", "*")).thenReturn(builder);
        when(builder.build()).thenReturn(request);

        PlcDiscoveryItem item = createMockItem("s7://10.0.0.6", "s7", "tcp",
                "10.0.0.6", "PLC-NoAttr", null);

        PlcDiscoveryResponse response = mock(PlcDiscoveryResponse.class);
        when(request.executeWithHandler(any(PlcDiscoveryItemHandler.class))).thenAnswer(invocation -> {
            PlcDiscoveryItemHandler handler = invocation.getArgument(0);
            handler.handle(item);
            return CompletableFuture.completedFuture(response);
        });

        List<Map<String, Object>> results = tool.discoverDevices("s7");

        assertEquals(1, results.size());
        assertFalse(results.get(0).containsKey("attributes"),
                "Null attributes should not produce an attributes key");
    }

    /**
     * Verifies that discovery items with empty attributes map do not have an
     * "attributes" key in the result map.
     */
    @Test
    void discoverDevices_itemWithEmptyAttributes_noAttributesKey() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getDiscoveryTimeoutSeconds()).thenReturn(5);

        PlcDriver driver = mock(PlcDriver.class);
        PlcDriverMetadata metadata = mock(PlcDriverMetadata.class);
        when(metadata.isDiscoverySupported()).thenReturn(true);
        when(driver.getMetadata()).thenReturn(metadata);
        when(driverManager.getDriver("s7")).thenReturn(driver);

        PlcDiscoveryRequest.Builder builder = mock(PlcDiscoveryRequest.Builder.class);
        PlcDiscoveryRequest request = mock(PlcDiscoveryRequest.class);
        when(driver.discoveryRequestBuilder()).thenReturn(builder);
        when(builder.addQuery("all", "*")).thenReturn(builder);
        when(builder.build()).thenReturn(request);

        PlcDiscoveryItem item = createMockItem("s7://10.0.0.7", "s7", "tcp",
                "10.0.0.7", "PLC-EmptyAttr", Collections.emptyMap());

        PlcDiscoveryResponse response = mock(PlcDiscoveryResponse.class);
        when(request.executeWithHandler(any(PlcDiscoveryItemHandler.class))).thenAnswer(invocation -> {
            PlcDiscoveryItemHandler handler = invocation.getArgument(0);
            handler.handle(item);
            return CompletableFuture.completedFuture(response);
        });

        List<Map<String, Object>> results = tool.discoverDevices("s7");

        assertEquals(1, results.size());
        assertFalse(results.get(0).containsKey("attributes"),
                "Empty attributes should not produce an attributes key");
    }

    /**
     * Verifies that when discoveryRequestBuilder itself throws, the error is
     * caught in runDiscovery and added to results rather than propagating.
     */
    @Test
    void discoverDevices_discoveryBuilderThrows_returnsErrorEntry() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getDiscoveryTimeoutSeconds()).thenReturn(5);

        PlcDriver driver = mock(PlcDriver.class);
        PlcDriverMetadata metadata = mock(PlcDriverMetadata.class);
        when(metadata.isDiscoverySupported()).thenReturn(true);
        when(driver.getMetadata()).thenReturn(metadata);
        when(driver.getProtocolCode()).thenReturn("s7");
        when(driver.discoveryRequestBuilder()).thenThrow(new RuntimeException("Builder failed"));
        when(driverManager.getDriver("s7")).thenReturn(driver);

        List<Map<String, Object>> results = tool.discoverDevices("s7");

        assertEquals(1, results.size());
        assertTrue(results.get(0).containsKey("error"));
        String error = (String) results.get(0).get("error");
        assertTrue(error.contains("s7"), "Error should mention protocol code");
        assertTrue(error.contains("Builder failed"), "Error should contain the original message");
    }

    /**
     * Verifies that error audit logging is invoked when the top-level catch fires.
     */
    @Test
    void discoverDevices_exceptionWithAuditEnabled_logsError() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(properties.getDiscoveryTimeoutSeconds()).thenReturn(5);
        when(driverManager.getDriver("bad")).thenThrow(new RuntimeException("Boom"));

        tool.discoverDevices("bad");

        verify(auditLog).write(eq(AuditLogEventType.ERROR), contains("Boom"));
    }

    /**
     * Helper to create a mock PlcDiscoveryItem with the given field values.
     */
    private PlcDiscoveryItem createMockItem(String connectionUrl, String protocolCode,
                                            String transportCode, String transportUrl,
                                            String name, Map<String, PlcValue> attributes) {
        PlcDiscoveryItem item = mock(PlcDiscoveryItem.class);
        when(item.getConnectionUrl()).thenReturn(connectionUrl);
        when(item.getProtocolCode()).thenReturn(protocolCode);
        when(item.getTransportCode()).thenReturn(transportCode);
        when(item.getTransportUrl()).thenReturn(transportUrl);
        when(item.getName()).thenReturn(name);
        when(item.getAttributes()).thenReturn(attributes);
        return item;
    }
}
