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

import java.util.Locale;

/**
 * Derives the device a connection string addresses: {@code host[:port]}, lower-cased.
 *
 * <p>Shared by the rate limiter and the write allowlist deliberately — if the two disagreed about
 * what counts as one device, a tag rule could apply to a device whose request budget was being
 * accounted somewhere else.</p>
 *
 * <p>Everything after the authority is dropped, so the same controller reached with different
 * driver options is one device. A port is kept, because two services on one address present two
 * independent request budgets and two independent write surfaces.</p>
 */
final class DeviceKey {

    private DeviceKey() {
    }

    static String of(String connectionUrl) {
        if (connectionUrl == null) {
            return "";
        }
        String remainder = connectionUrl;
        int schemeEnd = remainder.indexOf("://");
        if (schemeEnd >= 0) {
            remainder = remainder.substring(schemeEnd + 3);
        }
        int end = remainder.length();
        for (int i = 0; i < remainder.length(); i++) {
            char c = remainder.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        return remainder.substring(0, end).toLowerCase(Locale.ROOT);
    }
}
