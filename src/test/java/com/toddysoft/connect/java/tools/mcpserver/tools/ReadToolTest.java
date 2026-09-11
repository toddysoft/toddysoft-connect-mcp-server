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
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.types.PlcValueType;
import org.apache.plc4x.java.api.value.PlcValue;
import com.toddysoft.connect.java.tools.mcpserver.security.TestGuards;
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
import static org.mockito.Mockito.doReturn;

/**
 * Tests for {@link ReadTool}.
 * Verifies read operations, error handling, and audit log interaction
 * using mocked PLC connections.
 */
@ExtendWith(MockitoExtension.class)
class ReadToolTest {

    @Mock
    private PlcConnectionCache connectionCache;

    @Mock
    private McpServerProperties properties;

    @Mock
    private AuditLog auditLog;

    @Mock
    private PlcConnection connection;

    @Mock
    private PlcReadRequest.Builder readBuilder;

    @Mock
    private PlcReadRequest readRequest;

    @Mock
    private PlcReadResponse readResponse;

    private ReadTool tool;

    @BeforeEach
    void setUp() {
        tool = new ReadTool(connectionCache, properties, auditLog, TestGuards.permissive(), TestGuards.redactor());
    }

    /**
     * Verifies that reading a single tag with a successful response
     * returns the correct value and status.
     */
    @Test
    void readTags_singleTagOk_returnsValueAndStatus() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(properties.getTimeoutSeconds()).thenReturn(30);
        when(connectionCache.getConnection("s7://192.168.1.1")).thenReturn(connection);
        when(connection.readRequestBuilder()).thenReturn(readBuilder);
        when(readBuilder.addTagAddress(anyString(), anyString())).thenReturn(readBuilder);
        when(readBuilder.build()).thenReturn(readRequest);
        doReturn(CompletableFuture.completedFuture(readResponse)).when(readRequest).execute();

        // Set up response for a single tag.
        Collection<String> tagNames = List.of("%DB1.DBW0:INT");
        when(readResponse.getTagNames()).thenReturn(tagNames);
        when(readResponse.getResponseCode("%DB1.DBW0:INT")).thenReturn(PlcResponseCode.OK);

        PlcValue mockValue = mock(PlcValue.class);
        when(mockValue.isNull()).thenReturn(false);
        when(mockValue.getPlcValueType()).thenReturn(PlcValueType.INT);
        when(mockValue.getInteger()).thenReturn(42);
        when(readResponse.getPlcValue("%DB1.DBW0:INT")).thenReturn(mockValue);

        List<Map<String, Object>> result = tool.readTags("s7://192.168.1.1",
                List.of("%DB1.DBW0:INT"));

        assertEquals(1, result.size());
        Map<String, Object> entry = result.get(0);
        assertEquals("%DB1.DBW0:INT", entry.get("tagName"));
        assertEquals("OK", entry.get("status"));
        assertEquals(42, entry.get("value"));
        assertEquals("INT", entry.get("valueType"));
    }

    /**
     * Verifies that reading multiple tags returns results for each tag.
     */
    @Test
    void readTags_multipleTags_returnsAllResults() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getTimeoutSeconds()).thenReturn(30);
        when(connectionCache.getConnection("s7://192.168.1.1")).thenReturn(connection);
        when(connection.readRequestBuilder()).thenReturn(readBuilder);
        when(readBuilder.addTagAddress(anyString(), anyString())).thenReturn(readBuilder);
        when(readBuilder.build()).thenReturn(readRequest);
        doReturn(CompletableFuture.completedFuture(readResponse)).when(readRequest).execute();

        Collection<String> tagNames = List.of("tag1", "tag2");
        when(readResponse.getTagNames()).thenReturn(tagNames);
        when(readResponse.getResponseCode("tag1")).thenReturn(PlcResponseCode.OK);
        when(readResponse.getResponseCode("tag2")).thenReturn(PlcResponseCode.OK);

        PlcValue value1 = mock(PlcValue.class);
        when(value1.isNull()).thenReturn(false);
        when(value1.getPlcValueType()).thenReturn(PlcValueType.BOOL);
        when(value1.getBoolean()).thenReturn(true);
        when(readResponse.getPlcValue("tag1")).thenReturn(value1);

        PlcValue value2 = mock(PlcValue.class);
        when(value2.isNull()).thenReturn(false);
        when(value2.getPlcValueType()).thenReturn(PlcValueType.REAL);
        when(value2.getFloat()).thenReturn(3.14f);
        when(readResponse.getPlcValue("tag2")).thenReturn(value2);

        List<Map<String, Object>> result = tool.readTags("s7://192.168.1.1",
                List.of("tag1", "tag2"));

        assertEquals(2, result.size());
        assertEquals("tag1", result.get(0).get("tagName"));
        assertEquals(true, result.get(0).get("value"));
        assertEquals("tag2", result.get(1).get("tagName"));
        assertEquals(3.14f, result.get(1).get("value"));
    }

    /**
     * Verifies that a tag with a non-OK response code is returned
     * without value or valueType fields.
     */
    @Test
    void readTags_tagNotFound_returnsStatusOnly() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(properties.getTimeoutSeconds()).thenReturn(30);
        when(connectionCache.getConnection("s7://192.168.1.1")).thenReturn(connection);
        when(connection.readRequestBuilder()).thenReturn(readBuilder);
        when(readBuilder.addTagAddress(anyString(), anyString())).thenReturn(readBuilder);
        when(readBuilder.build()).thenReturn(readRequest);
        doReturn(CompletableFuture.completedFuture(readResponse)).when(readRequest).execute();

        Collection<String> tagNames = List.of("missing-tag");
        when(readResponse.getTagNames()).thenReturn(tagNames);
        when(readResponse.getResponseCode("missing-tag")).thenReturn(PlcResponseCode.NOT_FOUND);

        List<Map<String, Object>> result = tool.readTags("s7://192.168.1.1",
                List.of("missing-tag"));

        assertEquals(1, result.size());
        Map<String, Object> entry = result.get(0);
        assertEquals("NOT_FOUND", entry.get("status"));
        assertFalse(entry.containsKey("value"), "Non-OK response should not include value");
        assertFalse(entry.containsKey("valueType"), "Non-OK response should not include valueType");
    }

    /**
     * Verifies that a connection failure returns an error entry.
     */
    @Test
    void readTags_connectionFailure_returnsError() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(connectionCache.getConnection("s7://unreachable"))
                .thenThrow(new PlcConnectionException("Connection refused"));

        List<Map<String, Object>> result = tool.readTags("s7://unreachable",
                List.of("tag1"));

        assertEquals(1, result.size());
        assertTrue(result.get(0).containsKey("error"));
        assertTrue(((String) result.get(0).get("error")).contains("Connection refused"));
    }

    /**
     * Verifies that audit log request and response events are written.
     */
    @Test
    void readTags_auditLogEnabled_logsRequestAndResponse() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(properties.getTimeoutSeconds()).thenReturn(30);
        when(connectionCache.getConnection(anyString())).thenReturn(connection);
        when(connection.readRequestBuilder()).thenReturn(readBuilder);
        when(readBuilder.addTagAddress(anyString(), anyString())).thenReturn(readBuilder);
        when(readBuilder.build()).thenReturn(readRequest);
        doReturn(CompletableFuture.completedFuture(readResponse)).when(readRequest).execute();
        when(readResponse.getTagNames()).thenReturn(Collections.emptyList());

        tool.readTags("s7://192.168.1.1", List.of("tag1"));

        verify(auditLog).write(eq(AuditLogEventType.API_REQUEST),
                contains("read_tags invoked"), any());
        verify(auditLog).write(eq(AuditLogEventType.API_RESPONSE),
                contains("read_tags returned"), any());
    }

    /**
     * Verifies that an error event is logged on connection failure.
     */
    @Test
    void readTags_connectionFailure_logsError() throws Exception {
        when(auditLog.isEnabled()).thenReturn(true);
        when(connectionCache.getConnection(anyString()))
                .thenThrow(new PlcConnectionException("Timeout"));

        tool.readTags("s7://unreachable", List.of("tag1"));

        verify(auditLog).write(eq(AuditLogEventType.ERROR), contains("read_tags failed"));
    }

    /**
     * Verifies that no audit log events are written when the log is disabled.
     */
    @Test
    void readTags_auditLogDisabled_doesNotLog() throws Exception {
        when(auditLog.isEnabled()).thenReturn(false);
        when(connectionCache.getConnection(anyString()))
                .thenThrow(new PlcConnectionException("fail"));

        tool.readTags("s7://192.168.1.1", List.of("tag1"));

        verify(auditLog, never()).write(any(AuditLogEventType.class), anyString());
        verify(auditLog, never()).write(any(AuditLogEventType.class), anyString(), any());
    }
}
