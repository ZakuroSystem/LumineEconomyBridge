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
        for (int i = 0; i < 64; i++) {
            int v = Math.min(255, i * 4);
            p[i][0] = v;
            p[i][1] = v;
            p[i][2] = v;
        }
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
        return switch (name) {
            case "minecraft:grass_block" -> 1;
            case "minecraft:stone" -> 11;
            default -> 0;
        };
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
