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

import com.toddysoft.connect.java.tools.mcpserver.config.McpServerProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The defaults are a safety property, so they are asserted rather than assumed — and misconfigured
 * limits fail at startup rather than silently behaving as something the operator did not ask for.
 */
class GuardRailPropertiesTest {

    @Test
    void anUnconfiguredServerRefusesWritesAndDiscovery() {
        GuardRailProperties defaults = new McpServerProperties().getSecurity();

        assertFalse(defaults.getWrites().isEnabled(), "writes must be off until asked for");
        assertFalse(defaults.getDiscovery().isEnabled(), "discovery must be off until asked for");
        assertTrue(defaults.getDiscovery().getProtocols().isEmpty());
    }

    @Test
    void anUnconfiguredServerIsRateLimited() {
        GuardRailProperties defaults = new McpServerProperties().getSecurity();

        assertTrue(defaults.getRateLimit().isEnabled(), "pacing must be on by default");
        assertEquals(5.0, defaults.getRateLimit().getPerDevice().getRequestsPerSecond());
        assertEquals(10, defaults.getRateLimit().getPerDevice().getBurst());
        assertEquals(20.0, defaults.getRateLimit().getGlobal().getRequestsPerSecond());
        assertEquals(40, defaults.getRateLimit().getGlobal().getBurst());
    }

    @Test
    void theDefaultsAreValid() {
        assertDoesNotThrow(() -> new GuardRailProperties().validate());
    }

    @Test
    void aNonPositiveRateIsRejected() {
        GuardRailProperties properties = new GuardRailProperties();
        properties.getRateLimit().setPerDevice(new GuardRailProperties.Bucket(0.0, 10));

        IllegalStateException failure = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(failure.getMessage().contains("per-device"), failure.getMessage());
    }

    @Test
    void aBurstSmallerThanTheRateIsRejected() {
        // A burst below the per-second rate could never be spent in a second — it is a typo.
        GuardRailProperties properties = new GuardRailProperties();
        properties.getRateLimit().setGlobal(new GuardRailProperties.Bucket(20.0, 5));

        IllegalStateException failure = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(failure.getMessage().contains("burst"), failure.getMessage());
    }

    @Test
    void aNonPositiveTrackedDeviceCapIsRejected() {
        GuardRailProperties properties = new GuardRailProperties();
        properties.getRateLimit().setMaxTrackedDevices(0);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void writesEnabledWithAnEmptyAllowlistIsRejected() {
        // The failure mode this exists for: someone deletes the last three allowed tags. That must
        // not quietly open every tag, and must not quietly disable writes either — it must be said.
        GuardRailProperties properties = new GuardRailProperties();
        properties.getWrites().setEnabled(true);

        IllegalStateException failure = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(failure.getMessage().contains("allow"), failure.getMessage());
        assertTrue(failure.getMessage().contains("**"),
                "the message must show how to permit everything deliberately: " + failure.getMessage());
    }

    @Test
    void writesEnabledWithAnAllowlistIsValid() {
        GuardRailProperties properties = new GuardRailProperties();
        properties.getWrites().setEnabled(true);
        properties.getWrites().setAllow(Map.of("10.0.0.5", List.of("%DB10.*")));

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void aBareStarPatternIsRejectedInFavourOfDoubleStar() {
        // One spelling for "everything", and it is not one keystroke away from a narrow rule.
        GuardRailProperties properties = new GuardRailProperties();
        properties.getWrites().setEnabled(true);
        properties.getWrites().setAllow(Map.of("*", List.of("*")));

        IllegalStateException failure = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(failure.getMessage().contains("**"), failure.getMessage());
    }

    @Test
    void aWildcardWithinAPatternIsStillFine() {
        GuardRailProperties properties = new GuardRailProperties();
        properties.getWrites().setEnabled(true);
        properties.getWrites().setAllow(Map.of("*", List.of("%DB10.*")));

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void anAllowlistIsNotValidatedWhenWritesAreDisabled() {
        GuardRailProperties properties = new GuardRailProperties();
        properties.getWrites().setAllow(Map.of());

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void discoveryEnabledWithNothingAllowlistedIsRejected() {
        // Far more likely a half-finished config than an intent to enable discovery of nothing.
        GuardRailProperties properties = new GuardRailProperties();
        properties.getDiscovery().setEnabled(true);

        IllegalStateException failure = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(failure.getMessage().contains("protocols"), failure.getMessage());
    }

    @Test
    void discoveryEnabledWithAnAllowlistIsValid() {
        GuardRailProperties properties = new GuardRailProperties();
        properties.getDiscovery().setEnabled(true);
        properties.getDiscovery().setProtocols(List.of("modbus-tcp"));

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void limitsAreNotValidatedWhenRateLimitingIsOff() {
        // Nothing reads them, so rejecting a stale value would be pedantry.
        GuardRailProperties properties = new GuardRailProperties();
        properties.getRateLimit().setEnabled(false);
        properties.getRateLimit().setPerDevice(new GuardRailProperties.Bucket(0.0, 0));

        assertDoesNotThrow(properties::validate);
    }
}
