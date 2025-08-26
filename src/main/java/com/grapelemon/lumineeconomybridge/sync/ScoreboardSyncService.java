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
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class ScoreboardSyncService {
    private final OkHttpClient http;
    private final String baseUrl;
    private final LumineEconomyBridge plugin;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // 経済はDB主体。scoreboardは表示用で、"*_cash"は加減算要求として扱う。

    public ScoreboardSyncService(OkHttpClient http, String baseUrl, LumineEconomyBridge plugin) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.plugin = plugin;
    }

    public void seed(Player p) {
        sendAbsolute(p);
    }

    public void cleanup(UUID id) {}

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

    // Pythonからの絶対値適用
    public void applyFromPython(Player p, Map<String, Integer> abs) {
        Bukkit.getScheduler().runTask(plugin, () -> ScoreboardUtil.applyAbsoluteSync(p, abs));
    }

    private void sendDelta(Player p, Map<String, Integer> delta) {
        if (delta.isEmpty()) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("player", p.getUniqueId().toString());
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
                if (response.body() != null) response.body().close();
            }
        });
    }

    private void flushPlayer(Player p) {
        Map<String, Integer> delta = callSync(() -> {
            Map<String, Integer> out = new HashMap<>();
            Scoreboard sb = p.getScoreboard() != null ? p.getScoreboard() : Bukkit.getScoreboardManager().getMainScoreboard();
            String entry = p.getName();
            for (Objective obj : sb.getObjectives()) {
                String name = obj.getName();
                if (name.startsWith("currency") && name.endsWith("_cash")) {
                    int d = obj.getScore(entry).getScore();
                    if (d != 0) {
                        String baseName = name.substring(0, name.length() - 5);
                        int cur = ScoreboardUtil.readCurrency(sb, baseName, entry);
                        ScoreboardUtil.writeCurrency(sb, baseName, baseName, entry, cur + d);
                        obj.getScore(entry).setScore(0);
                        out.put(baseName, d);
                    }
                }
            }
            return out;
        });
        sendDelta(p, delta);
    }

    public void flushAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { flushPlayer(p); } catch (Exception ignored) {}
        }
    }

    // Expose flushing for a single player
    public void flush(Player p) {
        flushPlayer(p);
    }

    public void sendAbsolute(Player p) {
        UUID id = p.getUniqueId();
        Map<String, Integer> current = callSync(() -> ScoreboardUtil.readAllSync(p));
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
