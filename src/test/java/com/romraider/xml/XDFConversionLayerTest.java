/*
 * RomRaider Open-Source Tuning, Logging and Reflashing
 * Copyright (C) 2006-2021 RomRaider.com
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

package com.romraider.xml;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

import org.junit.Test;

import com.romraider.maps.Rom;
import com.romraider.maps.RomFixture;
import com.romraider.maps.Table;
import com.romraider.maps.Table3D;
import com.romraider.xml.ConversionLayer.XDFConversionLayer;

// A TunerPro XDF converted to a definition and loaded against an image
public class XDFConversionLayerTest {
    private static final String XDF = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<XDFFORMAT version=\"1.70\">"
            + "<XDFHEADER><deftitle>XDFTEST</deftitle>"
            + "<BASEOFFSET offset=\"0x1000\" subtract=\"1\" />"
            + "<DEFAULTS datasizeinbits=\"8\" sigdigits=\"2\" outputtype=\"1\" signed=\"0\" lsbfirst=\"0\" float=\"0\" />"
            + "<CATEGORY index=\"0x0\" name=\"Fuel\" /><CATEGORY index=\"0x1\" name=\"Limits\" /></XDFHEADER>"
            + "<XDFTABLE uniqueid=\"0x100\" flags=\"0x0\"><title>Map</title><CATEGORYMEM index=\"0\" category=\"1\" />"
            + "<XDFAXIS id=\"x\" uniqueid=\"0x0\">"
            + "<EMBEDDEDDATA mmedaddress=\"0x1010\" mmedelementsizebits=\"8\" mmedmajorstridebits=\"0\" mmedminorstridebits=\"0\" />"
            + "<units>rpm</units><indexcount>3</indexcount><decimalpl>0</decimalpl>"
            + "<MATH equation=\"X*100\"><VAR id=\"X\" /></MATH></XDFAXIS>"
            + "<XDFAXIS id=\"y\" uniqueid=\"0x0\"><units>load</units><indexcount>2</indexcount>"
            + "<LABEL index=\"0\" value=\"10.5\" /><LABEL index=\"1\" value=\"20.5\" />"
            + "<MATH equation=\"X\"><VAR id=\"X\" /></MATH></XDFAXIS>"
            + "<XDFAXIS id=\"z\"><EMBEDDEDDATA mmedtypeflags=\"0x02\" mmedaddress=\"0x1020\" mmedelementsizebits=\"16\" "
            + "mmedrowcount=\"2\" mmedcolcount=\"3\" mmedmajorstridebits=\"0\" mmedminorstridebits=\"0\" />"
            + "<units>ms</units><decimalpl>2</decimalpl><MATH equation=\"X/1000\"><VAR id=\"X\" /></MATH></XDFAXIS>"
            + "</XDFTABLE>"
            + "<XDFCONSTANT uniqueid=\"0x200\" flags=\"0x0\"><title>Limit</title><CATEGORYMEM index=\"0\" category=\"2\" />"
            + "<EMBEDDEDDATA mmedaddress=\"0x1030\" mmedelementsizebits=\"16\" mmedmajorstridebits=\"0\" mmedminorstridebits=\"0\" />"
            + "<units>rpm</units><decimalpl>0</decimalpl><MATH equation=\"X\"><VAR id=\"X\" /></MATH></XDFCONSTANT>"
            + "</XDFFORMAT>";

    @Test
    public void convertsTablesAxesAndConstants() throws Exception {
        File file = File.createTempFile("test", ".xdf");
        file.deleteOnExit();
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), "ISO-8859-1");
        try {
            writer.write(XDF);
        } finally {
            writer.close();
        }
        byte[] bin = new byte[0x40];
        bin[0x10] = 8;
        bin[0x11] = 9;
        bin[0x12] = 10;
        // Map data, little endian words 1000 to 6000
        for (int i = 0; i < 6; i++) {
            bin[0x20 + 2 * i] = (byte) ((i + 1) * 1000);
            bin[0x21 + 2 * i] = (byte) (((i + 1) * 1000) >> 8);
        }
        bin[0x30] = 0x12;
        bin[0x31] = 0x34;
        Rom rom = RomFixture.load(new XDFConversionLayer().convertToDocumentTree(file), bin);

        Table3D map = (Table3D) rom.getTableByName("Map");
        assertEquals("Fuel", map.getCategory());
        assertEquals("ms", map.getCurrentScale().getUnit());
        // The base offset is subtracted from the addresses
        assertEquals(900, map.getXAxis().getData()[1].getRealValue(), 0);
        assertEquals("20.5", map.getYAxis().getData()[1].getStaticText());
        // Row by row, x changes fastest
        assertEquals(2.0, map.get3dData()[1][0].getRealValue(), 0);
        assertEquals(4.0, map.get3dData()[0][1].getRealValue(), 0);

        // Without type flags the defaults apply: unsigned, most significant byte first
        Table limit = rom.getTableByName("Limit");
        assertEquals("Limits", limit.getCategory());
        assertEquals(0x1234, limit.getData()[0].getBinValue(), 0);
    }
}
