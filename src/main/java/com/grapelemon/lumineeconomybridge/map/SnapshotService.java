package com.grapelemon.lumineeconomybridge.map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

/**
 * Send chunk top snapshots to the Python service.
 */
public class SnapshotService {
    private final JavaPlugin plugin;
    private final String token;
    private final String baseUrl;
    private final Gson gson = new Gson();
    private final HttpClient client = HttpClient.newHttpClient();

    public SnapshotService(JavaPlugin plugin, String token, String baseUrl) {
        this.plugin = plugin;
        this.token = token;
        this.baseUrl = baseUrl;
    }

    public void sendTile(World world, int tx, int tz) {
        JsonObject req = new JsonObject();
        req.addProperty("world", world.getName());
        JsonArray chunks = new JsonArray();
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                int cx = tx * 4 + dx;
                int cz = tz * 4 + dz;
                if (!world.isChunkLoaded(cx, cz)) continue;
                byte[] top = makeChunkTop(world, cx, cz);
                JsonObject ch = new JsonObject();
                ch.addProperty("cx", cx);
                ch.addProperty("cz", cz);
                ch.addProperty("data", Base64.getEncoder().encodeToString(top));
                chunks.add(ch);
            }
        }
        req.add("chunks", chunks);
        req.addProperty("y_start", 250);
        req.addProperty("ts", System.currentTimeMillis());
        try {
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/plugin/chunk_snapshot"))
                    .header("Content-Type", "application/json")
                    .header("X-LE-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(req)))
                    .build();
            client.send(httpReq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            plugin.getLogger().warning("snapshot send failed: " + e.getMessage());
        }
    }

    private byte[] makeChunkTop(World world, int cx, int cz) {
        byte[] data = new byte[256];
        Chunk chunk = world.getChunkAt(cx, cz);
        int maxY = world.getMaxHeight() - 1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                Block b = chunk.getBlock(x, maxY, z);
                data[z * 16 + x] = (byte) (b.isEmpty() ? 0 : 1);
            }
        }
        return data;
    }
}
