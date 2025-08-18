package com.grapelemon.lumineeconomybridge;

import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import com.grapelemon.lumineeconomybridge.sync.ScoreboardUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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
            // 現在値と lastSent から delta を計算して送る
            sync.sendDelta(p, "rewrite");
            p.sendActionBar("§7同期を要求しました…");
            return true;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("player", p.getUniqueId().toString());
        payload.put("command", "/" + label + (args.length>0 ? " " + String.join(" ", args) : ""));
        payload.put("args", Arrays.asList(args));
        payload.put("timestamp", System.currentTimeMillis()/1000);

        Request req = new Request.Builder()
                .url(baseUrl + "/execute")
                .post(RequestBody.create(new Gson().toJson(payload), JSON))
                .build();

        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Bukkit.getScheduler().runTask(LumineEconomyBridge.getInstance(),
                        () -> p.sendMessage("§e⏳ 応答を待機中… §7(経済サーバーに接続できません)"));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body()!=null? response.body().string():"{}";
                JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                Bukkit.getScheduler().runTask(LumineEconomyBridge.getInstance(), () -> {
                    if (res.has("messages")) {
                        res.getAsJsonArray("messages").forEach(el -> {
                            String text = el.getAsJsonObject().get("text").getAsString();
                            p.sendMessage(text);
                        });
                    }
                    // 将来: scoreboard が返ってきたら適用する
                    if (res.has("scoreboard")) {
                        JsonObject sb = res.get("scoreboard").getAsJsonObject();
                        Integer c1 = sb.has("currency1") ? sb.get("currency1").getAsInt() : null;
                        Integer c2 = sb.has("currency2") ? sb.get("currency2").getAsInt() : null;
                        sync.applyFromPython(p, c1, c2);
                    }
                });
            }
        });

        p.sendActionBar("§7送信中…");
        return true;
    }
}
