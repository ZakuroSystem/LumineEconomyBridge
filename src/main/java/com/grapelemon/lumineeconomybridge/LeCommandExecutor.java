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
import java.util.*;

public class LeCommandExecutor implements CommandExecutor {

    private final String baseUrl;
    private final OkHttpClient http;
    private final ScoreboardSyncService sync;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public LeCommandExecutor(String baseUrl, OkHttpClient http, ScoreboardSyncService sync) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.sync = sync;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("プレイヤーのみ実行できます。");
            return true;
        }

        sync.seed(p); // 安全に初期化

        if (args.length > 0 && args[0].equalsIgnoreCase("rewrite")) {
            sync.rewriteAll();
            p.sendActionBar("§7同期を要求しました…");
            return true;
        }

        if (args.length == 0) {
            p.sendMessage("§7Usage: /le <content> | /le rewrite");
            return true;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("player", p.getUniqueId().toString());
        payload.put("command", "/" + String.join(" ", args));
        payload.put("timestamp", System.currentTimeMillis()/1000);

        Request req = new Request.Builder()
                .url(baseUrl + "/api/message")
                .post(RequestBody.create(new Gson().toJson(payload), JSON))
                .build();

        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                LumineEconomyBridge.getInstance().getLogger().warning("Message send failed: " + e.getMessage());
                Bukkit.getScheduler().runTask(LumineEconomyBridge.getInstance(),
                        () -> p.sendMessage("§c[EconomyBridge] 現在利用できません。"));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body()!=null? response.body().string():"{}";
                JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                Bukkit.getScheduler().runTask(LumineEconomyBridge.getInstance(), () -> {
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
                LumineEconomyBridge.getInstance().getLogger().fine("Message handled for " + p.getName());
            }
        });

        p.sendActionBar("§7送信中…");
        return true;
    }
}
