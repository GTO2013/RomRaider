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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.eclipsesource.json.JsonObject;
import com.romraider.maps.DataCell;
import com.romraider.maps.Rom;
import com.romraider.maps.Table;
import com.romraider.maps.Table1D;
import com.romraider.maps.Table2D;
import com.romraider.maps.Table3D;
import com.romraider.xml.ConversionLayer.ConversionLayer;

final class DefinitionTools {
    private DefinitionTools() {
    }

    static void register(ToolRegistry registry) {
        registry.add(new McpTool("table_probe", "Decode an address with a candidate table spec without adding "
                + "anything, to try storage types, endianness, sizes and scalings. Returns converted and raw "
                + "values with plausibility hints.",
                Schema.withRom().object("spec", TableSpec.DESCRIPTION, true)) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                Table table = DefinitionEditor.build(rom, call.getObject("spec"), call);
                try {
                    table.populateTable(rom);
                } catch (IndexOutOfBoundsException e) {
                    throw new McpException("The spec reads past the end of the binary (" + rom.getBinary().length + " bytes)");
                }
                try {
                    JsonObject result = TableJson.read(table, false);
                    result.add("raw_values", TableJson.read(table, true).get("values"));
                    result.add("hints", hints(table));
                    result.remove("modified");
                    return result;
                } finally {
                    rom.unregisterTable(table);
                }
            }
        });

        registry.add(new McpTool("table_add", "Define a new table in the open ROM. It is parsed like a "
                + "definition <table>, appears in the tree and opens in the editor.",
                Schema.withRom().object("spec", TableSpec.DESCRIPTION, true)) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                Table table = DefinitionEditor.add(rom, call.getObject("spec"), call);
                return TableJson.read(table, false);
            }
        });

        registry.add(new McpTool("table_update", "Change a table's definition, e.g. address, storage type, "
                + "size or scaling. spec holds only the fields to change (same format as table_add, nested "
                + "axis objects are merged). The open frame is rebuilt in place, otherwise the table is opened.",
                Schema.withTable().object("spec", "Fields to change. " + TableSpec.DESCRIPTION, true)) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                Table table = DefinitionEditor.update(rom, call.getTable(rom), call.getObject("spec"), call);
                return TableJson.read(table, false);
            }
        });

        registry.add(new McpTool("table_remove", "Remove a table from the ROM's definition and close its frame. "
                + "The binary is not changed.", Schema.withTable()) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                Table table = call.getTable(rom);
                DefinitionEditor.remove(rom, table);
                return new JsonObject().add("removed", table.getName());
            }
        });

        registry.add(new McpTool("definition_export", "Write the ROM's definition including tables added, "
                + "changed or removed over MCP. mode 'full' writes the whole definition document, "
                + "'changes' only the changed <table> elements to paste into the master definition. "
                + "Existing files, including the loaded definition, are only replaced with overwrite.",
                Schema.withRom()
                        .string("path", "Target file", true)
                        .choice("mode", "full (default) or changes", false, "full", "changes")
                        .bool("overwrite", "Allow replacing an existing file")) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                File target = new File(call.getString("path"));
                if (target.exists() && !call.optBoolean("overwrite", false)) {
                    boolean loaded = rom.getDefinitionPath() != null
                            && target.getAbsoluteFile().equals(rom.getDefinitionPath().getAbsoluteFile());
                    throw new McpException(target.getAbsolutePath() + (loaded ? " is the loaded definition" : " exists")
                            + ", pass overwrite to replace it");
                }

                String mode = call.optString("mode", "full");
                if (mode.equals("changes")) {
                    if (DefinitionEditor.changedCount(rom) == 0) throw new McpException("No tables were changed over MCP");
                    RomTools.write(target, DefinitionEditor.exportChanged(rom).getBytes("UTF-8"));
                } else {
                    RomTools.write(target, ConversionLayer.convertDocumentToString(rom.getDocument()).getBytes("UTF-8"));
                }
                return new JsonObject().add("written", target.getAbsolutePath()).add("mode", mode)
                        .add("changed_tables", DefinitionEditor.changedCount(rom));
            }
        });
    }

    // Signals that tell a real table from code or unrelated data
    private static JsonObject hints(Table table) {
        JsonObject hints = new JsonObject();
        if (table instanceof Table3D) {
            hints.add("x_axis", order(((Table3D) table).getXAxis()));
            hints.add("y_axis", order(((Table3D) table).getYAxis()));
        } else if (table instanceof Table2D) {
            hints.add("axis", order(((Table2D) table).getAxis()));
        }

        List<DataCell> cells = dataCells(table);
        if (cells.isEmpty()) return hints;

        double[] values = new double[cells.size()];
        int extremes = 0;
        for (int i = 0; i < values.length; i++) {
            DataCell cell = cells.get(i);
            values[i] = cell.getRealValue();
            if (cell.getBinValue() == 0 || cell.getBinValue() == cell.getMaxAllowedBin()) extremes++;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return hints.add("min", Js.num(sorted[0])).add("max", Js.num(sorted[sorted.length - 1]))
                .add("zero_or_max_raw_share", Js.num(extremes / (double) values.length))
                .add("roughness", Js.num(Math.max(0, roughness(values))));
    }

    private static List<DataCell> dataCells(Table table) {
        if (table instanceof Table3D) {
            List<DataCell> cells = new ArrayList<DataCell>();
            for (DataCell[] column : ((Table3D) table).get3dData()) {
                for (DataCell cell : column) {
                    cells.add(cell);
                }
            }
            return cells;
        }
        return Arrays.asList(table.getData());
    }

    private static String order(Table1D axis) {
        if (axis.isStaticDataTable()) return "static";
        DataCell[] cells = axis.getData();
        boolean rising = true;
        boolean falling = true;
        for (int i = 1; i < cells.length; i++) {
            if (cells[i].getRealValue() <= cells[i - 1].getRealValue()) rising = false;
            if (cells[i].getRealValue() >= cells[i - 1].getRealValue()) falling = false;
        }
        return rising ? "rising" : falling ? "falling" : "not monotonic";
    }

    // Mean step between neighbours relative to the value range: near 0 is smooth, near 1 is noise, -1 if constant
    private static double roughness(double[] values) {
        double min = values[0];
        double max = values[0];
        double steps = 0;
        for (int i = 1; i < values.length; i++) {
            min = Math.min(min, values[i]);
            max = Math.max(max, values[i]);
            steps += Math.abs(values[i] - values[i - 1]);
        }
        if (max == min) return -1;
        return steps / (values.length - 1) / (max - min);
    }
}
