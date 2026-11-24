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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.swing.JInternalFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.romraider.editor.ecu.ECUEditor;
import com.romraider.editor.ecu.ECUEditorManager;
import com.romraider.maps.Rom;
import com.romraider.maps.Table;
import com.romraider.maps.TableView;
import com.romraider.swing.TableFrame;

/**
 * Keeps an emulator serving the image of one open rom.
 *
 * Every TICK_MS the Swing thread compares the image with what was sent last.
 * Changed bytes, from any edit, get their checksums updated and are queued for
 * the I/O thread, which owns the driver: it writes them, runs the address hit
 * trace and hands the hits back.  The trace window spans the rom's open
 * tables, or all its tables while none is open.
 */
public final class EmulatorSession {
    static final int TICK_MS = 100;
    static final int POLL_MS = 50;
    // Changes this close together go out in one write
    static final int MERGE_GAP = 8;

    // Told on the Swing thread when the session ends by itself
    public interface Listener {
        // cause is the I/O error that closed the driver, or null when the
        // image was closed and the driver is free for another one
        void ended(EmulatorSession session, IOException cause);
    }

    private static final class Write {
        final int offset;
        final byte[] data;

        Write(int offset, byte[] data) {
            this.offset = offset;
            this.data = data;
        }
    }

    private final EmulatorDriver driver;
    private final Rom rom;
    private final byte[] sent;
    private final HitMap hitMap;
    private final HitDisplay hits;

    // Shared with the I/O thread
    private final Object lock = new Object();
    private final LinkedList<Write> writes = new LinkedList<Write>();
    private int[] wantedWindow;
    private final ConcurrentLinkedQueue<int[]> hitQueue = new ConcurrentLinkedQueue<int[]>();
    private volatile boolean running;
    private volatile String traceProblem;
    private volatile long bytesSent;

    // I/O thread only
    private int[] window;
    private final int[] hitBuf = new int[256];

    // Swing thread only
    private boolean live = true;
    private boolean trace;
    private Listener listener;
    private Thread thread;
    private Timer timer;
    private boolean ended;

    public EmulatorSession(EmulatorDriver driver, Rom rom, byte[] emulatorContents) {
        this.driver = driver;
        this.rom = rom;
        this.sent = emulatorContents.clone();
        this.hitMap = new HitMap(rom);
        this.hits = new HitDisplay(hitMap);
    }

    public void start(Listener l) {
        listener = l;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                ioLoop();
            }
        }, "Emulator I/O");
        thread.setDaemon(true);
        thread.start();
        timer = new Timer(TICK_MS, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                tick();
            }
        });
        timer.start();
    }

    /**
     * Ends the session and hands the driver back, still open.  Edits made
     * up to now are sent first.
     */
    public EmulatorDriver detach() throws IOException {
        if (!ended && live) queueChanges();
        stopThreads();
        hits.clear();
        List<Write> rest;
        synchronized (lock) {
            rest = new ArrayList<Write>(writes);
            writes.clear();
        }
        ended = true;
        driver.stopTrace();
        for (Write w : rest) send(w);
        return driver;
    }

    public Rom getRom() {
        return rom;
    }

    public EmulatorDriver getDriver() {
        return driver;
    }

    public boolean isLive() {
        return live;
    }

    public void setLive(boolean live) {
        this.live = live;
    }

    public void setTrace(boolean trace) {
        this.trace = trace;
        if (trace) traceProblem = null;
    }

    public boolean isTracing() {
        return trace;
    }

    public void setAccumulate(boolean accumulate) {
        hits.setAccumulate(accumulate, System.currentTimeMillis());
    }

    public void clearHits() {
        hits.clear();
    }

    // Why the trace was turned off by itself, or null
    public String getTraceProblem() {
        return traceProblem;
    }

    public long getBytesSent() {
        return bytesSent;
    }

    public long getHitTotal() {
        return hits.getTotal();
    }

    // How often the ECU read each data cell since the hits were cleared; Swing thread only
    public java.util.Map<HitMap.Target, Integer> getHitCounts() {
        return hits.getCounts();
    }

    // One step of the Swing side
    void tick() {
        if (ended) return;
        ECUEditor editor = ECUEditorManager.getECUEditorWithoutCreation();
        if (editor != null && !editor.getImages().contains(rom)) {
            // The image was closed; free the driver for another
            ended = true;
            try {
                detach();
                if (listener != null) listener.ended(this, null);
            } catch (IOException e) {
                fail(e);
            }
            return;
        }

        if (live) queueChanges();
        if (trace && traceProblem != null) trace = false;
        int[] w = trace ? currentWindow(editor) : null;
        synchronized (lock) {
            wantedWindow = w;
        }

        long now = System.currentTimeMillis();
        for (int[] batch = hitQueue.poll(); batch != null; batch = hitQueue.poll()) {
            for (int offset : batch) hits.hit(offset, now);
        }
        hits.refresh(now);
    }

    // Queues the bytes that changed since the last tick, checksums brought up to date
    void queueChanges() {
        byte[] bin = rom.getBinary();
        if (bin == null || Arrays.equals(bin, sent)) return;
        rom.updateChecksumsQuietly();
        List<int[]> ranges = changes(bin, sent, MERGE_GAP);
        synchronized (lock) {
            for (int[] r : ranges) {
                writes.add(new Write(r[0], Arrays.copyOfRange(bin, r[0], r[1])));
            }
            lock.notifyAll();
        }
        for (int[] r : ranges) System.arraycopy(bin, r[0], sent, r[0], r[1] - r[0]);
    }

    // Ranges [from, to) where a and b differ, joined when closer than gap
    static List<int[]> changes(byte[] a, byte[] b, int gap) {
        List<int[]> ranges = new ArrayList<int[]>();
        int[] open = null;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == b[i]) continue;
            if (open != null && i - open[1] <= gap) {
                open[1] = i + 1;
            } else {
                open = new int[] {i, i + 1};
                ranges.add(open);
            }
        }
        return ranges;
    }

    private int[] currentWindow(ECUEditor editor) {
        List<Table> open = new ArrayList<Table>();
        if (editor != null) {
            for (JInternalFrame f : editor.getRightPanel().getAllFrames()) {
                if (!(f instanceof TableFrame) || !f.isVisible()) continue;
                TableView view = ((TableFrame) f).getTableView();
                if (view != null && view.getTable() != null && view.getTable().getRom() == rom) {
                    open.add(view.getTable());
                }
            }
        }
        int[] w = hitMap.window(open);
        return w != null ? w : hitMap.window();
    }

    private void ioLoop() {
        try {
            while (running) ioStep(POLL_MS);
        } catch (final IOException e) {
            if (!running) return;
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    fail(e);
                }
            });
        }
    }

    // One step of the I/O side: queued writes, trace changes, then hits
    void ioStep(int pollMs) throws IOException {
        List<Write> todo;
        int[] want;
        synchronized (lock) {
            if (writes.isEmpty() && sameWindow(wantedWindow, window) && !driver.isTracing() && pollMs > 0) {
                try {
                    lock.wait(pollMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            todo = new ArrayList<Write>(writes);
            writes.clear();
            want = wantedWindow;
        }
        if (!todo.isEmpty()) {
            // One pause of the trace for all of them
            boolean resume = driver.isTracing();
            driver.stopTrace();
            for (Write w : todo) send(w);
            if (resume) window = null;
        }

        try {
            if (!sameWindow(want, window)) {
                window = want;
                if (want == null) {
                    driver.stopTrace();
                } else {
                    driver.startTrace(want[0], want[1]);
                }
            }
            if (driver.isTracing()) {
                int n = driver.pollTrace(hitBuf, pollMs);
                if (n > 0) hitQueue.add(Arrays.copyOf(hitBuf, n));
            }
        } catch (TraceUnsupportedException e) {
            traceProblem = e.getMessage();
        }
    }

    private void send(Write w) throws IOException {
        driver.write(w.offset, w.data, 0, w.data.length);
        bytesSent += w.data.length;
    }

    private static boolean sameWindow(int[] a, int[] b) {
        return Arrays.equals(a, b);
    }

    private void stopThreads() {
        running = false;
        if (timer != null) timer.stop();
        synchronized (lock) {
            lock.notifyAll();
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // The link failed: the driver is closed and the session over
    private void fail(IOException e) {
        if (ended && listener == null) return;
        ended = true;
        stopThreads();
        hits.clear();
        driver.close();
        Listener l = listener;
        listener = null;
        if (l != null) l.ended(this, e);
    }
}
