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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link GuardRailException} into the result shape the tools already use.
 *
 * <p>A refusal is returned to the model rather than thrown at the transport, so the model sees a
 * result it can reason about: what was refused, why, and — when waiting would help — for how long.
 * An exception escaping to the client would read as a broken server rather than a policy.</p>
 */
public final class GuardRailRefusal {

    private GuardRailRefusal() {
    }

    /** The refusal as a single-entry result list, for the tools that return a list. */
    public static List<Map<String, Object>> asResultList(GuardRailException refusal) {
        return List.of(asResult(refusal));
    }

    /** The refusal as one entry. */
    public static Map<String, Object> asResult(GuardRailException refusal) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("error", refusal.getMessage());
        entry.put("reason", refusal.getReason().name());
        if (refusal.getRetryAfterMillis() > 0) {
            entry.put("retryAfterMillis", refusal.getRetryAfterMillis());
        }
        return entry;
    }
}
