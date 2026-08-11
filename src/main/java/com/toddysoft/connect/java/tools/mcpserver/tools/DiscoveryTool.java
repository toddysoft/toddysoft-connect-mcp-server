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
import com.toddysoft.connect.java.tools.mcpserver.util.PlcValueConverter;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.auditlog.api.AuditLogEventType;
import org.apache.plc4x.java.api.PlcDriver;
import org.apache.plc4x.java.api.PlcDriverManager;
import org.apache.plc4x.java.api.messages.PlcDiscoveryItem;
import org.apache.plc4x.java.api.messages.PlcDiscoveryRequest;
import org.apache.plc4x.java.api.value.PlcValue;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * MCP tool that discovers PLC devices on the network using driver-specific
 * discovery mechanisms. Can target a specific protocol or scan all protocols
 * that support discovery.
 */
@Component
public class DiscoveryTool {

    private final PlcDriverManager driverManager;
    private final McpServerProperties properties;
    private final AuditLog auditLog;

    /**
     * Constructs a DiscoveryTool with the required dependencies.
     *
     * @param properties    configuration properties including discovery timeout
     * @param auditLog      the audit log for recording tool invocations
     */
    @Autowired
    public DiscoveryTool(McpServerProperties properties, AuditLog auditLog) {
        this(PlcDriverManager.getDefault(), properties, auditLog);
    }

    /**
     * Constructs a DiscoveryTool with explicit driver manager (for testing).
     *
     * @param driverManager the driver manager for driver lookup and enumeration
     * @param properties    configuration properties including discovery timeout
     * @param auditLog      the audit log for recording tool invocations
     */
    DiscoveryTool(PlcDriverManager driverManager, McpServerProperties properties, AuditLog auditLog) {
        this.driverManager = driverManager;
        this.properties = properties;
        this.auditLog = auditLog;
    }

    /**
     * Discovers PLC devices on the network. If a protocol code is specified,
     * only that protocol's discovery is executed. Otherwise, all discovery-capable
     * protocols are scanned in parallel.
     *
     * @param protocolCode optional protocol code to restrict discovery to a single driver
     * @return a list of maps describing discovered devices, each with connection URL,
     *         protocol, transport, and device name
     */
    @Tool(name = "discover_devices", description = "Discovers industrial devices on the network. Optionally specify a protocolCode to limit discovery to a specific protocol (e.g., 's7', 'modbus-tcp'). Returns discovered devices with their connection URLs.")
    public List<Map<String, Object>> discoverDevices(
            @ToolParam(description = "Optional protocol code to restrict discovery to a single driver (e.g., 's7', 'modbus-tcp'). Leave empty to discover across all supported protocols.") String protocolCode) {

        if (auditLog.isEnabled()) {
            auditLog.write(AuditLogEventType.API_REQUEST,
                    "discover_devices invoked" + (protocolCode != null ? " for protocol: " + protocolCode : " for all protocols"));
        }

        List<Map<String, Object>> results = new CopyOnWriteArrayList<>();
        int timeoutSeconds = properties.getDiscoveryTimeoutSeconds();

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            if (protocolCode != null && !protocolCode.isBlank()) {
                // Discover using a specific driver.
                PlcDriver driver = driverManager.getDriver(protocolCode);
                if (!driver.getMetadata().isDiscoverySupported()) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("error", "Driver '" + protocolCode + "' does not support discovery.");
                    results.add(info);
                } else {
                    futures.add(runDiscovery(driver, results));
                }
            } else {
                // Discover across all protocols that support it.
                for (String code : driverManager.getProtocolCodes()) {
                    PlcDriver driver = driverManager.getDriver(code);
                    if (driver.getMetadata().isDiscoverySupported()) {
                        futures.add(runDiscovery(driver, results));
                    }
                }
            }

            // Wait for all discovery operations to complete within the timeout.
            if (!futures.isEmpty()) {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(timeoutSeconds, TimeUnit.SECONDS);
            }

            if (auditLog.isEnabled()) {
                auditLog.write(AuditLogEventType.API_RESPONSE,
                        "discover_devices returned " + results.size() + " items", results);
            }
        } catch (Exception e) {
            if (auditLog.isEnabled()) {
                auditLog.write(AuditLogEventType.ERROR,
                        "discover_devices failed: " + e.getMessage());
            }
            Map<String, Object> errorEntry = new LinkedHashMap<>();
            errorEntry.put("error", "Discovery failed: " + e.getMessage());
            results.add(errorEntry);
        }

        return results;
    }

    /**
     * Runs discovery for a single driver using the handler-based approach,
     * collecting results into the shared list as they arrive.
     */
    private CompletableFuture<Void> runDiscovery(PlcDriver driver, List<Map<String, Object>> results) {
        try {
            PlcDiscoveryRequest request = driver.discoveryRequestBuilder()
                    .addQuery("all", "*")
                    .build();

            return request.executeWithHandler(item -> {
                Map<String, Object> itemMap = mapDiscoveryItem(item);
                results.add(itemMap);
            }).<Void>thenApply(response -> null)
              .exceptionally(ex -> {
                  // Capture async errors (e.g., socket bind failures) so they are not silently lost.
                  Map<String, Object> errorEntry = new LinkedHashMap<>();
                  errorEntry.put("error", "Discovery failed for " + driver.getProtocolCode() + ": " + ex.getMessage());
                  results.add(errorEntry);
                  return null;
              });
        } catch (Exception e) {
            Map<String, Object> errorEntry = new LinkedHashMap<>();
            errorEntry.put("error", "Discovery failed for " + driver.getProtocolCode() + ": " + e.getMessage());
            results.add(errorEntry);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Maps a single discovery item to a serializable map representation.
     */
    private Map<String, Object> mapDiscoveryItem(PlcDiscoveryItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("connectionUrl", item.getConnectionUrl());
        map.put("protocolCode", item.getProtocolCode());
        map.put("transportCode", item.getTransportCode());
        map.put("transportUrl", item.getTransportUrl());
        map.put("name", item.getName());

        Map<String, PlcValue> attributes = item.getAttributes();
        if (attributes != null && !attributes.isEmpty()) {
            Map<String, Object> convertedAttrs = new LinkedHashMap<>();
            for (Map.Entry<String, PlcValue> entry : attributes.entrySet()) {
                convertedAttrs.put(entry.getKey(), PlcValueConverter.toJsonValue(entry.getValue()));
            }
            map.put("attributes", convertedAttrs);
        }

        return map;
    }
}
