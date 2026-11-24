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

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;

// Builds the JSON schema of a tool's arguments
final class Schema {
    private final JsonObject properties = new JsonObject();
    private final JsonArray required = new JsonArray();

    static Schema withRom() {
        return new Schema().rom();
    }

    static Schema withTable() {
        return new Schema().rom().string("table", "Table name as shown in the editor tree", true);
    }

    Schema rom() {
        JsonObject rom = new JsonObject()
                .add("type", new JsonArray().add("string").add("integer"))
                .add("description", "ROM file name, path, xmlid or index from roms_list. "
                        + "Defaults to the ROM selected in the editor.");
        properties.add("rom", rom);
        return this;
    }

    Schema string(String name, String description, boolean isRequired) {
        return prop(name, "string", description, isRequired);
    }

    Schema integer(String name, String description, boolean isRequired) {
        return prop(name, "integer", description, isRequired);
    }

    Schema number(String name, String description, boolean isRequired) {
        return prop(name, "number", description, isRequired);
    }

    Schema bool(String name, String description) {
        return prop(name, "boolean", description, false);
    }

    Schema address(String name, String description, boolean isRequired) {
        JsonObject address = new JsonObject()
                .add("type", new JsonArray().add("string").add("integer"))
                .add("description", description + ". A string is hex (0x optional), a number is decimal.");
        return raw(name, address, isRequired);
    }

    Schema object(String name, String description, boolean isRequired) {
        return prop(name, "object", description, isRequired);
    }

    Schema array(String name, String description, boolean isRequired) {
        return prop(name, "array", description, isRequired);
    }

    Schema choice(String name, String description, boolean isRequired, String... values) {
        JsonArray options = new JsonArray();
        for (String value : values) {
            options.add(value);
        }
        return raw(name, new JsonObject().add("type", "string").add("enum", options)
                .add("description", description), isRequired);
    }

    Schema raw(String name, JsonObject schema, boolean isRequired) {
        properties.add(name, schema);
        if (isRequired) required.add(name);
        return this;
    }

    private Schema prop(String name, String type, String description, boolean isRequired) {
        return raw(name, new JsonObject().add("type", type).add("description", description), isRequired);
    }

    JsonObject toJson() {
        JsonObject schema = Json.object().add("type", "object").add("properties", properties);
        if (!required.isEmpty()) schema.add("required", required);
        return schema;
    }
}
