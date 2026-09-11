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

import org.apache.plc4x.java.api.types.PlcValueType;
import org.apache.plc4x.java.api.value.PlcValue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts {@link PlcValue} instances to JSON-serializable Java types.
 *
 * <p>Maps PLC4X IEC 61131-3 values to native Java types suitable for JSON serialization
 * in MCP tool responses. Uses {@link PlcValueType} for type dispatch rather than the
 * {@code is*()} convenience methods, which test convertibility rather than actual type.</p>
 *
 * <p>Conversion rules:</p>
 * <ul>
 *   <li>{@code BOOL} → {@link Boolean}</li>
 *   <li>{@code SINT, USINT, INT, UINT, BYTE, WORD, DINT} → {@link Integer}</li>
 *   <li>{@code UDINT, DWORD, LINT} → {@link Long}</li>
 *   <li>{@code ULINT, LWORD} → {@link BigInteger}</li>
 *   <li>{@code REAL} → {@link Float}</li>
 *   <li>{@code LREAL} → {@link Double}</li>
 *   <li>{@code STRING, WSTRING, CHAR, WCHAR} → {@link String}</li>
 *   <li>{@code Struct} → {@link Map} (recursive)</li>
 *   <li>{@code List} → {@link List} (recursive)</li>
 *   <li>{@code DATE, TIME_OF_DAY, DATE_AND_TIME, DATE_AND_LTIME, LDATE, LTIME_OF_DAY, TIME, LTIME} → {@link String} (ISO 8601 or duration)</li>
 *   <li>{@code RAW_BYTE_ARRAY} → {@link List} of {@link Integer} (unsigned, 0-255)</li>
 *   <li>{@code NULL} → {@code null}</li>
 * </ul>
 */
public final class PlcValueConverter {

    private PlcValueConverter() {
        // Utility class — not instantiable
    }

    /**
     * Converts a {@link PlcValue} to a JSON-serializable Java object.
     *
     * @param value the PLC value to convert (may be null)
     * @return a JSON-safe Java object, or null for null/PlcNull values
     */
    public static Object toJsonValue(PlcValue value) {
        if (value == null || value.isNull()) {
            return null;
        }

        PlcValueType type = value.getPlcValueType();
        return switch (type) {
            case BOOL -> value.getBoolean();

            // Signed types up to 32 bit and unsigned types up to 16 bit → int
            case SINT, USINT, INT, UINT, BYTE, WORD, DINT -> value.getInteger();

            // Unsigned 32 bit and signed 64 bit → long (an int would wrap 0xDEADBEEF to a negative)
            case UDINT, DWORD, LINT -> value.getLong();

            // Unsigned 64 bit → BigInteger (values above 2^63-1 do not fit a long)
            case ULINT, LWORD -> value.getBigInteger();

            // Floating point
            case REAL -> value.getFloat();
            case LREAL -> value.getDouble();

            // String types
            case STRING, WSTRING, CHAR, WCHAR -> value.getString();

            // Date/time types → ISO 8601 strings
            case DATE, LDATE -> value.getDate() != null ? value.getDate().toString() : null;
            case DATE_AND_TIME, DATE_AND_LTIME, LDATE_AND_TIME -> value.getDateTime() != null ? value.getDateTime().toString() : null;
            case TIME_OF_DAY, LTIME_OF_DAY -> value.getTime() != null ? value.getTime().toString() : null;
            case TIME, LTIME -> value.getDuration() != null ? value.getDuration().toString() : null;

            // Struct → recursive map
            case Struct -> {
                Map<String, Object> result = new LinkedHashMap<>();
                for (String key : value.getKeys()) {
                    result.put(key, toJsonValue(value.getValue(key)));
                }
                yield result;
            }

            // List → recursive list
            case List -> value.getList().stream()
                .map(PlcValueConverter::toJsonValue)
                .collect(Collectors.toList());

            // Raw bytes → list of unsigned ints, matching how BYTE arrays are rendered
            case RAW_BYTE_ARRAY -> {
                byte[] raw = value.getRaw();
                if (raw == null) {
                    yield null;
                }
                List<Integer> bytes = new ArrayList<>(raw.length);
                for (byte b : raw) {
                    bytes.add(b & 0xFF);
                }
                yield bytes;
            }
            case NULL -> null;
        };
    }

    /**
     * Returns the IEC 61131-3 type name for a {@link PlcValue}.
     *
     * <p>Uses the {@code PlcValueType} enum name, which corresponds to IEC type names
     * (e.g., {@code BOOL}, {@code INT}, {@code REAL}, {@code STRING}).</p>
     *
     * @param value the PLC value (may be null)
     * @return the type name string, or "NULL" for null values
     */
    public static String getTypeName(PlcValue value) {
        if (value == null || value.isNull()) {
            return "NULL";
        }
        return value.getPlcValueType().name();
    }

}
