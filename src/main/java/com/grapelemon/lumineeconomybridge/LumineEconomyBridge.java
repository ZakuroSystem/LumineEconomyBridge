package com.grapelemon.lumineeconomybridge;

import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import okhttp3.OkHttpClient;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

public class LumineEconomyBridge extends JavaPlugin {

    private static LumineEconomyBridge instance;
    private OkHttpClient httpClient;
    private ScoreboardSyncService syncService;
    private LeCommandExecutor executor;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        int timeout = getConfig().getInt("api.timeout", 2000);
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();

        String baseUrl = getConfig().getString("api.base_url", "http://127.0.0.1:5100");
        syncService = new ScoreboardSyncService(httpClient, baseUrl, this);

        executor = new LeCommandExecutor(baseUrl, httpClient, syncService);
        getCommand("le").setExecutor(executor);

        // 既存のオンラインプレイヤーをシード
        for (Player p : Bukkit.getOnlinePlayers()) {
            syncService.seed(p);
        }

        // 10秒ごとに差分送信（非同期ループ）。Scoreboard読み取りは同期タスク内で行うので安全
        long period = getConfig().getLong("sync.interval", 10L) * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> syncService.tickAll(), period, period);

        // ログイン/ログアウトでシード/掃除
        getServer().getPluginManager().registerEvents(new PlayerListener(syncService), this);

        // 起動時に全プレイヤーの絶対値同期
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> syncService.rewriteAll());

        getLogger().info("LumineEconomyBridge enabled. Endpoint = " + baseUrl);
    }

    public static LumineEconomyBridge getInstance() { return instance; }

    public OkHttpClient getHttpClient() { return httpClient; }

    public ScoreboardSyncService getSyncService() { return syncService; }
}
