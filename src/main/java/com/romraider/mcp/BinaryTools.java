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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.romraider.maps.DataCell;
import com.romraider.maps.Rom;
import com.romraider.maps.Table;
import com.romraider.maps.Table1D;
import com.romraider.util.HexUtil;

/**
 * Byte level access that needs the open image or its definition. Reading and
 * searching the bin itself is left to the client. Addresses are definition
 * addresses, the file offset is the address minus the rom's ram offset.
 */
final class BinaryTools {
    private BinaryTools() {
    }

    static void register(ToolRegistry registry) {
        registry.add(new McpTool("bytes_write", "Patch raw bytes of the open image. Every table covering the "
                + "bytes is refreshed, opened and the changed cells selected.",
                Schema.withRom()
                        .address("address", "Start address", true)
                        .string("hex", "Bytes to write, e.g. '0A FF 12'", true)) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                int address = call.getAddress("address");
                byte[] bytes;
                try {
                    bytes = HexUtil.asBytes(call.getString("hex"));
                } catch (RuntimeException e) {
                    throw new McpException("Not hex bytes: " + call.getString("hex"));
                }
                int offset = address - rom.getRomID().getRamOffset();
                if (offset < 0 || offset + bytes.length > rom.getBinary().length) {
                    throw new McpException(Js.hex(address) + " is outside the image (file offset " + Js.hex(offset)
                            + ", size " + Js.hex(rom.getBinary().length) + ")");
                }

                String before = HexUtil.bytesToHex(rom.getBinary(), offset, bytes.length).toUpperCase();
                System.arraycopy(bytes, 0, rom.getBinary(), offset, bytes.length);

                Map<Table, List<DataCell>> touched = new LinkedHashMap<Table, List<DataCell>>();
                for (DataCell cell : cellsOverlapping(rom, offset, bytes.length)) {
                    double old = cell.getBinValue();
                    cell.updateBinValueFromMemory();
                    if (cell.getBinValue() == old) continue;

                    Table shown = shownTable(cell.getTable());
                    if (!touched.containsKey(shown)) touched.put(shown, new ArrayList<DataCell>());
                    touched.get(shown).add(cell);
                }

                JsonArray tables = new JsonArray();
                for (Map.Entry<Table, List<DataCell>> entry : touched.entrySet()) {
                    McpView.reveal(entry.getKey(), entry.getValue());
                    tables.add(entry.getKey().getName());
                }
                if (touched.isEmpty()) call.warn("No table covers these bytes, the change is not shown in any frame");
                return new JsonObject().add("address", Js.hex(address))
                        .add("before", before).add("after", HexUtil.asHex(bytes)).add("tables", tables);
            }
        });

        registry.add(new McpTool("unmapped_regions", "Byte ranges no table of the definition covers.",
                Schema.withRom()
                        .address("start", "First address", false)
                        .address("end", "Address to stop at", false)
                        .integer("min_length", "Shortest gap to report, default 16", false)
                        .integer("limit", "Most ranges, default 100", false)) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                int ramOffset = rom.getRomID().getRamOffset();
                boolean[] covered = coverage(rom);
                int from = call.has("start") ? Math.max(0, call.getAddress("start") - ramOffset) : 0;
                int to = call.has("end") ? Math.min(covered.length, call.getAddress("end") - ramOffset) : covered.length;
                int minLength = call.optInt("min_length", 16);
                int limit = call.optInt("limit", 100);

                JsonArray regions = new JsonArray();
                int count = 0;
                int i = from;
                while (i < to) {
                    int start = i;
                    while (i < to && !covered[i]) i++;
                    if (i - start >= minLength && count++ < limit) {
                        regions.add(new JsonObject().add("address", Js.hex(start + ramOffset)).add("length", i - start));
                    }
                    i++;
                }
                JsonObject result = new JsonObject().add("count", count).add("regions", regions);
                if (count > limit) result.add("truncated", true);
                return result;
            }
        });
    }

    // Cells of loaded tables whose bytes overlap the range, from the rom's byte to cell mapping
    private static Set<DataCell> cellsOverlapping(Rom rom, int offset, int length) {
        Set<DataCell> cells = new LinkedHashSet<DataCell>();
        for (int start = Math.max(0, offset - 3); start < offset + length; start++) {
            for (DataCell cell : rom.getCellsAt(start)) {
                if (start + cell.getTable().getStorageByteLength() > offset) cells.add(cell);
            }
        }
        return cells;
    }

    // The table in the tree a cell belongs to, the parent for axis cells
    private static Table shownTable(Table table) {
        if (table instanceof Table1D && ((Table1D) table).getAxisParent() != null) {
            return ((Table1D) table).getAxisParent();
        }
        return table;
    }

    private static boolean[] coverage(Rom rom) {
        boolean[] covered = new boolean[rom.getBinary().length];
        for (int start = 0; start < covered.length; start++) {
            for (DataCell cell : rom.getCellsAt(start)) {
                Arrays.fill(covered, start, Math.min(covered.length, start + cell.getTable().getStorageByteLength()), true);
            }
        }
        return covered;
    }
}
