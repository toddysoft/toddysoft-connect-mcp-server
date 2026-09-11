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

import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.auditlog.api.AuditLogEventType;
import org.apache.plc4x.java.api.PlcDriver;
import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.PlcDriverManager;
import org.apache.plc4x.java.api.metadata.Option;
import org.apache.plc4x.java.api.metadata.OptionMetadata;
import org.apache.plc4x.java.api.types.OptionType;
import org.apache.plc4x.java.api.metadata.PlcDriverMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DriverListTool} using a mock {@link PlcDriverManager}
 * injected via the package-private constructor.
 */
@ExtendWith(MockitoExtension.class)
class DriverListToolTest {

    @Mock
    private PlcDriverManager driverManager;

    @Mock
    private AuditLog auditLog;

    private DriverListTool tool;

    @BeforeEach
    void setUp() {
        // Use the package-private constructor to inject the mock driver manager.
        tool = new DriverListTool(driverManager, auditLog);
    }

    /**
     * Verifies that listDrivers returns correct info for multiple loaded drivers,
     * including protocolCode, protocolName, defaultTransport, and canDiscover.
     */
    @Test
    void listDrivers_multipleDrivers_returnsCorrectInfo() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);

        // Set up two drivers: s7 and modbus.
        when(driverManager.getProtocolCodes()).thenReturn(new LinkedHashSet<>(List.of("s7", "modbus")));

        PlcDriver s7Driver = createMockDriver("s7", "Siemens S7", "tcp", true);
        PlcDriver modbusDriver = createMockDriver("modbus", "Modbus TCP", "tcp", false);
        when(driverManager.getDriver("s7")).thenReturn(s7Driver);
        when(driverManager.getDriver("modbus")).thenReturn(modbusDriver);

        List<Map<String, Object>> results = tool.listDrivers();

        assertEquals(2, results.size());

        // Results should be sorted by protocolCode.
        assertEquals("modbus", results.get(0).get("protocolCode"));
        assertEquals("s7", results.get(1).get("protocolCode"));

        // Verify first entry (modbus, sorted first).
        Map<String, Object> modbusEntry = results.get(0);
        assertEquals("Modbus TCP", modbusEntry.get("protocolName"));
        assertEquals("tcp", modbusEntry.get("defaultTransport"));
        assertEquals(false, modbusEntry.get("canDiscover"));

        // Verify second entry (s7).
        Map<String, Object> s7Entry = results.get(1);
        assertEquals("Siemens S7", s7Entry.get("protocolName"));
        assertEquals("tcp", s7Entry.get("defaultTransport"));
        assertEquals(true, s7Entry.get("canDiscover"));
    }

    /**
     * When no protocol codes are available, the result list should be empty.
     */
    @Test
    void listDrivers_emptyProtocolCodes_returnsEmptyList() {
        when(auditLog.isEnabled()).thenReturn(false);
        when(driverManager.getProtocolCodes()).thenReturn(Collections.emptySet());

        List<Map<String, Object>> results = tool.listDrivers();

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    /**
     * Verifies that a driver with discovery support reports canDiscover = true.
     */
    @Test
    void listDrivers_discoverySupported_canDiscoverTrue() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(driverManager.getProtocolCodes()).thenReturn(Set.of("s7"));

        PlcDriver driver = createMockDriver("s7", "Siemens S7", "tcp", true);
        when(driverManager.getDriver("s7")).thenReturn(driver);

        List<Map<String, Object>> results = tool.listDrivers();

        assertEquals(1, results.size());
        assertEquals(true, results.get(0).get("canDiscover"));
    }

    /**
     * Verifies that a driver without discovery support reports canDiscover = false.
     */
    @Test
    void listDrivers_discoveryNotSupported_canDiscoverFalse() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(driverManager.getProtocolCodes()).thenReturn(Set.of("modbus"));

        PlcDriver driver = createMockDriver("modbus", "Modbus TCP", "tcp", false);
        when(driverManager.getDriver("modbus")).thenReturn(driver);

        List<Map<String, Object>> results = tool.listDrivers();

        assertEquals(1, results.size());
        assertEquals(false, results.get(0).get("canDiscover"));
    }

    /**
     * When the driver manager throws an exception, the result should contain
     * an error entry rather than propagating the exception.
     */
    @Test
    void listDrivers_exceptionThrown_returnsError() {
        when(auditLog.isEnabled()).thenReturn(true);
        when(driverManager.getProtocolCodes()).thenThrow(new RuntimeException("Service failure"));

        List<Map<String, Object>> results = tool.listDrivers();

        assertFalse(results.isEmpty());
        assertTrue(results.get(0).containsKey("error"));
        String error = (String) results.get(0).get("error");
        assertTrue(error.contains("Service failure"));
    }

    /**
     * Verifies that the audit log is invoked for both request and response events.
     */
    @Test
    void listDrivers_auditLogEnabled_logsRequestAndResponse() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(driverManager.getProtocolCodes()).thenReturn(Set.of("s7"));

        PlcDriver driver = createMockDriver("s7", "Siemens S7", "tcp", true);
        when(driverManager.getDriver("s7")).thenReturn(driver);

        tool.listDrivers();

        verify(auditLog).write(eq(AuditLogEventType.API_REQUEST), eq("list_drivers invoked"));
        verify(auditLog).write(eq(AuditLogEventType.API_RESPONSE),
                contains("list_drivers returned 1 drivers"), any());
    }

    /**
     * Verifies that no audit log events are written when the log is disabled.
     */
    @Test
    void listDrivers_auditLogDisabled_doesNotLog() {
        when(auditLog.isEnabled()).thenReturn(false);
        when(driverManager.getProtocolCodes()).thenReturn(Collections.emptySet());

        tool.listDrivers();

        verify(auditLog, never()).write(any(AuditLogEventType.class), anyString());
        verify(auditLog, never()).write(any(AuditLogEventType.class), anyString(), any());
    }

    /**
     * Verifies that the results are sorted alphabetically by protocolCode.
     */
    @Test
    void listDrivers_resultsSortedByProtocolCode() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);

        // Provide codes in reverse order to verify sorting.
        when(driverManager.getProtocolCodes()).thenReturn(new LinkedHashSet<>(List.of("z-proto", "a-proto", "m-proto")));

        PlcDriver zDriver = createMockDriver("z-proto", "Z Protocol", "tcp", false);
        PlcDriver aDriver = createMockDriver("a-proto", "A Protocol", "udp", true);
        PlcDriver mDriver = createMockDriver("m-proto", "M Protocol", "serial", false);
        when(driverManager.getDriver("z-proto")).thenReturn(zDriver);
        when(driverManager.getDriver("a-proto")).thenReturn(aDriver);
        when(driverManager.getDriver("m-proto")).thenReturn(mDriver);

        List<Map<String, Object>> results = tool.listDrivers();

        assertEquals(3, results.size());
        assertEquals("a-proto", results.get(0).get("protocolCode"));
        assertEquals("m-proto", results.get(1).get("protocolCode"));
        assertEquals("z-proto", results.get(2).get("protocolCode"));
    }

    /**
     * Verifies that when a driver's metadata returns an empty Optional for
     * defaultTransportCode, the result shows "unknown".
     */
    @Test
    void listDrivers_noDefaultTransport_showsUnknown() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(driverManager.getProtocolCodes()).thenReturn(Set.of("custom"));

        PlcDriver driver = mock(PlcDriver.class);
        when(driver.getProtocolCode()).thenReturn("custom");
        when(driver.getProtocolName()).thenReturn("Custom Protocol");
        PlcDriverMetadata metadata = mock(PlcDriverMetadata.class);
        when(metadata.getDefaultTransportCode()).thenReturn(Optional.empty());
        when(metadata.isDiscoverySupported()).thenReturn(false);
        when(driver.getMetadata()).thenReturn(metadata);
        when(driverManager.getDriver("custom")).thenReturn(driver);

        List<Map<String, Object>> results = tool.listDrivers();

        assertEquals(1, results.size());
        assertEquals("unknown", results.get(0).get("defaultTransport"));
    }

    /**
     * Verifies that the error audit log event is written when an exception occurs
     * and audit logging is enabled.
     */
    @Test
    void listDrivers_exceptionWithAuditEnabled_logsError() {
        when(auditLog.isEnabled()).thenReturn(true);
        when(driverManager.getProtocolCodes()).thenThrow(new RuntimeException("Boom"));

        tool.listDrivers();

        verify(auditLog).write(eq(AuditLogEventType.ERROR), contains("Boom"));
    }

    /**
     * Helper to create a mock PlcDriver with the given field values.
     *
     * @param protocolCode      the protocol code
     * @param protocolName      the protocol name
     * @param defaultTransport  the default transport code (or null for empty Optional)
     * @param discoverySupported whether discovery is supported
     * @return a mock PlcDriver
     */
    private PlcDriver createMockDriver(String protocolCode, String protocolName,
                                       String defaultTransport, boolean discoverySupported) {
        PlcDriver driver = mock(PlcDriver.class);
        when(driver.getProtocolCode()).thenReturn(protocolCode);
        when(driver.getProtocolName()).thenReturn(protocolName);

        PlcDriverMetadata metadata = mock(PlcDriverMetadata.class);
        when(metadata.getDefaultTransportCode()).thenReturn(
                defaultTransport != null ? Optional.of(defaultTransport) : Optional.empty());
        when(metadata.isDiscoverySupported()).thenReturn(discoverySupported);
        when(driver.getMetadata()).thenReturn(metadata);

        return driver;
    }

    /**
     * The supported transports come straight from the driver metadata, because a caller building a
     * connection string needs to know which transport prefixes the driver will accept.
     */
    @Test
    void listDrivers_reportsSupportedTransports() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(driverManager.getProtocolCodes()).thenReturn(Set.of("modbus-tcp"));

        PlcDriver driver = createMockDriver("modbus-tcp", "Modbus TCP", "tcp", true);
        when(driver.getMetadata().getSupportedTransportCodes()).thenReturn(List.of("tcp", "udp", "tls"));
        when(driverManager.getDriver("modbus-tcp")).thenReturn(driver);

        List<Map<String, Object>> results = tool.listDrivers();

        assertEquals(List.of("tcp", "udp", "tls"), results.get(0).get("supportedTransports"));
    }

    /**
     * describe_driver renders the PLC4X option metadata verbatim: type, description, required
     * flag, default value and the secret marker.
     */
    @Test
    void describeDriver_rendersProtocolOptions() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);

        PlcDriver driver = createMockDriver("opcua", "OPC UA", "tcp", false);
        PlcDriverMetadata metadata = driver.getMetadata();
        OptionMetadata protocolOptions = optionMetadata(
                option("username", OptionType.STRING, "The user to connect as.", false, null, false),
                option("password", OptionType.STRING, "The password.", false, null, true),
                option("request-timeout", OptionType.INT, "Timeout in ms.", true, 5000, false));
        when(metadata.getProtocolConfigurationOptionMetadata()).thenReturn(Optional.of(protocolOptions));
        when(metadata.getTransportConfigurationOptionMetadata("tcp")).thenReturn(Optional.empty());
        when(driverManager.getDriver("opcua")).thenReturn(driver);

        Map<String, Object> result = tool.describeDriver("opcua", null);

        assertEquals("opcua", result.get("protocolCode"));
        assertEquals("tcp", result.get("transportCode"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) result.get("protocolOptions");
        assertEquals(3, options.size());

        Map<String, Object> username = options.get(0);
        assertEquals("username", username.get("key"));
        assertEquals("STRING", username.get("type"));
        assertEquals(false, username.get("required"));
        // A non-secret option carries no secret marker at all, rather than "secret": false.
        assertFalse(username.containsKey("secret"));
        // An absent default must stay absent, so it cannot be read as a default of null.
        assertFalse(username.containsKey("defaultValue"));

        assertEquals(true, options.get(1).get("secret"));

        Map<String, Object> timeout = options.get(2);
        assertEquals(true, timeout.get("required"));
        assertEquals(5000, timeout.get("defaultValue"));
    }

    /**
     * An explicitly named transport is described instead of the driver's default.
     */
    @Test
    void describeDriver_explicitTransport_overridesDefault() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);

        PlcDriver driver = createMockDriver("modbus-tcp", "Modbus TCP", "tcp", true);
        PlcDriverMetadata metadata = driver.getMetadata();
        when(metadata.getProtocolConfigurationOptionMetadata()).thenReturn(Optional.empty());
        OptionMetadata tlsOptions = optionMetadata(
                option("tls.keystore", OptionType.STRING, "Keystore path.", false, null, false));
        when(metadata.getTransportConfigurationOptionMetadata("tls")).thenReturn(Optional.of(tlsOptions));
        when(driverManager.getDriver("modbus-tcp")).thenReturn(driver);

        Map<String, Object> result = tool.describeDriver("modbus-tcp", "tls");

        assertEquals("tls", result.get("transportCode"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) result.get("transportOptions");
        assertEquals("tls.keystore", options.get(0).get("key"));
        // Protocol options are still reported, just empty for this driver.
        assertEquals(List.of(), result.get("protocolOptions"));
    }

    /**
     * An unknown protocol code is reported as an error entry rather than thrown, matching how
     * listDrivers already degrades.
     */
    @Test
    void describeDriver_unknownDriver_returnsError() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(driverManager.getDriver("nope"))
                .thenThrow(new PlcConnectionException("Unable to find driver for protocol 'nope'"));

        Map<String, Object> result = tool.describeDriver("nope", null);

        assertTrue(((String) result.get("error")).contains("nope"));
    }

    private static OptionMetadata optionMetadata(Option... options) {
        OptionMetadata metadata = mock(OptionMetadata.class);
        when(metadata.getOptions()).thenReturn(List.of(options));
        return metadata;
    }

    private static Option option(String key, OptionType type, String description,
                                 boolean required, Object defaultValue, boolean secret) {
        Option option = mock(Option.class);
        when(option.getKey()).thenReturn(key);
        when(option.getType()).thenReturn(type);
        when(option.getDescription()).thenReturn(description);
        when(option.isRequired()).thenReturn(required);
        when(option.getDefaultValue()).thenReturn(Optional.ofNullable(defaultValue));
        when(option.getSince()).thenReturn(Optional.empty());
        when(option.isSecret()).thenReturn(secret);
        return option;
    }
}
