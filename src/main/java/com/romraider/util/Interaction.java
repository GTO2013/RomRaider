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

package com.romraider.util;

import java.util.List;

/**
 * Lets remote callers run editor code without modal dialogs blocking them.
 * While a thread has a warning sink installed, dialog sites record their
 * message there instead of showing it.
 */
public final class Interaction {
    private static final ThreadLocal<List<String>> SINK = new ThreadLocal<List<String>>();

    private Interaction() {
    }

    // A null sink keeps dialogs on
    public static void suppressDialogs(List<String> warnings) {
        SINK.set(warnings);
    }

    public static void restoreDialogs() {
        SINK.remove();
    }

    /**
     * @return true if the caller should show its dialog, false if the
     *         message was recorded instead
     */
    public static boolean showDialog(String message) {
        List<String> warnings = SINK.get();
        if (warnings == null) return true;

        warnings.add(message);
        return false;
    }
}
