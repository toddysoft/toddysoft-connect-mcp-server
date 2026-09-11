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

import org.apache.plc4x.java.api.types.PlcResponseCode;

/**
 * Turns a per-tag {@link PlcResponseCode} into a human-readable explanation.
 *
 * <p>PLC4X reports per-tag failures as a bare enum constant: the driver-side exception that
 * caused it is logged by the driver but is not reachable through the PLC4X API, so a caller
 * seeing only {@code INTERNAL_ERROR} has nothing to act on. These messages say what the code
 * means and, where applicable, where to look for the underlying cause.</p>
 */
public final class PlcResponseCodes {

    private PlcResponseCodes() {
        // Utility class — not instantiable
    }

    /**
     * Returns a human-readable explanation for a non-OK response code.
     *
     * @param responseCode the per-tag response code (may be null)
     * @return an explanation, or null for {@code OK} and null codes
     */
    public static String explain(PlcResponseCode responseCode) {
        if (responseCode == null || responseCode == PlcResponseCode.OK) {
            return null;
        }
        return switch (responseCode) {
            case NOT_FOUND -> "The device does not know this tag. Check the block, offset and name.";
            case ACCESS_DENIED -> "The device refused access to this tag. Check protection level and credentials.";
            case INVALID_ADDRESS -> "The address was rejected, either by the driver's address parser or by the device "
                    + "because it does not exist there (e.g. an unknown data block). Check the syntax for this "
                    + "protocol, and that the block exists on the device.";
            case INVALID_DATATYPE -> "The requested data type does not match the tag on the device.";
            case INVALID_DATA -> "The value did not fit the tag's data type or range.";
            case INTERNAL_ERROR -> "The driver failed while encoding or decoding this tag. "
                    + "PLC4X does not expose the underlying exception, so check the server log for the stack trace.";
            case REMOTE_BUSY -> "The device is busy and could not serve this tag right now. Retry shortly.";
            case REMOTE_ERROR -> "The device reported an error for this tag.";
            case UNSUPPORTED -> "This driver does not support the requested operation on this tag.";
            case RESPONSE_PENDING -> "The device has not answered for this tag yet.";
            case NOT_READY -> "The device is not ready to serve this tag.";
            case OUT_OF_RANGE -> "The address lies outside the readable range of the device or block.";
            // OK is handled above; kept exhaustive so a new PLC4X code fails the build here.
            case OK -> null;
        };
    }

}
