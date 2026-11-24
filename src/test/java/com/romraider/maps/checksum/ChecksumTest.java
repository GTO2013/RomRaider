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

package com.romraider.maps.checksum;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

// The built-in checksum types as a definition's <checksum> element configures them
public class ChecksumTest {

    private static ChecksumManager manager(String... attributes) {
        Map<String, String> attrs = new HashMap<String, String>();
        for (int i = 0; i < attributes.length; i += 2) {
            attrs.put(attributes[i], attributes[i + 1]);
        }
        return ChecksumFactory.getManager(null, attrs);
    }

    private static byte[] counting(int size) {
        byte[] bin = new byte[size];
        for (int i = 0; i < size; i++) {
            bin[i] = (byte) (i + 1);
        }
        return bin;
    }

    private static byte[] bytes(byte[] bin, int from, int length) {
        return Arrays.copyOfRange(bin, from, from + length);
    }

    // Wrong before the update, all valid after it, and a second update has nothing to do
    private static void assertFixes(ChecksumManager cm, byte[] bin) {
        int all = cm.getNumberOfChecksums();
        assertTrue(cm.validate(bin) < all);
        cm.update(bin);
        assertEquals(all, cm.validate(bin));
        byte[] fixed = bin.clone();
        cm.update(bin);
        assertArrayEquals(fixed, bin);
    }

    @Test
    public void motronicSingleAddsTheBytesOfAnInclusiveRange() {
        byte[] bin = counting(0x20);
        ChecksumManager cm = manager("type", "motronicsingle", "start", "0x0", "end", "0x0F", "loc", "0x10",
                "initial", "0x100");
        assertFixes(cm, bin);
        // 0x100 + 1 + 2 + ... + 16
        assertArrayEquals(new byte[] {0x01, (byte) 0x88}, bytes(bin, 0x10, 2));
        assertEquals(0, cm.update(bin));
    }

    @Test
    public void motronicDoubleContinuesTheSumOverTheSecondRange() {
        byte[] bin = counting(0x20);
        ChecksumManager cm = manager("type", "motronicdouble", "start", "0", "end", "3", "loc", "1E",
                "startsecond", "8", "endsecond", "B");
        assertFixes(cm, bin);
        // 1+2+3+4 + 9+10+11+12
        assertArrayEquals(new byte[] {0x00, 0x34}, bytes(bin, 0x1E, 2));
    }

    @Test
    public void byteXorSkipsItsOwnLocation() {
        byte[] bin = {0x0F, 0x30, 0x00, 0x55, 0x00, 0x01, 0, 0};
        ChecksumManager cm = manager("type", "bytexor", "start", "0", "end", "6", "xorloc", "2");
        assertFixes(cm, bin);
        assertEquals(0x0F ^ 0x30 ^ 0x55 ^ 0x01, bin[2]);
    }

    @Test
    public void copyMirrorsTheRange() {
        byte[] bin = counting(0x10);
        ChecksumManager cm = manager("type", "copy", "start", "2", "end", "4", "loc", "A");
        assertFixes(cm, bin);
        assertArrayEquals(new byte[] {3, 4, 5}, bytes(bin, 0xA, 3));
    }

    @Test
    public void nissanSumAndXorOfWordsSkipTheirLocations() {
        for (String type : new String[] {"std", "alt"}) {
            byte[] bin = new byte[0x10];
            bin[0x3] = 0x01;
            bin[0x4] = 0x10;
            bin[0x7] = 0x03;
            ChecksumManager cm = manager("type", type, "start", "0", "end", "10", "sumloc", "8", "xorloc", "C");
            assertFixes(cm, bin);
            assertArrayEquals(type, new byte[] {0x10, 0, 0, 0x04, 0x10, 0, 0, 0x02}, bytes(bin, 8, 8));
        }
    }

    @Test
    public void nissanAlt2AlsoFixesTheCalibrationAndCodeWords() {
        byte[] bin = counting(0x40);
        ChecksumManager cm = manager("type", "alt2", "start", "0", "end", "40", "sumloc", "8", "xorloc", "C",
                "skiploc", "20");
        assertEquals(4, cm.getNumberOfChecksums());
        assertFixes(cm, bin);
        // Only in the code range, the 32 bit sums skip the word at skiploc
        bin[0x22]++;
        assertEquals(3, cm.validate(bin));
    }

    @Test
    public void e38FixesTheSumAndCvnOfAllSixSegments() {
        byte[] bin = counting(0x14000);
        int[] table = {0x24, 0x48, 0x6B, 0x8E, 0xB1, 0xD4};
        for (int i = 0; i < table.length; i++) {
            int start = 0x12000 + i * 0x100;
            System.arraycopy(new byte[] {0, 0x01, (byte) (start >> 8), 0}, 0, bin, 0x10000 + table[i], 4);
            System.arraycopy(new byte[] {0, 0x01, (byte) (start >> 8), 0x7F}, 0, bin, 0x10000 + table[i] + 4, 4);
        }
        ChecksumManager cm = manager("type", "e38pcm");
        assertFixes(cm, bin);
        bin[0x12150]++;
        assertEquals(10, cm.validate(bin));
    }
}
