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

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.eclipsesource.json.JsonObject;
import com.romraider.Settings;
import com.romraider.editor.ecu.ECUEditorManager;
import com.romraider.maps.Rom;
import com.romraider.maps.Table;
import com.romraider.swing.TableFrame;
import com.romraider.xml.TableScaleUnmarshaller;
import com.romraider.xml.ConversionLayer.ConversionLayer;

/**
 * Adds, redefines and removes tables of an open rom. The rom's definition
 * document is edited alongside, so exporting the definition keeps the changes.
 */
final class DefinitionEditor {
    // Per rom: lower case table name to the element written this session, an omit element for removals
    private static final Map<Rom, Map<String, Element>> CHANGED = new WeakHashMap<Rom, Map<String, Element>>();
    private static final Map<Rom, Set<String>> CREATED = new WeakHashMap<Rom, Set<String>>();

    private DefinitionEditor() {
    }

    // Parses a spec into an unpopulated table, without adding it to the rom
    static Table build(Rom rom, JsonObject spec, McpCall call) throws Exception {
        return build(rom, TableSpec.toElement(rom.getDocument(), spec), call);
    }

    private static Table build(Rom rom, Element element, McpCall call) throws Exception {
        TableScaleUnmarshaller unmarshaller = new TableScaleUnmarshaller();
        unmarshaller.unmarshallBaseScales(rom.getDocument().getDocumentElement());

        Settings.Endian memModel = memModelEndian(rom);
        if (memModel != null) {
            String endian = memModel == Settings.Endian.LITTLE ? "little" : "big";
            unmarshaller.setMemModelEndian(endian);
            if (element.hasAttribute("endian") && !element.getAttribute("endian").equalsIgnoreCase(endian)) {
                call.warn("The definition's memmodel forces " + endian + " endian");
            }
        }
        return unmarshaller.unmarshallTable(element, TableSpec.newTable(element), rom);
    }

    private static Settings.Endian memModelEndian(Rom rom) {
        for (Table table : rom.getTables()) {
            return table.getMemModelEndian();
        }
        return null;
    }

    static Table add(Rom rom, JsonObject spec, McpCall call) throws Exception {
        Element element = TableSpec.toElement(rom.getDocument(), spec);
        String name = element.getAttribute("name");
        if (rom.getTableByName(name) != null) {
            throw new McpException("A table named '" + name + "' exists, use table_update");
        }

        Table table = build(rom, element, call);
        populate(rom, table);

        rom.addTableByName(table);
        rom.getRomNode().appendChild(element);
        record(rom, name, element);
        created(rom).add(name.toLowerCase());

        table.setDefinitionChanged(true);
        ECUEditorManager.getECUEditor().getRomFilterPanel().refilter(rom);
        McpView.reveal(table);
        return table;
    }

    static Table update(Rom rom, Table old, JsonObject patch, McpCall call) throws Exception {
        JsonObject spec = TableSpec.merge(TableSpec.fromTable(old), patch);
        Element element = TableSpec.toElement(rom.getDocument(), spec);
        String name = element.getAttribute("name");
        if (!name.equalsIgnoreCase(old.getName()) && rom.getTableByName(name) != null) {
            throw new McpException("A table named '" + name + "' exists");
        }

        Table table = build(rom, element, call);
        populate(rom, table);

        // The frame shows the old table, reveal opens one for the rebuilt table
        closeFrame(old);
        rom.removeTableByName(old);
        rom.unregisterTable(old);
        rom.addTableByName(table);

        Node romNode = rom.getRomNode();
        Element existing = directElement(romNode, old.getName());
        if (existing != null) {
            romNode.replaceChild(element, existing);
        } else {
            // Overrides the table of a base definition
            romNode.appendChild(element);
        }
        if (!name.equalsIgnoreCase(old.getName())) {
            boolean wasCreated = created(rom).contains(old.getName().toLowerCase());
            omitIfInherited(rom, old.getName());
            if (wasCreated) created(rom).add(name.toLowerCase());
        }
        record(rom, name, element);

        table.setDefinitionChanged(true);
        ECUEditorManager.getECUEditor().getRomFilterPanel().refilter(rom);
        McpView.reveal(table);
        return table;
    }

    static void remove(Rom rom, Table table) {
        closeFrame(table);
        rom.removeTableByName(table);
        rom.unregisterTable(table);

        Element existing = directElement(rom.getRomNode(), table.getName());
        if (existing != null) rom.getRomNode().removeChild(existing);
        omitIfInherited(rom, table.getName());

        ECUEditorManager.getECUEditor().getRomFilterPanel().refilter(rom);
    }

    private static void closeFrame(Table table) {
        TableFrame frame = table.getTableFrame();
        if (frame != null) {
            ECUEditorManager.getECUEditor().removeDisplayTable(frame);
            frame.dispose();
        }
        table.setTableFrame(null);
        table.setTableView(null);
    }

    // A table that a base definition provides needs an omit marker to stay removed
    private static void omitIfInherited(Rom rom, String name) {
        Element romNode = (Element) rom.getRomNode();
        if (created(rom).remove(name.toLowerCase())) {
            changed(rom).remove(name.toLowerCase());
            return;
        }
        if (romNode.hasAttribute("base")) {
            Element omit = rom.getDocument().createElement("table");
            omit.setAttribute("name", name);
            omit.setAttribute("omit", "true");
            romNode.appendChild(omit);
            record(rom, name, omit);
        } else {
            changed(rom).remove(name.toLowerCase());
        }
    }

    private static void populate(Rom rom, Table table) throws McpException {
        if (!rom.populateTable(table)) {
            rom.getFaultyTables().remove(table.getName());
            rom.unregisterTable(table);
            throw new McpException("'" + table.getName() + "' does not fit the binary ("
                    + rom.getBinary().length + " bytes, ram offset " + Js.hex(rom.getRomID().getRamOffset()) + ")");
        }
    }

    private static Element directElement(Node romNode, String name) {
        NodeList children = romNode.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && child.getNodeName().equalsIgnoreCase("table")
                    && name.equalsIgnoreCase(((Element) child).getAttribute("name"))
                    && !"true".equalsIgnoreCase(((Element) child).getAttribute("omit"))) {
                return (Element) child;
            }
        }
        return null;
    }

    private static void record(Rom rom, String name, Element element) {
        changed(rom).put(name.toLowerCase(), element);
    }

    private static Map<String, Element> changed(Rom rom) {
        Map<String, Element> changed = CHANGED.get(rom);
        if (changed == null) {
            changed = new LinkedHashMap<String, Element>();
            CHANGED.put(rom, changed);
        }
        return changed;
    }

    private static Set<String> created(Rom rom) {
        Set<String> created = CREATED.get(rom);
        if (created == null) {
            created = new HashSet<String>();
            CREATED.put(rom, created);
        }
        return created;
    }

    static int changedCount(Rom rom) {
        Map<String, Element> changed = CHANGED.get(rom);
        return changed == null ? 0 : changed.size();
    }

    // The table elements changed this session, to paste into the rom's definition
    static String exportChanged(Rom rom) throws Exception {
        Document out = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element root = out.createElement("tables");
        root.setAttribute("xmlid", rom.getRomIDString());
        out.appendChild(root);
        for (Element element : changed(rom).values()) {
            root.appendChild(out.importNode(element, true));
        }
        return ConversionLayer.convertDocumentToString(out);
    }
}
