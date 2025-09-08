package com.grapelemon.lumineeconomybridge.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * Minimal HTTP server exposing map colour palette and block resolution.
 */
public class MapColorService {
    private final JavaPlugin plugin;
    private final HttpServer server;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final String token;
    private final int[][] palette;

    public MapColorService(JavaPlugin plugin, String token, int port) throws IOException {
        this.plugin = plugin;
        this.token = token;
        this.palette = generatePalette();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/plugin/mapcolor/palette", this::handlePalette);
        server.createContext("/plugin/mapcolor/resolve", this::handleResolve);
    }

    private int[][] generatePalette() {
        int[][] p = new int[64][3];
        // Fill with default colour (#404040)
        for (int i = 0; i < p.length; i++) {
            p[i][0] = 0x40;
            p[i][1] = 0x40;
            p[i][2] = 0x40;
        }
        // Custom palette indices
        p[1] = new int[] {0x9B, 0xEC, 0x77}; // grass/green
        p[2] = new int[] {0x79, 0xD4, 0x5C}; // leaves
        p[3] = new int[] {0x89, 0xB9, 0xCD}; // water
        p[4] = new int[] {0xF5, 0xF5, 0xF5}; // quartz/white/snow
        p[5] = new int[] {0xA5, 0xA5, 0xA5}; // stone/gray
        p[6] = new int[] {0xF8, 0x92, 0x21}; // lava
        return p;
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int getPaletteLength() {
        return palette.length;
    }

    private void handlePalette(HttpExchange ex) throws IOException {
        if (!checkToken(ex)) return;
        JsonObject obj = new JsonObject();
        JsonArray arr = new JsonArray();
        for (int[] c : palette) {
            JsonArray t = new JsonArray();
            t.add(c[0]);
            t.add(c[1]);
            t.add(c[2]);
            arr.add(t);
        }
        obj.add("palette", arr);
        JsonArray worlds = new JsonArray();
        JsonObject info = new JsonObject();
        info.addProperty("name", "world");
        info.addProperty("minY", 0);
        info.addProperty("maxY", plugin.getServer().getWorlds().get(0).getMaxHeight());
        info.addProperty("border", 29999984);
        worlds.add(info);
        obj.add("world_info", worlds);
        obj.addProperty("server_version", plugin.getServer().getVersion());
        byte[] out = gson.toJson(obj).getBytes();
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private void handleResolve(HttpExchange ex) throws IOException {
        if (!checkToken(ex)) return;
        String body = new String(ex.getRequestBody().readAllBytes());
        JsonObject req = gson.fromJson(body, JsonObject.class);
        JsonArray blocks = req.getAsJsonArray("blocks");
        JsonArray outArr = new JsonArray();
        for (int i = 0; i < blocks.size(); i++) {
            String name = blocks.get(i).getAsString();
            outArr.add(resolveBlock(name));
        }
        JsonObject resp = new JsonObject();
        resp.add("indices", outArr);
        byte[] out = gson.toJson(resp).getBytes();
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private int resolveBlock(String name) {
        String id = name;
        int colon = id.indexOf(':');
        if (colon != -1) {
            id = id.substring(colon + 1);
        }
        id = id.toLowerCase();
        if ("grass_block".equals(id) || id.contains("tall_grass") || id.contains("grass") || id.contains("green")) {
            return 1; // grass/green
        } else if (id.contains("leaves")) {
            return 2; // leaves
        } else if (id.contains("water")) {
            return 3; // water
        } else if (id.contains("quartz") || id.contains("white") || id.contains("snow")) {
            return 4; // quartz/white/snow
        } else if (id.contains("stone") || id.contains("gray")) {
            return 5; // stone/gray
        } else if (id.contains("lava")) {
            return 6; // lava
        } else {
            return 0; // other
        }
    }

    private boolean checkToken(HttpExchange ex) throws IOException {
        String hdr = ex.getRequestHeaders().getFirst("X-LE-Token");
        if (token != null && !token.isEmpty() && !token.equals(hdr)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return false;
        }
        return true;
    }
}
