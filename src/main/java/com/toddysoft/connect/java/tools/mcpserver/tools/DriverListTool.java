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
import org.apache.plc4x.java.api.metadata.Option;
import org.apache.plc4x.java.api.metadata.OptionMetadata;
import org.apache.plc4x.java.api.metadata.PlcDriverMetadata;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP tools that describe the PLC drivers registered via ServiceLoader.
 *
 * <p>{@code list_drivers} is the catalogue — one summary line per protocol.
 * {@code describe_driver} is the detail for one of them: the connection parameters PLC4X itself
 * declares through {@link PlcDriverMetadata} and {@link OptionMetadata}, which is what a caller
 * needs in order to build a valid connection string rather than guess at one.</p>
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
     * protocol name, default and supported transports, and whether the driver supports
     * device discovery. Use {@code describe_driver} for a driver's connection parameters.
     *
     * @return a list of maps, each describing one available driver
     */
    @Tool(name = "list_drivers", description = "Lists all available industrial protocol drivers and their capabilities, including which transports each supports. Use describe_driver for the connection parameters of one driver.")
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
                driverInfo.put("supportedTransports", metadata.getSupportedTransportCodes());
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

    /**
     * Describes the connection parameters of one driver, as PLC4X declares them.
     *
     * <p>Every PLC4X driver publishes its configuration options through
     * {@link PlcDriverMetadata} — key, type, description, whether it is required, its default, and
     * whether it carries a secret. That is the protocol-agnostic way to learn how a connection
     * string for this driver is built, so it is reported here verbatim rather than restated.</p>
     *
     * <p>Note that in Apache PLC4X credentials are ordinary connection parameters (OPC UA's
     * {@code username} / {@code password}, for instance). Options flagged {@code secret} hold one:
     * their values belong in the connection string the caller supplies, and must not be echoed back
     * in logs or transcripts.</p>
     *
     * @param protocolCode  the protocol code, as reported by {@code list_drivers}
     * @param transportCode the transport whose options to include; the driver's default when omitted
     * @return a map describing the driver's transports and configuration options
     */
    @Tool(name = "describe_driver", description = "Describes one driver's connection parameters: the protocol and transport configuration options PLC4X declares, each with its type, description, required flag, default value and whether it holds a secret. Use this to build a valid connection string.")
    public Map<String, Object> describeDriver(
            @ToolParam(description = "Protocol code of the driver, e.g. 's7', 'modbus-tcp', 'opcua'")
            String protocolCode,
            @ToolParam(required = false, description = "Transport code whose options to include, e.g. 'tcp'. Defaults to the driver's default transport.")
            String transportCode) {

        if (auditLog.isEnabled()) {
            auditLog.write(AuditLogEventType.API_REQUEST, "describe_driver invoked for " + protocolCode);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            PlcDriver driver = driverManager.getDriver(protocolCode);
            PlcDriverMetadata metadata = driver.getMetadata();

            result.put("protocolCode", driver.getProtocolCode());
            result.put("protocolName", driver.getProtocolName());
            result.put("defaultTransport", metadata.getDefaultTransportCode().orElse("unknown"));
            result.put("supportedTransports", metadata.getSupportedTransportCodes());
            result.put("canDiscover", metadata.isDiscoverySupported());

            result.put("protocolOptions", metadata.getProtocolConfigurationOptionMetadata()
                    .map(DriverListTool::describeOptions)
                    .orElseGet(List::of));

            // An explicit transport wins; otherwise describe the one a connection string would use
            // if it named no transport at all.
            String transport = (transportCode != null && !transportCode.isBlank())
                    ? transportCode
                    : metadata.getDefaultTransportCode().orElse(null);
            if (transport != null) {
                result.put("transportCode", transport);
                result.put("transportOptions", metadata.getTransportConfigurationOptionMetadata(transport)
                        .map(DriverListTool::describeOptions)
                        .orElseGet(List::of));
            }

            if (auditLog.isEnabled()) {
                auditLog.write(AuditLogEventType.API_RESPONSE,
                        "describe_driver returned metadata for " + protocolCode, result);
            }
        } catch (Exception e) {
            if (auditLog.isEnabled()) {
                auditLog.write(AuditLogEventType.ERROR,
                        "describe_driver failed for " + protocolCode + ": " + e.getMessage());
            }
            result.put("error", "Failed to describe driver '" + protocolCode + "': " + e.getMessage());
        }

        return result;
    }

    /**
     * Renders PLC4X {@link Option} metadata into plain maps, omitting what a driver did not state
     * so an absent default is distinguishable from a default of {@code null}.
     */
    private static List<Map<String, Object>> describeOptions(OptionMetadata optionMetadata) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (Option option : optionMetadata.getOptions()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", option.getKey());
            entry.put("type", String.valueOf(option.getType()));
            entry.put("description", option.getDescription());
            entry.put("required", option.isRequired());
            option.getDefaultValue().ifPresent(value -> entry.put("defaultValue", value));
            if (option.isSecret()) {
                entry.put("secret", true);
            }
            option.getSince().ifPresent(since -> entry.put("since", since));
            options.add(entry);
        }
        return options;
    }
}
