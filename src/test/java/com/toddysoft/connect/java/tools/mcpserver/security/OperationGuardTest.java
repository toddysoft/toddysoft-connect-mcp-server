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
import org.apache.plc4x.java.utils.auditlog.api.AuditLogEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link OperationGuard}, the single place each tool asks whether an operation is
 * permitted.
 */
@ExtendWith(MockitoExtension.class)
class OperationGuardTest {

    @Mock
    private AuditLog auditLog;

    private GuardRailProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GuardRailProperties();
    }

    private OperationGuard guard() {
        return new OperationGuard(properties, new RateLimiter(properties.getRateLimit(), () -> 0L), auditLog);
    }

    @Test
    void refusesAWriteByDefault() {
        GuardRailException refusal = assertThrows(GuardRailException.class,
                () -> guard().checkWrite("s7://10.0.0.5", List.of("%DB10.DBW0:INT")));

        assertEquals(GuardRailException.Reason.WRITES_DISABLED, refusal.getReason());
    }

    @Test
    void allowsAWriteOnceWritesAreEnabledAndTheTagIsAllowlisted() {
        properties.getWrites().setEnabled(true);
        properties.getWrites().setAllow(Map.of("*", List.of("**")));

        assertDoesNotThrow(() -> guard().checkWrite("s7://10.0.0.5", List.of("%DB10.DBW0:INT")));
    }

    @Test
    void refusesAWriteToATagOutsideTheAllowlist() {
        properties.getWrites().setEnabled(true);
        properties.getWrites().setAllow(Map.of("10.0.0.5", List.of("%DB10.*")));

        GuardRailException refusal = assertThrows(GuardRailException.class,
                () -> guard().checkWrite("s7://10.0.0.5", List.of("%DB11.DBW0:INT")));

        assertEquals(GuardRailException.Reason.TAG_NOT_ALLOWED, refusal.getReason());
    }

    @Test
    void oneDisallowedTagRefusesTheWholeCall() {
        // A multi-tag write is one intent; applying half of it leaves the device in a state nobody
        // asked for.
        properties.getWrites().setEnabled(true);
        properties.getWrites().setAllow(Map.of("10.0.0.5", List.of("%DB10.*")));

        GuardRailException refusal = assertThrows(GuardRailException.class,
                () -> guard().checkWrite("s7://10.0.0.5",
                        List.of("%DB10.DBW0:INT", "%DB11.DBW0:INT")));

        assertEquals(GuardRailException.Reason.TAG_NOT_ALLOWED, refusal.getReason());
    }

    @Test
    void aRefusalNamesEveryOffendingTagNotJustTheFirst() {
        properties.getWrites().setEnabled(true);
        properties.getWrites().setAllow(Map.of("10.0.0.5", List.of("%DB10.*")));

        GuardRailException refusal = assertThrows(GuardRailException.class,
                () -> guard().checkWrite("s7://10.0.0.5",
                        List.of("%DB11.DBW0:INT", "%DB12.DBW0:INT")));

        assertTrue(refusal.getMessage().contains("%DB11.DBW0:INT"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("%DB12.DBW0:INT"),
                "one round trip should be enough to fix the call: " + refusal.getMessage());
    }

    @Test
    void aTagRefusalDoesNotSpendTheDevicesRateBudget() {
        properties.getWrites().setEnabled(true);
        properties.getWrites().setAllow(Map.of("10.0.0.5", List.of("%DB10.*")));
        properties.getRateLimit().setPerDevice(new GuardRailProperties.Bucket(1.0, 1));
        OperationGuard guard = guard();

        assertThrows(GuardRailException.class,
                () -> guard.checkWrite("s7://10.0.0.5", List.of("%DB11.DBW0:INT")));

        assertDoesNotThrow(() -> guard.checkRead("s7://10.0.0.5"));
    }

    @Test
    void refusesDiscoveryByDefault() {
        GuardRailException refusal = assertThrows(GuardRailException.class,
                () -> guard().checkDiscovery("s7"));

        assertEquals(GuardRailException.Reason.DISCOVERY_DISABLED, refusal.getReason());
    }

    @Test
    void refusesAProtocolThatIsNotOnTheAllowlist() {
        properties.getDiscovery().setEnabled(true);
        properties.getDiscovery().setProtocols(List.of("modbus-tcp"));

        GuardRailException refusal = assertThrows(GuardRailException.class,
                () -> guard().checkDiscovery("s7"));

        assertEquals(GuardRailException.Reason.PROTOCOL_NOT_ALLOWED, refusal.getReason());
        assertTrue(refusal.getMessage().contains("modbus-tcp"),
                "the refusal should name what is permitted, was: " + refusal.getMessage());
    }

    @Test
    void allowsAnAllowlistedProtocol() {
        properties.getDiscovery().setEnabled(true);
        properties.getDiscovery().setProtocols(List.of("modbus-tcp"));

        assertDoesNotThrow(() -> guard().checkDiscovery("modbus-tcp"));
    }

    @Test
    void theAllowlistIsTheFanOutSetSoAnUnrestrictedSweepScansOnlyPermittedProtocols() {
        properties.getDiscovery().setEnabled(true);
        properties.getDiscovery().setProtocols(List.of("modbus-tcp", "s7"));

        assertEquals(List.of("modbus-tcp", "s7"), guard().allowedDiscoveryProtocols());
    }

    @Test
    void theFanOutSetIsEmptyWhenDiscoveryIsDisabled() {
        assertEquals(List.of(), guard().allowedDiscoveryProtocols());
    }

    @Test
    void readsAreAllowedByDefaultButStillPaced() {
        properties.getRateLimit().setPerDevice(new GuardRailProperties.Bucket(1.0, 1));
        OperationGuard guard = guard();

        assertDoesNotThrow(() -> guard.checkRead("s7://10.0.0.5"));

        GuardRailException refusal = assertThrows(GuardRailException.class,
                () -> guard.checkRead("s7://10.0.0.5"));
        assertEquals(GuardRailException.Reason.RATE_LIMITED, refusal.getReason());
        assertTrue(refusal.getRetryAfterMillis() > 0, "a rate-limited refusal must say how long to wait");
    }

    @Test
    void aWriteIsPacedLikeAnyOtherOperation() {
        properties.getWrites().setEnabled(true);
        properties.getWrites().setAllow(Map.of("*", List.of("**")));
        properties.getRateLimit().setPerDevice(new GuardRailProperties.Bucket(1.0, 1));
        OperationGuard guard = guard();
        assertDoesNotThrow(() -> guard.checkWrite("s7://10.0.0.5", List.of("%DB10.DBW0:INT")));

        GuardRailException refusal = assertThrows(GuardRailException.class,
                () -> guard.checkWrite("s7://10.0.0.5", List.of("%DB10.DBW0:INT")));

        assertEquals(GuardRailException.Reason.RATE_LIMITED, refusal.getReason());
    }

    @Test
    void aDisabledWriteIsRefusedBeforeAnyRateBudgetIsSpent() {
        properties.getRateLimit().setPerDevice(new GuardRailProperties.Bucket(1.0, 1));
        OperationGuard guard = guard();

        assertThrows(GuardRailException.class, () -> guard.checkWrite("s7://10.0.0.5", List.of("%DB10.DBW0:INT")));

        // The refused write must not have spent the device's single token.
        assertDoesNotThrow(() -> guard.checkRead("s7://10.0.0.5"));
    }

    @Test
    void aRefusalNamesTheDeviceNeverTheRawConnectionString() {
        // In Apache PLC4X credentials are connection-string parameters (OPC UA's username and
        // password, for instance). A refusal is reported to the model and written to the audit
        // log, so echoing the string back would leak the password into both.
        when(auditLog.isEnabled()).thenReturn(true);
        properties.getRateLimit().setPerDevice(new GuardRailProperties.Bucket(1.0, 1));
        OperationGuard guard = guard();
        String url = "opcua://10.0.0.5?username=admin&password=hunter2";
        guard.checkRead(url);

        GuardRailException refusal = assertThrows(GuardRailException.class, () -> guard.checkRead(url));

        assertFalse(refusal.getMessage().contains("hunter2"),
                "the refusal must not carry the credential: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("10.0.0.5"),
                "but it must still say which device: " + refusal.getMessage());
        verify(auditLog, never()).write(eq(AuditLogEventType.ERROR), contains("hunter2"));
    }

    @Test
    void everyRefusalIsAudited() {
        when(auditLog.isEnabled()).thenReturn(true);

        assertThrows(GuardRailException.class, () -> guard().checkWrite("s7://10.0.0.5", List.of("%DB10.DBW0:INT")));

        verify(auditLog).write(eq(AuditLogEventType.ERROR), contains("WRITES_DISABLED"));
    }

    @Test
    void anAllowedOperationIsNotAuditedAsARefusal() {
        properties.getWrites().setEnabled(true);
        properties.getWrites().setAllow(Map.of("*", List.of("**")));

        assertDoesNotThrow(() -> guard().checkWrite("s7://10.0.0.5", List.of("%DB10.DBW0:INT")));

        verify(auditLog, never()).write(eq(AuditLogEventType.ERROR), anyString());
    }
}
