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
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ResourceBundle;

import com.romraider.util.ResourceUtil;

/**
 * Moates Ostrich 2.0, and devices that speak its protocol such as One ROM
 * with its Ostrich plugin.  Commands are those of Moates' Hardware Protocols
 * sheet v19.
 *
 * Images sit top-justified in the Ostrich's 512 KB space, where TunerPro RT
 * puts them: a 32 KB image at 0x78000.  R and W take the low 16 bits of that
 * address, ZR, ZW and T all of it.
 */
public final class OstrichDriver implements EmulatorDriver {
    private static final ResourceBundle rb = new ResourceUtil().getBundle(OstrichDriver.class.getName());
    static final int TIMEOUT_MS = 1000;
    // How long open() waits for more bytes before it takes the device as quiet
    static final int QUIET_MS = 200;
    static final int SPACE = 0x80000;
    static final int VERSION_OSTRICH2 = 0x14;
    // 256-byte blocks per bulk command; Moates sees no gain beyond 4 KB
    static final int BULK_BLOCKS = 16;
    // Hits per trace packet; small, so a write never waits long for one
    static final int TRACE_HITS = 32;

    static final int OK = 'O';
    static final int FAIL = '?';
    // Any byte ends a trace.  TunerPro RT sends this one, which the device
    // then refuses as a command.
    static final int TRACE_ABORT = 'y';

    static final int X1_NOT_STREAMING = 0x01;
    static final int X1_WINDOWED = 0x02;
    static final int X1_NONREDUNDANT = 0x04;
    static final int X1_RELATIVE = 0x20;
    static final int X1_TWO_BYTE_ADDRESSES = 0x80;

    private Transport t;
    private int size;
    private int base;
    private String description = "";

    private boolean tracing;
    private boolean acked;
    private int traceStart;
    private int traceEnd;
    private int addrBytes;

    @Override
    public String getName() {
        return "Moates Ostrich 2.0";
    }

    @Override
    public void open(Transport transport, int imageSize) throws IOException {
        if (imageSize <= 0 || imageSize > 0x10000 || (imageSize & (imageSize - 1)) != 0) {
            throw new EmulatorException(msg("IMAGESIZE", String.valueOf(imageSize)));
        }
        t = transport;
        size = imageSize;
        base = SPACE - imageSize;
        tracing = false;

        // VV also ends a trace left running, so the version is the last three bytes
        send(new byte[] {'V', 'V'});
        byte[] reply = readUntilQuiet();
        int n = reply.length;
        if (n < 3 || (reply[n - 3] & 0xFF) != VERSION_OSTRICH2) {
            throw new EmulatorException(msg("NOANSWER", t.getName()));
        }
        description = msg("DESCRIPTION", String.valueOf(reply[n - 3] & 0xFF), String.valueOf(reply[n - 2] & 0xFF));
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public int getImageSize() {
        return size;
    }

    @Override
    public void read(int offset, byte[] buf, int off, int len) throws IOException {
        checkRange(offset, len);
        boolean resume = pauseTrace();
        while (len > 0) {
            int addr = base + offset;
            int n;
            if ((addr & 0xFF) == 0 && len >= 256) {
                int blocks = Math.min(BULK_BLOCKS, len / 256);
                n = blocks * 256;
                send(frame(new int[] {'Z', 'R', blocks, addr >> 16, addr >> 8}, null, 0, 0));
            } else {
                n = Math.min(len, 256 - (addr & 0xFF));
                send(frame(new int[] {'R', n, addr >> 8, addr}, null, 0, 0));
            }
            readFully(buf, off, n);
            if (readByte() != sum(buf, off, n, 0)) {
                throw new EmulatorException(msg("READCHECKSUM", hex(offset)));
            }
            offset += n;
            off += n;
            len -= n;
        }
        if (resume) arm();
    }

    @Override
    public void write(int offset, byte[] data, int off, int len) throws IOException {
        checkRange(offset, len);
        boolean resume = pauseTrace();
        while (len > 0) {
            int addr = base + offset;
            int n;
            if ((addr & 0xFF) == 0 && len >= 256) {
                int blocks = Math.min(BULK_BLOCKS, len / 256);
                n = blocks * 256;
                send(frame(new int[] {'Z', 'W', blocks, addr >> 16, addr >> 8}, data, off, n));
            } else {
                n = Math.min(len, 256 - (addr & 0xFF));
                send(frame(new int[] {'W', n, addr >> 8, addr}, data, off, n));
            }
            int reply = readByte();
            if (reply != OK) {
                throw new EmulatorException(msg("WRITEREFUSED", hex(offset), hex(reply)));
            }
            offset += n;
            off += n;
            len -= n;
        }
        if (resume) arm();
    }

    @Override
    public void startTrace(int start, int end) throws IOException {
        checkRange(start, end - start + 1);
        stopTrace();
        traceStart = start;
        traceEnd = end;
        arm();
    }

    @Override
    public int pollTrace(int[] hits, int timeoutMs) throws IOException {
        if (!tracing) return 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] unit = new byte[3];
        int n = 0;
        while (n < hits.length) {
            int wait = (int) Math.max(0, deadline - System.currentTimeMillis());
            if (t.read(unit, 0, 1, wait) == 0) break;
            int b = unit[0] & 0xFF;
            if (!acked) {
                if (b != OK) {
                    tracing = false;
                    if (b == FAIL) throw new TraceUnsupportedException(msg("NOTRACE"));
                    throw new EmulatorException(msg("TRACEANSWER", hex(b)));
                }
                acked = true;
            } else if (b == OK) {
                // The packet is complete and the trace with it
                arm();
            } else {
                readFully(unit, 1, addrBytes - 1);
                int rel = 0;
                for (int i = 0; i < addrBytes; i++) rel = (rel << 8) | (unit[i] & 0xFF);
                int offset = traceStart + rel;
                if (offset <= traceEnd) hits[n++] = offset;
            }
        }
        return n;
    }

    @Override
    public void stopTrace() throws IOException {
        if (!tracing) return;
        tracing = false;
        send(new byte[] {(byte) TRACE_ABORT});
        if (!acked && readByte() == FAIL) {
            // The trace was refused, and the abort byte is refused as well
            expect(FAIL);
            return;
        }
        // Hits until the 'O' that ends the trace, then the refusal of the abort byte
        byte[] unit = new byte[3];
        while (readByte() != OK) {
            readFully(unit, 0, addrBytes - 1);
        }
        expect(FAIL);
    }

    @Override
    public boolean isTracing() {
        return tracing;
    }

    @Override
    public void close() {
        try {
            stopTrace();
        } catch (IOException e) {
            // Closing anyway
        }
        if (t != null) t.close();
    }

    // Asks for one packet of hits in the trace window
    private void arm() throws IOException {
        // Relative addresses in a window of less than 0x4F00 bytes never
        // start with 'O', so an 'O' where a hit would start ends the trace.
        // Three-byte ones start with 0 in any window that fits an image.
        addrBytes = (traceEnd - traceStart < 0x4F00) ? 2 : 3;
        int x1 = X1_NOT_STREAMING | X1_WINDOWED | X1_NONREDUNDANT | X1_RELATIVE
                | (addrBytes == 2 ? X1_TWO_BYTE_ADDRESSES : 0);
        int s = base + traceStart;
        int e = base + traceEnd;
        send(frame(new int[] {'T', x1, 0, 0, TRACE_HITS, 1, s >> 16, s >> 8, s, e >> 16, e >> 8, e}, null, 0, 0));
        tracing = true;
        acked = false;
    }

    private boolean pauseTrace() throws IOException {
        if (!tracing) return false;
        stopTrace();
        return true;
    }

    private void checkRange(int offset, int len) throws EmulatorException {
        if (offset < 0 || len < 0 || offset + len > size) {
            throw new EmulatorException(msg("RANGE", hex(offset), hex(len), hex(size)));
        }
    }

    // A command: its header bytes, then data, then the 8-bit sum of all of them
    static byte[] frame(int[] header, byte[] data, int off, int len) {
        byte[] out = new byte[header.length + len + 1];
        for (int i = 0; i < header.length; i++) out[i] = (byte) header[i];
        if (len > 0) System.arraycopy(data, off, out, header.length, len);
        out[out.length - 1] = (byte) sum(out, 0, out.length - 1, 0);
        return out;
    }

    static int sum(byte[] b, int off, int len, int start) {
        int s = start;
        for (int i = off; i < off + len; i++) s += b[i] & 0xFF;
        return s & 0xFF;
    }

    private void send(byte[] b) throws IOException {
        t.write(b, 0, b.length);
    }

    private int readByte() throws IOException {
        byte[] b = new byte[1];
        readFully(b, 0, 1);
        return b[0] & 0xFF;
    }

    private void expect(int wanted) throws IOException {
        int b = readByte();
        if (b != wanted) {
            throw new EmulatorException(msg("UNEXPECTED", hex(wanted), hex(b)));
        }
    }

    private void readFully(byte[] buf, int off, int len) throws IOException {
        while (len > 0) {
            int n = t.read(buf, off, len, TIMEOUT_MS);
            if (n == 0) throw new EmulatorException(msg("NOREPLY", t.getName()));
            off += n;
            len -= n;
        }
    }

    private byte[] readUntilQuiet() throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        int n = t.read(buf, 0, buf.length, TIMEOUT_MS);
        // A streaming trace would never go quiet; VV has ended it by now
        while (n > 0 && all.size() < 0x10000) {
            all.write(buf, 0, n);
            n = t.read(buf, 0, buf.length, QUIET_MS);
        }
        return all.toByteArray();
    }

    private static String msg(String key, Object... args) {
        return MessageFormat.format(rb.getString(key), args);
    }

    private static String hex(int v) {
        return String.format("0x%X", v);
    }
}
