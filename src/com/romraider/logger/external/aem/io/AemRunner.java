/*
 * RomRaider Open-Source Tuning, Logging and Reflashing
 * Copyright (C) 2006-2010 RomRaider.com
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

package com.romraider.logger.external.aem.io;

import com.romraider.logger.external.core.RawDataListener;
import com.romraider.logger.external.core.Stoppable;
import com.romraider.logger.external.core.TerminalConnection;
import com.romraider.logger.external.core.TerminalConnectionImpl;

public final class AemRunner implements Stoppable {
    private static final AemConnectionProperties CONNECTION_PROPS = new AemConnectionProperties();
    private final TerminalConnection connection;
    private final RawDataListener listener;
    private boolean stop;

    public AemRunner(String port, RawDataListener listener) {
        this.connection = new TerminalConnectionImpl("AEM UEGO", port, CONNECTION_PROPS);
        this.listener = listener;
    }

    public void run() {
        try {
            while (!stop) {
                byte[] bytes = connection.read();
                listener.setBytes(bytes);
            }
        } finally {
            connection.close();
        }
    }

    public void stop() {
        stop = true;
    }
}