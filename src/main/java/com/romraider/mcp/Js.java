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

package com.romraider.mcp;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collection;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonValue;
import com.romraider.util.HexUtil;

final class Js {
    private static final MathContext PRECISION = new MathContext(12);

    private Js() {
    }

    // Trims binary floating point noise like 0.30000000000000004
    static JsonValue num(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Json.value(String.valueOf(value));
        }
        if (value == 0) return Json.value(0);
        return Json.value(new BigDecimal(value).round(PRECISION).doubleValue());
    }

    static String hex(int value) {
        return value < 0 ? "-" + HexUtil.intToHexString(-value) : HexUtil.intToHexString(value);
    }

    static JsonArray strings(Collection<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
