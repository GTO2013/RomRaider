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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.romraider.Settings;
import com.romraider.maps.PresetManager.PresetEntry;
import com.romraider.util.Interaction;
import com.romraider.xml.RomAttributeParser;

// Switches select one of their states, bitwise switches set single bits
public class TableSwitchTest {
    private static final int SUBARU = 0x100;

    private final List<String> warnings = new ArrayList<String>();
    private byte[] bin;
    private Rom rom;

    @Before
    public void load() throws Exception {
        Interaction.suppressDialogs(warnings);
        bin = RomFixture.image(0x200);
        bin[0x10] = 0x12;
        bin[0x11] = 0x34;
        bin[0x14] = 0x12;
        bin[0x15] = 0x34;
        bin[0x18] = (byte) 0xF4;
        bin[0x23] = 5;
        bin[0x30] = 1;
        // Subaru checksum entries: start, end, difference to 0x5AA5A55A, then the edit stamp
        putInt(SUBARU, 0x20);
        putInt(SUBARU + 4, 0x40);
        putInt(SUBARU + 12, 0x40);
        putInt(SUBARU + 16, 0x80);
        putInt(SUBARU + 204, 0xFFFFFFFF);
        rom = RomFixture.load(RomFixture.rom("SWITCH", 0x200,
                "<table name=\"Mode\" type=\"Switch\" storageaddress=\"0x10\" sizey=\"2\">"
                + "<state name=\"off\" data=\"00 00\"/><state name=\"on\" data=\"12 34\"/></table>"
                + "<table name=\"Word\" type=\"Switch\" storageaddress=\"0x14\" storagetype=\"uint16\" endian=\"little\">"
                + "<state name=\"on\" data=\"1234\"/></table>"
                + "<table name=\"Flags\" type=\"BitwiseSwitch\" storageaddress=\"0x18\">"
                + "<bit name=\"A\" position=\"0\"/><bit name=\"C\" position=\"2\"/></table>"
                + "<table name=\"Checksum Fix\" type=\"Switch\" storageaddress=\"0x100\" sizey=\"24\">"
                + "<state name=\"on\" data=\"00\"/></table>"), bin);
    }

    @After
    public void restoreDialogs() {
        Interaction.restoreDialogs();
    }

    private void putInt(int address, int value) {
        System.arraycopy(RomAttributeParser.parseIntegerValue(value, Settings.Endian.BIG, 4), 0, bin, address, 4);
    }

    private PresetEntry state(String table, String name) {
        PresetManager presets = rom.getTableByName(table).getPresetManager();
        for (PresetEntry entry : presets.getPresets()) {
            if (entry.getName().equals(name)) return entry;
        }
        throw new AssertionError(name);
    }

    private boolean active(String table, String name) {
        return rom.getTableByName(table).getPresetManager().isPresetActive(state(table, name));
    }

    @Test
    public void showsAndWritesTheStateWhoseBytesMatch() {
        assertTrue(active("Mode", "on"));
        assertFalse(active("Mode", "off"));

        rom.getTableByName("Mode").getPresetManager().applyPreset(state("Mode", "off"));
        assertEquals(0, bin[0x10]);
        assertEquals(0, bin[0x11]);
        assertTrue(active("Mode", "off"));
        assertFalse(active("Mode", "on"));
    }

    @Test
    public void statesAreInFileByteOrder() throws Exception {
        assertTrue(active("Word", "on"));
        rom.getTableByName("Word").getData()[0].setBinValue(0);
        assertFalse(active("Word", "on"));
        rom.getTableByName("Word").getPresetManager().applyPreset(state("Word", "on"));
        assertEquals(0x12, bin[0x14]);
        assertEquals(0x34, bin[0x15]);
    }

    @Test
    public void bitsChangeAloneAndKeepTheOtherBits() {
        assertFalse(active("Flags", "A"));
        assertTrue(active("Flags", "C"));

        PresetManager bits = rom.getTableByName("Flags").getPresetManager();
        bits.applyPreset(state("Flags", "A"));
        assertEquals((byte) 0xF5, bin[0x18]);
        bits.clearPreset(state("Flags", "C"));
        assertEquals((byte) 0xF1, bin[0x18]);
    }

    @Test
    public void subaruChecksumsAndEditStampAreUpdatedOnSave() {
        // Wrong on load, the user is told
        assertEquals(1, warnings.size());
        Table checksums = rom.getTableByName("Checksum Fix");
        assertEquals(1, RomChecksum.validateRomChecksum(bin, checksums));

        rom.saveFile();
        assertEquals(0, RomChecksum.validateRomChecksum(bin, checksums));
        int sum = 0;
        for (int i = 0x20; i < 0x40; i += 4) {
            sum += (int) RomAttributeParser.parseByteValue(bin, Settings.Endian.BIG, i, 4, true);
        }
        assertEquals(Settings.CHECK_TOTAL - sum,
                (int) RomAttributeParser.parseByteValue(bin, Settings.Endian.BIG, SUBARU + 8, 4, true));

        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        assertEquals(today + " v1", rom.getRomID().getEditStamp());
        rom.saveFile();
        assertEquals(today + " v2", rom.getRomID().getEditStamp());
    }
}
