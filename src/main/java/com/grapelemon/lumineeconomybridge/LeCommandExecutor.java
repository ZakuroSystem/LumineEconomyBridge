package com.grapelemon.lumineeconomybridge;

import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LeCommandExecutor implements CommandExecutor {

    private final LumineEconomyBridge plugin;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final Gson gson = new Gson();

    public LeCommandExecutor(LumineEconomyBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get("player-only"));
            return true;
        }

        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "rewrite" -> {
                    if (!plugin.isActive()) {
                        p.sendMessage(Lang.get("error-unavailable"));
                        return true;
                    }
                    plugin.getSyncService().rewriteAll();
                    p.sendActionBar(Lang.get("sync-requested"));
                    return true;
                }
                case "start" -> {
                    plugin.startBridge();
                    p.sendMessage(Lang.get("bridge-starting"));
                    return true;
                }
                case "stop" -> {
                    plugin.stopBridge();
                    p.sendMessage(Lang.get("bridge-stopped"));
                    return true;
                }
                case "reload" -> {
                    plugin.reloadBridge();
                    p.sendMessage(Lang.get("bridge-reloaded"));
                    return true;
                }
            }
        }

        if (args.length == 0) {
            p.sendMessage(Lang.get("usage"));
            return true;
        }

        if (!plugin.isActive()) {
            p.sendMessage(Lang.get("error-unavailable"));
            return true;
        }

        ScoreboardSyncService sync = plugin.getSyncService();
        OkHttpClient http = plugin.getHttpClient();
        String baseUrl = plugin.getBaseUrl();

        sync.seed(p); // 安全に初期化

        Map<String, Object> payload = new HashMap<>();
        payload.put("player", p.getUniqueId().toString());
        payload.put("executor", p.getName());
        payload.put("command", "/" + String.join(" ", args));
        payload.put("timestamp", System.currentTimeMillis() / 1000);
        Location loc = p.getLocation();
        Map<String, Object> locMap = new HashMap<>();
        locMap.put("world", loc.getWorld().getName());
        locMap.put("x", loc.getX());
        locMap.put("y", loc.getY());
        locMap.put("z", loc.getZ());
        payload.put("location", locMap);

        Request req = new Request.Builder()
                .url(baseUrl + "/api/message")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();

        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                plugin.getLogger().warning("Message send failed: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin,
                        () -> p.sendMessage(Lang.get("error-unavailable")));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "{}";
                JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (res.has("messages")) {
                        res.getAsJsonArray("messages").forEach(el -> {
                            JsonObject msg = el.getAsJsonObject();
                            String text = msg.has("text") ? msg.get("text").getAsString() : "";
                            String target = msg.has("target") ? msg.get("target").getAsString() : "chat";
                            switch (target.toLowerCase()) {
                                case "actionbar" -> p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
                                case "title" -> p.sendTitle(text, msg.has("subtitle") ? msg.get("subtitle").getAsString() : "", 10, 40, 10);
                                default -> p.sendMessage(text);
                            }
                        });
                    }
                    if (res.has("scoreboards")) {
                        res.getAsJsonObject("scoreboards").entrySet().forEach(en -> {
                            try {
                                UUID pid = UUID.fromString(en.getKey());
                                Player target = Bukkit.getPlayer(pid);
                                if (target != null) {
                                    JsonObject sb = en.getValue().getAsJsonObject();
                                    Map<String, Integer> updates = new HashMap<>();
                                    sb.entrySet().forEach(e -> updates.put(e.getKey(), e.getValue().getAsInt()));
                                    sync.applyFromPython(target, updates);
                                }
                            } catch (IllegalArgumentException ignored) {}
                        });
                    } else if (res.has("scoreboard")) {
                        JsonObject sb = res.getAsJsonObject("scoreboard");
                        Map<String, Integer> updates = new HashMap<>();
                        sb.entrySet().forEach(e -> updates.put(e.getKey(), e.getValue().getAsInt()));
                        sync.applyFromPython(p, updates);
                    }
                });
                plugin.getLogger().fine("Message handled for " + p.getName());
            }
        });

        p.sendActionBar(Lang.get("send-pending"));
        return true;
    }
}
