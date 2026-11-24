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

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ResourceBundle;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import com.romraider.editor.ecu.ECUEditorManager;
import com.romraider.util.ResourceUtil;

// The editor's Emulator menu
public final class EmulatorMenu extends JMenu implements ActionListener, MenuListener {
    private static final long serialVersionUID = 1L;
    private static final ResourceBundle rb = new ResourceUtil().getBundle(EmulatorMenu.class.getName());

    private final JMenuItem connect = new JMenuItem(rb.getString("CONNECT"));
    private final JMenuItem disconnect = new JMenuItem(rb.getString("DISCONNECT"));
    private final JMenuItem upload = new JMenuItem(rb.getString("UPLOAD"));
    private final JMenuItem openContents = new JMenuItem(rb.getString("OPENCONTENTS"));
    private final JCheckBoxMenuItem live = new JCheckBoxMenuItem(rb.getString("LIVE"));
    private final JCheckBoxMenuItem trace = new JCheckBoxMenuItem(rb.getString("TRACE"));
    private final JCheckBoxMenuItem accumulate = new JCheckBoxMenuItem(rb.getString("ACCUMULATE"));
    private final JMenuItem clearHits = new JMenuItem(rb.getString("CLEARHITS"));

    public EmulatorMenu() {
        super(rb.getString("EMULATOR"));
        setMnemonic('M');
        addItem(connect, 'C');
        addItem(disconnect, 'D');
        addSeparator();
        addItem(upload, 'U');
        addItem(openContents, 'O');
        addSeparator();
        addItem(live, 'L');
        addItem(trace, 'T');
        addItem(accumulate, 'A');
        addItem(clearHits, 'H');
        addMenuListener(this);
        update();
    }

    private void addItem(JMenuItem item, char mnemonic) {
        item.setMnemonic(mnemonic);
        item.addActionListener(this);
        add(item);
    }

    private static EmulatorController controller() {
        return EmulatorController.getInstance();
    }

    private void update() {
        EmulatorController c = controller();
        boolean idle = !c.isBusy();
        connect.setEnabled(idle && !c.isConnected());
        disconnect.setEnabled(c.isConnected());
        upload.setEnabled(idle && c.isConnected());
        openContents.setEnabled(idle && c.isConnected());
        live.setSelected(c.isLive());
        trace.setSelected(c.isTrace());
        accumulate.setSelected(c.isAccumulate());
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        EmulatorController c = controller();
        Object s = e.getSource();
        java.awt.Component parent = SwingUtilities.getWindowAncestor(this);
        if (parent == null) parent = ECUEditorManager.getECUEditor();
        if (s == connect) {
            c.connect(parent);
        } else if (s == disconnect) {
            c.disconnect();
        } else if (s == upload) {
            c.upload(parent);
        } else if (s == openContents) {
            c.openContents(parent);
        } else if (s == live) {
            c.setLive(live.isSelected());
        } else if (s == trace) {
            c.setTrace(trace.isSelected());
        } else if (s == accumulate) {
            c.setAccumulate(accumulate.isSelected());
        } else if (s == clearHits) {
            c.clearHits();
        }
    }

    @Override
    public void menuSelected(MenuEvent e) {
        update();
    }

    @Override
    public void menuDeselected(MenuEvent e) {
    }

    @Override
    public void menuCanceled(MenuEvent e) {
    }
}
