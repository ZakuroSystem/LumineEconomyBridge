package com.grapelemon.lumineeconomybridge.sync;

import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class ScoreboardSyncService {
    private final OkHttpClient http;
    private final String baseUrl;
    private final LumineEconomyBridge plugin;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // 同期用の状態
    private final Map<UUID, Map<String, Integer>> lastSentAbs = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Integer>> appliedFromPython = new ConcurrentHashMap<>();

    public ScoreboardSyncService(OkHttpClient http, String baseUrl, LumineEconomyBridge plugin) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.plugin = plugin;
    }

    public void seed(Player p) {
        // 初期観測: メインスレッドで読み取り
        Map<String, Integer> current = callSync(() -> ScoreboardUtil.readAllSync(p));
        lastSentAbs.putIfAbsent(p.getUniqueId(), current);
        appliedFromPython.putIfAbsent(p.getUniqueId(), new ConcurrentHashMap<>());
        sendAbsolute(p);
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
    public void applyFromPython(Player p, Map<String, Integer> abs) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Map<String, Integer> before = ScoreboardUtil.readAllSync(p);
            ScoreboardUtil.applyAbsoluteSync(p, abs);
            Map<String, Integer> after = ScoreboardUtil.readAllSync(p);

            Map<String, Integer> buf = appliedFromPython.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>());
            for (Map.Entry<String, Integer> e : after.entrySet()) {
                int d = e.getValue() - before.getOrDefault(e.getKey(), 0);
                if (d != 0) {
                    buf.merge(e.getKey(), d, Integer::sum);
                }
            }

            lastSentAbs.put(p.getUniqueId(), after);
        });
    }

    public void sendDelta(Player p) {
        UUID id = p.getUniqueId();

        // メインスレッドで現在値を取得
        Map<String, Integer> current = callSync(() -> ScoreboardUtil.readAllSync(p));
        Map<String, Integer> last = lastSentAbs.computeIfAbsent(id, k -> new ConcurrentHashMap<>());

        Map<String, Integer> applied = appliedFromPython.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        Map<String, Integer> delta = new HashMap<>();

        Set<String> keys = new HashSet<>();
        keys.addAll(current.keySet());
        keys.addAll(last.keySet());

        for (String k : keys) {
            int d = current.getOrDefault(k, 0) - last.getOrDefault(k, 0);
            d -= applied.getOrDefault(k, 0);
            if (d != 0) {
                delta.put(k, d);
            }
        }
        applied.clear();

        // 送る差分がゼロならスキップ
        if (delta.isEmpty()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("player", id.toString());
        payload.put("delta", delta);
        payload.put("timestamp", System.currentTimeMillis() / 1000);

        Request req = new Request.Builder()
                .url(baseUrl + "/api/sync")
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();

        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                plugin.getLogger().warning("Failed to sync scoreboard for " + p.getName() + ": " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                    // ACKが返ってきたら lastSent を current に更新
                    if (res.has("status") && res.get("status").getAsString().equalsIgnoreCase("success")) {
                        lastSentAbs.put(id, current);
                        plugin.getLogger().fine("Synced scoreboard for " + p.getName());
                    } else {
                        plugin.getLogger().warning("Failed to sync scoreboard for " + p.getName());
                    }
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
        Map<String, Integer> current = callSync(() -> ScoreboardUtil.readAllSync(p));
        lastSentAbs.put(id, current);
        Map<String, Object> payload = new HashMap<>();
        payload.put("player", id.toString());
        payload.put("scoreboard", current);
        payload.put("timestamp", System.currentTimeMillis() / 1000);

        Request req = new Request.Builder()
                .url(baseUrl + "/api/rewrite")
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();

          http.newCall(req).enqueue(new Callback() {
              @Override public void onFailure(Call call, IOException e) {
                  plugin.getLogger().warning("Failed to rewrite scoreboard for " + p.getName() + ": " + e.getMessage());
              }

              @Override public void onResponse(Call call, Response response) throws IOException {
                  try (response) {
                      String body = response.body() != null ? response.body().string() : "{}";
                      JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                      plugin.getLogger().fine("Rewrote scoreboard for " + p.getName());
                      if (res.has("messages")) {
                          Bukkit.getScheduler().runTask(plugin, () -> {
                              res.getAsJsonArray("messages").forEach(el -> {
                                  JsonObject msg = el.getAsJsonObject();
                                  String text = msg.has("text") ? msg.get("text").getAsString() : "";
                                  String target = msg.has("target") ? msg.get("target").getAsString() : "chat";
                                  long delay = msg.has("delay") ? msg.get("delay").getAsLong() : 0;

                                  Player targetPlayer = p;
                                  if (msg.has("player")) {
                                      try {
                                          UUID pid = UUID.fromString(msg.get("player").getAsString());
                                          Player other = Bukkit.getPlayer(pid);
                                          if (other != null) {
                                              targetPlayer = other;
                                          } else {
                                              return;
                                          }
                                      } catch (IllegalArgumentException ignored) {
                                          return;
                                      }
                                  }
                                  final Player recv = targetPlayer;

                                  Runnable task = switch (target.toLowerCase()) {
                                      case "actionbar" -> () -> recv.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
                                      case "title" -> () -> recv.sendTitle(text, msg.has("subtitle") ? msg.get("subtitle").getAsString() : "", 10, 40, 10);
                                      default -> () -> recv.sendMessage(text);
                                  };
                                  if (delay > 0) {
                                      Bukkit.getScheduler().runTaskLater(plugin, task, delay * 20L);
                                  } else {
                                      task.run();
                                  }
                              });
                          });
                      }
                  }
              }
          });
    }

    public void rewriteAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { sendAbsolute(p); } catch (Exception ignored) {}
        }
    }
}
