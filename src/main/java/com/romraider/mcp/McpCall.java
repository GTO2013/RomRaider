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

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.romraider.editor.ecu.ECUEditor;
import com.romraider.editor.ecu.ECUEditorManager;
import com.romraider.maps.Rom;
import com.romraider.maps.Table;
import com.romraider.util.HexUtil;

// The arguments of one tool call and the warnings it collects
final class McpCall {
    private final JsonObject args;
    private final List<String> warnings = new ArrayList<String>();

    McpCall(JsonObject args) {
        this.args = args == null ? new JsonObject() : args;
    }

    List<String> getWarnings() {
        return warnings;
    }

    void warn(String warning) {
        warnings.add(warning);
    }

    boolean has(String name) {
        JsonValue value = args.get(name);
        return value != null && !value.isNull();
    }

    JsonValue get(String name) throws McpException {
        if (!has(name)) throw new McpException("Missing argument '" + name + "'");
        return args.get(name);
    }

    String getString(String name) throws McpException {
        JsonValue value = get(name);
        if (!value.isString()) throw new McpException("'" + name + "' must be a string");
        return value.asString();
    }

    String optString(String name, String defaultValue) throws McpException {
        return has(name) ? getString(name) : defaultValue;
    }

    int getInt(String name) throws McpException {
        JsonValue value = get(name);
        if (!value.isNumber()) throw new McpException("'" + name + "' must be a number");
        return value.asInt();
    }

    int optInt(String name, int defaultValue) throws McpException {
        return has(name) ? getInt(name) : defaultValue;
    }

    double getDouble(String name) throws McpException {
        JsonValue value = get(name);
        if (!value.isNumber()) throw new McpException("'" + name + "' must be a number");
        return value.asDouble();
    }

    boolean optBoolean(String name, boolean defaultValue) throws McpException {
        if (!has(name)) return defaultValue;
        JsonValue value = args.get(name);
        if (!value.isBoolean()) throw new McpException("'" + name + "' must be true or false");
        return value.asBoolean();
    }

    JsonObject getObject(String name) throws McpException {
        JsonValue value = get(name);
        if (!value.isObject()) throw new McpException("'" + name + "' must be an object");
        return value.asObject();
    }

    int getAddress(String name) throws McpException {
        return parseAddress(get(name), name);
    }

    static int parseAddress(JsonValue value, String name) throws McpException {
        if (value.isNumber()) return value.asInt();
        if (value.isString()) {
            try {
                return HexUtil.hexToInt(value.asString().trim().toLowerCase());
            } catch (NumberFormatException e) {
                throw new McpException("'" + name + "' is not a hex address: " + value.asString());
            }
        }
        throw new McpException("'" + name + "' must be a hex string or a number");
    }

    Rom getRom() throws McpException {
        ECUEditor editor = ECUEditorManager.getECUEditor();
        Vector<Rom> roms = editor.getImages();
        if (roms.isEmpty()) throw new McpException("No ROM is open");

        if (!has("rom")) {
            if (editor.getLastSelectedRom() != null) return editor.getLastSelectedRom();
            if (roms.size() == 1) return roms.get(0);
            throw new McpException("Several ROMs are open, pass 'rom'");
        }

        JsonValue ref = args.get("rom");
        if (ref.isNumber()) {
            int index = ref.asInt();
            if (index < 0 || index >= roms.size()) throw new McpException("No ROM with index " + index);
            return roms.get(index);
        }

        String name = getString("rom");
        for (Rom rom : roms) {
            if (rom.getFileName().equalsIgnoreCase(name)
                    || rom.getFullFileName().getAbsolutePath().equalsIgnoreCase(name)
                    || name.equalsIgnoreCase(rom.getRomIDString())) {
                return rom;
            }
        }
        try {
            int index = Integer.parseInt(name);
            if (index >= 0 && index < roms.size()) return roms.get(index);
        } catch (NumberFormatException e) {
            // Not an index
        }
        throw new McpException("No open ROM matches '" + name + "'");
    }

    Table getTable(Rom rom) throws McpException {
        return findTable(rom, getString("table"));
    }

    static Table findTable(Rom rom, String name) throws McpException {
        Table table = rom.getTableByName(name);
        if (table != null) return table;

        StringBuilder similar = new StringBuilder();
        int found = 0;
        for (Table t : rom.getTables()) {
            if (t.getName().toLowerCase().contains(name.toLowerCase()) && found < 10) {
                similar.append(found++ == 0 ? "" : ", ").append(t.getName());
            }
        }
        throw new McpException("No table '" + name + "' in " + rom.getFileName()
                + (found > 0 ? ". Similar: " + similar : ""));
    }
}
