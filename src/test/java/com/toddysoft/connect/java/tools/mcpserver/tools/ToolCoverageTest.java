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

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every MCP tool must be a deliberate decision: either it touches a device and passes through
 * {@link com.toddysoft.connect.java.tools.mcpserver.security.OperationGuard}, or it is exempt
 * because it touches nothing.
 *
 * <p>This test fails when a tool is added and neither is recorded, so the gate cannot be skipped by
 * omission — the failure mode a guard-rail is least likely to survive.</p>
 */
class ToolCoverageTest {

    /** Tools that reach a device, and therefore must consult the guard. */
    private static final Set<String> DEVICE_TOUCHING = Set.of(
            "read_tags", "write_tags", "browse_tags", "discover_devices");

    /** Tools that read driver metadata in-process and send nothing. */
    private static final Set<String> EXEMPT = Set.of("list_drivers", "describe_driver");

    @Test
    void everyToolIsEitherGuardedOrDeliberatelyExempt() {
        Map<String, Class<?>> tools = advertisedTools();
        Set<String> classified = new HashSet<>(DEVICE_TOUCHING);
        classified.addAll(EXEMPT);

        Set<String> unclassified = new TreeSet<>(tools.keySet());
        unclassified.removeAll(classified);

        assertTrue(unclassified.isEmpty(),
                "These MCP tools are neither guarded nor recorded as exempt: " + unclassified
                        + ". Add the guard call, or record the tool as exempt with the reason.");
    }

    @Test
    void everyDeviceTouchingToolActuallyExists() {
        // Guards against the list rotting into a claim about tools that are long gone.
        Set<String> missing = new TreeSet<>(DEVICE_TOUCHING);
        missing.removeAll(advertisedTools().keySet());

        assertTrue(missing.isEmpty(), "Recorded as device-touching but not found: " + missing);
    }

    /**
     * Every {@code @Tool}-annotated method in the tools package.
     *
     * <p>Compiled classes are enumerated directly rather than through Spring's component scan,
     * because that scan evaluates {@code @ConditionalOnProperty} and would therefore hide exactly
     * the two tools whose coverage matters most — the ones that are conditionally registered.</p>
     */
    private static Map<String, Class<?>> advertisedTools() {
        String packageName = "com.toddysoft.connect.java.tools.mcpserver.tools";
        // The package exists under both target/classes and target/test-classes, so every root is
        // scanned rather than whichever the classloader happens to return first.
        List<URL> roots = assertDoesNotThrow(() -> Collections.list(
                ToolCoverageTest.class.getClassLoader().getResources(packageName.replace('.', '/'))));
        assertFalse(roots.isEmpty(), "cannot locate the compiled tools package");

        List<File> classFiles = new ArrayList<>();
        for (URL root : roots) {
            File[] found = new File(root.getPath())
                    .listFiles((dir, name) -> name.endsWith(".class") && !name.contains("$"));
            if (found != null) {
                classFiles.addAll(Arrays.asList(found));
            }
        }

        Map<String, Class<?>> tools = new TreeMap<>();
        for (File classFile : classFiles) {
            String className = packageName + "."
                    + classFile.getName().substring(0, classFile.getName().length() - ".class".length());
            Class<?> type = assertDoesNotThrow(() -> Class.forName(className));
            for (Method method : type.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null) {
                    tools.put(tool.name(), type);
                }
            }
        }
        assertFalse(tools.isEmpty(), "the scan found no tools at all, so it is not testing anything");
        return tools;
    }
}
