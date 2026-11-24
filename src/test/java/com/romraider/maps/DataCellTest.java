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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

// Reading, converting and writing single cells
public class DataCellTest {
    private static final String X = "<scaling expression=\"x\" to_byte=\"x\" format=\"#\" coarseincrement=\"1\"/>";
    private static final String TABLES =
            "<table name=\"Word\" type=\"1D\" storageaddress=\"0x10\" storagetype=\"uint16\" endian=\"big\">"
            + "<scaling expression=\"x*0.5\" to_byte=\"x/0.5\" format=\"0.0\"/></table>"
            + "<table name=\"HighByte\" type=\"1D\" storageaddress=\"0x10\" storagetype=\"uint8\">" + X + "</table>"
            + "<table name=\"SignedLittle\" type=\"1D\" storageaddress=\"0x14\" storagetype=\"int16\" endian=\"little\">" + X + "</table>"
            + "<table name=\"Byte\" type=\"1D\" storageaddress=\"0x16\" storagetype=\"uint8\">" + X + "</table>"
            + "<table name=\"SignedByte\" type=\"1D\" storageaddress=\"0x17\" storagetype=\"int8\">" + X + "</table>"
            + "<table name=\"Square\" type=\"1D\" storageaddress=\"0x18\" storagetype=\"uint8\">"
            + "<scaling expression=\"x*x\" format=\"#\"/></table>"
            + "<table name=\"Masked\" type=\"1D\" storageaddress=\"0x19\" storagetype=\"uint8\" mask=\"F0\">" + X + "</table>"
            + "<table name=\"Floats\" type=\"1D\" storageaddress=\"0x20\" storagetype=\"float\" sizey=\"2\">" + X + "</table>"
            + "<table name=\"SecondFloat\" type=\"1D\" storageaddress=\"0x24\" storagetype=\"float\">" + X + "</table>"
            + "<table name=\"Bosch\" type=\"1D\" storageaddress=\"0x28\" storagetype=\"uint8\" sizey=\"3\" dataLayout=\"bosch_subtract\">" + X + "</table>"
            + "<table name=\"Locked\" type=\"1D\" storageaddress=\"0x2C\" storagetype=\"uint8\" locked=\"true\">" + X + "</table>"
            + "<table name=\"Checksum Fix 2\" type=\"1D\" storageaddress=\"0x2D\" storagetype=\"uint8\">" + X + "</table>"
            + "<table name=\"Expert\" type=\"1D\" storageaddress=\"0x2E\" storagetype=\"uint8\" userlevel=\"5\">" + X + "</table>"
            + "<table name=\"Tenths\" type=\"1D\" storageaddress=\"0x30\" storagetype=\"uint8\">"
            + "<scaling expression=\"x/10\" to_byte=\"x*10\" format=\"0.0\" coarseincrement=\"0.01\"/></table>"
            + "<table name=\"Movi\" type=\"1D\" storageaddress=\"0x34\" storagetype=\"movi20\">" + X + "</table>"
            + "<table name=\"MoviS\" type=\"1D\" storageaddress=\"0x38\" storagetype=\"movi20s\">" + X + "</table>";

    private byte[] bin;
    private Rom rom;

    @Before
    public void load() throws Exception {
        bin = RomFixture.image(64);
        bin[0x10] = 0x01;
        bin[0x11] = 0x02;
        bin[0x14] = (byte) 0xFE;
        bin[0x15] = (byte) 0xFF;
        bin[0x16] = 5;
        bin[0x19] = (byte) 0x3C;
        System.arraycopy(new byte[] {0x3F, (byte) 0xC0, 0, 0, 0x40, 0x20, 0, 0}, 0, bin, 0x20, 8);
        bin[0x28] = 10;
        bin[0x29] = 20;
        bin[0x2A] = 30;
        bin[0x2C] = 5;
        bin[0x2D] = 5;
        bin[0x2E] = 5;
        // MOVI20 #-2,R10 and MOVI20S #0x1000,R11
        System.arraycopy(new byte[] {0x0A, (byte) 0xF0, (byte) 0xFF, (byte) 0xFE, 0x0B, 0x01, 0x00, 0x10}, 0, bin, 0x33, 8);
        rom = RomFixture.load(RomFixture.rom("CELLS", 64, TABLES), bin);
    }

    private DataCell cell(String table) {
        return cell(table, 0);
    }

    private DataCell cell(String table, int index) {
        return rom.getTableByName(table).getData()[index];
    }

    @Test
    public void readsByteOrderAndSign() {
        assertEquals(0x0102, cell("Word").getBinValue(), 0);
        assertEquals(129, cell("Word").getRealValue(), 0);
        assertEquals(-2, cell("SignedLittle").getBinValue(), 0);
        assertEquals(1.5, cell("Floats", 0).getBinValue(), 0);
        assertEquals(2.5, cell("Floats", 1).getBinValue(), 0);
    }

    @Test
    public void writesBigEndianWords() throws Exception {
        cell("Word").setBinValue(0xABCD);
        assertEquals((byte) 0xAB, bin[0x10]);
        assertEquals((byte) 0xCD, bin[0x11]);
    }

    @Test
    public void convertsRealToStoredValues() {
        assertEquals(10, cell("Word").toBinValue(5), 0);
        assertEquals(10, cell("Word").toBinValue(5.2), 0);
        assertEquals(5, cell("Square").toBinValue(25), 0);
    }

    @Test
    public void approximationGivesTheSameResultEveryTime() {
        DataCell square = cell("Square");
        double first = square.toBinValue(26);
        assertEquals(5, first, 0);
        assertEquals(first, square.toBinValue(26), 0);
    }

    @Test
    public void clampsToTheStorageRange() throws Exception {
        cell("Byte").setBinValue(300);
        assertEquals(255, cell("Byte").getBinValue(), 0);
        cell("SignedByte").setBinValue(-200);
        assertEquals(-128, cell("SignedByte").getBinValue(), 0);
        assertEquals((byte) 0x80, bin[0x17]);
    }

    @Test
    public void masksKeepTheOtherBits() throws Exception {
        DataCell masked = cell("Masked");
        assertEquals(3, masked.getBinValue(), 0);
        masked.setBinValue(9);
        assertEquals((byte) 0x9C, bin[0x19]);
    }

    @Test
    public void boschSubtractCellsStayConsistent() throws Exception {
        assertEquals(196, cell("Bosch", 0).getBinValue(), 0);
        assertEquals(206, cell("Bosch", 1).getBinValue(), 0);
        assertEquals(226, cell("Bosch", 2).getBinValue(), 0);

        cell("Bosch", 1).setBinValue(211);
        assertEquals(196, cell("Bosch", 0).getBinValue(), 0);
        assertEquals(211, cell("Bosch", 1).getBinValue(), 0);
        assertEquals(226, cell("Bosch", 2).getBinValue(), 0);
    }

    @Test
    public void lockedAndChecksumTablesIgnoreWrites() throws Exception {
        cell("Locked").setBinValue(9);
        cell("Checksum Fix 2").setBinValue(9);
        assertEquals(5, bin[0x2C]);
        assertEquals(5, bin[0x2D]);
    }

    @Test(expected = UserLevelException.class)
    public void tablesAboveTheUserLevelRefuseWrites() throws Exception {
        cell("Expert").setBinValue(9);
    }

    @Test
    public void incrementOnALockedTableReturns() throws Exception {
        cell("Locked").increment(1);
        assertEquals(5, cell("Locked").getBinValue(), 0);
    }

    @Test
    public void incrementBelowTheResolutionStillMovesOneStep() throws Exception {
        cell("Tenths").increment(0.01);
        assertEquals(1, cell("Tenths").getBinValue(), 0);
    }

    @Test
    public void tablesSharingAnAddressSeeEachOthersWrites() throws Exception {
        cell("Word").setBinValue(0x7F02);
        assertEquals(0x7F, cell("HighByte").getBinValue(), 0);
    }

    @Test
    public void floatTablesShareFourByteCells() throws Exception {
        cell("Floats", 1).setBinValue(7.25);
        assertEquals(7.25, cell("SecondFloat").getBinValue(), 0);
    }

    @Test
    public void tracksWhetherACellDiffersFromItsRevertPoint() throws Exception {
        DataCell word = cell("Word");
        word.setBinValue(7);
        assertTrue(word.isModified());
        word.setRevertPoint();
        assertTrue(!word.isModified());
        word.setBinValue(8);
        word.undo();
        assertEquals(7, word.getBinValue(), 0);
        assertTrue(!word.isModified());
    }

    @Test
    public void movi20KeepsTheInstructionAroundTheImmediate() throws Exception {
        assertEquals(-2, cell("Movi").getBinValue(), 0);
        assertEquals(0x1000, cell("MoviS").getBinValue(), 0);
        cell("Movi").setBinValue(0x12345);
        cell("MoviS").setBinValue(0x23400);
        assertArrayEquals(new byte[] {0x0A, 0x10, 0x23, 0x45, 0x0B, 0x01, 0x02, 0x34}, Arrays.copyOfRange(bin, 0x33, 0x3B));
    }

    @Test
    public void memModelSetsTheFloatByteOrder() throws Exception {
        byte[] little = RomFixture.image(64);
        System.arraycopy(new byte[] {0, 0, (byte) 0xC0, 0x3F}, 0, little, 0x20, 4);
        String rom = RomFixture.rom("LITTLE", 64,
                "<table name=\"Float\" type=\"1D\" storageaddress=\"0x20\" storagetype=\"float\">" + X + "</table>")
                .replace("</romid>", "<memmodel endian=\"little\">TEST</memmodel></romid>");
        Table table = RomFixture.load(rom, little).getTableByName("Float");
        assertEquals(1.5, table.getData()[0].getBinValue(), 0);

        // An image opened next, without a memmodel, keeps its own byte order
        Rom other = RomFixture.load(RomFixture.rom("CELLS", 64, TABLES), bin);
        assertEquals(1.5, other.getTableByName("Floats").getData()[0].getBinValue(), 0);
    }
}
