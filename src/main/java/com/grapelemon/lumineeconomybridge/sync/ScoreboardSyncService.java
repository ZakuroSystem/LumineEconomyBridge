package com.grapelemon.lumineeconomybridge.sync;

import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class ScoreboardSyncService {
    private final OkHttpClient http;
    private final String baseUrl;
    private final LumineEconomyBridge plugin;
    private final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // 同期用の状態
    private final Map<UUID, int[]> lastSentAbs = new ConcurrentHashMap<>();
    private final Map<UUID, int[]> appliedFromPython = new ConcurrentHashMap<>();

    public ScoreboardSyncService(OkHttpClient http, String baseUrl, LumineEconomyBridge plugin) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.plugin = plugin;
    }

    public void seed(Player p) {
        // 初期観測: メインスレッドで読み取り
        int[] current = callSync(() -> ScoreboardUtil.readBothSync(p));
        lastSentAbs.putIfAbsent(p.getUniqueId(), current);
        appliedFromPython.putIfAbsent(p.getUniqueId(), new int[]{0,0});
    }

    public void cleanup(UUID id) {
        lastSentAbs.remove(id);
        appliedFromPython.remove(id);
    }

    private <T> T callSync(Callable<T> task) {
        try {
            if (Bukkit.isPrimaryThread()) {
                return task.call();
            }
            Future<T> f = Bukkit.getScheduler().callSyncMethod(plugin, task);
            return f.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Pythonからの絶対値適用（将来使用）
    public void applyFromPython(Player p, Integer c1, Integer c2) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            int[] before = ScoreboardUtil.readBothSync(p);
            ScoreboardUtil.applyAbsoluteSync(p, c1, c2);
            int[] after = ScoreboardUtil.readBothSync(p);

            int d1 = after[0] - before[0];
            int d2 = after[1] - before[1];
            int[] buf = appliedFromPython.computeIfAbsent(p.getUniqueId(), k -> new int[]{0,0});
            buf[0] += d1; buf[1] += d2;

            lastSentAbs.put(p.getUniqueId(), after);
        });
    }

    public void sendDelta(Player p) {
        UUID id = p.getUniqueId();

        // メインスレッドで現在値を取得
        int[] current = callSync(() -> ScoreboardUtil.readBothSync(p));
        int[] last = lastSentAbs.computeIfAbsent(id, k -> current.clone());

        int d1 = current[0] - last[0];
        int d2 = current[1] - last[1];

        // Python由来の適用分を差し引く
        int[] applied = appliedFromPython.computeIfAbsent(id, k -> new int[]{0,0});
        d1 -= applied[0];
        d2 -= applied[1];
        applied[0] = 0; applied[1] = 0;

        // 送る差分がゼロならスキップ
        if (d1 == 0 && d2 == 0) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("player", id.toString());
        Map<String, Integer> delta = new HashMap<>();
        delta.put("currency1", d1);
        delta.put("currency2", d2);
        payload.put("delta", delta);
        payload.put("timestamp", System.currentTimeMillis() / 1000);

        Request req = new Request.Builder()
                .url(baseUrl + "/api/sync")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();

        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                plugin.getLogger().warning("Failed to sync scoreboard for " + p.getName() + ": " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body()!=null? response.body().string():"{}";
                JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                // ACKが返ってきたら lastSent を current に更新
                if (res.has("status") && res.get("status").getAsString().equalsIgnoreCase("success")) {
                    lastSentAbs.put(id, current);
                    plugin.getLogger().fine("Synced scoreboard for " + p.getName());
                } else {
                    plugin.getLogger().warning("Failed to sync scoreboard for " + p.getName());
                }
            }
        });
    }

    public void tickAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                sendDelta(p);
            } catch (Exception ignored) {}
        }
    }

    public void sendAbsolute(Player p) {
        UUID id = p.getUniqueId();
        int[] current = callSync(() -> ScoreboardUtil.readBothSync(p));
        lastSentAbs.put(id, current);
        Map<String, Object> payload = new HashMap<>();
        payload.put("player", id.toString());
        Map<String, Integer> scoreboard = new HashMap<>();
        scoreboard.put("currency1", current[0]);
        scoreboard.put("currency2", current[1]);
        payload.put("scoreboard", scoreboard);
        payload.put("timestamp", System.currentTimeMillis() / 1000);

        Request req = new Request.Builder()
                .url(baseUrl + "/api/rewrite")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();

        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                plugin.getLogger().warning("Failed to rewrite scoreboard for " + p.getName() + ": " + e.getMessage());
            }

            @Override public void onResponse(Call call, Response response) {
                plugin.getLogger().fine("Rewrote scoreboard for " + p.getName());
            }
        });
    }

    public void rewriteAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { sendAbsolute(p); } catch (Exception ignored) {}
        }
    }
}
