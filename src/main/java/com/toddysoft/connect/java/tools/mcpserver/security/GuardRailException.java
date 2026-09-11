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

/**
 * Thrown when a guard-rail refuses an operation.
 *
 * <p>Carries a machine-readable {@link Reason} alongside the operator-facing message, so a tool can
 * report the refusal to the model in a form it can act on — in particular
 * {@link #getRetryAfterMillis()}, which turns "no" into "not yet".</p>
 */
public class GuardRailException extends RuntimeException {

    /** Why an operation was refused. */
    public enum Reason {
        /** Writing is switched off on this server. */
        WRITES_DISABLED,
        /** Discovery is switched off on this server. */
        DISCOVERY_DISABLED,
        /** Discovery is permitted, but not for the requested protocol. */
        PROTOCOL_NOT_ALLOWED,
        /** Writing is permitted, but not at one or more of the requested addresses. */
        TAG_NOT_ALLOWED,
        /** Permitted in principle, but too many requests too quickly. */
        RATE_LIMITED
    }

    private final Reason reason;
    private final long retryAfterMillis;

    public GuardRailException(Reason reason, String message, long retryAfterMillis) {
        super(message);
        this.reason = reason;
        this.retryAfterMillis = retryAfterMillis;
    }

    public Reason getReason() {
        return reason;
    }

    /** How long until a retry could succeed; zero when retrying cannot help. */
    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }
}
