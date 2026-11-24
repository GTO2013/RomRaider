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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.romraider.maps.DataCell;
import com.romraider.maps.Rom;
import com.romraider.maps.RomFixture;
import com.romraider.maps.Table2D;
import com.romraider.maps.Table3D;

// The session without its threads: tick() is the Swing side, ioStep() the I/O side
public class EmulatorSessionTest {
    private static final int SIZE = 0x8000;
    private static final int CHECKSUM = 0x0400;
    private static final String X = "<scaling expression=\"x\" to_byte=\"x\" format=\"#\" coarseincrement=\"1\"/>";
    private static final String TABLES =
            "<table name=\"Curve\" type=\"2D\" category=\"Fuel\" storageaddress=\"0x344\" storagetype=\"uint8\" sizey=\"4\">"
            + X + "<table type=\"Y Axis\" name=\"Rpm\" storageaddress=\"0x340\" storagetype=\"uint8\">" + X
            + "</table></table>"
            + "<table name=\"Map\" type=\"3D\" category=\"Ignition\" storageaddress=\"0x356\" storagetype=\"uint8\" "
            + "sizex=\"3\" sizey=\"3\">" + X
            + "<table type=\"X Axis\" name=\"Rpm\" storageaddress=\"0x350\" storagetype=\"uint8\">" + X + "</table>"
            + "<table type=\"Y Axis\" name=\"Load\" storageaddress=\"0x353\" storagetype=\"uint8\">" + X
            + "</table></table>"
            + "<table name=\"Limit\" type=\"1D\" category=\"Misc\" storageaddress=\"0x360\" storagetype=\"uint16\">"
            + X + "</table>"
            + "<checksum type=\"motronicsingle\" start=\"0x0300\" end=\"0x03FF\" loc=\"0x0400\" />";

    private byte[] bin;
    private Rom rom;
    private FakeOstrich dev;
    private EmulatorSession session;

    @Before
    public void setUp() throws Exception {
        bin = RomFixture.image(SIZE);
        put(0x340, 0, 10, 20, 40);
        put(0x344, 0, 10, 30, 40);
        put(0x350, 0, 10, 20);
        put(0x353, 0, 100, 250);
        put(0x356, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        put(0x360, 0x12, 0x34);
        rom = RomFixture.load(RomFixture.rom("EMU", SIZE, TABLES), bin);

        dev = new FakeOstrich(SIZE);
        System.arraycopy(rom.getBinary(), 0, dev.mem, 0, SIZE);
        OstrichDriver driver = new OstrichDriver();
        driver.open(dev, SIZE);
        dev.commands.clear();
        session = new EmulatorSession(driver, rom, dev.mem);
    }

    private void put(int address, int... values) {
        for (int i = 0; i < values.length; i++) bin[address + i] = (byte) values[i];
    }

    private void step() throws Exception {
        session.tick();
        session.ioStep(0);
        session.tick();
    }

    private Table3D map() {
        return (Table3D) rom.getTableByName("Map");
    }

    private Integer count(DataCell cell) {
        for (Map.Entry<HitMap.Target, Integer> e : session.getHitCounts().entrySet()) {
            if (e.getKey().cell == cell) return e.getValue();
        }
        return null;
    }

    @Test
    public void nothingChangedNothingIsSent() throws Exception {
        step();
        assertTrue(dev.commands.isEmpty());
    }

    @Test
    public void editedCellGoesOutWithItsChecksum() throws Exception {
        byte[] before = {rom.getBinary()[CHECKSUM], rom.getBinary()[CHECKSUM + 1]};
        map().get3dData()[1][1].setBinValue(55);
        step();

        assertEquals(55, dev.mem[0x356 + 4]);
        int sum = 0;
        for (int i = 0x300; i <= 0x3FF; i++) sum += dev.mem[i] & 0xFF;
        assertEquals((sum >> 8) & 0xFF, dev.mem[CHECKSUM] & 0xFF);
        assertEquals(sum & 0xFF, dev.mem[CHECKSUM + 1] & 0xFF);
        assertFalse(before[0] == dev.mem[CHECKSUM] && before[1] == dev.mem[CHECKSUM + 1]);
        assertArrayEquals(rom.getBinary(), dev.mem);
        assertEquals(2, dev.count('W'));
    }

    @Test
    public void bytesWrittenPastTheCellsGoOutToo() throws Exception {
        rom.getBinary()[0x7000] = 0x22;
        step();
        assertEquals(0x22, dev.mem[0x7000]);
        // The byte and the checksum, which the image had wrong
        assertArrayEquals(rom.getBinary(), dev.mem);
        assertEquals(3, session.getBytesSent());
    }

    @Test
    public void pausedLiveUpdatesHoldEditsUntilResumed() throws Exception {
        session.setLive(false);
        map().get3dData()[0][0].setBinValue(77);
        step();
        assertEquals(1, dev.mem[0x356]);
        session.setLive(true);
        step();
        assertEquals(77, dev.mem[0x356]);
    }

    @Test
    public void nearbyChangesShareAWrite() {
        byte[] a = new byte[0x100];
        byte[] b = new byte[0x100];
        a[0x10] = 1;
        a[0x18] = 1;
        a[0x40] = 1;
        List<int[]> r = EmulatorSession.changes(a, b, EmulatorSession.MERGE_GAP);
        assertEquals(2, r.size());
        assertArrayEquals(new int[] {0x10, 0x19}, r.get(0));
        assertArrayEquals(new int[] {0x40, 0x41}, r.get(1));
    }

    @Test
    public void traceCoversTheTablesData() throws Exception {
        session.setTrace(true);
        step();
        byte[] t = dev.commands.get(0);
        assertEquals('T', t[0]);
        // Window 0x0344 (curve data) to 0x0361 (Limit's second byte), top-justified at 0x78000
        assertArrayEquals(new byte[] {0x07, (byte) 0x83, 0x44, 0x07, (byte) 0x83, 0x61},
                java.util.Arrays.copyOfRange(t, 6, 12));
    }

    @Test
    public void hitOnAMapCellLightsItsRowAndColumn() throws Exception {
        session.setTrace(true);
        step();
        // Data cell x=2, y=1 of the map, then a read of an axis
        dev.busRead(0x356 + 3 + 2, 0x351);
        step();

        DataCell[][] data = map().get3dData();
        assertEquals(Integer.valueOf(1), count(data[2][1]));
        assertEquals(1, session.getHitCounts().size());
        HitMap.Target target = session.getHitCounts().keySet().iterator().next();
        assertEquals(2, target.x);
        assertEquals(1, target.y);
        assertSame(map().getXAxis().getData()[2], target.xAxis);
        assertSame(map().getYAxis().getData()[1], target.yAxis);
    }

    @Test
    public void curveHitsCarryTheirAxisCell() throws Exception {
        session.setTrace(true);
        step();
        dev.busRead(0x346, 0x361, 0x360);
        step();
        Table2D curve = (Table2D) rom.getTableByName("Curve");
        assertEquals(Integer.valueOf(1), count(curve.getData()[2]));
        assertEquals(Integer.valueOf(2), count(rom.getTableByName("Limit").getData()[0]));
        assertEquals(3, session.getHitTotal());
    }

    @Test
    public void editsGoOutWhileTracing() throws Exception {
        session.setTrace(true);
        step();
        map().get3dData()[0][0].setBinValue(66);
        step();
        assertEquals(66, dev.mem[0x356]);
        dev.busRead(0x357);
        step();
        assertEquals(Integer.valueOf(1), count(map().get3dData()[1][0]));
    }

    @Test
    public void refusedTraceTurnsItselfOff() throws Exception {
        dev.traceSupported = false;
        session.setTrace(true);
        step();
        step();
        assertNotNull(session.getTraceProblem());
        assertFalse(session.isTracing());
        // The link still works
        map().get3dData()[0][0].setBinValue(12);
        step();
        assertEquals(12, dev.mem[0x356]);
    }

    @Test
    public void clearedHitsAreForgotten() throws Exception {
        session.setTrace(true);
        step();
        dev.busRead(0x356);
        step();
        session.clearHits();
        assertNull(count(map().get3dData()[0][0]));
        assertEquals(0, session.getHitTotal());
    }

    @Test
    public void detachSendsEditsNotYetSentAndStopsTheTrace() throws Exception {
        session.setTrace(true);
        step();
        map().get3dData()[2][2].setBinValue(99);
        EmulatorDriver driver = session.detach();
        assertEquals(99, dev.mem[0x356 + 8]);
        assertFalse(driver.isTracing());
        assertFalse(dev.isTraceActive());
    }
}
