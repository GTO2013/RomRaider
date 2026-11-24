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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.romraider.emulator.EmulatorController;
import com.romraider.emulator.EmulatorSession;
import com.romraider.emulator.HitMap;
import com.romraider.maps.DataCell;
import com.romraider.maps.Table;

/**
 * The ROM emulator connected in the editor (Emulator menu): its state, its
 * switches, and the table cells the ECU read according to its address hit
 * trace.  Edits made with the other tools reach the emulator by themselves
 * while Live Updates is on.
 */
final class EmulatorTools {
    private EmulatorTools() {
    }

    static void register(ToolRegistry registry) {
        registry.add(new McpTool("emulator_status", "State of the ROM emulator connected in the editor: device, "
                + "linked image, whether edits go out live, the address hit trace and its hit rate.",
                new Schema()) {
            @Override
            JsonObject run(McpCall call) {
                return status();
            }
        });

        registry.add(new McpTool("emulator_set", "Switch the emulator's Live Updates (edits go to the ECU within "
                + "about 0.1 s, checksums included), Address Hit Trace (shows which cells the ECU reads), "
                + "Accumulate Hits (keep the marks instead of fading them), or clear the hits.",
                new Schema()
                        .bool("live", "Send edits to the emulator as they are made")
                        .bool("trace", "Trace the ECU's reads of the open tables, or of all tables when none is open")
                        .bool("accumulate", "Keep hit marks until cleared")
                        .bool("clear_hits", "Forget the hits counted so far")) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                EmulatorController c = EmulatorController.getInstance();
                if (call.has("live")) c.setLive(call.optBoolean("live", true));
                if (call.has("trace")) c.setTrace(call.optBoolean("trace", false));
                if (call.has("accumulate")) c.setAccumulate(call.optBoolean("accumulate", false));
                if (call.optBoolean("clear_hits", false)) c.clearHits();
                return status();
            }
        });

        registry.add(new McpTool("emulator_hits", "The cells the ECU read since the hits were cleared, by the "
                + "emulator's address hit trace, most read first. 3D cells give x/y with their axis values, 1D and "
                + "2D cells their index. The trace samples the reads, so counts show where the engine runs, not "
                + "exact read counts.",
                new Schema()
                        .string("table", "Only this table", false)
                        .integer("limit", "Cells per table, default 20", false)) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                EmulatorSession s = EmulatorController.getInstance().getSession();
                if (s == null) throw new McpException("No emulator is linked to an image (Emulator > Connect)");
                String only = call.optString("table", null);
                int limit = call.optInt("limit", 20);

                Map<Table, List<Map.Entry<HitMap.Target, Integer>>> byTable =
                        new LinkedHashMap<Table, List<Map.Entry<HitMap.Target, Integer>>>();
                final Map<Table, Integer> totals = new LinkedHashMap<Table, Integer>();
                for (Map.Entry<HitMap.Target, Integer> e : s.getHitCounts().entrySet()) {
                    Table t = e.getKey().table;
                    if (only != null && !only.equalsIgnoreCase(t.getName())) continue;
                    if (!byTable.containsKey(t)) {
                        byTable.put(t, new ArrayList<Map.Entry<HitMap.Target, Integer>>());
                        totals.put(t, 0);
                    }
                    byTable.get(t).add(e);
                    totals.put(t, totals.get(t) + e.getValue());
                }

                List<Table> tables = new ArrayList<Table>(byTable.keySet());
                Collections.sort(tables, new Comparator<Table>() {
                    @Override
                    public int compare(Table a, Table b) {
                        return totals.get(b) - totals.get(a);
                    }
                });
                JsonArray out = new JsonArray();
                for (Table t : tables) {
                    List<Map.Entry<HitMap.Target, Integer>> cells = byTable.get(t);
                    Collections.sort(cells, new Comparator<Map.Entry<HitMap.Target, Integer>>() {
                        @Override
                        public int compare(Map.Entry<HitMap.Target, Integer> a, Map.Entry<HitMap.Target, Integer> b) {
                            return b.getValue() - a.getValue();
                        }
                    });
                    JsonArray list = new JsonArray();
                    for (int i = 0; i < cells.size() && i < limit; i++) list.add(cell(cells.get(i)));
                    out.add(new JsonObject().add("table", t.getName()).add("hits", totals.get(t))
                            .add("cells", list));
                }
                return status().add("tables", out);
            }
        });
    }

    private static JsonObject cell(Map.Entry<HitMap.Target, Integer> e) {
        HitMap.Target t = e.getKey();
        JsonObject o = new JsonObject();
        if (t.x >= 0) {
            o.add("x", t.x).add("y", t.y);
            if (t.xAxis != null) o.add("x_axis", value(t.xAxis));
            if (t.yAxis != null) o.add("y_axis", value(t.yAxis));
        } else {
            o.add("index", t.y);
            if (t.yAxis != null) o.add("axis", value(t.yAxis));
        }
        return o.add("value", value(t.cell)).add("hits", e.getValue());
    }

    private static String value(DataCell cell) {
        if (cell.getTable().isStaticDataTable()) return cell.getStaticText();
        return Js.num(cell.getRealValue()).toString();
    }

    private static JsonObject status() {
        EmulatorController c = EmulatorController.getInstance();
        JsonObject o = new JsonObject()
                .add("connected", c.isConnected())
                .add("busy", c.isBusy())
                .add("status", c.getStatusText())
                .add("live", c.isLive())
                .add("trace", c.isTrace())
                .add("accumulate", c.isAccumulate());
        if (c.isConnected()) {
            o.add("device", c.getDriver().getDescription()).add("port", c.getPort());
        }
        EmulatorSession s = c.getSession();
        if (s != null) {
            o.add("image", s.getRom().getFileName())
                    .add("bytes_sent", s.getBytesSent())
                    .add("hits_total", s.getHitTotal())
                    .add("hits_per_second", Math.round(c.getHitRate()));
            if (s.getTraceProblem() != null) o.add("trace_problem", s.getTraceProblem());
        }
        return o;
    }
}
