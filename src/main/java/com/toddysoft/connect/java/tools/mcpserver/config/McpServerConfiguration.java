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

import org.apache.plc4x.java.utils.cache.PlcConnectionCache;
import com.toddysoft.connect.java.tools.mcpserver.security.ConnectionStringRedactor;
import com.toddysoft.connect.java.tools.mcpserver.security.OperationGuard;
import com.toddysoft.connect.java.tools.mcpserver.security.RateLimiter;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Spring configuration that creates the core infrastructure beans for the MCP server.
 *
 * <p>Provides a {@link PlcConnectionCache} for pooled PLC connections and an
 * {@link AuditLog} for logging all MCP tool invocations. Both beans are configured
 * from {@link McpServerProperties} and cleaned up on application shutdown.</p>
 */
@Configuration
@EnableConfigurationProperties(McpServerProperties.class)
public class McpServerConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(McpServerConfiguration.class);

    private PlcConnectionCache connectionCache;
    private AuditLog auditLog;

    /**
     * Creates a connection cache that pools and reuses PLC connections.
     *
     * <p>Uses the default {@link PlcDriverManager} for driver discovery and configures
     * idle timeout and lease timeout from the application properties.</p>
     *
     * @param properties the MCP server configuration properties
     * @return a configured connection cache
     */
    @Bean
    public PlcConnectionCache plcConnectionCache(McpServerProperties properties) {
        connectionCache = PlcConnectionCache.getBuilder()
            .withConnectionFactory(PlcDriverManager.getDefault().getConnectionFactory())
            .withMaxIdleTime(properties.getCache().getMaxIdleMinutes(), TimeUnit.MINUTES)
            .withMaxLeaseTime(properties.getCache().getMaxLeaseSeconds(), TimeUnit.SECONDS)
            .build();
        return connectionCache;
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
     * Masks credentials before a connection string reaches the audit log.
     *
     * <p>In Apache PLC4X credentials are ordinary connection parameters, and each driver declares
     * which of its parameters are secret — so the redactor asks the drivers rather than guessing.</p>
     *
     * @return the connection-string redactor
     */
    @Bean
    public ConnectionStringRedactor connectionStringRedactor() {
        return new ConnectionStringRedactor(PlcDriverManager.getDefault());
    }

    /**
     * The two-bucket rate limiter, paced against the system's monotonic clock.
     *
     * @param properties the MCP server configuration properties
     * @return a rate limiter over the configured limits
     */
    @Bean
    public RateLimiter rateLimiter(McpServerProperties properties) {
        // Startup is the last moment a bad limit can be reported rather than silently enforced.
        properties.getSecurity().validate();
        return new RateLimiter(properties.getSecurity().getRateLimit(), System::nanoTime);
    }

    /**
     * The guard every device-touching tool consults before acting.
     *
     * @param properties  the MCP server configuration properties
     * @param rateLimiter the configured rate limiter
     * @param auditLog    the audit log, which records every refusal
     * @return the operation guard
     */
    @Bean
    public OperationGuard operationGuard(McpServerProperties properties, RateLimiter rateLimiter, AuditLog auditLog) {
        return new OperationGuard(properties.getSecurity(), rateLimiter, auditLog);
    }

    /**
     * Registers all MCP tool beans so the Spring AI MCP server exposes them to clients.
     *
     * <p>Without this provider, the server starts but advertises zero tools.</p>
     *
     * @param browseTool    the browse tool
     * @param readTool      the read tool
     * @param writeTool     the write tool, absent when writes are disabled
     * @param discoveryTool the discovery tool, absent when discovery is disabled
     * @param driverListTool the driver list tool
     * @return a provider that exposes all tool methods to the MCP server
     */
    @Bean
    public ToolCallbackProvider toolCallbackProvider(
            BrowseTool browseTool,
            ReadTool readTool,
            Optional<WriteTool> writeTool,
            Optional<DiscoveryTool> discoveryTool,
            DriverListTool driverListTool) {

        // A capability that is switched off is not advertised at all. Offering a tool that always
        // refuses invites a wasted round trip and reads to the model as a broken server rather
        // than a policy.
        List<Object> toolObjects = new ArrayList<>(List.of(browseTool, readTool, driverListTool));
        writeTool.ifPresent(toolObjects::add);
        discoveryTool.ifPresent(toolObjects::add);

        if (writeTool.isEmpty()) {
            logger.info("write_tags is not advertised: writes are disabled "
                    + "(set toddysoft.mcp.security.writes.enabled=true to permit them)");
        }
        if (discoveryTool.isEmpty()) {
            logger.info("discover_devices is not advertised: discovery is disabled "
                    + "(set toddysoft.mcp.security.discovery.enabled=true and allowlist protocols)");
        }

        return MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects.toArray())
                .build();
    }

    /**
     * Shuts down the connection cache and audit log on application stop.
     */
    @PreDestroy
    public void cleanup() {
        if (connectionCache != null) {
            try {
                connectionCache.close();
            } catch (Exception e) {
                logger.warn("Error closing connection cache", e);
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
