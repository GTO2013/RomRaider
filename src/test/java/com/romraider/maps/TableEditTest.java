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

package com.romraider.maps;

import static com.romraider.maps.RomFixture.select;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

// Edits on tables: selections, revert, interpolation, 3D layout and lookups
public class TableEditTest {
    private static final String X = "<scaling expression=\"x\" to_byte=\"x\" format=\"#\" coarseincrement=\"1\"/>";
    private static final String CURVE =
            "<table name=\"Curve\" type=\"2D\" category=\"Fuel\" storageaddress=\"0x44\" storagetype=\"uint8\" sizey=\"4\">" + X
            + "<table type=\"Y Axis\" name=\"Rpm\" storageaddress=\"0x40\" storagetype=\"uint8\">" + X + "</table></table>";

    private byte[] bin;
    private Rom rom;

    @Before
    public void load() throws Exception {
        bin = RomFixture.image(128);
        put(0x40, 0, 10, 20, 40);
        put(0x44, 0, 10, 30, 40);
        // 3x3 map: x axis, y axis, then rows
        put(0x50, 0, 10, 20);
        put(0x53, 0, 100, 250);
        put(0x56, 0, 99, 40, 99, 5, 6, 50, 7, 8);
        rom = RomFixture.load(RomFixture.rom("EDIT", 128, CURVE + map("Map", "")), bin);
    }

    private static String map(String name, String attributes) {
        return "<table name=\"" + name + "\" type=\"3D\" category=\"Ignition\" storageaddress=\"0x56\" storagetype=\"uint8\" "
                + "sizex=\"3\" sizey=\"3\" " + attributes + ">" + X
                + "<table type=\"X Axis\" name=\"Rpm\" storageaddress=\"0x50\" storagetype=\"uint8\">" + X + "</table>"
                + "<table type=\"Y Axis\" name=\"Load\" storageaddress=\"0x53\" storagetype=\"uint8\">" + X + "</table></table>";
    }

    private void put(int address, int... values) {
        for (int i = 0; i < values.length; i++) {
            bin[address + i] = (byte) values[i];
        }
    }

    private Table2D curve() {
        return (Table2D) rom.getTableByName("Curve");
    }

    private Table3D map() {
        return (Table3D) rom.getTableByName("Map");
    }

    private static double[] values(DataCell[] cells) {
        double[] values = new double[cells.length];
        for (int i = 0; i < cells.length; i++) {
            values[i] = cells[i].getBinValue();
        }
        return values;
    }

    private static void assertValues(double[] expected, DataCell[] cells) {
        assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(values(cells)));
    }

    @Test
    public void editsOnlyTheSelectedCells() throws Exception {
        DataCell[] data = curve().getData();
        select(data[1], data[2]);
        curve().multiply(2);
        assertValues(new double[] {0, 20, 60, 40}, data);

        curve().increment(1);
        assertValues(new double[] {0, 21, 61, 40}, data);

        curve().setRealValue("7");
        assertValues(new double[] {0, 7, 7, 40}, data);
        assertEquals(7, bin[0x45]);
    }

    @Test
    public void revertsToTheRevertPoint() throws Exception {
        Table2D curve = curve();
        curve.getData()[0].setBinValue(5);
        curve.getAxis().getData()[3].setBinValue(50);
        curve.setRevertPoint();
        curve.getData()[0].setBinValue(6);
        curve.getAxis().getData()[3].setBinValue(60);

        curve.undoAll();
        assertEquals(5, curve.getData()[0].getBinValue(), 0);
        assertEquals(50, curve.getAxis().getData()[3].getBinValue(), 0);
    }

    @Test
    public void interpolates2DAlongTheAxisSpacing() throws Exception {
        DataCell[] data = curve().getData();
        data[1].setBinValue(99);
        data[2].setBinValue(99);
        select(data);
        curve().horizontalInterpolate();
        assertValues(new double[] {0, 10, 20, 40}, data);
    }

    @Test
    public void interpolates3DRowsAndColumns() throws Exception {
        DataCell[][] data = map().get3dData();
        select(data[0][0], data[1][0], data[2][0]);
        map().horizontalInterpolate();
        assertEquals(20, data[1][0].getBinValue(), 0);

        map().clearSelection();
        select(data[0][0], data[0][1], data[0][2]);
        map().verticalInterpolate();
        assertEquals(20, data[0][1].getBinValue(), 0);
    }

    @Test
    public void reads3DRowsWithXChangingFastest() {
        DataCell[][] data = map().get3dData();
        assertEquals(99, data[1][0].getBinValue(), 0);
        assertEquals(99, data[0][1].getBinValue(), 0);
        assertEquals(8, data[2][2].getBinValue(), 0);
    }

    @Test
    public void swapXYStoresColumns() throws Exception {
        Table3D swapped = (Table3D) RomFixture.load(RomFixture.rom("SWAP", 128, map("Map", "swapxy=\"true\"")), bin)
                .getTableByName("Map");
        DataCell[][] data = swapped.get3dData();
        assertEquals(99, data[0][1].getBinValue(), 0);
        assertEquals(40, data[0][2].getBinValue(), 0);
        assertEquals(99, data[1][0].getBinValue(), 0);
    }

    @Test
    public void flipXMirrorsAboutTheXAxis() throws Exception {
        Table3D flipped = (Table3D) RomFixture.load(RomFixture.rom("FLIP", 128, map("Map", "flipx=\"true\"")), bin)
                .getTableByName("Map");
        DataCell[][] data = flipped.get3dData();
        assertEquals(50, data[0][0].getBinValue(), 0);
        assertEquals(0, data[0][2].getBinValue(), 0);
    }

    @Test
    public void skipCellsLeavesGapsAfterEachRow() throws Exception {
        Table3D skipping = (Table3D) RomFixture.load(RomFixture.rom("SKIP", 128, map("Map", "skipCells=\"1\"")), bin)
                .getTableByName("Map");
        DataCell[][] data = skipping.get3dData();
        assertEquals(0, data[0][0].getBinValue(), 0);
        assertEquals(5, data[0][1].getBinValue(), 0);
        assertEquals(50, data[2][1].getBinValue(), 0);
    }

    @Test
    public void lookupsInterpolateAndClampToTheAxis() {
        assertEquals(20, curve().queryTable(15.0, null), 0);
        assertEquals(40, curve().queryTable(80.0, null), 0);
        assertEquals(0, curve().queryTable(-5.0, null), 0);
        assertEquals(50.75, map().queryTable(5.0, 50.0), 0);
    }

    @Test
    public void tracksModifiedTablesAndFiltersTheTree() throws Exception {
        assertFalse(curve().isModified());
        curve().getAxis().getData()[1].setBinValue(30);
        assertTrue(curve().isModified());

        rom.refreshDisplayedTables(null, true);
        assertEquals(1, rom.getChildCount());
        assertEquals("Fuel", rom.getChildAt(0).toString());

        curve().undoAll();
        assertFalse(curve().isModified());
        rom.refreshDisplayedTables(null, false);
        assertEquals(2, rom.getChildCount());
    }

    @Test
    public void clampsTheUserLevel() {
        curve().setUserLevel(9);
        assertEquals(5, curve().getUserLevel());
        curve().setUserLevel(0);
        assertEquals(1, curve().getUserLevel());
    }
}
