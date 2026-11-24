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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.romraider.maps.DataCell;
import com.romraider.maps.Rom;
import com.romraider.maps.Table;
import com.romraider.maps.Table2D;
import com.romraider.swing.JProgressPane;
import com.romraider.util.SettingsManager;
import com.romraider.xml.DOMRomUnmarshaller;

public class McpTablesTest {
    private static final String DEFINITION =
            "<roms><rom>"
            + "<romid><xmlid>TESTROM</xmlid><internalidaddress>0</internalidaddress>"
            + "<internalidstring>TEST</internalidstring><filesize>64b</filesize></romid>"
            + "<table name=\"Existing\" type=\"2D\" category=\"Fuel\" storageaddress=\"0x10\" "
            + "storagetype=\"uint8\" endian=\"big\" sizey=\"4\">"
            + "<scaling units=\"ms\" expression=\"x*0.5\" to_byte=\"x/0.5\" format=\"0.0\"/>"
            + "<table type=\"Y Axis\" name=\"RPM\" storageaddress=\"0x14\" storagetype=\"uint8\">"
            + "<scaling units=\"rpm\" expression=\"x*100\" to_byte=\"x/100\" format=\"#\"/></table>"
            + "</table>"
            + "<table name=\"Gone\" omit=\"true\"/>"
            + "<table name=\"Later\" type=\"1D\" category=\"Misc\" storageaddress=\"0x18\" storagetype=\"uint8\"/>"
            + "</rom></roms>";

    private Rom rom;
    private byte[] bin;

    @Before
    public void openRom() throws Exception {
        SettingsManager.setTesting(true);
        bin = new byte[64];
        for (int i = 0; i < bin.length; i++) bin[i] = (byte) i;
        // Float 1.5f and 2.5f at 0x30, big endian
        System.arraycopy(new byte[] {0x3F, (byte) 0xC0, 0, 0, 0x40, 0x20, 0, 0}, 0, bin, 0x30, 8);

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(DEFINITION.getBytes("UTF-8")));
        Element romNode = (Element) doc.getElementsByTagName("rom").item(0);
        JProgressPane progress = new JProgressPane();
        rom = new DOMRomUnmarshaller().unmarshallXMLDefinition(null, doc.getDocumentElement(), romNode, bin, progress);
        rom.setDocument(doc);
        rom.populateTables(bin, progress);
    }

    @Test
    public void readsDefinedTable() throws Exception {
        JsonObject json = read("Existing");
        assertEquals("[8,8.5,9,9.5]", json.get("values").toString());
        assertEquals("[2000,2100,2200,2300]", json.get("axis").asObject().get("values").toString());
        assertTrue(rom.getRomNode() != null);
    }

    @Test
    public void omittedTableDoesNotHideLaterTables() {
        assertNotNull(rom.getTableByName("Later"));
    }

    @Test
    public void probes3DTableAsRows() throws Exception {
        JsonObject spec = Json.parse("{\"name\":\"Probe\",\"type\":\"3D\",\"address\":\"0x24\","
                + "\"storage_type\":\"uint8\",\"size_x\":3,\"size_y\":2,"
                + "\"x_axis\":{\"name\":\"X\",\"address\":\"0x20\",\"storage_type\":\"uint8\"},"
                + "\"y_axis\":{\"name\":\"Y\",\"values\":[\"low\",\"high\"]}}").asObject();
        Table table = DefinitionEditor.build(rom, spec, new McpCall(null));
        table.populateTable(rom);
        JsonObject json = TableJson.read(table, false);
        rom.unregisterTable(table);

        assertEquals("[32,33,34]", json.get("x_axis").asObject().get("values").toString());
        assertEquals("[\"low\",\"high\"]", json.get("y_axis").asObject().get("values").toString());
        // Row major in the file: x varies fastest
        assertEquals("[[36,37,38],[39,40,41]]", json.get("values").toString());
    }

    @Test
    public void specRoundTripKeepsTable() throws Exception {
        Table existing = rom.getTableByName("Existing");
        JsonObject spec = TableSpec.fromTable(existing);
        Table rebuilt = DefinitionEditor.build(rom, spec, new McpCall(null));
        rebuilt.populateTable(rom);

        assertEquals(TableJson.read(existing, false).get("values"), TableJson.read(rebuilt, false).get("values"));
        assertEquals(TableJson.read(existing, false).get("axis"), TableJson.read(rebuilt, false).get("axis"));
        assertEquals("Fuel", rebuilt.getCategory());
        rom.unregisterTable(rebuilt);
    }

    @Test
    public void mergePatchesFirstScalingAndNestedAxis() throws Exception {
        JsonObject base = TableSpec.fromTable(rom.getTableByName("Existing"));
        JsonObject patch = Json.parse("{\"scaling\":{\"expression\":\"x*2\",\"to_byte\":\"x/2\"},"
                + "\"axis\":{\"address\":\"0x15\"}}").asObject();
        JsonObject merged = TableSpec.merge(base, patch);

        JsonObject scaling = merged.get("scalings").asArray().get(0).asObject();
        assertEquals("x*2", scaling.getString("expression", null));
        assertEquals("ms", scaling.getString("units", null));
        JsonObject axis = merged.get("axis").asObject();
        assertEquals("0x15", axis.getString("address", null));
        assertEquals("uint8", axis.getString("storage_type", null));
    }

    @Test
    public void realValuesConvertIndependentOfLocale() throws Exception {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            DataCell cell = rom.getTableByName("Existing").getData()[0];
            cell.setBinValue(cell.toBinValue(2.5));
            assertEquals(5.0, cell.getBinValue(), 0);
            assertEquals(5, bin[0x10]);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void tracksModifiedTablesAndFiltersTree() throws Exception {
        Table2D table = (Table2D) rom.getTableByName("Existing");
        assertFalse(table.isModified());

        table.getAxis().getData()[1].setBinValue(30);
        table.getData()[2].setBinValue(99);
        assertTrue(table.isModified());
        assertEquals(2, modifiedCells(table));

        rom.refreshDisplayedTables(null, true);
        assertEquals(1, rom.getChildCount());
        assertEquals("Fuel", rom.getChildAt(0).toString());

        table.undoAll();
        assertFalse(table.isModified());
        assertEquals(0, modifiedCells(table));

        rom.refreshDisplayedTables(null, false);
        assertEquals(2, rom.getChildCount());
    }

    @Test
    public void floatTablesAliasByByteOffset() throws Exception {
        Table pair = DefinitionEditor.build(rom, Json.parse("{\"name\":\"Pair\",\"type\":\"1D\",\"address\":\"0x30\","
                + "\"storage_type\":\"float\",\"size\":2}").asObject(), new McpCall(null));
        Table second = DefinitionEditor.build(rom, Json.parse("{\"name\":\"Second\",\"type\":\"1D\",\"address\":\"0x34\","
                + "\"storage_type\":\"float\"}").asObject(), new McpCall(null));
        assertTrue(rom.populateTable(pair));
        assertTrue(rom.populateTable(second));
        assertEquals(2.5, second.getData()[0].getBinValue(), 0);

        pair.getData()[1].setBinValue(7.25);
        assertEquals(7.25, second.getData()[0].getBinValue(), 0);
    }

    @Test(expected = McpException.class)
    public void rejectsStaticAxisOfWrongSize() throws Exception {
        TableSpec.toElement(rom.getDocument(), Json.parse("{\"name\":\"Bad\",\"type\":\"2D\",\"address\":16,"
                + "\"storage_type\":\"uint8\",\"size\":3,\"axis\":{\"values\":[1,2]}}").asObject());
    }

    private static int modifiedCells(Table table) {
        int count = 0;
        for (DataCell cell : table.getAllCells()) {
            if (cell.isModified()) count++;
        }
        return count;
    }

    private JsonObject read(String table) throws Exception {
        return TableJson.read(McpCall.findTable(rom, table), false);
    }
}
