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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.romraider.Settings;

// Comparing a table with the same table of another image, or with its own revert point
public class TableCompareTest {
    private static final String X = "<scaling expression=\"x\" to_byte=\"x\" format=\"#\"/>";
    private static final String HALF = "<scaling expression=\"x*0.5\" to_byte=\"x/0.5\" format=\"0.0\"/>";
    private static final String ROM = RomFixture.rom("CMP", 64,
            curve("Curve", 0x10, 4) + curve("Short", 0x30, 2)
            + "<table name=\"Limit\" type=\"1D\" storageaddress=\"0x38\" storagetype=\"uint8\">" + X + "</table>"
            + "<table name=\"Map\" type=\"3D\" storageaddress=\"0x24\" storagetype=\"uint8\" sizex=\"2\" sizey=\"2\">" + X
            + "<table type=\"X Axis\" name=\"Rpm\" storageaddress=\"0x20\" storagetype=\"uint8\">" + X + "</table>"
            + "<table type=\"Y Axis\" name=\"Load\" storageaddress=\"0x22\" storagetype=\"uint8\">" + X + "</table>"
            + "</table>");

    private static String curve(String name, int axis, int size) {
        return "<table name=\"" + name + "\" type=\"2D\" storageaddress=\"0x" + Integer.toHexString(axis + size)
                + "\" storagetype=\"uint8\" sizey=\"" + size + "\">" + HALF
                + "<table type=\"Y Axis\" name=\"Rpm\" storageaddress=\"0x" + Integer.toHexString(axis)
                + "\" storagetype=\"uint8\">" + X + "</table></table>";
    }

    private static Rom load(int... curve) throws Exception {
        byte[] bin = RomFixture.image(64);
        for (int i = 0; i < curve.length; i++) {
            bin[0x10 + i] = (byte) curve[i];
        }
        return RomFixture.load(ROM, bin);
    }

    @Test
    public void otherImageShowsTheDifferenceOfRealValues() throws Exception {
        Table2D mine = (Table2D) load(1, 2, 3, 4, 10, 20, 30, 40).getTableByName("Curve");
        Table2D theirs = (Table2D) load(1, 2, 3, 5, 10, 10, 60, 40).getTableByName("Curve");
        mine.setCompareTable(theirs);
        mine.populateCompareValues(theirs);

        DataCell[] data = mine.getData();
        assertEquals(0, data[0].getRealCompareValue(), 0);
        assertEquals(5, data[1].getRealCompareValue(), 0);
        assertEquals(1, data[1].getRealCompareChangeValue(), 0);
        assertEquals(-15, data[2].getRealCompareValue(), 0);
        assertEquals(-0.5, data[2].getRealCompareChangeValue(), 0);
        assertEquals(-1, mine.getAxis().getData()[3].getRealCompareValue(), 0);

        theirs.getData()[1].setBinValue(20);
        mine.refreshCompare();
        assertEquals(0, data[1].getRealCompareValue(), 0);
    }

    @Test
    public void originalComparesWithTheRevertPoint() throws Exception {
        Table3D map = (Table3D) load().getTableByName("Map");
        map.setCompareValueType(Settings.DataType.ORIGINAL);
        map.setCompareTable(map);
        map.populateCompareValues(map);

        map.get3dData()[1][0].setBinValue(4);
        map.getXAxis().getData()[0].setBinValue(2);
        assertEquals(4, map.get3dData()[1][0].getCompareValue(), 0);
        assertEquals(0, map.get3dData()[0][1].getCompareValue(), 0);
        assertEquals(2, map.getXAxis().getData()[0].getCompareValue(), 0);

        map.setRevertPoint();
        map.refreshCompare();
        assertEquals(0, map.get3dData()[1][0].getCompareValue(), 0);
        assertEquals(0, map.getXAxis().getData()[0].getCompareValue(), 0);
    }

    // Compare Images lists the tables whose values differ
    @Test
    public void imagesDifferInTheTablesWithChangedCells() throws Exception {
        Rom mine = load(1, 2, 3, 4, 10, 20, 30, 40);
        Rom theirs = load(1, 2, 3, 4, 10, 20, 30, 40);
        String[] names = {"Curve", "Short", "Map", "Limit"};
        for (String name : names) {
            assertTrue(name, mine.getTableByName(name).equals(theirs.getTableByName(name)));
        }

        ((Table2D) theirs.getTableByName("Curve")).getAxis().getData()[2].setBinValue(9);
        ((Table3D) theirs.getTableByName("Map")).get3dData()[1][1].setBinValue(9);
        theirs.getTableByName("Limit").getData()[0].setBinValue(9);
        for (String name : names) {
            assertEquals(name, name.equals("Short"), mine.getTableByName(name).equals(theirs.getTableByName(name)));
        }
    }

    @Test
    public void tablesOfAnotherSizeAreNotCompared() throws Exception {
        Rom rom = load(1, 2, 3, 4, 10, 20, 30, 40);
        Table curve = rom.getTableByName("Curve");
        curve.populateCompareValues(rom.getTableByName("Short"));
        assertEquals(0, curve.getData()[3].getCompareToValue(), 0);
    }
}
