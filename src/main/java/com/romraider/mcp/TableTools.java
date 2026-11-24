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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JInternalFrame;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.romraider.Settings;
import com.romraider.editor.ecu.ECUEditor;
import com.romraider.editor.ecu.ECUEditorManager;
import com.romraider.maps.DataCell;
import com.romraider.maps.PresetManager;
import com.romraider.maps.PresetManager.PresetEntry;
import com.romraider.maps.Rom;
import com.romraider.maps.Scale;
import com.romraider.maps.Table;
import com.romraider.maps.Table2D;
import com.romraider.maps.Table3D;
import com.romraider.maps.UserLevelException;
import com.romraider.swing.TableFrame;
import com.romraider.util.JEPUtil;

final class TableTools {
    private static final int MAX_LISTED = 500;
    private static final String AXIS = "Cells of 'data' (default) or of the 'x' or 'y' axis";
    private static final String RANGE = "Optional bounds x0, x1, y0, y1 (inclusive cell indexes), "
            + "the whole table when absent. Tables with one dimension use x.";

    private TableTools() {
    }

    static void register(ToolRegistry registry) {
        registry.add(new McpTool("tables_list", "List tables of a ROM with type, category and address.",
                Schema.withRom()
                        .string("filter", "Only names containing this text", false)
                        .string("category", "Only this category or its subcategories", false)
                        .bool("modified_only", "Only tables changed since load or the revert point")) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                String filter = call.optString("filter", "").toLowerCase();
                String category = call.optString("category", null);
                boolean modifiedOnly = call.optBoolean("modified_only", false);

                JsonArray tables = new JsonArray();
                int matches = 0;
                for (Table table : rom.getTables()) {
                    if (!table.getName().toLowerCase().contains(filter)) continue;
                    if (category != null && !table.getCategory().toLowerCase().startsWith(category.toLowerCase())) continue;
                    if (modifiedOnly && !table.isModified()) continue;
                    if (matches++ < MAX_LISTED) tables.add(TableJson.summary(table));
                }
                JsonObject result = new JsonObject().add("count", matches).add("tables", tables);
                if (matches > MAX_LISTED) result.add("truncated", true);
                return result;
            }
        });

        registry.add(new McpTool("table_info", "Definition of a table in table_add's spec format, "
                + "plus its scales, sizes, lock and modified state.", Schema.withTable()) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                Table table = call.getTable(rom);
                JsonObject info = TableJson.summary(table)
                        .add("file_offset", Js.hex(table.getStorageAddress() - table.getRamOffset()))
                        .add("locked", table.isLocked())
                        .add("user_level", table.getUserLevel())
                        .add("open", McpView.isOpen(table));
                if (table.getType() != Table.TableType.SWITCH) info.add("spec", TableSpec.fromTable(table));

                JsonArray scales = new JsonArray();
                for (Scale scale : table.getScales()) {
                    scales.add(TableJson.scale(scale).add("current", scale == table.getCurrentScale()));
                }
                info.add("scales", scales);
                return info;
            }
        });

        registry.add(new McpTool("table_read", "Read a table's converted values with its axes. "
                + "3D values are rows: values[y][x].",
                Schema.withTable().bool("raw", "Return stored (bin) values instead of converted ones")) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                return TableJson.read(call.getTable(rom), call.optBoolean("raw", false));
            }
        });

        registry.add(new McpTool("table_query", "Interpolate a 2D or 3D table at axis values, like the ECU lookup.",
                Schema.withTable()
                        .number("x", "X axis value (the axis value for 2D tables)", true)
                        .number("y", "Y axis value for 3D tables", false)) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                Table table = call.getTable(rom);
                if (!(table instanceof Table2D) && !(table instanceof Table3D)) {
                    throw new McpException("Only 2D and 3D tables can be queried");
                }
                Double y = call.has("y") ? call.getDouble("y") : null;
                if (table instanceof Table3D && y == null) throw new McpException("3D tables need y");
                return new JsonObject().add("value", Js.num(table.queryTable(call.getDouble("x"), y)));
            }
        });

        registry.add(new McpTool("table_write", "Write cell values. The table is opened in the editor and "
                + "the written cells are selected. Pass values (whole table, 3D as rows values[y][x], "
                + "null keeps a cell) or cells [{x, y, value}].",
                Schema.withTable()
                        .choice("axis", AXIS, false, "data", "x", "y")
                        .array("values", "All values of the target", false)
                        .array("cells", "Individual cells: [{x, y, value}]", false)
                        .bool("raw", "Values are stored (bin) values instead of converted ones")) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                Table table = call.getTable(rom);
                Table target = TableJson.target(table, call.optString("axis", null));
                Map<DataCell, Double> assignments = assignments(call, target);
                return write(call, table, target, assignments, call.optBoolean("raw", false));
            }
        });

        registry.add(new McpTool("table_edit", "Change a range of cells: add, multiply or set a value, "
                + "or interpolate between the range's edges (interpolate, interpolate_x, interpolate_y). "
                + "The table is opened and the range selected.",
                Schema.withTable()
                        .choice("op", "Operation", true, "add", "multiply", "set",
                                "interpolate", "interpolate_x", "interpolate_y")
                        .number("value", "Operand of add, multiply and set", false)
                        .choice("axis", AXIS, false, "data", "x", "y")
                        .integer("x0", RANGE, false).integer("x1", RANGE, false)
                        .integer("y0", RANGE, false).integer("y1", RANGE, false)
                        .bool("raw", "add, multiply and set work on stored (bin) values")) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                Table table = call.getTable(rom);
                Table target = TableJson.target(table, call.optString("axis", null));
                List<DataCell> range = range(call, target);
                String op = call.getString("op");
                boolean raw = call.optBoolean("raw", false);

                if (op.startsWith("interpolate")) {
                    return interpolate(table, target, range, op);
                }

                double operand = call.getDouble("value");
                Map<DataCell, Double> assignments = new LinkedHashMap<DataCell, Double>();
                for (DataCell cell : range) {
                    double current = raw ? cell.getBinValue() : cell.getRealValue();
                    double value = op.equals("add") ? current + operand
                            : op.equals("multiply") ? current * operand : operand;
                    assignments.put(cell, value);
                }
                return write(call, table, target, assignments, raw);
            }
        });

        registry.add(new McpTool("table_revert", "Undo changes since load or the last revert point, "
                + "for the whole table (with axes) or a range of the target. The table is opened.",
                Schema.withTable()
                        .choice("axis", AXIS, false, "data", "x", "y")
                        .integer("x0", RANGE, false).integer("x1", RANGE, false)
                        .integer("y0", RANGE, false).integer("y1", RANGE, false)) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                Table table = call.getTable(rom);
                List<DataCell> cells;
                boolean whole = !call.has("axis") && !call.has("x0") && !call.has("x1")
                        && !call.has("y0") && !call.has("y1");
                cells = whole ? table.getAllCells() : range(call, TableJson.target(table, call.optString("axis", null)));

                List<DataCell> reverted = new ArrayList<DataCell>();
                for (DataCell cell : cells) {
                    if (!cell.isModified()) continue;
                    cell.undo();
                    reverted.add(cell);
                }
                McpView.reveal(table, reverted);
                return new JsonObject().add("reverted_cells", reverted.size());
            }
        });

        registry.add(new McpTool("table_set_revert_point", "Make the current values the baseline that "
                + "modified state and table_revert compare against. The table is opened.", Schema.withTable()) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                Table table = call.getTable(rom);
                table.setRevertPoint();
                McpView.reveal(table);
                return new JsonObject().add("table", table.getName());
            }
        });

        registry.add(new McpTool("table_modified", "Cells that differ from load or the revert point, "
                + "with original and current converted values.", Schema.withTable()) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                Table table = call.getTable(rom);
                JsonArray cells = new JsonArray();
                int count = 0;
                for (DataCell cell : table.getAllCells()) {
                    if (!cell.isModified()) continue;
                    if (count++ >= MAX_LISTED) continue;
                    Table owner = cell.getTable();
                    JsonObject entry = TableJson.position(owner, cell);
                    if (owner != table) entry.add("axis", axisName(table, owner));
                    entry.add("original", Js.num(toReal(owner, cell.getOriginalValue())))
                            .add("current", Js.num(cell.getRealValue()));
                    cells.add(entry);
                }
                return new JsonObject().add("table", table.getName())
                        .add("count", count).add("cells", cells);
            }
        });

        registry.add(new McpTool("table_open", "Open a table in the editor, or bring its frame to the front.",
                Schema.withTable()) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                Table table = call.getTable(rom);
                McpView.reveal(table);
                return new JsonObject().add("opened", table.getName());
            }
        });

        registry.add(new McpTool("table_switch", "Set a switch table to one of its states (see table_read). "
                + "The table is opened.",
                Schema.withTable().string("state", "State name", true)) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                Table table = call.getTable(rom);
                String state = call.getString("state");
                PresetManager presets = table.getPresetManager();
                if (presets == null) throw new McpException(table.getName() + " has no states");

                for (PresetEntry entry : presets.getPresets()) {
                    if (entry.getName().equalsIgnoreCase(state)) {
                        presets.applyPreset(entry);
                        McpView.reveal(table);
                        if (!presets.isPresetActive(entry)) {
                            throw new McpException("State was not applied, the table is locked or above the user level");
                        }
                        return TableJson.read(table, true);
                    }
                }
                throw new McpException(table.getName() + " has no state '" + state + "'");
            }
        });

        registry.add(new McpTool("editor_selection", "What the user has selected in the editor: "
                + "the selected ROM, the active table frame and its selected cells.", new Schema()) {
            @Override
            JsonObject run(McpCall call) {
                ECUEditor editor = ECUEditorManager.getECUEditor();
                JsonObject result = new JsonObject();
                if (editor.getLastSelectedRom() != null) result.add("rom", editor.getLastSelectedRom().getFileName());

                JInternalFrame frame = editor.getRightPanel().getSelectedFrame();
                if (!(frame instanceof TableFrame) || ((TableFrame) frame).getTable() == null) return result;

                Table table = ((TableFrame) frame).getTable();
                result.add("table", table.getName()).add("table_rom", table.getRom().getFileName());
                JsonArray cells = new JsonArray();
                for (DataCell cell : table.getAllCells()) {
                    if (!cell.isSelected()) continue;
                    Table owner = cell.getTable();
                    JsonObject entry = TableJson.position(owner, cell);
                    if (owner != table) entry.add("axis", axisName(table, owner));
                    entry.add("value", TableJson.value(cell, false)).add("raw", Js.num(cell.getBinValue()));
                    cells.add(entry);
                }
                return result.add("selected_cells", cells);
            }
        });
    }

    private static Map<DataCell, Double> assignments(McpCall call, Table target) throws McpException {
        Map<DataCell, Double> assignments = new LinkedHashMap<DataCell, Double>();
        if (call.has("values")) {
            JsonValue values = call.get("values");
            if (!values.isArray()) throw new McpException("'values' must be an array");
            if (target instanceof Table3D) {
                Table3D t3 = (Table3D) target;
                if (values.asArray().size() != t3.getSizeY()) {
                    throw new McpException("'values' needs " + t3.getSizeY() + " rows of " + t3.getSizeX());
                }
                for (int y = 0; y < t3.getSizeY(); y++) {
                    JsonValue row = values.asArray().get(y);
                    if (!row.isArray() || row.asArray().size() != t3.getSizeX()) {
                        throw new McpException("Row " + y + " needs " + t3.getSizeX() + " values");
                    }
                    for (int x = 0; x < t3.getSizeX(); x++) {
                        put(assignments, TableJson.cell(t3, x, y), row.asArray().get(x));
                    }
                }
            } else {
                if (values.asArray().size() != target.getDataSize()) {
                    throw new McpException("'values' needs " + target.getDataSize() + " values");
                }
                for (int i = 0; i < target.getDataSize(); i++) {
                    put(assignments, TableJson.cell(target, i, 0), values.asArray().get(i));
                }
            }
        }
        if (call.has("cells")) {
            JsonValue cells = call.get("cells");
            if (!cells.isArray()) throw new McpException("'cells' must be an array");
            for (JsonValue entry : cells.asArray()) {
                if (!entry.isObject()) throw new McpException("Each cell must be {x, y, value}");
                JsonObject cell = entry.asObject();
                put(assignments, TableJson.cell(target, cell.getInt("x", 0), cell.getInt("y", 0)), cell.get("value"));
            }
        }
        if (assignments.isEmpty()) throw new McpException("Pass values or cells");
        return assignments;
    }

    private static void put(Map<DataCell, Double> assignments, DataCell cell, JsonValue value) throws McpException {
        if (value == null || value.isNull()) return;
        if (!value.isNumber()) throw new McpException("Cell values must be numbers");
        if (cell.getStaticText() != null) throw new McpException("Static axis cells cannot be written");
        assignments.put(cell, value.asDouble());
    }

    private static List<DataCell> range(McpCall call, Table target) throws McpException {
        return TableJson.cells(target, call.optInt("x0", 0), call.optInt("x1", -1),
                call.optInt("y0", 0), call.optInt("y1", -1));
    }

    // Writes, then reports cells whose stored value did not become what was asked
    private static JsonObject write(McpCall call, Table table, Table target,
            Map<DataCell, Double> assignments, boolean raw) throws McpException {
        List<DataCell> changed = new ArrayList<DataCell>();
        JsonArray problems = new JsonArray();
        int unchanged = 0;

        for (Map.Entry<DataCell, Double> assignment : assignments.entrySet()) {
            DataCell cell = assignment.getKey();
            double before = cell.getBinValue();
            double wanted = raw ? assignment.getValue() : cell.toBinValue(assignment.getValue());
            String reason = null;
            try {
                cell.setBinValue(wanted);
            } catch (UserLevelException e) {
                reason = "table user level " + target.getUserLevel() + " is above the editor's user level";
            }

            if (cell.getBinValue() != before) changed.add(cell);
            else if (sameStored(target, before, wanted)) unchanged++;

            if (!sameStored(target, cell.getBinValue(), wanted)) {
                if (reason == null) reason = target.isLocked() ? "table is locked"
                        : table.getName().contains("Checksum Fix") ? "checksum fix tables are not written"
                        : "clamped to the storage range " + Js.num(cell.getMinAllowedBin()) + ".." + Js.num(cell.getMaxAllowedBin());
                if (problems.size() < 50) {
                    problems.add(TableJson.position(target, cell)
                            .add("requested", Js.num(assignment.getValue()))
                            .add("result", TableJson.value(cell, raw))
                            .add("reason", reason));
                }
            }
        }

        McpView.reveal(table, changed);

        JsonObject result = new JsonObject().add("table", table.getName())
                .add("changed_cells", changed.size()).add("unchanged_cells", unchanged);
        if (!problems.isEmpty()) {
            result.add("not_applied", problems);
            call.warn("Some values were not stored as requested, see not_applied");
        }
        return result;
    }

    private static boolean sameStored(Table target, double a, double b) {
        if (target.getStorageType() == Settings.STORAGE_TYPE_FLOAT) return (float) a == (float) b;
        return a == b;
    }

    private static JsonObject interpolate(Table table, Table target, List<DataCell> range, String op)
            throws UserLevelException {
        Map<DataCell, Double> before = new LinkedHashMap<DataCell, Double>();
        for (DataCell cell : target.getAllCells()) {
            before.put(cell, cell.getBinValue());
        }

        target.clearSelection();
        for (DataCell cell : range) {
            cell.setSelected(true);
        }
        if (op.equals("interpolate_x")) target.horizontalInterpolate();
        else if (op.equals("interpolate_y")) target.verticalInterpolate();
        else target.interpolate();

        int changed = 0;
        for (Map.Entry<DataCell, Double> entry : before.entrySet()) {
            if (entry.getKey().getBinValue() != entry.getValue()) changed++;
        }
        McpView.reveal(table, range);
        return new JsonObject().add("table", table.getName()).add("changed_cells", changed);
    }

    private static double toReal(Table owner, double binValue) {
        Scale scale = owner.getCurrentScale();
        return scale == null ? binValue : JEPUtil.evaluate(scale.getExpression(), binValue);
    }

    private static String axisName(Table table, Table axis) {
        if (table instanceof Table3D) return ((Table3D) table).getXAxis() == axis ? "x" : "y";
        return "x";
    }
}
