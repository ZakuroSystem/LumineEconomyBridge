package com.grapelemon.lumineeconomybridge;

import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class LumineEconomyBridge extends JavaPlugin {

    private static LumineEconomyBridge instance;
    private OkHttpClient httpClient;
    private ScoreboardSyncService syncService;
    private BukkitTask syncTask;
    private BukkitTask retryTask;
    private LeCommandExecutor executor;

    private String baseUrl;
    private int timeout = 2000;
    private long syncInterval = 10L;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        Lang.load(this);
        baseUrl = getConfig().getString("api.base_url", "http://127.0.0.1:8000");

        executor = new LeCommandExecutor(this);
        getCommand("le").setExecutor(executor);
        getCommand("le").setTabCompleter(new LeTabCompleter());
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        startBridge();
    }

    public void startBridge() {
        if (syncService != null) return;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (!attemptStart()) {
                if (retryTask == null) {
                    long period = 3 * 60L * 20L;
                    retryTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                        if (attemptStart() && retryTask != null) {
                            retryTask.cancel();
                            retryTask = null;
                        }
                    }, period, period);
                }
            }
        });
    }

    private boolean attemptStart() {
        OkHttpClient temp = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .build();
        Request req = new Request.Builder().url(baseUrl + "/api/config").build();
        try (Response res = temp.newCall(req).execute()) {
            String body = res.body() != null ? res.body().string() : "{}";
            JsonObject cfg = JsonParser.parseString(body).getAsJsonObject();
            timeout = cfg.has("timeout") ? cfg.get("timeout").getAsInt() : 2000;
            syncInterval = cfg.has("sync_interval") ? cfg.get("sync_interval").getAsLong() : 10L;

            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                    .readTimeout(timeout, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                    .build();
            syncService = new ScoreboardSyncService(httpClient, baseUrl, this);

            for (Player p : Bukkit.getOnlinePlayers()) {
                syncService.seed(p);
            }
            long period = syncInterval * 20L;
            syncTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> syncService.tickAll(), period, period);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> syncService.rewriteAll());

            getLogger().info("LumineEconomyBridge started. Endpoint = " + baseUrl);
            return true;
        } catch (IOException e) {
            getLogger().warning("Failed to fetch config: " + e.getMessage());
            return false;
        }
    }

    public void stopBridge() {
        if (retryTask != null) { retryTask.cancel(); retryTask = null; }
        if (syncTask != null) { syncTask.cancel(); syncTask = null; }
        syncService = null;
        httpClient = null;
        getLogger().info("LumineEconomyBridge stopped.");
    }

    public void reloadBridge() {
        reloadConfig();
        baseUrl = getConfig().getString("api.base_url", baseUrl);
        Lang.load(this);
        stopBridge();
        startBridge();
    }

    public static LumineEconomyBridge getInstance() { return instance; }

    public OkHttpClient getHttpClient() { return httpClient; }

    public ScoreboardSyncService getSyncService() { return syncService; }

    public String getBaseUrl() { return baseUrl; }

    public boolean isActive() { return syncService != null; }
}
