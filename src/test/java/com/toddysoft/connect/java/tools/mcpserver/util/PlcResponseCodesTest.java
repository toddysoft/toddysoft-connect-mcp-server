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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlcResponseCodesTest {

    @Test
    void explain_withNull_returnsNull() {
        assertNull(PlcResponseCodes.explain(null));
    }

    @Test
    void explain_withOk_returnsNull() {
        assertNull(PlcResponseCodes.explain(PlcResponseCode.OK));
    }

    @Test
    void explain_withInternalError_pointsAtTheServerLog() {
        String message = PlcResponseCodes.explain(PlcResponseCode.INTERNAL_ERROR);
        assertNotNull(message);
        assertTrue(message.contains("server log"), "should tell the caller where the cause is: " + message);
    }

    @Test
    void explain_withEveryNonOkCode_returnsANonEmptyMessage() {
        for (PlcResponseCode code : PlcResponseCode.values()) {
            if (code == PlcResponseCode.OK) {
                continue;
            }
            String message = PlcResponseCodes.explain(code);
            assertNotNull(message, "no explanation for " + code);
            assertFalse(message.isBlank(), "blank explanation for " + code);
        }
    }

}
