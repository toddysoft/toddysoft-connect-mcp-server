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
package com.toddysoft.connect.java.tools.mcpserver.security;

import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.mockito.Mockito;

import java.util.List;

/**
 * Guards for tests that are about something other than the guard-rails themselves.
 *
 * <p>Kept in the test tree deliberately: production code must not carry a "permit everything"
 * constructor that could be reached by accident.</p>
 */
public final class TestGuards {

    private TestGuards() {
    }

    /** A redactor over the real drivers on the classpath, for tests that only need audit output. */
    public static ConnectionStringRedactor redactor() {
        return new ConnectionStringRedactor(org.apache.plc4x.java.api.PlcDriverManager.getDefault());
    }

    /**
     * A guard that permits everything and paces nothing, for tests exercising a tool's own logic.
     *
     * @param discoverableProtocols protocol codes discovery is allowed to use
     */
    public static OperationGuard permissive(String... discoverableProtocols) {
        GuardRailProperties properties = new GuardRailProperties();
        properties.getWrites().setEnabled(true);
        properties.getWrites().setAllow(java.util.Map.of("*", List.of("**")));
        properties.getDiscovery().setEnabled(true);
        properties.getDiscovery().setProtocols(List.of(discoverableProtocols));
        properties.getRateLimit().setEnabled(false);
        AuditLog auditLog = Mockito.mock(AuditLog.class);
        return new OperationGuard(properties, new RateLimiter(properties.getRateLimit(), () -> 0L), auditLog);
    }
}
