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
package com.toddysoft.connect.java.tools.mcpserver.util;

import org.apache.plc4x.java.spi.values.*;
import org.apache.plc4x.java.api.value.PlcValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlcValueConverterTest {

    @Test
    void toJsonValue_withNull_returnsNull() {
        assertNull(PlcValueConverter.toJsonValue(null));
    }

    @Test
    void toJsonValue_withPlcNull_returnsNull() {
        assertNull(PlcValueConverter.toJsonValue(new PlcNull()));
    }

    @Test
    void toJsonValue_withPlcBool_returnsBoolean() {
        assertEquals(true, PlcValueConverter.toJsonValue(new PlcBOOL(true)));
        assertEquals(false, PlcValueConverter.toJsonValue(new PlcBOOL(false)));
    }

    @Test
    void toJsonValue_withPlcInt_returnsInteger() {
        assertEquals(42, PlcValueConverter.toJsonValue(new PlcINT(42)));
    }

    @Test
    void toJsonValue_withPlcDint_returnsInteger() {
        assertEquals(100000, PlcValueConverter.toJsonValue(new PlcDINT(100000)));
    }

    @Test
    void toJsonValue_withPlcLint_returnsLong() {
        long bigValue = 5_000_000_000L;
        assertEquals(bigValue, PlcValueConverter.toJsonValue(new PlcLINT(bigValue)));
    }

    @Test
    void toJsonValue_withPlcUdint_returnsUnsignedLong() {
        // 0xFFFFFFFF must not wrap to -1
        Object result = PlcValueConverter.toJsonValue(new PlcUDINT(4_294_967_295L));
        assertInstanceOf(Long.class, result);
        assertEquals(4_294_967_295L, result);
    }

    @Test
    void toJsonValue_withPlcDword_returnsUnsignedLong() {
        // 0xDEADBEEF used to come back as -559038737
        Object result = PlcValueConverter.toJsonValue(new PlcDWORD(0xDEADBEEFL));
        assertInstanceOf(Long.class, result);
        assertEquals(3_735_928_559L, result);
    }

    @Test
    void toJsonValue_withPlcUlint_returnsUnsignedBigInteger() {
        // Above Long.MAX_VALUE, so only a BigInteger keeps the value intact
        BigInteger big = new BigInteger("18446744073709551000");
        Object result = PlcValueConverter.toJsonValue(new PlcULINT(big));
        assertInstanceOf(BigInteger.class, result);
        assertEquals(big, result);
    }

    @Test
    void toJsonValue_withPlcLword_returnsUnsignedBigInteger() {
        BigInteger big = new BigInteger("0123456789ABCDEF", 16);
        Object result = PlcValueConverter.toJsonValue(new PlcLWORD(big));
        assertInstanceOf(BigInteger.class, result);
        assertEquals(big, result);
    }

    @Test
    void toJsonValue_withPlcSint_returnsInteger() {
        // PlcSINT is a byte, should be converted to int
        Object result = PlcValueConverter.toJsonValue(new PlcSINT(42));
        assertInstanceOf(Integer.class, result);
        assertEquals(42, result);
    }

    @Test
    void toJsonValue_withPlcReal_returnsFloat() {
        Object result = PlcValueConverter.toJsonValue(new PlcREAL(3.14f));
        assertInstanceOf(Float.class, result);
        assertEquals(3.14f, (Float) result, 0.001f);
    }

    @Test
    void toJsonValue_withPlcLreal_returnsDouble() {
        Object result = PlcValueConverter.toJsonValue(new PlcLREAL(3.14159265));
        assertInstanceOf(Double.class, result);
        assertEquals(3.14159265, (Double) result, 0.0000001);
    }

    @Test
    void toJsonValue_withPlcString_returnsString() {
        assertEquals("hello", PlcValueConverter.toJsonValue(new PlcSTRING("hello")));
    }

    @Test
    void toJsonValue_withPlcDate_returnsIsoString() {
        LocalDate date = LocalDate.of(2025, 6, 15);
        assertEquals("2025-06-15", PlcValueConverter.toJsonValue(new PlcDATE(date)));
    }

    @Test
    void toJsonValue_withPlcDateTime_returnsIsoString() {
        LocalDateTime dateTime = LocalDateTime.of(2025, 6, 15, 10, 30, 45);
        assertEquals("2025-06-15T10:30:45", PlcValueConverter.toJsonValue(new PlcDATE_AND_TIME(dateTime)));
    }

    @Test
    void toJsonValue_withPlcTime_returnsDurationString() {
        // PlcTIME wraps Duration, isDuration() returns true
        Object result = PlcValueConverter.toJsonValue(new PlcTIME(Duration.ofMillis(5000)));
        assertInstanceOf(String.class, result);
        assertEquals("PT5S", result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toJsonValue_withPlcStruct_returnsMap() {
        Map<String, PlcValue> fields = new LinkedHashMap<>();
        fields.put("temperature", new PlcREAL(23.5f));
        fields.put("running", new PlcBOOL(true));
        PlcStruct struct = new PlcStruct(fields);

        Object result = PlcValueConverter.toJsonValue(struct);
        assertInstanceOf(Map.class, result);

        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(23.5f, (Float) map.get("temperature"), 0.001f);
        assertEquals(true, map.get("running"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toJsonValue_withPlcList_returnsList() {
        PlcList list = new PlcList(List.of(new PlcINT(1), new PlcINT(2), new PlcINT(3)));

        Object result = PlcValueConverter.toJsonValue(list);
        assertInstanceOf(List.class, result);

        List<Object> items = (List<Object>) result;
        assertEquals(3, items.size());
        assertEquals(1, items.get(0));
        assertEquals(2, items.get(1));
        assertEquals(3, items.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toJsonValue_withNestedStruct_recursesCorrectly() {
        Map<String, PlcValue> inner = new LinkedHashMap<>();
        inner.put("value", new PlcREAL(42.0f));
        Map<String, PlcValue> outer = new LinkedHashMap<>();
        outer.put("sensor", new PlcStruct(inner));

        Object result = PlcValueConverter.toJsonValue(new PlcStruct(outer));
        assertInstanceOf(Map.class, result);

        Map<String, Object> outerMap = (Map<String, Object>) result;
        assertInstanceOf(Map.class, outerMap.get("sensor"));

        Map<String, Object> innerMap = (Map<String, Object>) outerMap.get("sensor");
        assertEquals(42.0f, (Float) innerMap.get("value"), 0.001f);
    }

    @Test
    void getTypeName_withNull_returnsNull() {
        assertEquals("NULL", PlcValueConverter.getTypeName(null));
    }

    @Test
    void getTypeName_withPlcNull_returnsNull() {
        assertEquals("NULL", PlcValueConverter.getTypeName(new PlcNull()));
    }

    @Test
    void getTypeName_withPlcBool_returnsBool() {
        assertEquals("BOOL", PlcValueConverter.getTypeName(new PlcBOOL(true)));
    }

    @Test
    void getTypeName_withPlcInt_returnsInt() {
        assertEquals("INT", PlcValueConverter.getTypeName(new PlcINT(0)));
    }

    @Test
    void getTypeName_withPlcReal_returnsReal() {
        assertEquals("REAL", PlcValueConverter.getTypeName(new PlcREAL(0.0f)));
    }

    @Test
    void getTypeName_withPlcString_returnsString() {
        assertEquals("STRING", PlcValueConverter.getTypeName(new PlcSTRING("test")));
    }

    @Test
    void getTypeName_withPlcStruct_returnsStruct() {
        assertEquals("Struct", PlcValueConverter.getTypeName(new PlcStruct(Map.of())));
    }

    @Test
    void getTypeName_withPlcList_returnsList() {
        assertEquals("List", PlcValueConverter.getTypeName(new PlcList()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toJsonValue_withPlcRawByteArray_returnsUnsignedIntList() {
        // Used to serialize as the Java array's default toString (e.g. "[B@363f783e")
        Object result = PlcValueConverter.toJsonValue(
            new PlcRawByteArray(new byte[]{(byte) 0xDE, (byte) 0xAD, 0x12, 0x00}));
        assertInstanceOf(List.class, result);
        assertEquals(List.of(222, 173, 18, 0), (List<Integer>) result);
    }

    @Test
    void toJsonValue_withEmptyPlcRawByteArray_returnsEmptyList() {
        assertEquals(List.of(), PlcValueConverter.toJsonValue(new PlcRawByteArray(new byte[0])));
    }
}
