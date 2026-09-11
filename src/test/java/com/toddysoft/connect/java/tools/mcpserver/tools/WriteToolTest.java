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

import org.apache.plc4x.java.utils.cache.PlcConnectionCache;
import com.toddysoft.connect.java.tools.mcpserver.config.McpServerProperties;
import org.apache.plc4x.java.utils.auditlog.api.AuditLog;
import org.apache.plc4x.java.utils.auditlog.api.AuditLogEventType;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
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
 * Tests for {@link WriteTool}.
 * Verifies write operations, error handling, and audit log interaction
 * using mocked PLC connections.
 */
@ExtendWith(MockitoExtension.class)
class WriteToolTest {

    @Mock
    private PlcConnectionCache connectionCache;

    @Mock
    private McpServerProperties properties;

    @Mock
    private AuditLog auditLog;

    @Mock
    private PlcConnection connection;

    @Mock
    private PlcWriteRequest.Builder writeBuilder;

    @Mock
    private PlcWriteRequest writeRequest;

    @Mock
    private PlcWriteResponse writeResponse;

    private WriteTool tool;

    @BeforeEach
    void setUp() {
        tool = new WriteTool(connectionCache, properties, auditLog);
    }

    /**
     * Verifies that writing a single tag with a successful response
     * returns the correct status.
     */
    @Test
    void writeTags_singleTagOk_returnsOkStatus() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(properties.getTimeoutSeconds()).thenReturn(30);
        when(connectionCache.getConnection("s7://192.168.1.1")).thenReturn(connection);
        when(connection.writeRequestBuilder()).thenReturn(writeBuilder);
        when(writeBuilder.addTagAddress(anyString(), anyString(), any())).thenReturn(writeBuilder);
        when(writeBuilder.build()).thenReturn(writeRequest);
        doReturn(CompletableFuture.completedFuture(writeResponse)).when(writeRequest).execute();

        Collection<String> tagNames = List.of("%DB1.DBW0:INT");
        when(writeResponse.getTagNames()).thenReturn(tagNames);
        when(writeResponse.getResponseCode("%DB1.DBW0:INT")).thenReturn(PlcResponseCode.OK);

        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("%DB1.DBW0:INT", 42);

        List<Map<String, Object>> result = tool.writeTags("s7://192.168.1.1", tags);

        assertEquals(1, result.size());
        Map<String, Object> entry = result.get(0);
        assertEquals("%DB1.DBW0:INT", entry.get("tagName"));
        assertEquals("OK", entry.get("status"));
    }

    /**
     * Verifies that writing multiple tags returns results for each tag.
     */
    @Test
    void writeTags_multipleTags_returnsAllResults() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getTimeoutSeconds()).thenReturn(30);
        when(connectionCache.getConnection("s7://192.168.1.1")).thenReturn(connection);
        when(connection.writeRequestBuilder()).thenReturn(writeBuilder);
        when(writeBuilder.addTagAddress(anyString(), anyString(), any())).thenReturn(writeBuilder);
        when(writeBuilder.build()).thenReturn(writeRequest);
        doReturn(CompletableFuture.completedFuture(writeResponse)).when(writeRequest).execute();

        Collection<String> tagNames = List.of("tag1", "tag2");
        when(writeResponse.getTagNames()).thenReturn(tagNames);
        when(writeResponse.getResponseCode("tag1")).thenReturn(PlcResponseCode.OK);
        when(writeResponse.getResponseCode("tag2")).thenReturn(PlcResponseCode.OK);

        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("tag1", true);
        tags.put("tag2", 3.14);

        List<Map<String, Object>> result = tool.writeTags("s7://192.168.1.1", tags);

        assertEquals(2, result.size());
        assertEquals("tag1", result.get(0).get("tagName"));
        assertEquals("OK", result.get(0).get("status"));
        assertEquals("tag2", result.get(1).get("tagName"));
        assertEquals("OK", result.get(1).get("status"));
    }

    /**
     * Verifies that a write with a non-OK response code is reported correctly.
     */
    @Test
    void writeTags_tagWriteFailure_returnsFailureStatus() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getTimeoutSeconds()).thenReturn(30);
        when(connectionCache.getConnection("s7://192.168.1.1")).thenReturn(connection);
        when(connection.writeRequestBuilder()).thenReturn(writeBuilder);
        when(writeBuilder.addTagAddress(anyString(), anyString(), any())).thenReturn(writeBuilder);
        when(writeBuilder.build()).thenReturn(writeRequest);
        doReturn(CompletableFuture.completedFuture(writeResponse)).when(writeRequest).execute();

        Collection<String> tagNames = List.of("read-only-tag");
        when(writeResponse.getTagNames()).thenReturn(tagNames);
        when(writeResponse.getResponseCode("read-only-tag")).thenReturn(PlcResponseCode.ACCESS_DENIED);

        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("read-only-tag", 99);

        List<Map<String, Object>> result = tool.writeTags("s7://192.168.1.1", tags);

        assertEquals(1, result.size());
        assertEquals("ACCESS_DENIED", result.get(0).get("status"));
    }

    /**
     * Verifies that a connection failure returns an error entry.
     */
    @Test
    void writeTags_connectionFailure_returnsError() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(connectionCache.getConnection("s7://unreachable"))
                .thenThrow(new PlcConnectionException("Connection refused"));

        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("tag1", 42);

        List<Map<String, Object>> result = tool.writeTags("s7://unreachable", tags);

        assertEquals(1, result.size());
        assertTrue(result.get(0).containsKey("error"));
        assertTrue(((String) result.get(0).get("error")).contains("Connection refused"));
    }

    /**
     * Verifies that audit log request and response events are written.
     */
    @Test
    void writeTags_auditLogEnabled_logsRequestAndResponse() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(properties.getTimeoutSeconds()).thenReturn(30);
        when(connectionCache.getConnection(anyString())).thenReturn(connection);
        when(connection.writeRequestBuilder()).thenReturn(writeBuilder);
        when(writeBuilder.addTagAddress(anyString(), anyString(), any())).thenReturn(writeBuilder);
        when(writeBuilder.build()).thenReturn(writeRequest);
        doReturn(CompletableFuture.completedFuture(writeResponse)).when(writeRequest).execute();
        when(writeResponse.getTagNames()).thenReturn(Collections.emptyList());

        tool.writeTags("s7://192.168.1.1", Map.of("tag1", 42));

        verify(auditLog).write(eq(AuditLogEventType.API_REQUEST),
                contains("write_tags invoked"), any());
        verify(auditLog).write(eq(AuditLogEventType.API_RESPONSE),
                contains("write_tags completed"), any());
    }

    /**
     * Verifies that an error event is logged on connection failure.
     */
    @Test
    void writeTags_connectionFailure_logsError() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(connectionCache.getConnection(anyString()))
                .thenThrow(new PlcConnectionException("Timeout"));

        tool.writeTags("s7://unreachable", Map.of("tag1", 42));

        verify(auditLog).write(eq(AuditLogEventType.ERROR), contains("write_tags failed"));
    }

    /**
     * Verifies that no audit log events are written when the log is disabled.
     */
    @Test
    void writeTags_auditLogDisabled_doesNotLog() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(connectionCache.getConnection(anyString()))
                .thenThrow(new PlcConnectionException("fail"));

        tool.writeTags("s7://192.168.1.1", Map.of("tag1", 42));

        verify(auditLog, never()).write(any(AuditLogEventType.class), anyString());
        verify(auditLog, never()).write(any(AuditLogEventType.class), anyString(), any());
    }

    /**
     * Verifies that writing with string values works correctly.
     */
    @Test
    void writeTags_stringValue_passedToBuilder() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getTimeoutSeconds()).thenReturn(30);
        when(connectionCache.getConnection("s7://192.168.1.1")).thenReturn(connection);
        when(connection.writeRequestBuilder()).thenReturn(writeBuilder);
        when(writeBuilder.addTagAddress(anyString(), anyString(), any())).thenReturn(writeBuilder);
        when(writeBuilder.build()).thenReturn(writeRequest);
        doReturn(CompletableFuture.completedFuture(writeResponse)).when(writeRequest).execute();

        Collection<String> tagNames = List.of("string-tag");
        when(writeResponse.getTagNames()).thenReturn(tagNames);
        when(writeResponse.getResponseCode("string-tag")).thenReturn(PlcResponseCode.OK);

        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("string-tag", "hello");

        List<Map<String, Object>> result = tool.writeTags("s7://192.168.1.1", tags);

        assertEquals(1, result.size());
        assertEquals("OK", result.get(0).get("status"));
        // Verify the string value was passed to the builder.
        verify(writeBuilder).addTagAddress("string-tag", "string-tag", "hello");
    }
}
