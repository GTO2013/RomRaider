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

package com.romraider.emulator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.romraider.maps.DataCell;
import com.romraider.maps.Rom;
import com.romraider.maps.Table;
import com.romraider.maps.Table1D;
import com.romraider.maps.Table2D;
import com.romraider.maps.Table3D;
import com.romraider.maps.TableSwitch;

/**
 * Which table cells each byte of an image belongs to, so an ECU read can be
 * shown on the cell it read.
 *
 * Only data cells are mapped, not switches.  An axis lights up with the data cells read in
 * its row or column: looking up a map reads through its axes to find the
 * place, so reads of axis bytes don't show where the engine runs.
 */
public final class HitMap {
    // A data cell and the axis cells of its row and column
    public static final class Target {
        public final Table table;
        public final DataCell cell;
        // Column and row in a 3D table; x is -1 and y the index in 1D and 2D ones
        public final int x;
        public final int y;
        public final DataCell xAxis;
        public final DataCell yAxis;

        Target(Table table, DataCell cell, int x, int y, DataCell xAxis, DataCell yAxis) {
            this.table = table;
            this.cell = cell;
            this.x = x;
            this.y = y;
            this.xAxis = xAxis;
            this.yAxis = yAxis;
        }
    }

    private final int size;
    private final Map<Integer, List<Target>> targets = new HashMap<Integer, List<Target>>();
    // First and last data byte of each table
    private final Map<Table, int[]> spans = new LinkedHashMap<Table, int[]>();

    HitMap(Rom rom) {
        size = rom.getBinary().length;
        for (Table t : rom.findTables(".*")) add(t);
    }

    List<Target> at(int offset) {
        List<Target> list = targets.get(offset);
        return list == null ? Collections.<Target>emptyList() : list;
    }

    // From the first to the last data byte of the tables, null if none are mapped
    int[] window(Collection<Table> tables) {
        int[] w = null;
        for (Table t : tables) {
            int[] s = spans.get(t);
            if (s == null) continue;
            if (w == null) {
                w = new int[] {s[0], s[1]};
            } else {
                w[0] = Math.min(w[0], s[0]);
                w[1] = Math.max(w[1], s[1]);
            }
        }
        return w;
    }

    int[] window() {
        return window(spans.keySet());
    }

    private void add(Table t) {
        // A switch's bytes are often code, which the CPU fetches all the time
        if (t.isStaticDataTable() || t instanceof TableSwitch) return;
        if (t instanceof Table3D) {
            Table3D m = (Table3D) t;
            DataCell[][] data = m.get3dData();
            DataCell[] xs = m.getXAxis().getData();
            DataCell[] ys = m.getYAxis().getData();
            for (int x = 0; x < data.length; x++) {
                for (int y = 0; y < data[x].length; y++) {
                    put(new Target(t, data[x][y], x, y, cell(xs, x), cell(ys, y)));
                }
            }
        } else if (t instanceof Table2D) {
            DataCell[] data = t.getData();
            DataCell[] axis = ((Table2D) t).getAxis().getData();
            for (int i = 0; i < data.length; i++) put(new Target(t, data[i], -1, i, null, cell(axis, i)));
        } else if (t instanceof Table1D && ((Table1D) t).getAxisParent() == null) {
            DataCell[] data = t.getData();
            for (int i = 0; i < data.length; i++) put(new Target(t, data[i], -1, i, null, null));
        }
    }

    private static DataCell cell(DataCell[] cells, int i) {
        return cells != null && i < cells.length ? cells[i] : null;
    }

    private void put(Target target) {
        if (target.cell == null) return;
        int start = DataCell.getMemoryStartAddress(target.cell);
        int len = Math.max(1, target.table.getStorageByteLength());
        if (start < 0 || start + len > size) return;
        for (int i = start; i < start + len; i++) {
            List<Target> list = targets.get(i);
            if (list == null) {
                list = new ArrayList<Target>(1);
                targets.put(i, list);
            }
            list.add(target);
        }
        int[] s = spans.get(target.table);
        if (s == null) {
            spans.put(target.table, new int[] {start, start + len - 1});
        } else {
            s[0] = Math.min(s[0], start);
            s[1] = Math.max(s[1], start + len - 1);
        }
    }
}
