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

import java.beans.PropertyVetoException;
import java.util.Collection;

import com.romraider.editor.ecu.ECUEditorManager;
import com.romraider.maps.DataCell;
import com.romraider.maps.Table;
import com.romraider.swing.TableFrame;
import com.romraider.swing.TableTreeNode;

// The one path that makes MCP edits visible: every touched table is opened and its changes selected
final class McpView {
    private McpView() {
    }

    static void reveal(Table table) {
        reveal(table, null);
    }

    static void reveal(Table table, Collection<DataCell> changed) {
        TableTreeNode node = table.getRom().getTableNodeByName(table.getName());
        if (node == null) return;

        // displayTable toggles an open frame, so it is only used to open a closed one
        if (!isOpen(table)) {
            table.setTableFrame(null);
            ECUEditorManager.getECUEditor().displayTable(node);
        }
        TableFrame frame = table.getTableFrame();
        if (frame != null) {
            try {
                frame.setIcon(false);
                frame.setSelected(true);
            } catch (PropertyVetoException e) {
                // The frame stays where it is
            }
            frame.toFront();
        }

        if (changed != null && !changed.isEmpty()) {
            table.clearSelection();
            for (DataCell cell : changed) {
                cell.setSelected(true);
            }
        }
        table.colorCells();
    }

    // False once the user closed the table's frame
    static boolean isOpen(Table table) {
        TableFrame frame = table.getTableFrame();
        return frame != null && !frame.isClosed() && frame.getParent() != null;
    }
}
