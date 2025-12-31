package com.grapelemon.lumineeconomybridge;

import com.google.gson.JsonObject;
import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;

public class PlayerListener implements Listener {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final LumineEconomyBridge plugin;

    public PlayerListener(LumineEconomyBridge plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        ScoreboardSyncService sync = plugin.getSyncService();
        if (sync != null) {
            sync.seed(player);
        }
        ensureAccount(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ScoreboardSyncService sync = plugin.getSyncService();
        if (sync != null) {
            sync.cleanup(e.getPlayer().getUniqueId());
        }
    }

    private void ensureAccount(Player player) {
        if (!plugin.isActive()) {
            return;
        }
        OkHttpClient http = plugin.getHttpClient();
        if (http == null) {
            return;
        }
        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        JsonObject payload = new JsonObject();
        payload.addProperty("player_uuid", uuid);
        payload.addProperty("player_name", name);
        Request req = new Request.Builder()
                .url(plugin.getBaseUrl() + "/api/account/ensure")
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                plugin.getLogger().warning("Failed to ensure account for " + name + ": " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful()) {
                        plugin.getLogger().warning(
                                "Account ensure failed for " + name + ": status " + response.code());
                    }
                }
            }
        });
    }
}
