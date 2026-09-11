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
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.auditlog.api.AuditLogEventType;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * MCP tool that writes values to tags on a PLC device via a cached connection.
 * Supports writing multiple tags in a single request. Values are passed as
 * JSON-compatible types (String, Number, Boolean) and converted by the driver.
 */
@Component
public class WriteTool {

    private final PlcConnectionCache connectionCache;
    private final McpServerProperties properties;
    private final AuditLog auditLog;

    /**
     * Constructs a WriteTool with the required dependencies.
     *
     * @param connectionCache pooling connection cache for PLC connections
     * @param properties        configuration properties including request timeout
     * @param auditLog          the audit log for recording tool invocations
     */
    public WriteTool(PlcConnectionCache connectionCache,
                     McpServerProperties properties,
                     AuditLog auditLog) {
        this.connectionCache = connectionCache;
        this.properties = properties;
        this.auditLog = auditLog;
    }

    /**
     * Writes values to one or more tags on a PLC device. Each entry in the tags
     * map is a tag address mapped to the value to write. Values should be
     * JSON-compatible types: String, Number (Integer, Long, Double), or Boolean.
     * The driver handles type conversion to the PLC's native format.
     *
     * @param connectionUrl the PLC connection URL (e.g., "s7://192.168.1.1" or "modbus-tcp://10.0.0.5")
     * @param tags          map of tag address to value (e.g., {"%DB1.DBW0:INT": 42, "%M0.0:BOOL": true})
     * @return a list of maps, each containing tagName and status indicating write success or failure
     */
    @Tool(name = "write_tags", description = "Writes values to PLC tags. Provide the connection URL and a map of tag addresses to values. Values can be strings, numbers, or booleans. Returns the write status for each tag.")
    public List<Map<String, Object>> writeTags(
            @ToolParam(description = "PLC connection URL, e.g., 's7://192.168.1.1' or 'modbus-tcp://10.0.0.5'") String connectionUrl,
            @ToolParam(description = "Map of tag address to value, e.g., {'%DB1.DBW0:INT': 42, '%M0.0:BOOL': true}") Map<String, Object> tags) {

        if (auditLog.isEnabled()) {
            auditLog.write(AuditLogEventType.API_REQUEST,
                    "write_tags invoked for " + connectionUrl + " with " + tags.size() + " tags", tags);
        }

        List<Map<String, Object>> results = new ArrayList<>();

        try (PlcConnection connection = connectionCache.getConnection(connectionUrl)) {
            PlcWriteRequest.Builder builder = connection.writeRequestBuilder();

            // Use the tag address as both the name and the address for simplicity.
            for (Map.Entry<String, Object> tagEntry : tags.entrySet()) {
                String tagAddress = tagEntry.getKey();
                Object value = tagEntry.getValue();
                // Pass the raw value to the builder; the driver's value handler
                // will convert it to the appropriate PlcValue type.
                builder.addTagAddress(tagAddress, tagAddress, value);
            }

            PlcWriteRequest request = builder.build();
            PlcWriteResponse response = request.execute()
                    .get(properties.getTimeoutSeconds(), TimeUnit.SECONDS);

            // Map each tag's write result to an output entry.
            for (String tagName : response.getTagNames()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("tagName", tagName);

                PlcResponseCode responseCode = response.getResponseCode(tagName);
                entry.put("status", responseCode.name());

                results.add(entry);
            }

            if (auditLog.isEnabled()) {
                auditLog.write(AuditLogEventType.API_RESPONSE,
                        "write_tags completed " + results.size() + " writes", results);
            }
        } catch (Exception e) {
            if (auditLog.isEnabled()) {
                auditLog.write(AuditLogEventType.ERROR,
                        "write_tags failed for " + connectionUrl + ": " + e.getMessage());
            }
            Map<String, Object> errorEntry = new LinkedHashMap<>();
            errorEntry.put("error", "Write failed: " + e.getMessage());
            results.add(errorEntry);
        }

        return results;
    }
}
