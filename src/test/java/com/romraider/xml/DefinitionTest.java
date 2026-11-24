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

package com.romraider.xml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.romraider.maps.Rom;
import com.romraider.maps.RomFixture;
import com.romraider.util.Interaction;

// Finding the <rom> of an image in a definition file and building its tables
public class DefinitionTest {
    private static final String X = "<scaling expression=\"x\" to_byte=\"x\" format=\"#\"/>";

    private final List<String> warnings = new ArrayList<String>();

    @After
    public void restoreDialogs() {
        Interaction.restoreDialogs();
    }

    private static String romid(String xmlid, String idAddress, String idString, int fileSize) {
        return "<romid><xmlid>" + xmlid + "</xmlid><internalidaddress>" + idAddress + "</internalidaddress>"
                + "<internalidstring>" + idString + "</internalidstring><filesize>" + fileSize + "b</filesize></romid>";
    }

    private static String byte1D(String name, String address, String more) {
        return "<table name=\"" + name + "\" type=\"1D\" storageaddress=\"" + address + "\" storagetype=\"uint8\" "
                + more + ">" + X + "</table>";
    }

    @Test
    public void picksTheRomWhoseIdIsInTheImage() throws Exception {
        String roms = "<roms>"
                + "<rom>" + romid("HEX", "0x8", "0xCAFE", 16) + byte1D("Which", "0x0", "") + "</rom>"
                + "<rom>" + romid("TEXT", "0x4", "ab", 16) + byte1D("Which", "0x1", "") + "</rom>"
                + "</roms>";
        byte[] bin = new byte[16];
        bin[1] = 2;
        bin[4] = 'A';
        bin[5] = 'B';
        assertEquals("TEXT", RomFixture.loadDefinition(roms, bin).getRomIDString());

        bin[8] = (byte) 0xCA;
        bin[9] = (byte) 0xFE;
        Rom hex = RomFixture.loadDefinition(roms, bin);
        assertEquals("HEX", hex.getRomIDString());
        assertEquals(0, hex.getTableByName("Which").getData()[0].getBinValue(), 0);
    }

    @Test
    public void childRomsInheritAndChangeTheTablesOfTheirBase() throws Exception {
        String roms = "<roms>"
                + "<scalingbase name=\"half\" units=\"%\" expression=\"x/2\" to_byte=\"x*2\" format=\"0.0\"/>"
                + "<rom>" + romid("BASE", "0", "", 16)
                + "<table name=\"Fuel\" type=\"1D\" category=\"Base\" storageaddress=\"0x8\" storagetype=\"uint8\">"
                + "<scaling base=\"half\"/></table>"
                + byte1D("Spark", "0x9", "category=\"Base\"") + byte1D("Gone", "0xA", "") + "</rom>"
                + "<rom base=\"BASE\">" + romid("CHILD", "0", "TEST", 16)
                + "<table name=\"Spark\" storageaddress=\"0xB\"/>"
                + "<table name=\"Gone\" omit=\"true\"/>"
                + byte1D("Extra", "0xC", "") + "</rom>"
                + "</roms>";
        byte[] bin = RomFixture.image(16);
        bin[0x8] = 9;
        bin[0xB] = 7;
        bin[0xC] = 5;
        Rom rom = RomFixture.loadDefinition(roms, bin);

        assertEquals("CHILD", rom.getRomIDString());
        assertEquals(4.5, rom.getTableByName("Fuel").getData()[0].getRealValue(), 0);
        assertEquals("%", rom.getTableByName("Fuel").getCurrentScale().getUnit());
        assertEquals(7, rom.getTableByName("Spark").getData()[0].getBinValue(), 0);
        assertEquals("Base", rom.getTableByName("Spark").getCategory());
        assertNull(rom.getTableByName("Gone"));
        assertEquals(5, rom.getTableByName("Extra").getData()[0].getBinValue(), 0);
    }

    @Test
    public void imagesSmallerThanTheDefinedFileSizeAreItsEnd() throws Exception {
        String rom = "<rom>" + romid("TOP", "0", "TEST", 64)
                + byte1D("High", "0x35", "") + byte1D("Low", "0x5", "beforeram=\"true\"") + "</rom>";
        byte[] bin = RomFixture.image(16);
        bin[0x5] = 3;
        Rom loaded = RomFixture.load(rom, bin);
        assertEquals(3, loaded.getTableByName("High").getData()[0].getBinValue(), 0);
        assertEquals(3, loaded.getTableByName("Low").getData()[0].getBinValue(), 0);
    }

    @Test
    public void unknownChecksumTypeIsReportedAndSkipped() throws Exception {
        Interaction.suppressDialogs(warnings);
        String rom = RomFixture.rom("SUMS", 16, byte1D("Byte", "0x8", "")
                + "<checksum type=\"motronicsingle\" start=\"0x8\" end=\"0x9\" loc=\"0xA\"/>"
                + "<checksum type=\"nosuchsum\"/>");
        byte[] bin = RomFixture.image(16);
        bin[0x8] = 1;
        bin[0x9] = 2;
        bin[0xB] = 3;
        Rom loaded = RomFixture.load(rom, bin);

        assertEquals(1, warnings.size());
        assertEquals(1, loaded.getTotalAmountOfChecksums());
        assertEquals(1, loaded.validateChecksum());
        loaded.getTableByName("Byte").getData()[0].setBinValue(5);
        assertEquals(0, loaded.validateChecksum());
        assertEquals(2, warnings.size());

        loaded.saveFile();
        assertEquals(7, bin[0xB]);
    }
}
