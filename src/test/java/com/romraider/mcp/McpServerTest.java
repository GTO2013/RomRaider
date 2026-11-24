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
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

public class McpServerTest {
    private final McpServer server = McpServer.getInstance();
    private int port;

    @Before
    public void start() throws Exception {
        ServerSocket free = new ServerSocket(0);
        port = free.getLocalPort();
        free.close();
        assertTrue(server.start(port));
    }

    @After
    public void stop() {
        server.stop();
        assertFalse(server.isRunning());
    }

    @Test
    public void initializesWithRequestedProtocol() throws Exception {
        JsonObject result = rpc("initialize", "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}").get("result").asObject();
        assertEquals("2025-03-26", result.getString("protocolVersion", null));
        assertTrue(result.get("capabilities").asObject().get("tools") != null);
        assertTrue(result.getString("instructions", "").length() > 0);
    }

    @Test
    public void listsToolsWithSchemas() throws Exception {
        Set<String> names = new HashSet<String>();
        for (JsonValue tool : rpc("tools/list", "{}").get("result").asObject().get("tools").asArray()) {
            JsonObject t = tool.asObject();
            names.add(t.getString("name", null));
            assertEquals("object", t.get("inputSchema").asObject().getString("type", null));
            assertTrue(t.getString("description", "").length() > 0);
        }
        for (String expected : new String[] {"roms_list", "rom_open", "rom_save", "rom_close", "table_read",
                "table_write", "table_edit", "table_probe", "table_add", "table_update", "table_remove",
                "definition_export", "bytes_write", "unmapped_regions", "editor_selection"}) {
            assertTrue(expected, names.contains(expected));
        }
    }

    @Test
    public void reportsProtocolErrors() throws Exception {
        assertEquals(-32601, rpc("no/such/method", "{}").get("error").asObject().getInt("code", 0));
        assertEquals(-32602, rpc("tools/call", "{\"name\":\"missing_tool\"}").get("error").asObject().getInt("code", 0));
        assertEquals(-32700, Json.parse(post("{not json").body).asObject().get("error").asObject().getInt("code", 0));
    }

    @Test
    public void acceptsNotificationsWithoutBody() throws Exception {
        assertEquals(202, post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").status);
    }

    @Test
    public void refusesForeignOriginsAndOtherMethods() throws Exception {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        assertEquals(403, rawStatus("POST", ping, "http://evil.example"));
        assertEquals(200, rawStatus("POST", ping, "http://localhost:3000"));
        assertEquals(405, rawStatus("GET", "", null));
    }

    // HttpURLConnection drops the Origin header, so this speaks HTTP directly
    private int rawStatus(String method, String body, String origin) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        try {
            byte[] content = body.getBytes("UTF-8");
            String request = method + " /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
                    + (origin != null ? "Origin: " + origin + "\r\n" : "")
                    + "Content-Length: " + content.length + "\r\nConnection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes("UTF-8"));
            out.write(content);
            out.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            return Integer.parseInt(in.readLine().split(" ")[1]);
        } finally {
            socket.close();
        }
    }

    private JsonObject rpc(String method, String params) throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}";
        Response response = post(request);
        assertEquals(200, response.status);
        JsonObject json = Json.parse(response.body).asObject();
        assertEquals(7, json.getInt("id", 0));
        return json;
    }

    private Response post(String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(McpServer.urlForPort(port)).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        OutputStream out = connection.getOutputStream();
        out.write(body.getBytes("UTF-8"));
        out.close();

        Response response = new Response();
        response.status = connection.getResponseCode();
        if (response.status == 200) {
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) bytes.write(buffer, 0, read);
            in.close();
            response.body = bytes.toString("UTF-8");
        }
        return response;
    }

    private static final class Response {
        int status;
        String body;
    }
}
