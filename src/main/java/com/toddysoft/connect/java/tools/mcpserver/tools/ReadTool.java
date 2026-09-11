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

import org.apache.plc4x.java.utils.cache.PlcConnectionCache;
import com.toddysoft.connect.java.tools.mcpserver.config.McpServerProperties;
import com.toddysoft.connect.java.tools.mcpserver.util.PlcResponseCodes;
import com.toddysoft.connect.java.tools.mcpserver.util.PlcValueConverter;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.auditlog.api.AuditLogEventType;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import com.toddysoft.connect.java.tools.mcpserver.security.ConnectionStringRedactor;
import com.toddysoft.connect.java.tools.mcpserver.security.GuardRailException;
import com.toddysoft.connect.java.tools.mcpserver.security.GuardRailRefusal;
import com.toddysoft.connect.java.tools.mcpserver.security.OperationGuard;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * MCP tool that reads tag values from a PLC device via a cached connection.
 * Supports reading multiple tags in a single request for efficiency.
 */
@Component
public class ReadTool {

    private final PlcConnectionCache connectionCache;
    private final McpServerProperties properties;
    private final AuditLog auditLog;
    private final OperationGuard guard;
    private final ConnectionStringRedactor redactor;

    /**
     * Constructs a ReadTool with the required dependencies.
     *
     * @param connectionCache pooling connection cache for PLC connections
     * @param properties        configuration properties including request timeout
     * @param auditLog          the audit log for recording tool invocations
     * @param guard             decides whether this operation is permitted at all
     * @param redactor          masks credentials before the connection string is logged
     */
    public ReadTool(PlcConnectionCache connectionCache,
                    McpServerProperties properties,
                    AuditLog auditLog,
                    OperationGuard guard,
                    ConnectionStringRedactor redactor) {
        this.connectionCache = connectionCache;
        this.properties = properties;
        this.auditLog = auditLog;
        this.guard = guard;
        this.redactor = redactor;
    }

    /**
     * Reads one or more tags from a PLC device. Each tag is specified as an
     * address string (the format depends on the protocol driver). Returns the
     * current value, data type, and response status for each tag.
     *
     * @param connectionUrl the PLC connection URL (e.g., "s7://192.168.1.1" or "modbus-tcp://10.0.0.5")
     * @param tags          list of tag addresses to read (e.g., ["%DB1.DBW0:INT", "%MW100:INT"])
     * @return a list of maps, each containing tagName, status, value, and valueType
     */
    @Tool(name = "read_tags", description = "Reads values from PLC tags. Provide the connection URL and a list of tag addresses. Returns the current value and data type for each tag.")
    public List<Map<String, Object>> readTags(
            @ToolParam(description = "PLC connection URL, e.g., 's7://192.168.1.1' or 'modbus-tcp://10.0.0.5'") String connectionUrl,
            @ToolParam(description = "List of tag addresses to read, e.g., ['%DB1.DBW0:INT', '%MW100:INT']") List<String> tags) {

        if (auditLog.isEnabled()) {
            auditLog.write(AuditLogEventType.API_REQUEST,
                    "read_tags invoked for " + redactor.redact(connectionUrl) + " with " + tags.size() + " tags", tags);
        }

        List<Map<String, Object>> results = new ArrayList<>();

        try {
            guard.checkRead(connectionUrl);
        } catch (GuardRailException refusal) {
            return GuardRailRefusal.asResultList(refusal);
        }

        try (PlcConnection connection = connectionCache.getConnection(connectionUrl)) {
            PlcReadRequest.Builder builder = connection.readRequestBuilder();

            // Use the tag address as both the name and the address for simplicity.
            for (String tagAddress : tags) {
                builder.addTagAddress(tagAddress, tagAddress);
            }

            PlcReadRequest request = builder.build();
            PlcReadResponse response = request.execute()
                    .get(properties.getTimeoutSeconds(), TimeUnit.SECONDS);

            // Map each tag's response to a result entry.
            for (String tagName : response.getTagNames()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("tagName", tagName);

                PlcResponseCode responseCode = response.getResponseCode(tagName);
                entry.put("status", responseCode.name());

                if (responseCode == PlcResponseCode.OK) {
                    PlcValue plcValue = response.getPlcValue(tagName);
                    entry.put("value", PlcValueConverter.toJsonValue(plcValue));
                    entry.put("valueType", PlcValueConverter.getTypeName(plcValue));
                } else {
                    entry.put("message", PlcResponseCodes.explain(responseCode));
                }

                results.add(entry);
            }

            if (auditLog.isEnabled()) {
                auditLog.write(AuditLogEventType.API_RESPONSE,
                        "read_tags returned " + results.size() + " results", results);
            }
        } catch (Exception e) {
            if (auditLog.isEnabled()) {
                auditLog.write(AuditLogEventType.ERROR,
                        "read_tags failed for " + redactor.redact(connectionUrl) + ": " + e.getMessage());
            }
            Map<String, Object> errorEntry = new LinkedHashMap<>();
            errorEntry.put("error", "Read failed: " + e.getMessage());
            results.add(errorEntry);
        }

        return results;
    }
}
