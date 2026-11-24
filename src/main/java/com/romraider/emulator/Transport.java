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

// The byte link to an emulator, such as a serial port
public interface Transport {
    String getName();

    void write(byte[] data, int off, int len) throws IOException;

    // Reads up to len bytes, waiting at most timeoutMs for the first one.
    // Returns 0 if none arrived in that time.  A timeout of 0 takes only
    // what has arrived already.
    int read(byte[] buf, int off, int len, int timeoutMs) throws IOException;

    void close();
}
