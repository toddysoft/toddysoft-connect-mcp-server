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
import org.apache.plc4x.java.api.PlcDriverManager;
import org.apache.plc4x.java.api.metadata.PlcDriverMetadata;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP tool that enumerates all available PLC drivers registered via ServiceLoader.
 * Reports protocol info, default transport, and discovery capability for each driver.
 */
@Component
public class DriverListTool {

    private final PlcDriverManager driverManager;
    private final AuditLog auditLog;

    /**
     * Constructs a DriverListTool with the required dependencies.
     *
     * @param auditLog the audit log for recording tool invocations
     */
    @Autowired
    public DriverListTool(AuditLog auditLog) {
        this(PlcDriverManager.getDefault(), auditLog);
    }

    /**
     * Constructs a DriverListTool with explicit driver manager (for testing).
     *
     * @param driverManager the driver manager for protocol enumeration
     * @param auditLog      the audit log for recording tool invocations
     */
    DriverListTool(PlcDriverManager driverManager, AuditLog auditLog) {
        this.driverManager = driverManager;
        this.auditLog = auditLog;
    }

    /**
     * Lists all available PLC drivers. Each entry includes the protocol code,
     * protocol name, default transport, and whether the driver supports device discovery.
     *
     * @return a list of maps, each describing one available driver
     */
    @Tool(name = "list_drivers", description = "Lists all available industrial protocol drivers and their capabilities")
    public List<Map<String, Object>> listDrivers() {
        if (auditLog.isEnabled()) {
            auditLog.write(AuditLogEventType.API_REQUEST, "list_drivers invoked");
        }

        List<Map<String, Object>> result = new ArrayList<>();

        try {
            for (String code : driverManager.getProtocolCodes()) {
                PlcDriver driver = driverManager.getDriver(code);
                Map<String, Object> driverInfo = new LinkedHashMap<>();
                driverInfo.put("protocolCode", driver.getProtocolCode());
                driverInfo.put("protocolName", driver.getProtocolName());

                PlcDriverMetadata metadata = driver.getMetadata();
                driverInfo.put("defaultTransport",
                        metadata.getDefaultTransportCode().orElse("unknown"));
                driverInfo.put("canDiscover", metadata.isDiscoverySupported());

                result.add(driverInfo);
            }

            result.sort(Comparator.comparing(m -> (String) m.get("protocolCode")));

            if (auditLog.isEnabled()) {
                auditLog.write(AuditLogEventType.API_RESPONSE,
                        "list_drivers returned " + result.size() + " drivers", result);
            }
        } catch (Exception e) {
            if (auditLog.isEnabled()) {
                auditLog.write(AuditLogEventType.ERROR,
                        "list_drivers failed: " + e.getMessage());
            }
            Map<String, Object> errorEntry = new LinkedHashMap<>();
            errorEntry.put("error", "Failed to enumerate drivers: " + e.getMessage());
            result.add(errorEntry);
        }

        return result;
    }
}
