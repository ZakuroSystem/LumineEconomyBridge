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
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

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
            sender.sendMessage("プレイヤーのみ実行できます。");
            return true;
        }

        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "rewrite" -> {
                    if (!plugin.isActive()) {
                        p.sendMessage("§c[EconomyBridge] 現在利用できません。");
                        return true;
                    }
                    plugin.getSyncService().rewriteAll();
                    p.sendActionBar("§7同期を要求しました…");
                    return true;
                }
                case "start" -> {
                    plugin.startBridge();
                    p.sendMessage("§7Bridge starting…");
                    return true;
                }
                case "stop" -> {
                    plugin.stopBridge();
                    p.sendMessage("§7Bridge stopped");
                    return true;
                }
                case "reload" -> {
                    plugin.reloadBridge();
                    p.sendMessage("§7Bridge reloaded");
                    return true;
                }
            }
        }

        if (args.length == 0) {
            p.sendMessage("§7Usage: /le <content> | /le rewrite | /le start | /le stop | /le reload");
            return true;
        }

        if (!plugin.isActive()) {
            p.sendMessage("§c[EconomyBridge] 現在利用できません。");
            return true;
        }

        ScoreboardSyncService sync = plugin.getSyncService();
        OkHttpClient http = plugin.getHttpClient();
        String baseUrl = plugin.getBaseUrl();

        sync.seed(p); // 安全に初期化

        Map<String, Object> payload = new HashMap<>();
        payload.put("player", p.getUniqueId().toString());
        payload.put("command", "/" + String.join(" ", args));
        payload.put("timestamp", System.currentTimeMillis() / 1000);

        Request req = new Request.Builder()
                .url(baseUrl + "/api/message")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();

        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                plugin.getLogger().warning("Message send failed: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin,
                        () -> p.sendMessage("§c[EconomyBridge] 現在利用できません。"));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "{}";
                JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (res.has("messages")) {
                        res.getAsJsonArray("messages").forEach(el -> {
                            JsonObject msg = el.getAsJsonObject();
                            String text = msg.get("text").getAsString();
                            String target = msg.has("target") ? msg.get("target").getAsString() : "chat";
                            switch (target.toLowerCase()) {
                                case "actionbar" -> p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
                                case "title" -> p.sendTitle(text, "", 10, 40, 10);
                                case "subtitle" -> p.sendTitle("", text, 10, 40, 10);
                                default -> p.sendMessage(text);
                            }
                        });
                    }
                    if (res.has("scoreboard")) {
                        JsonObject sb = res.get("scoreboard").getAsJsonObject();
                        Integer c1 = sb.has("currency1") ? sb.get("currency1").getAsInt() : null;
                        Integer c2 = sb.has("currency2") ? sb.get("currency2").getAsInt() : null;
                        sync.applyFromPython(p, c1, c2);
                    }
                });
                plugin.getLogger().fine("Message handled for " + p.getName());
            }
        });

        p.sendActionBar("§7送信中…");
        return true;
    }
}
