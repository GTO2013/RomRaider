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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.romraider.editor.ecu.ECUEditor;
import com.romraider.editor.ecu.ECUEditorManager;
import com.romraider.editor.ecu.OpenImageWorker;
import com.romraider.maps.Rom;
import com.romraider.maps.RomID;
import com.romraider.maps.Table;

final class RomTools {
    private static final long OPEN_TIMEOUT_MILLIS = 120000;

    private RomTools() {
    }

    static void register(ToolRegistry registry) {
        registry.add(new McpTool("roms_list", "List the ROM images open in the editor.", new Schema()) {
            @Override
            JsonObject run(McpCall call) {
                ECUEditor editor = ECUEditorManager.getECUEditor();
                JsonArray roms = new JsonArray();
                Vector<Rom> images = editor.getImages();
                for (int i = 0; i < images.size(); i++) {
                    Rom rom = images.get(i);
                    roms.add(new JsonObject()
                            .add("index", i)
                            .add("file", rom.getFileName())
                            .add("xmlid", rom.getRomIDString())
                            .add("selected", rom == editor.getLastSelectedRom())
                            .add("modified_tables", modifiedTables(rom).size()));
                }
                return new JsonObject().add("roms", roms);
            }
        });

        registry.add(new McpTool("rom_info", "Details of a ROM: ids, definition, size, ram offset, "
                + "checksums, modified tables and whether the image differs from its file.", Schema.withRom()) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                return info(call.getRom());
            }
        });

        registry.add(new McpTool("rom_open", "Open a ROM image (bin) in the editor and wait until it is loaded. "
                + "The definition is matched like File > Open, or taken from definition.",
                new Schema().string("path", "Absolute path of the image file", true)
                        .string("definition", "Definition file to match against instead of the "
                                + "configured definitions", false)) {
            @Override
            boolean runsOnEdt() {
                return false;
            }

            @Override
            JsonObject run(final McpCall call) throws Exception {
                final File file = new File(call.getString("path"));
                if (!file.isFile()) throw new McpException("No such file: " + file.getAbsolutePath());
                final File definition = call.has("definition") ? new File(call.getString("definition")) : null;
                if (definition != null && !definition.isFile()) {
                    throw new McpException("No such definition: " + definition.getAbsolutePath());
                }

                OpenImageWorker worker = Edt.call(new Callable<OpenImageWorker>() {
                    @Override
                    public OpenImageWorker call() {
                        OpenImageWorker worker = new OpenImageWorker(file, definition, call.getWarnings());
                        worker.addPropertyChangeListener(ECUEditorManager.getECUEditor().getStatusPanel());
                        worker.execute();
                        return worker;
                    }
                });

                final Rom rom = worker.waitForAddedRom(OPEN_TIMEOUT_MILLIS);
                if (rom == null) {
                    String reason = worker.isDone() ? "The ROM could not be opened" : "Opening the ROM timed out";
                    if (worker.isDone()) {
                        try {
                            worker.get();
                        } catch (ExecutionException e) {
                            reason += ": " + e.getCause();
                        }
                    }
                    throw new McpException(reason);
                }

                return Edt.call(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() throws Exception {
                        return info(rom);
                    }
                });
            }
        });

        registry.add(new McpTool("rom_save", "Write the image to its file, or to path. Checksums are "
                + "updated like File > Save.",
                Schema.withRom()
                        .string("path", "Target file, defaults to the ROM's own file", false)
                        .bool("overwrite", "Allow replacing a different existing file")) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                File target = call.has("path") ? new File(call.getString("path")) : rom.getFullFileName();
                boolean ownFile = target.getAbsoluteFile().equals(rom.getFullFileName().getAbsoluteFile());
                if (target.exists() && !ownFile && !call.optBoolean("overwrite", false)) {
                    throw new McpException(target.getAbsolutePath() + " exists, pass overwrite to replace it");
                }

                write(target, rom.saveFile());
                rom.setFullFileName(target.getAbsoluteFile());
                ECUEditor editor = ECUEditorManager.getECUEditor();
                if (rom == editor.getLastSelectedRom()) editor.setLastSelectedRom(rom);

                JsonObject result = new JsonObject().add("saved", target.getAbsolutePath());
                if (rom.getNumChecksumsManagers() > 0) {
                    result.add("checksums_valid", rom.validateChecksum())
                            .add("checksums_total", rom.getTotalAmountOfChecksums());
                }
                return result;
            }
        });

        registry.add(new McpTool("rom_close", "Close a ROM and its table frames. Refuses when the image "
                + "differs from its file unless discard is true.",
                Schema.withRom().bool("discard", "Close even with unsaved changes")) {
            @Override
            JsonObject run(McpCall call) throws Exception {
                Rom rom = call.getRom();
                if (differsFromFile(rom) && !call.optBoolean("discard", false)) {
                    throw new McpException(rom.getFileName() + " has unsaved changes, save it or pass discard");
                }
                String name = rom.getFileName();
                ECUEditor editor = ECUEditorManager.getECUEditor();
                editor.setLastSelectedRom(rom);
                editor.closeImage();
                return new JsonObject().add("closed", name);
            }
        });
    }

    static JsonObject info(Rom rom) throws IOException {
        ECUEditor editor = ECUEditorManager.getECUEditor();
        RomID id = rom.getRomID();
        JsonObject info = new JsonObject()
                .add("index", editor.getImages().indexOf(rom))
                .add("file", rom.getFileName())
                .add("path", rom.getFullFileName().getAbsolutePath())
                .add("xmlid", id.getXmlid())
                .add("make", id.getMake())
                .add("model", id.getModel())
                .add("year", id.getYear())
                .add("ecu_id", id.getEcuId())
                .add("memmodel", id.getMemModel())
                .add("definition", rom.getDefinitionPath() == null ? null : rom.getDefinitionPath().getAbsolutePath())
                .add("size", rom.getBinary().length)
                .add("ram_offset", Js.hex(id.getRamOffset()))
                .add("tables", rom.getTableNodes().size())
                .add("modified_tables", Js.strings(modifiedTables(rom)))
                .add("definition_changes", DefinitionEditor.changedCount(rom))
                .add("unsaved", differsFromFile(rom));
        if (rom.getNumChecksumsManagers() > 0) {
            info.add("checksums_valid", rom.validateChecksum())
                    .add("checksums_total", rom.getTotalAmountOfChecksums());
        }
        return info;
    }

    static Vector<String> modifiedTables(Rom rom) {
        Vector<String> names = new Vector<String>();
        for (Table table : rom.getTables()) {
            if (table.isModified()) names.add(table.getName());
        }
        return names;
    }

    static void write(File target, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(target);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    static boolean differsFromFile(Rom rom) throws IOException {
        File file = rom.getFullFileName();
        if (!file.isFile()) return true;
        return !Arrays.equals(ECUEditor.readFile(file), rom.getBinary());
    }
}
