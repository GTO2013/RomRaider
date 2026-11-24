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

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.romraider.maps.DataCell;
import com.romraider.maps.DataCellView;

/**
 * Marks the cells the ECU read on their table views, fading over SHOW_MS, or
 * kept while hits accumulate, and counts the reads of each data cell.
 * Used on the Swing thread only.
 */
final class HitDisplay {
    static final long SHOW_MS = 1500;

    private final HitMap map;
    private final Map<DataCell, Long> marked = new HashMap<DataCell, Long>();
    private final Map<HitMap.Target, Integer> counts = new LinkedHashMap<HitMap.Target, Integer>();
    private boolean accumulate;
    private long total;

    HitDisplay(HitMap map) {
        this.map = map;
    }

    void hit(int offset, long now) {
        for (HitMap.Target t : map.at(offset)) {
            mark(t.cell, now);
            mark(t.xAxis, now);
            mark(t.yAxis, now);
            Integer n = counts.get(t);
            counts.put(t, n == null ? 1 : n + 1);
            total++;
        }
    }

    void setAccumulate(boolean accumulate, long now) {
        if (this.accumulate && !accumulate) {
            // Kept marks start fading now
            for (Map.Entry<DataCell, Long> e : marked.entrySet()) e.setValue(now);
        }
        this.accumulate = accumulate;
    }

    // Brings the marks on the table views up to date
    void refresh(long now) {
        for (Iterator<Map.Entry<DataCell, Long>> it = marked.entrySet().iterator(); it.hasNext();) {
            Map.Entry<DataCell, Long> e = it.next();
            double age = accumulate ? 0 : (now - e.getValue()) / (double) SHOW_MS;
            show(e.getKey(), age);
            if (age >= 1) it.remove();
        }
    }

    // Removes every mark and count
    void clear() {
        for (DataCell cell : marked.keySet()) show(cell, -1);
        marked.clear();
        counts.clear();
        total = 0;
    }

    Map<HitMap.Target, Integer> getCounts() {
        return counts;
    }

    long getTotal() {
        return total;
    }

    private void mark(DataCell cell, long now) {
        if (cell != null) marked.put(cell, now);
    }

    private static void show(DataCell cell, double age) {
        DataCellView view = cell.getDataView();
        if (view != null) view.setHit(age);
    }
}
