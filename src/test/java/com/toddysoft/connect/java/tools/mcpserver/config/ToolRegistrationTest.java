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

import com.toddysoft.connect.java.tools.mcpserver.security.TestGuards;
import com.toddysoft.connect.java.tools.mcpserver.tools.*;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.cache.PlcConnectionCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A disabled capability must not be advertised at all.
 *
 * <p>Refusing a call the model was invited to make wastes a round trip and tokens, and reads to the
 * model as a broken tool rather than a policy. Not offering it is both safer and cheaper.</p>
 */
@ExtendWith(MockitoExtension.class)
class ToolRegistrationTest {

    @Mock
    private PlcConnectionCache connectionCache;

    @Mock
    private AuditLog auditLog;

    private McpServerConfiguration configuration;
    private McpServerProperties properties;

    @BeforeEach
    void setUp() {
        configuration = new McpServerConfiguration();
        properties = new McpServerProperties();
    }

    private List<String> advertisedTools(Optional<WriteTool> writeTool, Optional<DiscoveryTool> discoveryTool) {
        ToolCallbackProvider provider = configuration.toolCallbackProvider(
                new BrowseTool(connectionCache, properties, auditLog, TestGuards.permissive(), TestGuards.redactor()),
                new ReadTool(connectionCache, properties, auditLog, TestGuards.permissive(), TestGuards.redactor()),
                writeTool,
                discoveryTool,
                new DriverListTool(auditLog));
        return Arrays.stream(provider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private WriteTool aWriteTool() {
        return new WriteTool(connectionCache, properties, auditLog, TestGuards.permissive(), TestGuards.redactor());
    }

    private DiscoveryTool aDiscoveryTool() {
        return new DiscoveryTool(properties, auditLog, TestGuards.permissive("s7"));
    }

    @Test
    void writeTagsIsNotAdvertisedWhenWritesAreDisabled() {
        List<String> tools = advertisedTools(Optional.empty(), Optional.of(aDiscoveryTool()));

        assertFalse(tools.contains("write_tags"), "was: " + tools);
    }

    @Test
    void discoverDevicesIsNotAdvertisedWhenDiscoveryIsDisabled() {
        List<String> tools = advertisedTools(Optional.of(aWriteTool()), Optional.empty());

        assertFalse(tools.contains("discover_devices"), "was: " + tools);
    }

    @Test
    void bothAreAdvertisedWhenEnabled() {
        List<String> tools = advertisedTools(Optional.of(aWriteTool()), Optional.of(aDiscoveryTool()));

        assertTrue(tools.contains("write_tags"), "was: " + tools);
        assertTrue(tools.contains("discover_devices"), "was: " + tools);
    }

    @Test
    void theAlwaysSafeToolsAreAdvertisedRegardless() {
        List<String> tools = advertisedTools(Optional.empty(), Optional.empty());

        assertTrue(tools.containsAll(List.of("read_tags", "browse_tags", "list_drivers", "describe_driver")),
                "was: " + tools);
    }
}
