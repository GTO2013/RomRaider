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

package com.romraider.swing;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Enumeration;
import java.util.List;
import java.util.ResourceBundle;

import com.romraider.maps.Rom;
import com.romraider.swing.RomTree;
import com.romraider.util.ResourceUtil;
import com.romraider.util.SettingsManager;

public class RomFilterPanel extends JPanel {

	private static final long serialVersionUID = 1L;

    private static final ResourceBundle rb = new ResourceUtil().getBundle(
    		RomFilterPanel.class.getName());
	
    private final DefaultMutableTreeNode imageRoot;
    private final RomTree imageList;
    private final JTextField filterField;
    private final JToggleButton modifiedOnlyButton;
    private boolean refilterPending = false;

    public RomFilterPanel(final DefaultMutableTreeNode imageRoot, final RomTree imageList) {
        super(new BorderLayout());
        this.imageRoot = imageRoot;
        this.imageList = imageList;

        JLabel label = new JLabel(rb.getString("LBLFILTER"));
        label.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
        filterField = new JTextField(20);
        filterField.setToolTipText(rb.getString("LBLTOOLTIP"));

        modifiedOnlyButton = new JToggleButton(new ModifiedCellIcon());
        modifiedOnlyButton.setToolTipText(rb.getString("BTNMODIFIEDTIP"));
        modifiedOnlyButton.setMargin(new Insets(1, 3, 1, 3));
        modifiedOnlyButton.setFocusable(false);

        add(label, BorderLayout.WEST);
        add(filterField, BorderLayout.CENTER);
        add(modifiedOnlyButton, BorderLayout.EAST);

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { refilter(); }
            @Override
            public void removeUpdate(DocumentEvent e) { refilter(); }
            @Override
            public void changedUpdate(DocumentEvent e) { refilter(); }
        });

        modifiedOnlyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { refilter(); }
        });
    }

    public void refilter() {
        final Enumeration<?> children = imageRoot.children();
        while (children.hasMoreElements()) {
            Object child = children.nextElement();
            if (child instanceof Rom) {
                refilter((Rom) child);
            }
        }
    }

    public void refilter(Rom rom) {
        List<TreePath> pathsToExpand = rom.refreshDisplayedTables(
                filterField.getText().trim(), modifiedOnlyButton.isSelected());

        DefaultTreeModel model = (DefaultTreeModel) imageList.getModel();
        model.reload(rom);

        for (TreePath path : pathsToExpand) {
            imageList.expandPath(path);
        }
        imageList.repaint();
    }

    // A table switched between modified and unmodified
    public void tableModifiedStateChanged() {
        if (!modifiedOnlyButton.isSelected() || refilterPending) return;

        // Coalesce, a single edit can flip many tables
        refilterPending = true;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                refilterPending = false;
                refilter();
            }
        });
    }

    // A table cell with the border edited cells get in table frames
    private static final class ModifiedCellIcon implements Icon {
        private static final int SIZE = 14;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(Color.WHITE);
            g.fillRect(x + 1, y + 3, SIZE - 2, SIZE - 6);
            g.setColor(SettingsManager.getSettings().getIncreaseBorder());
            g.drawRect(x + 1, y + 3, SIZE - 3, SIZE - 7);
            g.drawRect(x + 2, y + 4, SIZE - 5, SIZE - 9);
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }
}
