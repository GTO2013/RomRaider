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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Random;

import org.junit.Before;
import org.junit.Test;

public class OstrichDriverTest {
    private static final int SIZE = 0x8000;

    private FakeOstrich dev;
    private OstrichDriver driver;

    @Before
    public void setUp() throws Exception {
        dev = new FakeOstrich(SIZE);
        driver = new OstrichDriver();
        driver.open(dev, SIZE);
        dev.received.reset();
        dev.commands.clear();
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static int[] hits(OstrichDriver d, int max) throws Exception {
        int[] h = new int[max];
        return Arrays.copyOf(h, d.pollTrace(h, 0));
    }

    // Commands TunerPro RT sent to a real Ostrich 2.0 v20.9, with their checksums
    @Test
    public void checksumsMatchTunerProCapture() {
        assertArrayEquals(bytes(0x54, 0x23, 0, 0, 1, 1, 0x07, 0xF1, 0x6E, 0x07, 0xF2, 0x31, 0x09),
                OstrichDriver.frame(new int[] {0x54, 0x23, 0, 0, 1, 1, 0x07, 0xF1, 0x6E, 0x07, 0xF2, 0x31}, null, 0, 0));
        assertArrayEquals(bytes('H', 'R', 7, 0, 1, 0xA2),
                OstrichDriver.frame(new int[] {'H', 'R', 7, 0, 1}, null, 0, 0));
    }

    @Test
    public void openReadsVersion() throws Exception {
        FakeOstrich d = new FakeOstrich(SIZE);
        OstrichDriver o = new OstrichDriver();
        o.open(d, SIZE);
        assertArrayEquals(bytes('V', 'V'), d.received.toByteArray());
        assertEquals("Ostrich v20.9", o.getDescription());
    }

    @Test
    public void openRefusesOtherDevices() throws Exception {
        FakeOstrich d = new FakeOstrich(SIZE);
        d.version = 0x01;
        try {
            new OstrichDriver().open(d, SIZE);
            fail();
        } catch (EmulatorException e) {
            assertTrue(e.getMessage().contains("No Ostrich"));
        }
    }

    @Test
    public void openRefusesSizesTheOstrichCannotServe() throws Exception {
        for (int size : new int[] {0xC000, 0x20000, 0}) {
            try {
                new OstrichDriver().open(new FakeOstrich(SIZE), size);
                fail("size " + size);
            } catch (EmulatorException e) {
                // expected
            }
        }
    }

    @Test
    public void openEndsATraceLeftRunning() throws Exception {
        driver.startTrace(0x0300, 0x03FF);
        dev.busRead(0x0310, 0x0320);
        OstrichDriver again = new OstrichDriver();
        again.open(dev, SIZE);
        assertEquals("Ostrich v20.9", again.getDescription());
        assertFalse(dev.isTraceActive());
    }

    @Test
    public void smallWriteIsOneTopJustifiedW() throws Exception {
        driver.write(0x1C18, bytes(0x20, 0x60), 0, 2);
        assertArrayEquals(bytes('W', 2, 0x9C, 0x18, 0x20, 0x60, 0x8D), dev.received.toByteArray());
        assertEquals(0x20, dev.mem[0x1C18]);
        assertEquals(0x60, dev.mem[0x1C19]);
    }

    @Test
    public void writeSplitsAtPageEnds() throws Exception {
        byte[] data = new byte[0x30];
        new Random(1).nextBytes(data);
        driver.write(0x0FF0, data, 0, data.length);
        assertEquals(2, dev.count('W'));
        assertArrayEquals(data, Arrays.copyOfRange(dev.mem, 0x0FF0, 0x1020));
    }

    @Test
    public void imageGoesUpAndComesBackInBulk() throws Exception {
        byte[] image = new byte[SIZE];
        new Random(2).nextBytes(image);
        driver.write(0, image, 0, SIZE);
        assertArrayEquals(image, dev.mem);
        assertEquals(SIZE / 0x1000, dev.count('Z'));
        assertEquals(0, dev.count('W'));
        assertArrayEquals(bytes('Z', 'W', 16, 0x07, 0x80), Arrays.copyOf(dev.commands.get(0), 5));

        dev.commands.clear();
        byte[] back = new byte[SIZE];
        driver.read(0, back, 0, SIZE);
        assertArrayEquals(image, back);
        assertArrayEquals(bytes('Z', 'R', 16, 0x07, 0x80, 0x43), dev.commands.get(0));
    }

    @Test
    public void unalignedReadUsesR() throws Exception {
        new Random(3).nextBytes(dev.mem);
        byte[] back = new byte[0x30];
        driver.read(0x0FF0, back, 0, back.length);
        assertArrayEquals(Arrays.copyOfRange(dev.mem, 0x0FF0, 0x1020), back);
        assertEquals(2, dev.count('R'));
    }

    @Test
    public void readChecksumIsChecked() throws Exception {
        dev.corruptReads = true;
        try {
            driver.read(0x100, new byte[4], 0, 4);
            fail();
        } catch (EmulatorException e) {
            assertTrue(e.getMessage().contains("checksum"));
        }
    }

    @Test
    public void rangeOutsideImageIsRefused() throws Exception {
        try {
            driver.write(SIZE - 1, new byte[2], 0, 2);
            fail();
        } catch (EmulatorException e) {
            assertEquals(0, dev.received.size());
        }
    }

    @Test
    public void traceAsksForRelativeHitsInTheWindow() throws Exception {
        driver.startTrace(0x0300, 0x03FF);
        // Not streaming, windowed, nonredundant, relative, 2-byte addresses;
        // 32 hits in 1 packet; window 0x78300-0x783FF
        assertArrayEquals(bytes('T', 0xA7, 0, 0, 32, 1, 0x07, 0x83, 0x00, 0x07, 0x83, 0xFF, 0x2F),
                dev.received.toByteArray());

        // Outside the window, a repeat and a code fetch are not reported
        dev.busRead(0x0100, 0x0310, 0x0310, 0x0320, 0x4000, 0x03FF);
        assertArrayEquals(new int[] {0x0310, 0x0320, 0x03FF}, hits(driver, 64));
        assertTrue(driver.isTracing());
    }

    @Test
    public void traceAsksAgainAfterEachPacket() throws Exception {
        driver.startTrace(0x0000, 0x1FFF);
        int[] reads = new int[40];
        for (int i = 0; i < reads.length; i++) reads[i] = 2 * i;
        dev.busRead(Arrays.copyOf(reads, 32));
        int[] h = hits(driver, 64);
        assertEquals(32, h.length);
        assertEquals(2, dev.count('T'));
        dev.busRead(Arrays.copyOfRange(reads, 32, 40));
        h = hits(driver, 64);
        assertEquals(8, h.length);
        assertEquals(2 * 39, h[7]);
    }

    @Test
    public void hitsUseThreeByteAddressesInWideWindows() throws Exception {
        driver.startTrace(0x0000, 0x7FFF);
        assertEquals(0x27, dev.commands.get(0)[1] & 0xFF);
        dev.busRead(0x4F4F, 0x7FFF);
        assertArrayEquals(new int[] {0x4F4F, 0x7FFF}, hits(driver, 8));
    }

    @Test
    public void writeDuringTracePausesIt() throws Exception {
        driver.startTrace(0x0300, 0x03FF);
        dev.busRead(0x0310);
        dev.received.reset();
        driver.write(0x0310, bytes(0x55), 0, 1);
        byte[] sent = dev.received.toByteArray();
        assertEquals('y', sent[0]);
        assertEquals('W', sent[1]);
        assertEquals('T', sent[7]);
        assertEquals(0x55, dev.mem[0x0310]);
        assertTrue(driver.isTracing());
        dev.busRead(0x0320);
        assertArrayEquals(new int[] {0x0320}, hits(driver, 8));
    }

    @Test
    public void stopAfterThePacketEndedOnItsOwn() throws Exception {
        driver.startTrace(0x0000, 0x1FFF);
        int[] reads = new int[32];
        for (int i = 0; i < reads.length; i++) reads[i] = i;
        dev.busRead(reads);
        // The device sends the packet and its closing 'O' before the abort arrives
        dev.pump();
        assertFalse(dev.isTraceActive());
        driver.stopTrace();
        assertFalse(driver.isTracing());
        assertFalse(dev.isTraceActive());
        // The link is in step again
        driver.write(0x10, bytes(1), 0, 1);
        assertEquals(1, dev.mem[0x10]);
    }

    @Test
    public void refusedTraceIsReported() throws Exception {
        dev.traceSupported = false;
        driver.startTrace(0x0300, 0x03FF);
        try {
            hits(driver, 8);
            fail();
        } catch (EmulatorException e) {
            assertTrue(e.getMessage().contains("doesn't support"));
        }
        assertFalse(driver.isTracing());
    }

    @Test
    public void stopOfARefusedTraceKeepsTheLinkInStep() throws Exception {
        dev.traceSupported = false;
        driver.startTrace(0x0300, 0x03FF);
        driver.stopTrace();
        driver.write(0x10, bytes(2), 0, 1);
        assertEquals(2, dev.mem[0x10]);
    }
}
