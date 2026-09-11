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

import org.apache.plc4x.java.api.PlcDriver;
import org.apache.plc4x.java.api.PlcDriverManager;
import org.apache.plc4x.java.api.metadata.Option;
import org.apache.plc4x.java.api.metadata.OptionMetadata;
import org.apache.plc4x.java.api.metadata.PlcDriverMetadata;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Masks credentials in a connection string before it is logged.
 *
 * <p>In Apache PLC4X credentials are ordinary connection parameters — OPC UA's {@code password},
 * {@code knxproj-password}, the keystore passwords — so the string that says where a device is also
 * says how to authenticate to it. Anything that records that string records the credential with it.
 *
 * <p>Which parameters are secret is <strong>not this class's opinion</strong>: every driver declares
 * it through {@link Option#isSecret()}, and that declaration is what is applied here. A key is left
 * visible while its value is masked, because knowing which credential was supplied is exactly what
 * diagnosing a failed connect needs; the value never is.</p>
 *
 * <p>When the protocol code does not resolve to a driver, <strong>every</strong> parameter value is
 * masked. A driver that cannot be interrogated is precisely the case where we do not know what its
 * secrets are called, and a name heuristic that misses leaks the thing it exists to protect.</p>
 */
public class ConnectionStringRedactor {

    private static final String MASK = "***";

    private final PlcDriverManager driverManager;

    /** Secret parameter names per protocol code; the metadata behind it is static. */
    private final Map<String, Optional<Set<String>>> secretsByProtocol = new ConcurrentHashMap<>();

    public ConnectionStringRedactor(PlcDriverManager driverManager) {
        this.driverManager = driverManager;
    }

    /**
     * The connection string with every secret value masked.
     *
     * <p>Never throws: this runs on the logging path, and a redactor that failed on odd input would
     * take out the audit line it exists to protect.</p>
     */
    public String redact(String connectionUrl) {
        if (connectionUrl == null) {
            return null;
        }
        try {
            return redactInternal(connectionUrl);
        } catch (RuntimeException e) {
            // Unparseable: say nothing rather than risk saying a password.
            return MASK;
        }
    }

    private String redactInternal(String connectionUrl) {
        int queryStart = connectionUrl.indexOf('?');
        String base = queryStart < 0 ? connectionUrl : connectionUrl.substring(0, queryStart);
        String query = queryStart < 0 ? null : connectionUrl.substring(queryStart + 1);

        String redactedBase = redactUserinfo(base);
        if (query == null || query.isEmpty()) {
            return redactedBase;
        }

        // Absent (rather than empty) means "driver unknown", which masks everything.
        Optional<Set<String>> secrets = secretsFor(protocolCodeOf(connectionUrl));

        StringBuilder redacted = new StringBuilder(redactedBase).append('?');
        String[] parameters = query.split("&", -1);
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                redacted.append('&');
            }
            redacted.append(redactParameter(parameters[i], secrets));
        }
        return redacted.toString();
    }

    private static String redactParameter(String parameter, Optional<Set<String>> secrets) {
        int equals = parameter.indexOf('=');
        if (equals < 0) {
            // A flag carries no value, so there is nothing to mask.
            return parameter;
        }
        String key = parameter.substring(0, equals);
        boolean secret = secrets
                .map(names -> names.contains(key.trim().toLowerCase(Locale.ROOT)))
                .orElse(true);
        return secret ? key + "=" + MASK : parameter;
    }

    /** Masks a password in {@code user:password@host}, which is never anything but a credential. */
    private static String redactUserinfo(String base) {
        int schemeEnd = base.indexOf("://");
        if (schemeEnd < 0) {
            return base;
        }
        String authority = base.substring(schemeEnd + 3);
        int at = authority.indexOf('@');
        if (at < 0) {
            return base;
        }
        String userinfo = authority.substring(0, at);
        int colon = userinfo.indexOf(':');
        if (colon < 0) {
            return base;
        }
        return base.substring(0, schemeEnd + 3) + userinfo.substring(0, colon) + ":" + MASK
                + authority.substring(at);
    }

    /** {@code s7:tcp://host} and {@code s7://host} both name the protocol {@code s7}. */
    private static String protocolCodeOf(String connectionUrl) {
        int schemeEnd = connectionUrl.indexOf("://");
        String scheme = schemeEnd < 0 ? connectionUrl : connectionUrl.substring(0, schemeEnd);
        int transportSeparator = scheme.indexOf(':');
        return transportSeparator < 0 ? scheme : scheme.substring(0, transportSeparator);
    }

    /**
     * The secret parameter names a driver declares, or empty when the driver is unknown — which the
     * caller reads as "mask everything".
     */
    private Optional<Set<String>> secretsFor(String protocolCode) {
        return secretsByProtocol.computeIfAbsent(protocolCode, code -> {
            try {
                PlcDriver driver = driverManager.getDriver(code);
                PlcDriverMetadata metadata = driver.getMetadata();
                Set<String> secrets = new HashSet<>();
                metadata.getProtocolConfigurationOptionMetadata()
                        .ifPresent(options -> collectSecrets(options, secrets));
                for (String transport : metadata.getSupportedTransportCodes()) {
                    metadata.getTransportConfigurationOptionMetadata(transport)
                            .ifPresent(options -> collectSecrets(options, secrets));
                }
                return Optional.of(secrets);
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    private static void collectSecrets(OptionMetadata optionMetadata, Set<String> secrets) {
        for (Option option : optionMetadata.getOptions()) {
            if (option.isSecret()) {
                secrets.add(option.getKey().toLowerCase(Locale.ROOT));
            }
        }
    }
}
