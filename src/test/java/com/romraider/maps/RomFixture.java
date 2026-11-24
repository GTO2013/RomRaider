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

package com.romraider.maps;

import java.io.StringReader;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import com.romraider.util.SettingsManager;
import com.romraider.xml.DOMRomUnmarshaller;

/**
 * Builds roms from definition XML and image bytes the way File > Open does,
 * without the editor and on default settings.
 */
public final class RomFixture {
    public static final String ID = "TEST";

    private RomFixture() {
    }

    // A <rom> matched by ID at address 0 of an image of the given size
    public static String rom(String xmlid, int size, String tables) {
        return "<rom><romid><xmlid>" + xmlid + "</xmlid><internalidaddress>0</internalidaddress>"
                + "<internalidstring>" + ID + "</internalidstring><filesize>" + size + "b</filesize></romid>"
                + tables + "</rom>";
    }

    // An image starting with ID
    public static byte[] image(int size) {
        byte[] bin = new byte[size];
        System.arraycopy(ID.getBytes(), 0, bin, 0, ID.length());
        return bin;
    }

    public static Rom load(String rom, byte[] bin) throws Exception {
        return loadDefinition("<roms>" + rom + "</roms>", bin);
    }

    // Loads the <rom> of a whole definition whose id matches the image
    public static Rom loadDefinition(String roms, byte[] bin) throws Exception {
        SettingsManager.setTesting(true);
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(roms)));
        Node romNode = new DOMRomUnmarshaller().checkDefinitionMatch(doc.getDocumentElement(), bin);
        if (romNode == null) throw new IllegalArgumentException("No rom of the definition matches the image");

        Rom rom = new DOMRomUnmarshaller().unmarshallXMLDefinition(null, doc.getDocumentElement(), romNode, bin, null);
        rom.setDocument(doc);
        rom.populateTables(bin, null);
        return rom;
    }

    // Selects cells, like dragging over them in a table frame
    public static void select(DataCell... cells) {
        for (DataCell cell : cells) {
            cell.setSelected(true);
        }
    }
}
