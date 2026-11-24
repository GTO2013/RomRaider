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

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.romraider.maps.DataCell;
import com.romraider.maps.PresetManager;
import com.romraider.maps.PresetManager.PresetEntry;
import com.romraider.maps.Scale;
import com.romraider.maps.Table;
import com.romraider.maps.Table1D;
import com.romraider.maps.Table2D;
import com.romraider.maps.Table3D;

// Table values and cell addressing as seen by MCP clients
final class TableJson {
    private TableJson() {
    }

    static String typeName(Table table) {
        int dimension = table.getType().getDimension();
        return dimension > 0 ? dimension + "D" : "Switch";
    }

    static JsonObject summary(Table table) {
        JsonObject summary = new JsonObject()
                .add("name", table.getName())
                .add("type", typeName(table))
                .add("category", table.getCategory());
        if (!table.isStaticDataTable()) summary.add("address", Js.hex(table.getStorageAddress()));
        if (table.isModified()) summary.add("modified", true);
        return summary;
    }

    static JsonObject scale(Scale scale) {
        JsonObject json = new JsonObject()
                .add("category", scale.getCategory())
                .add("name", scale.getName())
                .add("units", scale.getUnit())
                .add("expression", scale.getExpression())
                .add("format", scale.getFormat());
        if (scale.getByteExpression() != null) json.add("to_byte", scale.getByteExpression());
        return json;
    }

    // Values grouped like the table frame shows them; 3D values are rows, one per y axis cell
    static JsonObject read(Table table, boolean raw) {
        JsonObject json = summary(table);
        if (table.getCurrentScale() != null) json.add("scale", scale(table.getCurrentScale()));

        if (table instanceof Table3D) {
            Table3D t3 = (Table3D) table;
            json.add("x_axis", axis(t3.getXAxis(), raw));
            json.add("y_axis", axis(t3.getYAxis(), raw));
            DataCell[][] data = t3.get3dData();
            JsonArray rows = new JsonArray();
            for (int y = 0; y < t3.getSizeY(); y++) {
                JsonArray row = new JsonArray();
                for (int x = 0; x < t3.getSizeX(); x++) {
                    row.add(value(data[x][y], raw));
                }
                rows.add(row);
            }
            json.add("values", rows);
        } else {
            if (table instanceof Table2D) {
                json.add("axis", axis(((Table2D) table).getAxis(), raw));
            }
            json.add("values", values(table, raw));
        }

        PresetManager presets = table.getPresetManager();
        if (presets != null && !presets.getPresets().isEmpty()) {
            JsonArray states = new JsonArray();
            for (PresetEntry entry : presets.getPresets()) {
                states.add(new JsonObject().add("name", entry.getName())
                        .add("active", presets.isPresetActive(entry)));
            }
            json.add("states", states);
        }
        return json;
    }

    private static JsonObject axis(Table1D axis, boolean raw) {
        JsonObject json = new JsonObject().add("name", axis.getName() == null ? "" : axis.getName());
        if (axis.isStaticDataTable()) {
            json.add("static", true);
        } else {
            json.add("address", Js.hex(axis.getStorageAddress()));
            if (axis.getCurrentScale() != null) json.add("units", axis.getCurrentScale().getUnit());
        }
        json.add("values", values(axis, raw));
        return json;
    }

    private static JsonArray values(Table table, boolean raw) {
        JsonArray values = new JsonArray();
        for (DataCell cell : table.getData()) {
            values.add(value(cell, raw));
        }
        return values;
    }

    static JsonValue value(DataCell cell, boolean raw) {
        if (cell == null) return Json.NULL;
        if (cell.getStaticText() != null) return Json.value(cell.getStaticText());
        return Js.num(raw ? cell.getBinValue() : cell.getRealValue());
    }

    // The 1D table whose cells an argument addresses: the table itself or one of its axes
    static Table target(Table table, String axis) throws McpException {
        if (axis == null || axis.equalsIgnoreCase("data")) return table;
        if (table instanceof Table3D) {
            if (axis.equalsIgnoreCase("x")) return ((Table3D) table).getXAxis();
            if (axis.equalsIgnoreCase("y")) return ((Table3D) table).getYAxis();
        } else if (table instanceof Table2D && (axis.equalsIgnoreCase("x") || axis.equalsIgnoreCase("y"))) {
            return ((Table2D) table).getAxis();
        }
        throw new McpException(table.getName() + " has no '" + axis + "' axis");
    }

    // x is the column, y the row; tables with one dimension use x (or y) as the index
    static DataCell cell(Table target, int x, int y) throws McpException {
        if (target instanceof Table3D) {
            Table3D t3 = (Table3D) target;
            if (x < 0 || x >= t3.getSizeX() || y < 0 || y >= t3.getSizeY()) {
                throw new McpException("Cell " + x + "," + y + " is outside " + t3.getSizeX() + "x" + t3.getSizeY());
            }
            return t3.get3dData()[x][y];
        }
        int index = Math.max(x, y);
        if (index < 0 || index >= target.getDataSize()) {
            throw new McpException("Cell " + index + " is outside 0.." + (target.getDataSize() - 1));
        }
        return target.getData()[index];
    }

    // Cells inside a rectangle, all cells when the bounds are absent (-1)
    static List<DataCell> cells(Table target, int x0, int x1, int y0, int y1) throws McpException {
        List<DataCell> cells = new ArrayList<DataCell>();
        if (target instanceof Table3D) {
            Table3D t3 = (Table3D) target;
            int maxX = x1 < 0 ? t3.getSizeX() - 1 : x1;
            int maxY = y1 < 0 ? t3.getSizeY() - 1 : y1;
            for (int y = Math.max(0, y0); y <= maxY; y++) {
                for (int x = Math.max(0, x0); x <= maxX; x++) {
                    cells.add(cell(t3, x, y));
                }
            }
        } else {
            int from = Math.max(0, Math.max(x0, y0));
            int to = Math.max(x1, y1) < 0 ? target.getDataSize() - 1 : Math.max(x1, y1);
            for (int i = from; i <= to; i++) {
                cells.add(cell(target, i, 0));
            }
        }
        return cells;
    }

    // Column and row of a cell, or its index for tables with one dimension
    static JsonObject position(Table target, DataCell cell) {
        if (target instanceof Table3D) {
            DataCell[][] data = ((Table3D) target).get3dData();
            for (int x = 0; x < data.length; x++) {
                for (int y = 0; y < data[x].length; y++) {
                    if (data[x][y] == cell) return new JsonObject().add("x", x).add("y", y);
                }
            }
        }
        DataCell[] data = target.getData();
        for (int i = 0; i < data.length; i++) {
            if (data[i] == cell) return new JsonObject().add("x", i);
        }
        return new JsonObject();
    }
}
