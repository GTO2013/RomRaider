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

import java.io.IOException;

/**
 * A ROM emulator that serves an image to the ECU while it runs.
 *
 * Offsets are offsets into the image file; the driver translates them to the
 * device's addresses.  A driver is used by one thread at a time.
 */
public interface EmulatorDriver {
    // The device type, such as "Moates Ostrich 2.0"
    String getName();

    // Checks that the device on t is one this driver talks to and readies it
    // for an image of imageSize bytes.  Throws if it isn't.
    void open(Transport t, int imageSize) throws IOException;

    // What the device reported about itself, such as its version
    String getDescription();

    int getImageSize();

    void read(int offset, byte[] buf, int off, int len) throws IOException;

    // Writes to the image the ECU is served.  A running trace is paused for it.
    void write(int offset, byte[] data, int off, int len) throws IOException;

    // Starts reporting the ECU's reads of offsets start to end, inclusive
    void startTrace(int start, int end) throws IOException;

    // Waits at most timeoutMs for reads and puts their offsets in hits.
    // Returns how many it put there.  The trace keeps running.
    int pollTrace(int[] hits, int timeoutMs) throws IOException;

    void stopTrace() throws IOException;

    boolean isTracing();

    void close();
}
