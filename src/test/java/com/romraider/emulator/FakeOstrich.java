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

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An Ostrich 2.0 in memory, answering as One ROM's Ostrich plugin does
 * (one-rom-ostrich src/ostrich_main.c and trace.c): addresses wrap at the chip
 * size, bank 0, no start or end triggers.  ECU reads are queued with busRead()
 * and turn into trace hits when the host next reads.
 */
final class FakeOstrich implements Transport {
    final byte[] mem;
    int version = 0x14;
    boolean traceSupported = true;
    boolean corruptReads;
    // Every byte the host sent, and each command it completed
    final ByteArrayOutputStream received = new ByteArrayOutputStream();
    final List<byte[]> commands = new ArrayList<byte[]>();

    private final int mask;
    private final ArrayDeque<Integer> out = new ArrayDeque<Integer>();
    private final ArrayDeque<Integer> bus = new ArrayDeque<Integer>();
    private final byte[] cmd = new byte[0x11000];
    private int cmdLen;

    private boolean traceActive;
    private int x1;
    private int perPacket;
    private int packets;
    private int inPacket;
    private int packetsDone;
    private int winStart;
    private int winEnd;
    private int addrBytes;
    private boolean haveLast;
    private int last;

    FakeOstrich(int chipSize) {
        mem = new byte[chipSize];
        mask = chipSize - 1;
    }

    // The ECU reads these chip offsets
    void busRead(int... offsets) {
        for (int o : offsets) bus.add(o & mask);
    }

    boolean isTraceActive() {
        return traceActive;
    }

    int count(char command) {
        int n = 0;
        for (byte[] c : commands) if (c[0] == command) n++;
        return n;
    }

    @Override
    public String getName() {
        return "fake";
    }

    @Override
    public void write(byte[] data, int off, int len) {
        received.write(data, off, len);
        for (int i = off; i < off + len; i++) {
            if (traceActive) {
                // Any byte ends a trace and is then taken as a command
                finishTrace();
            }
            feed(data[i] & 0xFF);
        }
    }

    @Override
    public int read(byte[] buf, int off, int len, int timeoutMs) {
        pump();
        int n = 0;
        while (n < len && !out.isEmpty()) buf[off + n++] = (byte) (int) out.poll();
        return n;
    }

    @Override
    public void close() {
    }

    private void feed(int b) {
        cmd[cmdLen++] = (byte) b;
        int need = needed();
        if (need == 0) {
            reply('?');
            cmdLen = 0;
        } else if (need > 0 && cmdLen >= need) {
            commands.add(Arrays.copyOf(cmd, cmdLen));
            execute();
            cmdLen = 0;
        }
    }

    // Length of the command in cmd, -1 while unknown, 0 for no command
    private int needed() {
        switch (cmd[0]) {
        case 'V':
            return 2;
        case 'R':
            return 5;
        case 'W':
            return cmdLen < 2 ? -1 : 5 + n256(cmd[1]);
        case 'Z':
            if (cmdLen < 3) return -1;
            return cmd[1] == 'W' ? 6 + n256(cmd[2]) * 256 : 6;
        case 'T':
            return 13;
        default:
            return 0;
        }
    }

    private static int n256(byte n) {
        return n == 0 ? 256 : n & 0xFF;
    }

    private void execute() {
        if (cmd[0] == 'V') {
            if (cmd[1] == 'V') {
                reply(version);
                reply(9);
                reply('O');
            } else {
                reply('?');
            }
            return;
        }
        if (OstrichDriver.sum(cmd, 0, cmdLen - 1, 0) != (cmd[cmdLen - 1] & 0xFF)) {
            reply('?');
            return;
        }
        int addr;
        switch (cmd[0]) {
        case 'R':
            addr = ((cmd[2] & 0xFF) << 8) | (cmd[3] & 0xFF);
            replyImage(addr, n256(cmd[1]));
            break;
        case 'W':
            addr = ((cmd[2] & 0xFF) << 8) | (cmd[3] & 0xFF);
            store(addr, 4, n256(cmd[1]));
            reply('O');
            break;
        case 'Z':
            // MMSB counts only in bank 8
            addr = (cmd[4] & 0xFF) << 8;
            if (cmd[1] == 'R') {
                replyImage(addr, n256(cmd[2]) * 256);
            } else {
                store(addr, 5, n256(cmd[2]) * 256);
                reply('O');
            }
            break;
        case 'T':
            if (!traceSupported) {
                reply('?');
                break;
            }
            reply('O');
            beginTrace();
            break;
        default:
            reply('?');
        }
    }

    private void replyImage(int addr, int len) {
        int s = 0;
        for (int i = 0; i < len; i++) {
            int b = mem[(addr + i) & mask] & 0xFF;
            s += b;
            reply(b);
        }
        reply((s + (corruptReads ? 1 : 0)) & 0xFF);
    }

    private void store(int addr, int from, int len) {
        for (int i = 0; i < len; i++) mem[(addr + i) & mask] = cmd[from + i];
    }

    private void beginTrace() {
        x1 = cmd[1] & 0xFF;
        switch (x1 >> 6) {
        case 1:
            addrBytes = 1;
            break;
        case 2:
            addrBytes = 2;
            break;
        default:
            addrBytes = 3;
        }
        perPacket = n256(cmd[4]);
        packets = n256(cmd[5]);
        int start = ((cmd[6] & 0xFF) << 16) | ((cmd[7] & 0xFF) << 8) | (cmd[8] & 0xFF);
        int end = ((cmd[9] & 0xFF) << 16) | ((cmd[10] & 0xFF) << 8) | (cmd[11] & 0xFF);
        winStart = start & mask;
        winEnd = end & mask;
        inPacket = 0;
        packetsDone = 0;
        haveLast = false;
        // Only reads from now on are reported
        bus.clear();
        traceActive = true;
    }

    // Turns queued ECU reads into hits, as the device does while it runs
    void pump() {
        while (traceActive && !bus.isEmpty()) {
            int offset = bus.poll();
            if ((x1 & OstrichDriver.X1_WINDOWED) != 0 && (offset < winStart || offset > winEnd)) continue;
            if ((x1 & OstrichDriver.X1_NONREDUNDANT) != 0 && haveLast && offset == last) continue;
            last = offset;
            haveLast = true;
            int addr = (x1 & OstrichDriver.X1_RELATIVE) != 0 ? (offset - winStart) & mask : offset;
            for (int i = addrBytes - 1; i >= 0; i--) reply((addr >> (8 * i)) & 0xFF);
            if (++inPacket < perPacket) continue;
            inPacket = 0;
            packetsDone++;
            if ((x1 & OstrichDriver.X1_NOT_STREAMING) != 0 && packetsDone >= packets) finishTrace();
        }
    }

    private void finishTrace() {
        traceActive = false;
        reply('O');
    }

    private void reply(int b) {
        out.add(b & 0xFF);
    }
}
