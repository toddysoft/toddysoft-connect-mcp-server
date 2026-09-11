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

import org.apache.plc4x.java.api.PlcDriverManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConnectionStringRedactor}, run against the real drivers on the classpath.
 *
 * <p>Which parameters are secret is not this class's opinion — PLC4X declares it through
 * {@code Option.isSecret()}, so the tests assert against what the drivers actually say.</p>
 */
class ConnectionStringRedactorTest {

    private final ConnectionStringRedactor redactor =
            new ConnectionStringRedactor(PlcDriverManager.getDefault());

    @Test
    void masksASecretParameterButKeepsItsName() {
        // Which credential was supplied is what diagnosing a failed connect needs; its value is not.
        String redacted = redactor.redact("opcua://10.0.0.5?username=admin&password=hunter2");

        assertFalse(redacted.contains("hunter2"), redacted);
        assertTrue(redacted.contains("password="), "the parameter must still be visible: " + redacted);
    }

    @Test
    void keepsANonSecretParameterIntact() {
        // OPC UA declares username as NOT secret: masking it would cost the ability to diagnose
        // which account a failing connect used, for no gain.
        String redacted = redactor.redact("opcua://10.0.0.5?username=admin&password=hunter2");

        assertTrue(redacted.contains("username=admin"), redacted);
    }

    @Test
    void masksTheKnxProjectPassword() {
        String redacted = redactor.redact(
                "knxnet-ip://10.0.0.5?knxproj-password=secret&group-address-num-levels=3");

        assertFalse(redacted.contains("secret"), redacted);
        assertTrue(redacted.contains("group-address-num-levels=3"),
                "a non-secret parameter must survive: " + redacted);
    }

    @Test
    void leavesADriverWithNoSecretsAlone() {
        String url = "s7://10.0.0.5?remote-rack=0&remote-slot=1";

        assertEquals(url, redactor.redact(url));
    }

    @Test
    void masksEverythingWhenTheProtocolIsUnknown() {
        // A driver that cannot be interrogated is exactly the case where we do not know what its
        // secrets are called, so nothing is assumed to be safe.
        String redacted = redactor.redact("nosuchdriver://10.0.0.5?token=abc123&mode=fast");

        assertFalse(redacted.contains("abc123"), redacted);
        assertFalse(redacted.contains("fast"), redacted);
        assertTrue(redacted.contains("10.0.0.5"), "the device must still be identifiable: " + redacted);
    }

    @Test
    void masksUrlUserinfo() {
        String redacted = redactor.redact("s7://operator:hunter2@10.0.0.5");

        assertFalse(redacted.contains("hunter2"), redacted);
        assertTrue(redacted.contains("operator"), "the identity is diagnostic, the password is not: " + redacted);
    }

    @Test
    void handlesATransportPrefixedString() {
        String redacted = redactor.redact("opcua:tcp://10.0.0.5?password=hunter2");

        assertFalse(redacted.contains("hunter2"), redacted);
        assertTrue(redacted.startsWith("opcua:tcp://"), redacted);
    }

    @Test
    void leavesAStringWithoutParametersUnchanged() {
        String url = "s7://10.0.0.5";

        assertEquals(url, redactor.redact(url));
    }

    @Test
    void aValuelessParameterIsPreserved() {
        String redacted = redactor.redact("s7://10.0.0.5?verbose");

        assertTrue(redacted.contains("verbose"), redacted);
    }

    @Test
    void malformedInputIsNeverAllowedToThrow() {
        // A redactor that fails on odd input would take out the audit line it exists to protect.
        assertDoesNotThrow(() -> redactor.redact("not a url at all"));
        assertDoesNotThrow(() -> redactor.redact("?"));
        assertDoesNotThrow(() -> redactor.redact(""));
        assertNull(redactor.redact(null));
    }

    @Test
    void repeatedCallsAgreeWithEachOther() {
        // The metadata lookup is cached; the cache must not change the answer.
        String url = "opcua://10.0.0.5?password=hunter2";

        assertEquals(redactor.redact(url), redactor.redact(url));
    }
}
