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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import com.fazecast.jSerialComm.SerialPort;
import com.romraider.util.ResourceUtil;

// A serial port, real or over USB
public final class SerialTransport implements Transport {
    private static final ResourceBundle rb = new ResourceUtil().getBundle(SerialTransport.class.getName());
    // The Ostrich 2.0 runs at 921.6k; USB devices ignore the rate
    static final int BAUD = 921600;
    private static final int WRITE_TIMEOUT_MS = 2000;

    private final SerialPort port;
    private int readTimeout = -1;

    public SerialTransport(String portName) throws IOException {
        port = SerialPort.getCommPort(portName);
        port.setComPortParameters(BAUD, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        if (!port.openPort()) throw new EmulatorException(msg("CANTOPEN", portName));
        // USB serial devices often send nothing until the host raises DTR
        port.setDTR();
        port.setRTS();
        setReadTimeout(OstrichDriver.TIMEOUT_MS);
    }

    // The name and the device's description of each port there is
    public static List<String[]> listPorts() {
        List<String[]> ports = new ArrayList<String[]>();
        for (SerialPort p : SerialPort.getCommPorts()) {
            ports.add(new String[] {p.getSystemPortName(), p.getPortDescription()});
        }
        return ports;
    }

    @Override
    public String getName() {
        return port.getSystemPortName();
    }

    @Override
    public void write(byte[] data, int off, int len) throws IOException {
        while (len > 0) {
            int n = port.writeBytes(data, len, off);
            if (n <= 0) throw new EmulatorException(msg("WRITEFAILED", getName()));
            off += n;
            len -= n;
        }
    }

    @Override
    public int read(byte[] buf, int off, int len, int timeoutMs) throws IOException {
        if (timeoutMs <= 0) {
            int available = port.bytesAvailable();
            if (available < 0) throw new EmulatorException(msg("GONE", getName()));
            len = Math.min(len, available);
            if (len == 0) return 0;
            // These bytes are there already, so the timeout doesn't matter
            setReadTimeout(1);
        } else {
            setReadTimeout(timeoutMs);
        }
        int n = port.readBytes(buf, len, off);
        if (n < 0) throw new EmulatorException(msg("GONE", getName()));
        return n;
    }

    @Override
    public void close() {
        port.closePort();
    }

    private static String msg(String key, Object... args) {
        return MessageFormat.format(rb.getString(key), args);
    }

    private void setReadTimeout(int ms) {
        if (ms == readTimeout) return;
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                ms, WRITE_TIMEOUT_MS);
        readTimeout = ms;
    }
}
