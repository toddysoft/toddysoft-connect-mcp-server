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

import org.apache.plc4x.java.utils.cache.CachedPlcConnectionManager;
import com.toddysoft.connect.java.tools.mcpserver.tools.*;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import jakarta.annotation.PreDestroy;
import org.apache.plc4x.java.api.PlcDriverManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Spring configuration that creates the core infrastructure beans for the MCP server.
 *
 * <p>Provides a {@link CachedPlcConnectionManager} for pooled PLC connections and an
 * {@link AuditLog} for logging all MCP tool invocations. Both beans are configured
 * from {@link McpServerProperties} and cleaned up on application shutdown.</p>
 */
@Configuration
@EnableConfigurationProperties(McpServerProperties.class)
public class McpServerConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(McpServerConfiguration.class);

    private CachedPlcConnectionManager connectionManager;
    private AuditLog auditLog;

    /**
     * Creates a connection cache that pools and reuses PLC connections.
     *
     * <p>Uses the default {@link PlcDriverManager} for driver discovery and configures
     * idle timeout and lease timeout from the application properties.</p>
     *
     * @param properties the MCP server configuration properties
     * @return a configured connection manager
     */
    @Bean
    public CachedPlcConnectionManager cachedPlcConnectionManager(McpServerProperties properties) {
        connectionManager = CachedPlcConnectionManager.getBuilder()
            .withConnectionManager(PlcDriverManager.getDefault().getConnectionManager())
            .withMaxIdleTime(properties.getCache().getMaxIdleMinutes(), TimeUnit.MINUTES)
            .withMaxLeaseTime(properties.getCache().getMaxLeaseSeconds(), TimeUnit.SECONDS)
            .build();
        return connectionManager;
    }

    /**
     * Creates an audit log instance for recording MCP tool invocations.
     *
     * @return an audit log with source identifier "mcp-server"
     */
    @Bean
    public AuditLog auditLog() {
        auditLog = AuditLog.builder()
            .withSource("mcp-server")
            .build();
        return auditLog;
    }

    /**
     * Registers all MCP tool beans so the Spring AI MCP server exposes them to clients.
     *
     * <p>Without this provider, the server starts but advertises zero tools.</p>
     *
     * @param browseTool    the browse tool
     * @param readTool      the read tool
     * @param writeTool     the write tool
     * @param discoveryTool the discovery tool
     * @param driverListTool the driver list tool
     * @return a provider that exposes all tool methods to the MCP server
     */
    @Bean
    public ToolCallbackProvider toolCallbackProvider(
            BrowseTool browseTool,
            ReadTool readTool,
            WriteTool writeTool,
            DiscoveryTool discoveryTool,
            DriverListTool driverListTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(browseTool, readTool, writeTool, discoveryTool, driverListTool)
                .build();
    }

    /**
     * Shuts down the connection cache and audit log on application stop.
     */
    @PreDestroy
    public void cleanup() {
        if (connectionManager != null) {
            try {
                connectionManager.close();
            } catch (Exception e) {
                logger.warn("Error closing connection manager", e);
            }
        }
        if (auditLog != null) {
            try {
                auditLog.close();
            } catch (Exception e) {
                logger.warn("Error closing audit log", e);
            }
        }
    }

}
