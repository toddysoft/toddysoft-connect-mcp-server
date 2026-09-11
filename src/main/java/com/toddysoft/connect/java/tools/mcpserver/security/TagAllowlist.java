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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which tag addresses may be written, per device.
 *
 * <p>Patterns are matched against the raw address string the caller supplied. This layer
 * deliberately understands none of the driver tag grammars — {@code %DB10.DBW0:INT},
 * {@code 40001[0..3]:INT} and {@code MAIN.g_var} are all just strings here — because a policy that
 * had to parse every grammar would be wrong for whichever driver changed its grammar next.</p>
 *
 * <p>{@code *} is the only wildcard and matches any run of characters; everything else, including
 * {@code .} and {@code [}, is literal. A pattern of exactly {@code **} permits every address, and is
 * the only way to say so — see {@link GuardRailProperties#validate()}, which refuses a bare
 * {@code *} pattern so that "everything" cannot be reached by a one-character typo, and refuses an
 * empty allowlist so that deleting the last rule cannot quietly open the device up.</p>
 */
class TagAllowlist {

    /** The reserved pattern meaning "every address". */
    static final String ALL = "**";

    /** The reserved device key meaning "every device". */
    static final String ANY_DEVICE = "*";

    private final Map<String, List<Pattern>> rulesByDevice;

    TagAllowlist(Map<String, List<String>> rules) {
        this.rulesByDevice = rules.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().toLowerCase(Locale.ROOT),
                        entry -> entry.getValue().stream().map(TagAllowlist::compile).toList()));
    }

    /** Whether this device may be written at this address. */
    boolean permits(String connectionUrl, String tagAddress) {
        if (rulesByDevice.isEmpty()) {
            return false;
        }
        String device = DeviceKey.of(connectionUrl);
        List<Pattern> applicable = new ArrayList<>();
        applicable.addAll(rulesByDevice.getOrDefault(device, List.of()));
        applicable.addAll(rulesByDevice.getOrDefault(ANY_DEVICE, List.of()));

        String address = tagAddress == null ? "" : tagAddress;
        return applicable.stream().anyMatch(pattern -> pattern.matcher(address).matches());
    }

    /**
     * Compiles one glob into a regex, quoting every literal run so that a tag grammar's own
     * punctuation cannot behave as a regex operator.
     */
    private static Pattern compile(String glob) {
        if (ALL.equals(glob)) {
            return Pattern.compile(".*", Pattern.DOTALL);
        }
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(".*");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }
}
