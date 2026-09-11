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

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The single place a tool asks whether an operation is permitted.
 *
 * <p>Each check either returns normally or throws {@link GuardRailException}. Checks are ordered so
 * that a categorical refusal — writes switched off, a protocol not allowlisted — is decided
 * <em>before</em> any rate budget is spent: an operation that was never going to happen should not
 * consume the allowance of one that could.</p>
 *
 * <p>Every refusal is written to the audit log. A refusal is precisely the event an operator wants
 * to find afterwards when asking what the model attempted.</p>
 */
public class OperationGuard {

    private final GuardRailProperties properties;
    private final RateLimiter rateLimiter;
    private final AuditLog auditLog;
    private final TagAllowlist allowlist;

    public OperationGuard(GuardRailProperties properties, RateLimiter rateLimiter, AuditLog auditLog) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.auditLog = auditLog;
        this.allowlist = new TagAllowlist(properties.getWrites().getAllow());
    }

    /**
     * Permission to scan for devices with one protocol.
     *
     * <p>Refusing here is what keeps the packets off the wire — filtering results afterwards would
     * not, since by then the sweep has already been broadcast.</p>
     */
    public void checkDiscovery(String protocolCode) {
        GuardRailProperties.Discovery discovery = properties.getDiscovery();
        if (!discovery.isEnabled()) {
            throw refuse(GuardRailException.Reason.DISCOVERY_DISABLED,
                    "Discovery is disabled on this server. Enable "
                            + "toddysoft.mcp.security.discovery.enabled and allowlist the protocols "
                            + "permitted to scan.", 0);
        }
        if (!isAllowlisted(protocolCode)) {
            throw refuse(GuardRailException.Reason.PROTOCOL_NOT_ALLOWED,
                    "Discovery with protocol '" + protocolCode + "' is not permitted. Allowed: "
                            + String.join(", ", discovery.getProtocols()) + ".", 0);
        }
        enforce(rateLimiter.tryAcquireGlobal(), "discovery");
    }

    /** Permission to read from, or browse, one device. */
    public void checkRead(String connectionUrl) {
        enforce(rateLimiter.tryAcquireForDevice(connectionUrl), DeviceKey.of(connectionUrl));
    }

    /**
     * Permission to write the given addresses to one device.
     *
     * <p>All of them, or none: a multi-tag write is one intent — a setpoint and the flag that acts
     * on it — so a single disallowed address refuses the whole call rather than applying the part
     * that happened to be permitted.</p>
     */
    public void checkWrite(String connectionUrl, Collection<String> tagAddresses) {
        if (!properties.getWrites().isEnabled()) {
            throw refuse(GuardRailException.Reason.WRITES_DISABLED,
                    "Writes are disabled on this server. Enable "
                            + "toddysoft.mcp.security.writes.enabled to permit them.", 0);
        }
        List<String> refused = tagAddresses.stream()
                .filter(address -> !allowlist.permits(connectionUrl, address))
                .toList();
        if (!refused.isEmpty()) {
            // Every offending address, so one round trip is enough to correct the call.
            throw refuse(GuardRailException.Reason.TAG_NOT_ALLOWED,
                    "Write refused: " + String.join(", ", refused) + " not writable on "
                            + DeviceKey.of(connectionUrl)
                            + ". Nothing was written. See toddysoft.mcp.security.writes.allow.", 0);
        }
        enforce(rateLimiter.tryAcquireForDevice(connectionUrl), DeviceKey.of(connectionUrl));
    }

    /**
     * The protocols an unrestricted discovery call may sweep — the allowlist itself, and empty when
     * discovery is off. A fan-out therefore scans what is permitted, never everything on the
     * classpath.
     */
    public List<String> allowedDiscoveryProtocols() {
        if (!properties.getDiscovery().isEnabled()) {
            return List.of();
        }
        return List.copyOf(properties.getDiscovery().getProtocols());
    }

    private boolean isAllowlisted(String protocolCode) {
        if (protocolCode == null) {
            return false;
        }
        String requested = protocolCode.toLowerCase(Locale.ROOT);
        return properties.getDiscovery().getProtocols().stream()
                .anyMatch(allowed -> allowed.toLowerCase(Locale.ROOT).equals(requested));
    }

    /**
     * Turns a refused decision into an exception.
     *
     * @param decision the limiter's answer
     * @param target   the <strong>device</strong>, never the raw connection string: in Apache PLC4X
     *                 credentials are connection-string parameters, and this message reaches both
     *                 the model and the audit log
     */
    private void enforce(RateLimiter.Decision decision, String target) {
        if (decision.allowed()) {
            return;
        }
        String limit = decision.scope() == RateLimiter.Scope.DEVICE
                ? "Rate limit exceeded for " + target + "."
                : "Server-wide rate limit exceeded.";
        throw refuse(GuardRailException.Reason.RATE_LIMITED,
                limit + " Retry in " + decision.retryAfterMillis() + "ms.",
                decision.retryAfterMillis());
    }

    private GuardRailException refuse(GuardRailException.Reason reason, String message, long retryAfterMillis) {
        if (auditLog.isEnabled()) {
            auditLog.write(AuditLogEventType.ERROR, "guard-rail refused: " + reason + " — " + message);
        }
        return new GuardRailException(reason, message, retryAfterMillis);
    }
}
