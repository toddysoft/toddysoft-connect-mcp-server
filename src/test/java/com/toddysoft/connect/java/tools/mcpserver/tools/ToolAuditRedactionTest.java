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
package com.toddysoft.connect.java.tools.mcpserver.tools;

import com.toddysoft.connect.java.tools.mcpserver.config.McpServerProperties;
import com.toddysoft.connect.java.tools.mcpserver.security.ConnectionStringRedactor;
import com.toddysoft.connect.java.tools.mcpserver.security.TestGuards;
import org.apache.plc4x.java.api.PlcDriverManager;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.auditlog.api.AuditLogEventType;
import org.apache.plc4x.java.utils.cache.PlcConnectionCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The audit log must not become the place a credential ends up.
 *
 * <p>In Apache PLC4X credentials are connection-string parameters, and every tool logs the
 * connection string it was asked to act on.</p>
 */
@ExtendWith(MockitoExtension.class)
class ToolAuditRedactionTest {

    private static final String URL_WITH_SECRET = "opcua://10.0.0.5?username=admin&password=hunter2";

    @Mock
    private PlcConnectionCache connectionCache;

    @Mock
    private AuditLog auditLog;

    private McpServerProperties properties;
    private ConnectionStringRedactor redactor;

    @BeforeEach
    void setUp() {
        properties = new McpServerProperties();
        redactor = new ConnectionStringRedactor(PlcDriverManager.getDefault());
        when(auditLog.isEnabled()).thenReturn(true);
    }

    /** Everything the tool wrote to the audit log, whichever overload it used. */
    private String everythingAudited() {
        ArgumentCaptor<String> withPayload = ArgumentCaptor.forClass(String.class);
        verify(auditLog, atLeast(0)).write(any(AuditLogEventType.class), withPayload.capture(), any());

        ArgumentCaptor<String> plain = ArgumentCaptor.forClass(String.class);
        verify(auditLog, atLeast(0)).write(any(AuditLogEventType.class), plain.capture());

        String all = String.join(" | ", withPayload.getAllValues())
                + " | " + String.join(" | ", plain.getAllValues());
        assertTrue(all.contains("invoked") || all.contains("failed"),
                "nothing was audited, so this test proves nothing: " + all);
        return all;
    }

    @Test
    void readDoesNotAuditTheCredential() throws Exception {
        when(connectionCache.getConnection(anyString())).thenThrow(new IllegalStateException("no device"));
        ReadTool tool = new ReadTool(connectionCache, properties, auditLog, TestGuards.permissive(), redactor);

        tool.readTags(URL_WITH_SECRET, List.of("ns=2;i=1"));

        String audited = everythingAudited();
        assertFalse(audited.contains("hunter2"), audited);
        assertTrue(audited.contains("10.0.0.5"), "the device must still be identifiable: " + audited);
    }

    @Test
    void writeDoesNotAuditTheCredential() throws Exception {
        when(connectionCache.getConnection(anyString())).thenThrow(new IllegalStateException("no device"));
        WriteTool tool = new WriteTool(connectionCache, properties, auditLog, TestGuards.permissive(), redactor);

        tool.writeTags(URL_WITH_SECRET, Map.of("ns=2;i=1", 42));

        assertFalse(everythingAudited().contains("hunter2"));
    }

    @Test
    void browseDoesNotAuditTheCredential() throws Exception {
        when(connectionCache.getConnection(anyString())).thenThrow(new IllegalStateException("no device"));
        BrowseTool tool = new BrowseTool(connectionCache, properties, auditLog, TestGuards.permissive(), redactor);

        tool.browseTags(URL_WITH_SECRET, "*");

        assertFalse(everythingAudited().contains("hunter2"));
    }

    @Test
    void theConnectionItselfStillUsesTheRealString() throws Exception {
        // Redaction is for the log only: masking the string the driver is handed would break it.
        when(connectionCache.getConnection(anyString())).thenThrow(new IllegalStateException("no device"));
        ReadTool tool = new ReadTool(connectionCache, properties, auditLog, TestGuards.permissive(), redactor);

        tool.readTags(URL_WITH_SECRET, List.of("ns=2;i=1"));

        verify(connectionCache).getConnection(URL_WITH_SECRET);
    }
}
