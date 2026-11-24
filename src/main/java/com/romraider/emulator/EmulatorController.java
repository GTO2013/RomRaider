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

import static javax.swing.JOptionPane.ERROR_MESSAGE;
import static javax.swing.JOptionPane.INFORMATION_MESSAGE;
import static javax.swing.JOptionPane.OK_CANCEL_OPTION;
import static javax.swing.JOptionPane.OK_OPTION;
import static javax.swing.JOptionPane.WARNING_MESSAGE;

import java.awt.Component;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.ResourceBundle;

import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.romraider.editor.ecu.ECUEditor;
import com.romraider.editor.ecu.ECUEditorManager;
import com.romraider.editor.ecu.OpenImageWorker;
import com.romraider.maps.Rom;
import com.romraider.util.ResourceUtil;
import com.romraider.util.SettingsManager;

/**
 * The editor's ROM emulator: connects one, links it to an open image and
 * keeps the menu's settings.  Used on the Swing thread; device I/O runs on
 * worker threads.
 */
public final class EmulatorController {
    private static final ResourceBundle rb = new ResourceUtil().getBundle(EmulatorController.class.getName());
    private static final EmulatorController INSTANCE = new EmulatorController();
    private static final long OPEN_TIMEOUT_MS = 10 * 60 * 1000;

    private EmulatorDriver driver;
    private String port;
    private EmulatorSession session;
    private String busy;
    private String lastPort;
    private boolean live = true;
    private boolean trace;
    private boolean accumulate;

    private final Timer statusTimer;
    private long lastHitTotal;
    private long lastHitTime;
    private double hitRate;

    private EmulatorController() {
        statusTimer = new Timer(1000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                updateStatus();
            }
        });
    }

    public static EmulatorController getInstance() {
        return INSTANCE;
    }

    // The device types there are drivers for
    static List<EmulatorDriver> drivers() {
        List<EmulatorDriver> list = new ArrayList<EmulatorDriver>();
        list.add(new OstrichDriver());
        return list;
    }

    public boolean isConnected() {
        return driver != null;
    }

    public boolean isBusy() {
        return busy != null;
    }

    public EmulatorSession getSession() {
        return session;
    }

    public EmulatorDriver getDriver() {
        return driver;
    }

    public String getPort() {
        return port;
    }

    public boolean isLive() {
        return live;
    }

    public boolean isTrace() {
        return trace;
    }

    public boolean isAccumulate() {
        return accumulate;
    }

    public double getHitRate() {
        return hitRate;
    }

    public void setLive(boolean live) {
        this.live = live;
        if (session != null) session.setLive(live);
        updateStatus();
    }

    public void setTrace(boolean trace) {
        this.trace = trace;
        if (session != null) session.setTrace(trace);
        updateStatus();
    }

    public void setAccumulate(boolean accumulate) {
        this.accumulate = accumulate;
        if (session != null) session.setAccumulate(accumulate);
    }

    public void clearHits() {
        if (session != null) session.clearHits();
    }

    // Asks for the device and port, connects and links the selected image
    public void connect(final Component parent) {
        if (driver != null || busy != null) return;
        final Rom rom = editor().getLastSelectedRom();
        if (rom == null) {
            JOptionPane.showMessageDialog(parent, rb.getString("NOIMAGE"), rb.getString("TITLE"), INFORMATION_MESSAGE);
            return;
        }

        List<EmulatorDriver> drivers = drivers();
        JComboBox driverBox = new JComboBox();
        for (EmulatorDriver d : drivers) driverBox.addItem(d.getName());
        List<String[]> ports = SerialTransport.listPorts();
        if (ports.isEmpty()) {
            JOptionPane.showMessageDialog(parent, rb.getString("NOPORTS"), rb.getString("TITLE"), WARNING_MESSAGE);
            return;
        }
        JComboBox portBox = new JComboBox();
        int pick = -1;
        for (int i = 0; i < ports.size(); i++) {
            String[] p = ports.get(i);
            portBox.addItem(p[0] + "  " + p[1]);
            if (p[0].equals(lastPort)) pick = i;
        }
        for (int i = 0; i < ports.size() && pick < 0; i++) {
            String description = ports.get(i)[1].toLowerCase();
            if (description.contains("ostrich") && !description.contains("datalog")) pick = i;
        }
        if (pick >= 0) portBox.setSelectedIndex(pick);

        JPanel panel = new JPanel(new GridLayout(0, 2, 6, 6));
        panel.add(new JLabel(rb.getString("DEVICE")));
        panel.add(driverBox);
        panel.add(new JLabel(rb.getString("PORT")));
        panel.add(portBox);
        if (JOptionPane.showConfirmDialog(parent, panel, rb.getString("CONNECT"), OK_CANCEL_OPTION) != OK_OPTION) {
            return;
        }

        final EmulatorDriver d = drivers.get(driverBox.getSelectedIndex());
        final String portName = ports.get(portBox.getSelectedIndex())[0];
        lastPort = portName;
        setBusy(MessageFormat.format(rb.getString("CONNECTING"), portName));
        background(new Runnable() {
            @Override
            public void run() {
                Transport t = null;
                try {
                    t = new SerialTransport(portName);
                    d.open(t, rom.getBinary().length);
                    final byte[] contents = readAll(d);
                    later(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(null);
                            driver = d;
                            port = portName;
                            statusTimer.start();
                            connected(parent, rom, contents);
                        }
                    });
                } catch (final IOException e) {
                    if (t != null) t.close();
                    later(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(null);
                            error(parent, e);
                        }
                    });
                }
            }
        });
    }

    public void disconnect() {
        if (session != null) {
            try {
                session.detach();
            } catch (IOException e) {
                // Closing anyway
            }
            session = null;
        }
        if (driver != null) driver.close();
        driver = null;
        port = null;
        statusTimer.stop();
        updateStatus();
    }

    // Sends the selected image to the emulator and links it
    public void upload(Component parent) {
        Rom rom = editor().getLastSelectedRom();
        if (rom == null) {
            JOptionPane.showMessageDialog(parent, rb.getString("NOIMAGE"), rb.getString("TITLE"), INFORMATION_MESSAGE);
            return;
        }
        upload(parent, rom);
    }

    // Reads the emulator's image into a file and opens it
    public void openContents(final Component parent) {
        final EmulatorDriver d = takeDriver(parent);
        if (d == null) return;
        setBusy(rb.getString("READING"));
        background(new Runnable() {
            @Override
            public void run() {
                try {
                    final byte[] contents = readAll(d);
                    later(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(null);
                            saveAndOpen(parent, contents);
                        }
                    });
                } catch (IOException e) {
                    lostLater(parent, e);
                }
            }
        });
    }

    private void connected(Component parent, Rom rom, byte[] contents) {
        byte[] bin = rom.getBinary();
        int differ = 0;
        for (int i = 0; i < bin.length; i++) if (bin[i] != contents[i]) differ++;
        if (differ == 0) {
            link(parent, rom, contents);
            return;
        }

        Object[] options = {rb.getString("UPLOAD"), rb.getString("OPENCONTENTS"), rb.getString("DISCONNECT")};
        int choice = JOptionPane.showOptionDialog(parent,
                MessageFormat.format(rb.getString("DIFFERS"), differ, rom.getFileName()),
                rb.getString("TITLE"), JOptionPane.DEFAULT_OPTION, WARNING_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            upload(parent, rom);
        } else if (choice == 1) {
            saveAndOpen(parent, contents);
        } else {
            disconnect();
        }
    }

    private void upload(final Component parent, final Rom rom) {
        if (driver != null && rom.getBinary().length != driver.getImageSize()) {
            JOptionPane.showMessageDialog(parent, MessageFormat.format(rb.getString("WRONGSIZE"),
                    driver.getImageSize(), rom.getBinary().length), rb.getString("TITLE"), WARNING_MESSAGE);
            return;
        }
        final EmulatorDriver d = takeDriver(parent);
        if (d == null) return;
        rom.updateChecksumsQuietly();
        final byte[] image = rom.getBinary().clone();
        setBusy(rb.getString("UPLOADING"));
        background(new Runnable() {
            @Override
            public void run() {
                try {
                    d.write(0, image, 0, image.length);
                    later(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(null);
                            if (editor().getImages().contains(rom)) link(parent, rom, image);
                        }
                    });
                } catch (IOException e) {
                    lostLater(parent, e);
                }
            }
        });
    }

    private void saveAndOpen(final Component parent, final byte[] contents) {
        File dir = SettingsManager.getSettings().getLastImageDir();
        JFileChooser fc = new JFileChooser(dir);
        fc.setSelectedFile(new File(dir, MessageFormat.format(rb.getString("FILENAME"),
                new SimpleDateFormat("yyyyMMdd_HHmm").format(new Date()))));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            updateStatus();
            return;
        }
        final File file = fc.getSelectedFile();
        try {
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(contents);
            } finally {
                out.close();
            }
        } catch (IOException e) {
            error(parent, e);
            return;
        }

        final OpenImageWorker worker = new OpenImageWorker(file);
        worker.addPropertyChangeListener(editor().getStatusPanel());
        worker.execute();
        setBusy(rb.getString("OPENING"));
        background(new Runnable() {
            @Override
            public void run() {
                Rom opened = null;
                try {
                    opened = worker.waitForAddedRom(OPEN_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                final Rom rom = opened;
                later(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(null);
                        if (rom == null) {
                            JOptionPane.showMessageDialog(parent,
                                    MessageFormat.format(rb.getString("NOTOPENED"), file.getAbsolutePath()),
                                    rb.getString("TITLE"), WARNING_MESSAGE);
                        } else if (driver != null) {
                            link(parent, rom, contents);
                        }
                    }
                });
            }
        });
    }

    private void link(final Component parent, Rom rom, byte[] contents) {
        if (driver == null) return;
        session = new EmulatorSession(driver, rom, contents);
        session.setLive(live);
        session.setTrace(trace);
        session.setAccumulate(accumulate);
        lastHitTotal = 0;
        lastHitTime = System.currentTimeMillis();
        hitRate = 0;
        session.start(new EmulatorSession.Listener() {
            @Override
            public void ended(EmulatorSession s, IOException cause) {
                if (s != session) return;
                session = null;
                if (cause != null) lost(parent, cause);
                updateStatus();
            }
        });
        updateStatus();
    }

    // Ends the session and hands back the driver, or null after an error
    private EmulatorDriver takeDriver(Component parent) {
        if (driver == null || busy != null) return null;
        if (session != null) {
            EmulatorSession s = session;
            session = null;
            try {
                s.detach();
            } catch (IOException e) {
                lost(parent, e);
                return null;
            }
        }
        return driver;
    }

    private void lost(Component parent, IOException e) {
        if (session != null) {
            session = null;
        }
        if (driver != null) driver.close();
        driver = null;
        port = null;
        statusTimer.stop();
        updateStatus();
        error(parent, e);
    }

    private void lostLater(final Component parent, final IOException e) {
        later(new Runnable() {
            @Override
            public void run() {
                setBusy(null);
                lost(parent, e);
            }
        });
    }

    private void setBusy(String what) {
        busy = what;
        updateStatus();
    }

    // The emulator's state for the status bar; empty with none connected
    public String getStatusText() {
        if (busy != null) return busy;
        if (driver == null) return "";
        String device = MessageFormat.format(rb.getString("DEVICEPORT"), driver.getDescription(), port);
        if (session == null) return MessageFormat.format(rb.getString("STATUSNOIMAGE"), device);
        String state = rb.getString(live ? "STATUSLIVE" : "STATUSPAUSED");
        String traceText;
        if (session.getTraceProblem() != null) {
            traceText = rb.getString("STATUSNOTRACE");
        } else if (trace) {
            traceText = MessageFormat.format(rb.getString("STATUSTRACE"), Math.round(hitRate));
        } else {
            traceText = "";
        }
        return MessageFormat.format(rb.getString("STATUSLINKED"), device, session.getRom().getFileName(), state,
                traceText);
    }

    private void updateStatus() {
        if (session != null) {
            long now = System.currentTimeMillis();
            long total = session.getHitTotal();
            if (now > lastHitTime && total >= lastHitTotal) {
                hitRate = (total - lastHitTotal) * 1000.0 / (now - lastHitTime);
            }
            lastHitTotal = total;
            lastHitTime = now;
            if (session.getTraceProblem() != null && trace) trace = false;
        }
        ECUEditor e = ECUEditorManager.getECUEditorWithoutCreation();
        if (e != null) e.getStatusPanel().setEmulatorStatus(getStatusText());
    }

    private void error(Component parent, IOException e) {
        JOptionPane.showMessageDialog(parent, e.getMessage(), rb.getString("TITLE"), ERROR_MESSAGE);
    }

    private static byte[] readAll(EmulatorDriver d) throws IOException {
        byte[] b = new byte[d.getImageSize()];
        d.read(0, b, 0, b.length);
        return b;
    }

    private static ECUEditor editor() {
        return ECUEditorManager.getECUEditor();
    }

    private static void background(Runnable r) {
        Thread t = new Thread(r, "Emulator");
        t.setDaemon(true);
        t.start();
    }

    private static void later(Runnable r) {
        SwingUtilities.invokeLater(r);
    }
}
