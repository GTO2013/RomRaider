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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.romraider.Settings;
import com.romraider.util.NumberUtil;

// Clipboard text of tables and selections, written and pasted back
public class TableClipboardTest {
    private static final String X = "<scaling expression=\"x\" to_byte=\"x\" format=\"#\"/>";

    private byte[] bin;
    private Rom rom;

    @Before
    public void load() throws Exception {
        bin = RomFixture.image(128);
        // Curve axis 1..4 and data 5..8, Map axes and data 10..24
        for (int i = 0; i < 8; i++) {
            bin[0x10 + i] = (byte) (i + 1);
        }
        for (int i = 0; i < 15; i++) {
            bin[0x40 + i] = (byte) (10 + i);
        }
        rom = RomFixture.load(RomFixture.rom("CLIP", 128,
                curve("Curve", 0x10, 0x14) + curve("OtherCurve", 0x30, 0x34)
                + map("Map", 0x40) + map("OtherMap", 0x60)
                + "<table name=\"Half\" type=\"1D\" storageaddress=\"0x20\" storagetype=\"uint8\">"
                + "<scaling expression=\"x*0.5\" to_byte=\"x/0.5\" format=\"#\"/></table>"
                + "<table name=\"Gears\" type=\"2D\" storageaddress=\"0x70\" storagetype=\"uint8\" sizey=\"2\">" + X
                + "<table type=\"Static Y Axis\" name=\"Gear\" sizey=\"2\"><data>1st</data><data>2nd</data></table>"
                + "</table>"), bin);
    }

    @Test
    public void staticAxesAreCopiedAsTheirLabelsAndKeptOnPaste() throws Exception {
        Table2D gears = (Table2D) table("Gears");
        gears.getData()[1].setBinValue(6);
        String header = gears.getSettings().getTable2DHeader();
        assertEquals("1st\t2nd" + Settings.NEW_LINE + NumberUtil.stringValue(0) + "\t" + NumberUtil.stringValue(6),
                gears.getTableAsString().toString());

        gears.paste(header + "1st\t2nd" + Settings.NEW_LINE + "7\t8");
        assertEquals(7, bin[0x70]);
        assertEquals(8, bin[0x71]);
        assertEquals("1st", gears.getAxis().getData()[0].getStaticText());
    }

    private static String curve(String name, int axis, int data) {
        return "<table name=\"" + name + "\" type=\"2D\" storageaddress=\"" + Integer.toHexString(data)
                + "\" storagetype=\"uint8\" sizey=\"4\">" + X
                + "<table type=\"Y Axis\" name=\"Axis\" storageaddress=\"" + Integer.toHexString(axis)
                + "\" storagetype=\"uint8\">" + X + "</table></table>";
    }

    // 3x3 map with axes in front of the data
    private static String map(String name, int address) {
        return "<table name=\"" + name + "\" type=\"3D\" storageaddress=\"" + Integer.toHexString(address + 6)
                + "\" storagetype=\"uint8\" sizex=\"3\" sizey=\"3\">" + X
                + "<table type=\"X Axis\" name=\"Rpm\" storageaddress=\"" + Integer.toHexString(address)
                + "\" storagetype=\"uint8\">" + X + "</table>"
                + "<table type=\"Y Axis\" name=\"Load\" storageaddress=\"" + Integer.toHexString(address + 3)
                + "\" storagetype=\"uint8\">" + X + "</table></table>";
    }

    private Table table(String name) {
        return rom.getTableByName(name);
    }

    @Test
    public void copiesNothingWithoutASelection() {
        assertNull(table("Curve").copySelection());
        assertNull(table("Map").copySelection());
    }

    @Test
    public void selectionKeepsGapsWhenPasted() throws Exception {
        DataCell[] source = table("Curve").getData();
        select(source[0], source[2]);
        String copied = table("Curve").copySelection();
        assertTrue(copied.startsWith("[Selection1D]"));

        DataCell[] target = table("OtherCurve").getData();
        select(target[1]);
        table("OtherCurve").paste(copied);
        assertEquals(0, target[0].getBinValue(), 0);
        assertEquals(5, target[1].getBinValue(), 0);
        assertEquals(0, target[2].getBinValue(), 0);
        assertEquals(7, target[3].getBinValue(), 0);
    }

    @Test
    public void axisSelectionIsCopiedFromTheAxis() throws Exception {
        Table2D curve = (Table2D) table("Curve");
        select(curve.getAxis().getData()[1]);
        Table2D other = (Table2D) table("OtherCurve");
        select(other.getAxis().getData()[0]);

        other.paste(curve.copySelection());
        assertEquals(2, other.getAxis().getData()[0].getBinValue(), 0);
    }

    @Test
    public void wholeTablesRoundTrip() throws Exception {
        Table2D curve = (Table2D) table("Curve");
        Table2D other = (Table2D) table("OtherCurve");
        other.paste(curve.getSettings().getTable2DHeader() + curve.getTableAsString());
        assertEquals(curve.getTableAsString().toString(), other.getTableAsString().toString());

        Table3D map = (Table3D) table("Map");
        Table3D otherMap = (Table3D) table("OtherMap");
        otherMap.paste(map.getSettings().getTable3DHeader() + map.getTableAsString());
        assertEquals(map.getTableAsString().toString(), otherMap.getTableAsString().toString());
    }

    @Test
    public void mapSelectionPastesAtTheSelectedCell() throws Exception {
        DataCell[][] source = ((Table3D) table("Map")).get3dData();
        select(source[0][0], source[1][0], source[0][1], source[1][1]);
        String copied = table("Map").copySelection();
        assertTrue(copied.startsWith("[Selection3D]"));

        Table3D target = (Table3D) table("OtherMap");
        select(target.get3dData()[1][1]);
        target.paste(copied);
        DataCell[][] data = target.get3dData();
        assertEquals(source[0][0].getBinValue(), data[1][1].getBinValue(), 0);
        assertEquals(source[1][0].getBinValue(), data[2][1].getBinValue(), 0);
        assertEquals(source[0][1].getBinValue(), data[1][2].getBinValue(), 0);
        assertEquals(source[1][1].getBinValue(), data[2][2].getBinValue(), 0);
        assertEquals(0, data[0][0].getBinValue(), 0);
    }

    @Test
    public void pastingTheDisplayedTextKeepsTheStoredValue() throws Exception {
        DataCell half = table("Half").getData()[0];
        half.setBinValue(3);
        select(half);
        table("Half").paste("[Selection1D]\n2");
        assertEquals(3, half.getBinValue(), 0);

        table("Half").paste("[Selection1D]\n4");
        assertEquals(8, half.getBinValue(), 0);
    }
}
