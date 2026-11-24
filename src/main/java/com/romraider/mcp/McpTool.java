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

import java.util.concurrent.Callable;

import com.eclipsesource.json.JsonObject;
import com.romraider.util.Interaction;

abstract class McpTool {
    private final String name;
    private final String description;
    private final Schema schema;

    McpTool(String name, String description, Schema schema) {
        this.name = name;
        this.description = description;
        this.schema = schema;
    }

    String getName() {
        return name;
    }

    JsonObject describe() {
        return new JsonObject()
                .add("name", name)
                .add("description", description)
                .add("inputSchema", schema.toJson());
    }

    // Tools that wait for background work run on the server thread and use Edt themselves
    boolean runsOnEdt() {
        return true;
    }

    abstract JsonObject run(McpCall call) throws Exception;

    JsonObject invoke(final McpCall call) throws Exception {
        if (!runsOnEdt()) return run(call);

        return Edt.call(new Callable<JsonObject>() {
            @Override
            public JsonObject call() throws Exception {
                Interaction.suppressDialogs(call.getWarnings());
                try {
                    return run(call);
                } finally {
                    Interaction.restoreDialogs();
                }
            }
        });
    }
}
