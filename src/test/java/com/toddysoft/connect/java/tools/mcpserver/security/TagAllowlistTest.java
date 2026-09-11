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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TagAllowlist} — which tag addresses a device may be written at.
 *
 * <p>Patterns are matched against the raw address string the caller supplied, because the grammar
 * differs per driver and this layer deliberately understands none of them.</p>
 */
class TagAllowlistTest {

    private static TagAllowlist of(Map<String, List<String>> rules) {
        return new TagAllowlist(rules);
    }

    @Test
    void permitsAnAddressMatchingAPatternForItsDevice() {
        TagAllowlist allowlist = of(Map.of("10.0.0.5", List.of("%DB10.*")));

        assertTrue(allowlist.permits("s7://10.0.0.5", "%DB10.DBW0:INT"));
    }

    @Test
    void refusesAnAddressOutsideThePattern() {
        TagAllowlist allowlist = of(Map.of("10.0.0.5", List.of("%DB10.*")));

        assertFalse(allowlist.permits("s7://10.0.0.5", "%DB11.DBW0:INT"));
    }

    @Test
    void rulesAreScopedToTheirDevice() {
        TagAllowlist allowlist = of(Map.of("10.0.0.5", List.of("%DB10.*")));

        assertFalse(allowlist.permits("s7://10.0.0.9", "%DB10.DBW0:INT"),
                "one PLC's safe DB is another's interlock");
    }

    @Test
    void aDeviceAbsentFromANonEmptyAllowlistIsRefusedEntirely() {
        TagAllowlist allowlist = of(Map.of("10.0.0.5", List.of("**")));

        assertFalse(allowlist.permits("s7://10.0.0.9", "%M0.0:BOOL"));
    }

    @Test
    void wildcardDeviceRulesApplyEverywhere() {
        TagAllowlist allowlist = of(Map.of("*", List.of("%DB99.*")));

        assertTrue(allowlist.permits("s7://10.0.0.5", "%DB99.DBW0:INT"));
        assertTrue(allowlist.permits("s7://10.0.0.9", "%DB99.DBW0:INT"));
    }

    @Test
    void deviceRulesAndWildcardRulesAreUnioned() {
        TagAllowlist allowlist = of(Map.of(
                "10.0.0.5", List.of("%DB10.*"),
                "*", List.of("%DB99.*")));

        assertTrue(allowlist.permits("s7://10.0.0.5", "%DB10.DBW0:INT"), "its own rule");
        assertTrue(allowlist.permits("s7://10.0.0.5", "%DB99.DBW0:INT"), "and the shared rule");
    }

    @Test
    void doubleStarPermitsEveryAddress() {
        TagAllowlist allowlist = of(Map.of("*", List.of("**")));

        assertTrue(allowlist.permits("s7://10.0.0.5", "%DB1.DBX0.0:BOOL"));
        assertTrue(allowlist.permits("modbus-tcp://10.0.0.9:502", "40001:INT"));
    }

    @Test
    void anEmptyAllowlistPermitsNothing() {
        // Deleting the last rule must never be the same as permitting everything. Startup
        // validation rejects this combination outright; this is the belt to that braces.
        TagAllowlist allowlist = of(Map.of());

        assertFalse(allowlist.permits("s7://10.0.0.5", "%DB10.DBW0:INT"));
    }

    @Test
    void regexMetacharactersInAnAddressAreMatchedLiterally() {
        // A Modbus array address is full of characters a regex would otherwise interpret.
        TagAllowlist allowlist = of(Map.of("10.0.0.9", List.of("40001[0..3]:INT")));

        assertTrue(allowlist.permits("modbus-tcp://10.0.0.9", "40001[0..3]:INT"));
        assertFalse(allowlist.permits("modbus-tcp://10.0.0.9", "4000110003XINT"),
                "the brackets and dots must not behave as a character class or wildcards");
    }

    @Test
    void theDotInAPatternIsLiteral() {
        TagAllowlist allowlist = of(Map.of("10.0.0.5", List.of("%DB10.*")));

        assertFalse(allowlist.permits("s7://10.0.0.5", "%DB10XDBW0:INT"),
                "'.' is a literal dot, not 'any character'");
    }

    @Test
    void matchingIsCaseInsensitive() {
        TagAllowlist allowlist = of(Map.of("10.0.0.5", List.of("%db10.*")));

        assertTrue(allowlist.permits("S7://10.0.0.5", "%DB10.DBW0:INT"));
    }

    @Test
    void aWildcardMatchesInTheMiddleOfAnAddress() {
        TagAllowlist allowlist = of(Map.of("10.0.0.5", List.of("%DB*.DBW0:INT")));

        assertTrue(allowlist.permits("s7://10.0.0.5", "%DB10.DBW0:INT"));
        assertTrue(allowlist.permits("s7://10.0.0.5", "%DB7.DBW0:INT"));
        assertFalse(allowlist.permits("s7://10.0.0.5", "%DB10.DBW2:INT"));
    }

    @Test
    void portsDistinguishDevicesTheSameWayTheRateLimiterSeesThem() {
        TagAllowlist allowlist = of(Map.of("10.0.0.9:502", List.of("**")));

        assertTrue(allowlist.permits("modbus-tcp://10.0.0.9:502", "40001:INT"));
        assertFalse(allowlist.permits("modbus-tcp://10.0.0.9:503", "40001:INT"));
    }

    @Test
    void connectionParametersDoNotAffectWhichRulesApply() {
        TagAllowlist allowlist = of(Map.of("10.0.0.5", List.of("%DB10.*")));

        assertTrue(allowlist.permits("s7://10.0.0.5?remote-rack=0", "%DB10.DBW0:INT"));
    }
}
