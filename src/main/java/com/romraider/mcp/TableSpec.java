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

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.romraider.Settings;
import com.romraider.maps.DataCell;
import com.romraider.maps.Scale;
import com.romraider.maps.Table;
import com.romraider.maps.Table1D;
import com.romraider.maps.Table2D;
import com.romraider.maps.Table3D;
import com.romraider.xml.RomAttributeParser;

/**
 * A table definition in JSON. It is turned into a definition {@code <table>}
 * element, which the regular TableScaleUnmarshaller reads, so tables added
 * over MCP are exactly what the definition file will contain.
 */
final class TableSpec {
    static final String DESCRIPTION =
            "Table definition. name, type (1D, 2D or 3D), address and storage_type "
            + "(uint8, uint16, uint32, int8, int16, int32, float, movi20, movi20s) are required. "
            + "Optional: category ('Parent//Child'), endian (big, little), size (1D/2D cell count), "
            + "size_x and size_y (3D), description, swap_xy, flip_x, flip_y, skip_cells, before_ram, "
            + "user_level, locked, log_param, mask (hex), data_layout (bosch_subtract), "
            + "scaling {units, expression, to_byte, format, fine_increment, coarse_increment, min, max, category, name} "
            + "or scalings [..]. Expressions use x for the raw value. "
            + "Axes: axis (2D), x_axis and y_axis (3D), each {name, address, storage_type, endian, scaling, log_param} "
            + "or {name, values: [..]} for a static axis.";

    private TableSpec() {
    }

    static Element toElement(Document doc, JsonObject spec) throws McpException {
        String type = string(spec, "type", null);
        if (type == null) throw new McpException("Table spec needs a type: 1D, 2D or 3D");
        type = type.toUpperCase();
        if (!type.equals("1D") && !type.equals("2D") && !type.equals("3D")) {
            throw new McpException("Unsupported table type '" + type + "', use 1D, 2D or 3D");
        }

        Element table = doc.createElement("table");
        table.setAttribute("name", required(spec, "name"));
        table.setAttribute("type", type);
        setString(table, "category", spec, "category");
        setStorage(table, spec);

        if (type.equals("3D")) {
            table.setAttribute("sizex", String.valueOf(positive(spec, "size_x")));
            table.setAttribute("sizey", String.valueOf(positive(spec, "size_y")));
            setFlag(table, "swapxy", spec, "swap_xy");
            setFlag(table, "flipx", spec, "flip_x");
            setFlag(table, "flipy", spec, "flip_y");
            if (spec.get("skip_cells") != null) table.setAttribute("skipCells", String.valueOf(spec.get("skip_cells").asInt()));
        } else if (spec.get("size") != null) {
            table.setAttribute("sizey", String.valueOf(positive(spec, "size")));
        } else if (type.equals("2D")) {
            throw new McpException("A 2D table needs a size");
        }

        setFlag(table, "beforeram", spec, "before_ram");
        setFlag(table, "locked", spec, "locked");
        if (spec.get("user_level") != null) table.setAttribute("userlevel", String.valueOf(spec.get("user_level").asInt()));
        setString(table, "logparam", spec, "log_param");
        setString(table, "dataLayout", spec, "data_layout");
        if (spec.get("mask") != null) {
            table.setAttribute("mask", Js.hex(McpCall.parseAddress(spec.get("mask"), "mask")).substring(2));
        }

        String description = string(spec, "description", null);
        if (description != null) {
            Element desc = doc.createElement("description");
            desc.setTextContent(description);
            table.appendChild(desc);
        }

        appendScalings(doc, table, spec);

        if (type.equals("2D")) {
            table.appendChild(axis(doc, object(spec, "axis", true), "Y Axis", spec.get("size").asInt()));
        } else if (type.equals("3D")) {
            table.appendChild(axis(doc, object(spec, "x_axis", true), "X Axis", spec.get("size_x").asInt()));
            table.appendChild(axis(doc, object(spec, "y_axis", true), "Y Axis", spec.get("size_y").asInt()));
        }
        return table;
    }

    private static Element axis(Document doc, JsonObject spec, String type, int size) throws McpException {
        Element axis = doc.createElement("table");
        axis.setAttribute("type", type);
        setString(axis, "name", spec, "name");

        JsonValue values = spec.get("values");
        if (values != null && !values.isNull() && spec.get("address") == null) {
            if (!values.isArray() || values.asArray().size() != size) {
                throw new McpException("Static " + type + " needs " + size + " values");
            }
            axis.setAttribute("type", "Static " + type);
            // Sized here, otherwise the parent resizes the axis and drops the values
            axis.setAttribute(type.startsWith("X") ? "sizex" : "sizey", String.valueOf(size));
            for (JsonValue value : values.asArray()) {
                Element data = doc.createElement("data");
                data.setTextContent(value.isString() ? value.asString() : value.toString());
                axis.appendChild(data);
            }
            return axis;
        }

        setStorage(axis, spec);
        setString(axis, "logparam", spec, "log_param");
        appendScalings(doc, axis, spec);
        return axis;
    }

    private static void setStorage(Element table, JsonObject spec) throws McpException {
        if (spec.get("address") == null) throw new McpException("'" + string(spec, "name", "table") + "' needs an address");
        table.setAttribute("storageaddress", Js.hex(McpCall.parseAddress(spec.get("address"), "address")));

        String storageType = required(spec, "storage_type");
        try {
            RomAttributeParser.parseStorageType(storageType);
        } catch (NumberFormatException e) {
            throw new McpException("Unknown storage_type '" + storageType + "'");
        }
        table.setAttribute("storagetype", storageType);

        String endian = string(spec, "endian", null);
        if (endian != null) {
            if (!endian.equalsIgnoreCase("big") && !endian.equalsIgnoreCase("little")) {
                throw new McpException("endian must be big or little");
            }
            table.setAttribute("endian", endian.toLowerCase());
        }
    }

    private static void appendScalings(Document doc, Element table, JsonObject spec) throws McpException {
        JsonValue scalings = spec.get("scalings");
        if (scalings != null && scalings.isArray()) {
            for (JsonValue scaling : scalings.asArray()) {
                table.appendChild(scaling(doc, scaling.asObject()));
            }
        } else if (spec.get("scaling") != null) {
            table.appendChild(scaling(doc, object(spec, "scaling", true)));
        }
    }

    private static Element scaling(Document doc, JsonObject spec) throws McpException {
        Element scaling = doc.createElement("scaling");
        setString(scaling, "base", spec, "base");
        setString(scaling, "category", spec, "category");
        setString(scaling, "name", spec, "name");
        setString(scaling, "units", spec, "units");
        setString(scaling, "expression", spec, "expression");
        setString(scaling, "to_byte", spec, "to_byte");
        setString(scaling, "format", spec, "format");
        setNumber(scaling, "min", spec, "min");
        setNumber(scaling, "max", spec, "max");
        setNumber(scaling, "fineincrement", spec, "fine_increment");
        setNumber(scaling, "coarseincrement", spec, "coarse_increment");
        return scaling;
    }

    // The spec of an existing table, in the same shape table_add takes
    static JsonObject fromTable(Table table) throws McpException {
        if (table.getType() == Table.TableType.SWITCH) {
            throw new McpException("Switch tables cannot be edited over MCP");
        }

        JsonObject spec = new JsonObject()
                .add("name", table.getName())
                .add("type", TableJson.typeName(table))
                .add("category", table.getCategory());
        storage(spec, table);
        if (table.getDescription() != null && !table.getDescription().isEmpty()) {
            spec.add("description", table.getDescription());
        }

        if (table instanceof Table3D) {
            Table3D t3 = (Table3D) table;
            spec.add("size_x", t3.getSizeX()).add("size_y", t3.getSizeY());
            if (t3.getSwapXY()) spec.add("swap_xy", true);
            if (t3.getFlipX()) spec.add("flip_x", true);
            if (t3.getFlipY()) spec.add("flip_y", true);
            if (t3.getSkipCells() != 0) spec.add("skip_cells", t3.getSkipCells());
            spec.add("x_axis", axisSpec(t3.getXAxis()));
            spec.add("y_axis", axisSpec(t3.getYAxis()));
        } else {
            spec.add("size", table.getDataSize());
            if (table instanceof Table2D) spec.add("axis", axisSpec(((Table2D) table).getAxis()));
        }

        if (table.isBeforeRam()) spec.add("before_ram", true);
        if (table.isLocked()) spec.add("locked", true);
        if (table.getUserLevel() > 1) spec.add("user_level", table.getUserLevel());
        if (table.getBitMask() != 0) spec.add("mask", Js.hex(table.getBitMask()));
        if (table.getDataLayout() == Table.DataLayout.BOSCH_SUBTRACT) spec.add("data_layout", "bosch_subtract");
        return spec;
    }

    private static JsonObject axisSpec(Table1D axis) {
        JsonObject spec = new JsonObject().add("name", axis.getName() == null ? "" : axis.getName());
        if (axis.isStaticDataTable()) {
            JsonArray values = new JsonArray();
            for (DataCell cell : axis.getData()) {
                values.add(cell == null ? "" : cell.getStaticText());
            }
            return spec.add("values", values);
        }
        storage(spec, axis);
        return spec;
    }

    private static void storage(JsonObject spec, Table table) {
        spec.add("address", Js.hex(table.getStorageAddress()))
                .add("storage_type", storageTypeName(table))
                .add("endian", table.getEndian() == Settings.Endian.BIG ? "big" : "little");
        if (table.getLogParam() != null && !table.getLogParam().isEmpty()) spec.add("log_param", table.getLogParam());

        JsonArray scalings = new JsonArray();
        for (Scale scale : table.getScales()) {
            if (scale.getCategory().equalsIgnoreCase("Raw Value")) continue;
            JsonObject json = TableJson.scale(scale)
                    .add("fine_increment", scale.getFineIncrement())
                    .add("coarse_increment", scale.getCoarseIncrement());
            if (scale.getMin() != 0 || scale.getMax() != 0) json.add("min", scale.getMin()).add("max", scale.getMax());
            scalings.add(json);
        }
        if (!scalings.isEmpty()) spec.add("scalings", scalings);
    }


    // Overlays an update on a full spec; a single 'scaling' patches the first scaling
    static JsonObject merge(JsonObject base, JsonObject patch) {
        JsonObject merged = new JsonObject().merge(base);
        for (JsonObject.Member member : patch) {
            String name = member.getName();
            JsonValue value = member.getValue();
            JsonValue current = merged.get(name);

            if (name.equals("scaling") && value.isObject()) {
                JsonValue scalings = merged.get("scalings");
                JsonObject first = scalings != null && scalings.isArray() && !scalings.asArray().isEmpty()
                        ? scalings.asArray().get(0).asObject() : new JsonObject();
                merged.remove("scaling");
                merged.set("scalings", new JsonArray().add(merge(first, value.asObject())));
            } else if (value.isObject() && current != null && current.isObject()) {
                merged.set(name, merge(current.asObject(), value.asObject()));
            } else {
                merged.set(name, value);
            }
        }
        return merged;
    }

    static String storageTypeName(Table table) {
        int type = table.getStorageType();
        if (type == Settings.STORAGE_TYPE_FLOAT) return "float";
        if (type == Settings.STORAGE_TYPE_MOVI20) return "movi20";
        if (type == Settings.STORAGE_TYPE_MOVI20S) return "movi20s";
        return (table.isSignedData() ? "int" : "uint") + (type * 8);
    }

    static Table newTable(Element element) {
        String type = element.getAttribute("type");
        if (type.equalsIgnoreCase("3D")) return new Table3D();
        if (type.equalsIgnoreCase("2D")) return new Table2D();
        return new Table1D();
    }

    private static String string(JsonObject spec, String name, String defaultValue) {
        JsonValue value = spec.get(name);
        if (value == null || value.isNull()) return defaultValue;
        return value.isString() ? value.asString() : value.toString();
    }

    private static String required(JsonObject spec, String name) throws McpException {
        String value = string(spec, name, null);
        if (value == null || value.trim().isEmpty()) throw new McpException("Table spec needs '" + name + "'");
        return value;
    }

    private static int positive(JsonObject spec, String name) throws McpException {
        JsonValue value = spec.get(name);
        if (value == null || !value.isNumber() || value.asInt() < 1) {
            throw new McpException("'" + name + "' must be a positive number");
        }
        return value.asInt();
    }

    private static JsonObject object(JsonObject spec, String name, boolean isRequired) throws McpException {
        JsonValue value = spec.get(name);
        if (value == null || !value.isObject()) {
            if (isRequired) throw new McpException("Table spec needs '" + name + "'");
            return new JsonObject();
        }
        return value.asObject();
    }

    private static void setString(Element element, String attribute, JsonObject spec, String name) {
        String value = string(spec, name, null);
        if (value != null) element.setAttribute(attribute, value);
    }

    private static void setNumber(Element element, String attribute, JsonObject spec, String name) {
        JsonValue value = spec.get(name);
        if (value != null && value.isNumber()) element.setAttribute(attribute, value.toString());
    }

    private static void setFlag(Element element, String attribute, JsonObject spec, String name) {
        JsonValue value = spec.get(name);
        if (value != null && value.isBoolean() && value.asBoolean()) element.setAttribute(attribute, "true");
    }
}
