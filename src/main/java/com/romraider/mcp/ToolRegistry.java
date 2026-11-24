/*
 * RomRaider Open-Source Tuning, Logging and Reflashing
 * Copyright (C) 2006-2025 RomRaider.com
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.romraider.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.log4j.Logger;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.WriterConfig;

final class ToolRegistry {
    private static final Logger LOGGER = Logger.getLogger(ToolRegistry.class);
    private final Map<String, McpTool> tools = new LinkedHashMap<String, McpTool>();

    static ToolRegistry createDefault() {
        ToolRegistry registry = new ToolRegistry();
        RomTools.register(registry);
        TableTools.register(registry);
        DefinitionTools.register(registry);
        BinaryTools.register(registry);
        return registry;
    }

    void add(McpTool tool) {
        tools.put(tool.getName(), tool);
    }

    boolean contains(String name) {
        return tools.containsKey(name);
    }

    JsonArray describe() {
        JsonArray list = new JsonArray();
        for (McpTool tool : tools.values()) {
            list.add(tool.describe());
        }
        return list;
    }

    // Returns an MCP tool result, failures are reported in it rather than thrown
    JsonObject call(String name, JsonObject args) {
        McpTool tool = tools.get(name);
        McpCall call = new McpCall(args);
        try {
            JsonObject result = tool.invoke(call);
            if (!call.getWarnings().isEmpty()) {
                result.add("warnings", Js.strings(call.getWarnings()));
            }
            return toolResult(result.toString(WriterConfig.PRETTY_PRINT), false);
        } catch (McpException e) {
            return toolResult(withWarnings(e.getMessage(), call), true);
        } catch (Exception e) {
            LOGGER.error("MCP tool " + name + " failed", e);
            return toolResult(withWarnings(e.toString(), call), true);
        }
    }

    private static String withWarnings(String message, McpCall call) {
        if (call.getWarnings().isEmpty()) return message;
        StringBuilder text = new StringBuilder(message);
        for (String warning : call.getWarnings()) {
            text.append("\nwarning: ").append(warning);
        }
        return text.toString();
    }

    private static JsonObject toolResult(String text, boolean isError) {
        JsonArray content = new JsonArray().add(new JsonObject().add("type", "text").add("text", text));
        return new JsonObject().add("content", content).add("isError", isError);
    }
}
