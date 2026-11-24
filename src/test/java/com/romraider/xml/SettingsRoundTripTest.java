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

package com.romraider.xml;

import static org.junit.Assert.assertEquals;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;

import com.romraider.Settings;
import com.romraider.swing.JProgressPane;

// The editor settings survive being saved and loaded again
public class SettingsRoundTripTest {

    @Test
    public void editorSettingsComeBackAsSaved() throws Exception {
        Settings saved = new Settings();
        saved.setWindowMaximized(true);
        saved.setWindowSize(new Dimension(1024, 700));
        saved.setWindowLocation(new Point(5, 6));
        saved.setSplitPaneLocation(321);
        saved.addEcuDefinitionFile(new File("defs/a.xml").getAbsoluteFile());
        saved.addEcuDefinitionFile(new File("defs/b.xdf").getAbsoluteFile());
        saved.setLastImageDir(new File("images").getAbsoluteFile());
        saved.setUserLevel(4);
        saved.setTableClickCount(1);
        saved.setTableClickBehavior(1);
        saved.setTableTreeSorted(true);
        saved.setSaveDebugTables(true);
        saved.setDisplayHighTables(true);
        saved.setValueLimitWarning(false);
        saved.setColorAxis(true);
        saved.setOpenExpanded(false);
        saved.setAlwaysOpenTableAtZero(true);
        saved.setDefaultScale("Standard");
        saved.setScaleHeadersAndData(false);
        saved.setTableFont(new Font("Dialog", Font.ITALIC, 14));
        saved.setCellSize(new Dimension(50, 20));
        saved.setMaxColor(new Color(1, 2, 3));
        saved.setWarningColor(new Color(4, 5, 6));
        saved.setTableClipboardFormat(Settings.CUSTOM_CLIPBOARD_FORMAT);
        saved.setTable2DHeader("[2D]");
        saved.setTable3DHeader("[3D]");
        saved.setEditorIconScale(150);
        saved.setTableIconScale(75);
        saved.setMcpEnabled(true);
        saved.setMcpPort(9123);

        File file = File.createTempFile("settings", ".xml");
        file.deleteOnExit();
        new DOMSettingsBuilder().buildSettings(saved, file, new JProgressPane(), "1.2.3");
        Settings loaded = new DOMSettingsUnmarshaller().unmarshallSettings(
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getDocumentElement());

        assertEquals(saved.isWindowMaximized(), loaded.isWindowMaximized());
        assertEquals(saved.getWindowSize(), loaded.getWindowSize());
        assertEquals(saved.getWindowLocation(), loaded.getWindowLocation());
        assertEquals(saved.getSplitPaneLocation(), loaded.getSplitPaneLocation());
        assertEquals(saved.getEcuDefinitionFiles(), loaded.getEcuDefinitionFiles());
        assertEquals(saved.getLastImageDir(), loaded.getLastImageDir());
        assertEquals(saved.getUserLevel(), loaded.getUserLevel());
        assertEquals(saved.getTableClickCount(), loaded.getTableClickCount());
        assertEquals(saved.getTableClickBehavior(), loaded.getTableClickBehavior());
        assertEquals(saved.isTableTreeSorted(), loaded.isTableTreeSorted());
        assertEquals(saved.isSaveDebugTables(), loaded.isSaveDebugTables());
        assertEquals(saved.isDisplayHighTables(), loaded.isDisplayHighTables());
        assertEquals(saved.isValueLimitWarning(), loaded.isValueLimitWarning());
        assertEquals(saved.isColorAxis(), loaded.isColorAxis());
        assertEquals(saved.isOpenExpanded(), loaded.isOpenExpanded());
        assertEquals(saved.isAlwaysOpenTableAtZero(), loaded.isAlwaysOpenTableAtZero());
        assertEquals(saved.getDefaultScale(), loaded.getDefaultScale());
        assertEquals(saved.isScaleHeadersAndData(), loaded.isScaleHeadersAndData());
        assertEquals(saved.getTableFont(), loaded.getTableFont());
        assertEquals(saved.getCellSize(), loaded.getCellSize());
        assertEquals(saved.getMaxColor(), loaded.getMaxColor());
        assertEquals(saved.getWarningColor(), loaded.getWarningColor());
        assertEquals(saved.getTable2DHeader(), loaded.getTable2DHeader());
        assertEquals(saved.getTable3DHeader(), loaded.getTable3DHeader());
        assertEquals(saved.getEditorIconScale(), loaded.getEditorIconScale());
        assertEquals(saved.getTableIconScale(), loaded.getTableIconScale());
        assertEquals(saved.isMcpEnabled(), loaded.isMcpEnabled());
        assertEquals(saved.getMcpPort(), loaded.getMcpPort());
        assertEquals("1.2.3", loaded.getRecentVersion());
    }
}
