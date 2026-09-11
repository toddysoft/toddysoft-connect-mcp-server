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
import org.apache.plc4x.java.api.messages.PlcBrowseItem;
import org.apache.plc4x.java.api.messages.PlcBrowseRequest;
import org.apache.plc4x.java.api.messages.PlcBrowseResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * MCP tool that browses the tag address space of a PLC device.
 * Returns a hierarchical tree of available tags with their data types
 * and access capabilities (readable, writable, subscribable, publishable).
 */
@Component
public class BrowseTool {

    private final PlcConnectionCache connectionCache;
    private final McpServerProperties properties;
    private final AuditLog auditLog;

    /**
     * Constructs a BrowseTool with the required dependencies.
     *
     * @param connectionCache pooling connection cache for PLC connections
     * @param properties        configuration properties including request timeout
     * @param auditLog          the audit log for recording tool invocations
     */
    public BrowseTool(PlcConnectionCache connectionCache,
                      McpServerProperties properties,
                      AuditLog auditLog) {
        this.connectionCache = connectionCache;
        this.properties = properties;
        this.auditLog = auditLog;
    }

    /**
     * Browses the tag address space of a PLC device. Returns a hierarchical
     * structure of available tags including their addresses, data types, and
     * access capabilities. An optional query string can filter the results.
     *
     * @param connectionUrl the PLC connection URL (e.g., "s7://192.168.1.1" or "ads://10.0.0.5")
     * @param query         optional query string to filter the browse (default: "*" for all)
     * @return a list of maps representing the tag tree, each with name, address, dataType, and children
     */
    @Tool(name = "browse_tags", description = "Browses the address space of a PLC. Returns a hierarchical list of tags with their data types and read/write capabilities. Use this before reading or writing to discover what tags are available.")
    public List<Map<String, Object>> browseTags(
            @ToolParam(description = "PLC connection URL, e.g., 's7://192.168.1.1' or 'ads://10.0.0.5'") String connectionUrl,
            @ToolParam(description = "Optional browse query to filter results (default: '*' for all tags)") String query) {

        // Default to wildcard query if none specified.
        String effectiveQuery = (query != null && !query.isBlank()) ? query : "*";

        if (auditLog.isEnabled()) {
            auditLog.write(AuditLogEventType.API_REQUEST,
                    "browse_tags invoked for " + connectionUrl + " with query: " + effectiveQuery);
        }

        List<Map<String, Object>> results = new ArrayList<>();

        try (PlcConnection connection = connectionCache.getConnection(connectionUrl)) {
            PlcBrowseRequest request = connection.browseRequestBuilder()
                    .addQuery("query", effectiveQuery)
                    .build();

            PlcBrowseResponse response = request.execute()
                    .get(properties.getTimeoutSeconds(), TimeUnit.SECONDS);

            PlcResponseCode responseCode = response.getResponseCode("query");
            if (responseCode != PlcResponseCode.OK) {
                Map<String, Object> errorEntry = new LinkedHashMap<>();
                errorEntry.put("error", "Browse returned status: " + responseCode.name());
                results.add(errorEntry);
            } else {
                List<PlcBrowseItem> items = response.getValues("query");
                for (PlcBrowseItem item : items) {
                    results.add(mapBrowseItem(item));
                }
            }

            if (auditLog.isEnabled()) {
                auditLog.write(AuditLogEventType.API_RESPONSE,
                        "browse_tags returned " + results.size() + " top-level items", results);
            }
        } catch (Exception e) {
            if (auditLog.isEnabled()) {
                auditLog.write(AuditLogEventType.ERROR,
                        "browse_tags failed for " + connectionUrl + ": " + e.getMessage());
            }
            Map<String, Object> errorEntry = new LinkedHashMap<>();
            // Include the full cause chain so the root cause is visible to the caller.
            StringBuilder errorMsg = new StringBuilder("Browse failed: " + e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                errorMsg.append(" -> ").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
                cause = cause.getCause();
            }
            errorEntry.put("error", errorMsg.toString());
            results.add(errorEntry);
        }

        return results;
    }

    /**
     * Recursively maps a PlcBrowseItem and its children to a serializable map.
     * Includes the tag address, data type, and access capability flags.
     *
     * @param item the browse item to convert
     * @return a map containing the item's properties and any children
     */
    private Map<String, Object> mapBrowseItem(PlcBrowseItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", item.getName());
        map.put("address", item.getTag().getAddressString());
        map.put("dataType", item.getTag().getPlcValueType().name());
        map.put("readable", item.isReadable());
        map.put("writable", item.isWritable());
        map.put("subscribable", item.isSubscribable());
        map.put("publishable", item.isPublishable());

        // Recursively include children if present.
        Map<String, PlcBrowseItem> children = item.getChildren();
        if (children != null && !children.isEmpty()) {
            List<Map<String, Object>> childList = new ArrayList<>();
            for (PlcBrowseItem child : children.values()) {
                childList.add(mapBrowseItem(child));
            }
            map.put("children", childList);
        }

        return map;
    }
}
