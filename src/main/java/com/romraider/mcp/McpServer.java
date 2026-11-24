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

import static com.romraider.Version.PRODUCT_NAME;
import static com.romraider.Version.VERSION;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.apache.log4j.Logger;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.eclipsesource.json.ParseException;
import com.romraider.Settings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Model Context Protocol server (Streamable HTTP transport, JSON responses)
 * that lets an MCP client read and edit the ROMs open in the editor.
 * It only listens on the loopback interface.
 */
public final class McpServer {
    private static final Logger LOGGER = Logger.getLogger(McpServer.class);
    private static final McpServer INSTANCE = new McpServer();

    private static final String LATEST_PROTOCOL = "2025-06-18";
    private static final List<String> PROTOCOLS = Arrays.asList(LATEST_PROTOCOL, "2025-03-26", "2024-11-05");

    private static final String INSTRUCTIONS =
            "Drives the running RomRaider editor. Every table you add or change is opened in the editor "
            + "and the changed cells are selected, so the user sees each edit. "
            + "Addresses are definition addresses (storageaddress in the XML); a string is hex, a number is decimal. "
            + "The file offset is the address minus the ROM's ram_offset (see rom_info); read, search or compare "
            + "raw bytes in the bin file directly. "
            + "Use table_probe to try an address, storage type and scaling before table_add. "
            + "Edits change the open image only, rom_save writes the bin and definition_export the definition.";

    private ToolRegistry tools;
    private HttpServer server;
    private ExecutorService executor;
    private int port = -1;
    private String lastError;

    private McpServer() {
    }

    public static McpServer getInstance() {
        return INSTANCE;
    }

    static String urlForPort(int port) {
        return "http://127.0.0.1:" + port + "/mcp";
    }

    // Starts, stops or moves the server to match the settings
    public synchronized void applySettings(Settings settings) {
        if (!settings.isMcpEnabled()) {
            stop();
        } else if (!isRunning() || port != settings.getMcpPort()) {
            stop();
            start(settings.getMcpPort());
        }
    }

    public synchronized boolean start(int port) {
        stop();
        try {
            HttpServer httpServer = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
            httpServer.createContext("/mcp", new Handler());
            // One request at a time, tool calls change shared editor state
            executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "MCP Server");
                    thread.setDaemon(true);
                    return thread;
                }
            });
            httpServer.setExecutor(executor);
            httpServer.start();

            server = httpServer;
            this.port = port;
            lastError = null;
            LOGGER.info("MCP server listening on " + getUrl());
            return true;
        } catch (IOException e) {
            lastError = "port " + port + ": " + e.getMessage();
            LOGGER.error("MCP server could not start on " + lastError);
            if (executor != null) executor.shutdownNow();
            executor = null;
            return false;
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            executor.shutdownNow();
            LOGGER.info("MCP server stopped");
        }
        server = null;
        executor = null;
        port = -1;
        lastError = null;
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized String getUrl() {
        return isRunning() ? urlForPort(port) : null;
    }

    public synchronized String getLastError() {
        return lastError;
    }

    private synchronized ToolRegistry getTools() {
        if (tools == null) tools = ToolRegistry.createDefault();
        return tools;
    }

    // Handles one JSON-RPC message, returns null for notifications and responses
    JsonValue handleMessage(JsonValue message) {
        if (!message.isObject()) return error(Json.NULL, -32600, "Invalid request");

        JsonObject request = message.asObject();
        JsonValue id = request.get("id");
        JsonValue method = request.get("method");
        if (method == null || !method.isString()) {
            return id == null ? null : error(id, -32600, "Invalid request");
        }
        if (id == null) return null;

        JsonValue paramsValue = request.get("params");
        JsonObject params = paramsValue != null && paramsValue.isObject() ? paramsValue.asObject() : new JsonObject();

        String name = method.asString();
        if ("initialize".equals(name)) {
            String requested = params.getString("protocolVersion", LATEST_PROTOCOL);
            JsonObject result = new JsonObject()
                    .add("protocolVersion", PROTOCOLS.contains(requested) ? requested : LATEST_PROTOCOL)
                    .add("capabilities", new JsonObject().add("tools", new JsonObject()))
                    .add("serverInfo", new JsonObject().add("name", PRODUCT_NAME).add("version", VERSION))
                    .add("instructions", INSTRUCTIONS);
            return result(id, result);
        } else if ("ping".equals(name)) {
            return result(id, new JsonObject());
        } else if ("tools/list".equals(name)) {
            return result(id, new JsonObject().add("tools", getTools().describe()));
        } else if ("tools/call".equals(name)) {
            String tool = params.getString("name", null);
            if (tool == null || !getTools().contains(tool)) {
                return error(id, -32602, "Unknown tool: " + tool);
            }
            JsonValue args = params.get("arguments");
            return result(id, getTools().call(tool, args != null && args.isObject() ? args.asObject() : null));
        }
        return error(id, -32601, "Method not found: " + name);
    }

    private static JsonObject result(JsonValue id, JsonObject result) {
        return new JsonObject().add("jsonrpc", "2.0").add("id", id).add("result", result);
    }

    private static JsonObject error(JsonValue id, int code, String message) {
        return new JsonObject().add("jsonrpc", "2.0").add("id", id)
                .add("error", new JsonObject().add("code", code).add("message", message));
    }

    // Browsers send an Origin, refuse pages that are not served from this machine
    private static boolean isLocalOrigin(String origin) {
        try {
            String host = new URI(origin).getHost();
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        } catch (Exception e) {
            return false;
        }
    }

    private final class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                if (origin != null && !isLocalOrigin(origin)) {
                    send(exchange, 403, null);
                } else if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().add("Allow", "POST");
                    send(exchange, 405, null);
                } else {
                    handlePost(exchange);
                }
            } catch (Exception e) {
                LOGGER.error("MCP request failed", e);
                send(exchange, 500, null);
            } finally {
                exchange.close();
            }
        }

        private void handlePost(HttpExchange exchange) throws IOException {
            JsonValue message;
            try {
                message = Json.parse(readBody(exchange.getRequestBody()));
            } catch (ParseException e) {
                send(exchange, 200, error(Json.NULL, -32700, "Parse error"));
                return;
            }

            JsonValue response;
            if (message.isArray()) {
                JsonArray responses = new JsonArray();
                for (JsonValue item : message.asArray()) {
                    JsonValue single = handleMessage(item);
                    if (single != null) responses.add(single);
                }
                response = responses.isEmpty() ? null : responses;
            } else {
                response = handleMessage(message);
            }

            if (response == null) {
                send(exchange, 202, null);
            } else {
                send(exchange, 200, response);
            }
        }

        private String readBody(InputStream in) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                body.write(buffer, 0, read);
            }
            return body.toString("UTF-8");
        }

        private void send(HttpExchange exchange, int status, JsonValue body) throws IOException {
            if (body == null) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            byte[] bytes = body.toString().getBytes("UTF-8");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes);
            out.close();
        }
    }
}
