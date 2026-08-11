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

import org.apache.plc4x.java.utils.cache.CachedPlcConnectionManager;
import com.toddysoft.connect.java.tools.mcpserver.config.McpServerProperties;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.auditlog.api.AuditLogEventType;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.messages.PlcBrowseItem;
import org.apache.plc4x.java.api.messages.PlcBrowseRequest;
import org.apache.plc4x.java.api.messages.PlcBrowseResponse;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.types.PlcValueType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link BrowseTool}.
 * Verifies browse operations, hierarchical tag mapping, error handling,
 * and audit log interaction using mocked PLC connections.
 */
@ExtendWith(MockitoExtension.class)
class BrowseToolTest {

    @Mock
    private CachedPlcConnectionManager connectionManager;

    @Mock
    private McpServerProperties properties;

    @Mock
    private AuditLog auditLog;

    @Mock
    private PlcConnection connection;

    @Mock
    private PlcBrowseRequest.Builder browseBuilder;

    @Mock
    private PlcBrowseRequest browseRequest;

    @Mock
    private PlcBrowseResponse browseResponse;

    private BrowseTool tool;

    @BeforeEach
    void setUp() {
        tool = new BrowseTool(connectionManager, properties, auditLog);
    }

    /**
     * Creates a mock PlcBrowseItem with the given properties.
     */
    private PlcBrowseItem createMockBrowseItem(String name, String address,
                                                PlcValueType valueType,
                                                boolean readable, boolean writable,
                                                boolean subscribable, boolean publishable,
                                                Map<String, PlcBrowseItem> children) {
        PlcBrowseItem item = mock(PlcBrowseItem.class);
        PlcTag tag = mock(PlcTag.class);

        when(item.getName()).thenReturn(name);
        when(item.getTag()).thenReturn(tag);
        when(tag.getAddressString()).thenReturn(address);
        when(tag.getPlcValueType()).thenReturn(valueType);
        when(item.isReadable()).thenReturn(readable);
        when(item.isWritable()).thenReturn(writable);
        when(item.isSubscribable()).thenReturn(subscribable);
        when(item.isPublishable()).thenReturn(publishable);
        when(item.getChildren()).thenReturn(children);

        return item;
    }

    /**
     * Verifies that browsing returns correctly mapped tag items.
     */
    @Test
    void browseTags_returnsTagItems() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getTimeoutSeconds()).thenReturn(30);
        when(connectionManager.getConnection("s7://192.168.1.1")).thenReturn(connection);
        when(connection.browseRequestBuilder()).thenReturn(browseBuilder);
        when(browseBuilder.addQuery(anyString(), anyString())).thenReturn(browseBuilder);
        when(browseBuilder.build()).thenReturn(browseRequest);
        doReturn(CompletableFuture.completedFuture(browseResponse)).when(browseRequest).execute();
        when(browseResponse.getResponseCode("query")).thenReturn(PlcResponseCode.OK);

        PlcBrowseItem item = createMockBrowseItem("DB1", "%DB1:INT",
                PlcValueType.INT, true, true, false, false, Collections.emptyMap());
        when(browseResponse.getValues("query")).thenReturn(List.of(item));

        List<Map<String, Object>> result = tool.browseTags("s7://192.168.1.1", "*");

        assertEquals(1, result.size());
        Map<String, Object> entry = result.get(0);
        assertEquals("DB1", entry.get("name"));
        assertEquals("%DB1:INT", entry.get("address"));
        assertEquals("INT", entry.get("dataType"));
        assertEquals(true, entry.get("readable"));
        assertEquals(true, entry.get("writable"));
        assertEquals(false, entry.get("subscribable"));
        assertEquals(false, entry.get("publishable"));
    }

    /**
     * Verifies that browse items with children produce a hierarchical result.
     */
    @Test
    void browseTags_withChildren_returnsHierarchy() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getTimeoutSeconds()).thenReturn(30);
        when(connectionManager.getConnection("s7://192.168.1.1")).thenReturn(connection);
        when(connection.browseRequestBuilder()).thenReturn(browseBuilder);
        when(browseBuilder.addQuery(anyString(), anyString())).thenReturn(browseBuilder);
        when(browseBuilder.build()).thenReturn(browseRequest);
        doReturn(CompletableFuture.completedFuture(browseResponse)).when(browseRequest).execute();
        when(browseResponse.getResponseCode("query")).thenReturn(PlcResponseCode.OK);

        // Create a child item.
        PlcBrowseItem child = createMockBrowseItem("Field1", "%DB1.DBW0:REAL",
                PlcValueType.REAL, true, false, false, false, Collections.emptyMap());

        // Create parent with child.
        Map<String, PlcBrowseItem> children = new LinkedHashMap<>();
        children.put("Field1", child);
        PlcBrowseItem parent = createMockBrowseItem("DB1", "%DB1:Struct",
                PlcValueType.Struct, true, false, false, false, children);

        when(browseResponse.getValues("query")).thenReturn(List.of(parent));

        List<Map<String, Object>> result = tool.browseTags("s7://192.168.1.1", "*");

        assertEquals(1, result.size());
        Map<String, Object> parentEntry = result.get(0);
        assertTrue(parentEntry.containsKey("children"), "Parent should have children");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> childList = (List<Map<String, Object>>) parentEntry.get("children");
        assertEquals(1, childList.size());
        assertEquals("Field1", childList.get(0).get("name"));
        assertEquals("REAL", childList.get(0).get("dataType"));
    }

    /**
     * Verifies that a null query defaults to the wildcard "*".
     */
    @Test
    void browseTags_nullQuery_defaultsToWildcard() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getTimeoutSeconds()).thenReturn(30);
        when(connectionManager.getConnection("s7://192.168.1.1")).thenReturn(connection);
        when(connection.browseRequestBuilder()).thenReturn(browseBuilder);
        when(browseBuilder.addQuery(eq("query"), eq("*"))).thenReturn(browseBuilder);
        when(browseBuilder.build()).thenReturn(browseRequest);
        doReturn(CompletableFuture.completedFuture(browseResponse)).when(browseRequest).execute();
        when(browseResponse.getResponseCode("query")).thenReturn(PlcResponseCode.OK);
        when(browseResponse.getValues("query")).thenReturn(Collections.emptyList());

        List<Map<String, Object>> result = tool.browseTags("s7://192.168.1.1", null);

        assertNotNull(result);
        // Verify the wildcard query was passed to the builder.
        verify(browseBuilder).addQuery("query", "*");
    }

    /**
     * Verifies that a non-OK browse response code returns an error entry.
     */
    @Test
    void browseTags_nonOkResponseCode_returnsError() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getTimeoutSeconds()).thenReturn(30);
        when(connectionManager.getConnection("s7://192.168.1.1")).thenReturn(connection);
        when(connection.browseRequestBuilder()).thenReturn(browseBuilder);
        when(browseBuilder.addQuery(anyString(), anyString())).thenReturn(browseBuilder);
        when(browseBuilder.build()).thenReturn(browseRequest);
        doReturn(CompletableFuture.completedFuture(browseResponse)).when(browseRequest).execute();
        when(browseResponse.getResponseCode("query")).thenReturn(PlcResponseCode.INTERNAL_ERROR);

        List<Map<String, Object>> result = tool.browseTags("s7://192.168.1.1", "*");

        assertEquals(1, result.size());
        assertTrue(result.get(0).containsKey("error"));
        assertTrue(((String) result.get(0).get("error")).contains("INTERNAL_ERROR"));
    }

    /**
     * Verifies that a connection failure returns an error entry.
     */
    @Test
    void browseTags_connectionFailure_returnsError() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(connectionManager.getConnection("s7://unreachable"))
                .thenThrow(new PlcConnectionException("Host unreachable"));

        List<Map<String, Object>> result = tool.browseTags("s7://unreachable", "*");

        assertEquals(1, result.size());
        assertTrue(result.get(0).containsKey("error"));
        assertTrue(((String) result.get(0).get("error")).contains("Host unreachable"));
    }

    /**
     * Verifies that the audit log request and response events are written.
     */
    @Test
    void browseTags_auditLogEnabled_logsRequestAndResponse() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(properties.getTimeoutSeconds()).thenReturn(30);
        when(connectionManager.getConnection(anyString())).thenReturn(connection);
        when(connection.browseRequestBuilder()).thenReturn(browseBuilder);
        when(browseBuilder.addQuery(anyString(), anyString())).thenReturn(browseBuilder);
        when(browseBuilder.build()).thenReturn(browseRequest);
        doReturn(CompletableFuture.completedFuture(browseResponse)).when(browseRequest).execute();
        when(browseResponse.getResponseCode("query")).thenReturn(PlcResponseCode.OK);
        when(browseResponse.getValues("query")).thenReturn(Collections.emptyList());

        tool.browseTags("s7://192.168.1.1", "DB1");

        verify(auditLog).write(eq(AuditLogEventType.API_REQUEST),
                contains("browse_tags invoked"));
        verify(auditLog).write(eq(AuditLogEventType.API_RESPONSE),
                contains("browse_tags returned"), any());
    }

    /**
     * Verifies that an error event is logged on connection failure.
     */
    @Test
    void browseTags_connectionFailure_logsError() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(connectionManager.getConnection(anyString()))
                .thenThrow(new PlcConnectionException("Refused"));

        tool.browseTags("s7://unreachable", "*");

        verify(auditLog).write(eq(AuditLogEventType.ERROR), contains("browse_tags failed"));
    }

    /**
     * Verifies that no audit log events are written when the log is disabled.
     */
    @Test
    void browseTags_auditLogDisabled_doesNotLog() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(connectionManager.getConnection(anyString()))
                .thenThrow(new PlcConnectionException("fail"));

        tool.browseTags("s7://192.168.1.1", "*");

        verify(auditLog, never()).write(any(AuditLogEventType.class), anyString());
        verify(auditLog, never()).write(any(AuditLogEventType.class), anyString(), any());
    }
}
