package com.grapelemon.lumineeconomybridge;

import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import com.grapelemon.lumineeconomybridge.sync.ScoreboardUtil;
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

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(getConfig().getInt("http_timeout_millis", 2000), TimeUnit.MILLISECONDS)
                .readTimeout(getConfig().getInt("http_timeout_millis", 2000), TimeUnit.MILLISECONDS)
                .writeTimeout(getConfig().getInt("http_timeout_millis", 2000), TimeUnit.MILLISECONDS)
                .build();

        String baseUrl = getConfig().getString("endpoint", "http://127.0.0.1:8000");
        syncService = new ScoreboardSyncService(httpClient, baseUrl, this);

        executor = new LeCommandExecutor(baseUrl, httpClient, syncService);
        getCommand("le").setExecutor(executor);

        // 既存のオンラインプレイヤーをシード
        for (Player p : Bukkit.getOnlinePlayers()) {
            syncService.seed(p);
        }

        // 10秒ごとに差分送信（非同期ループ）。Scoreboard読み取りは同期タスク内で行うので安全
        long period = getConfig().getLong("update_period_ticks", 200L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> syncService.tickAll(), period, period);

        // ログイン/ログアウトでシード/掃除
        getServer().getPluginManager().registerEvents(new PlayerListener(syncService), this);

        getLogger().info("LumineEconomyBridge enabled. Endpoint = " + baseUrl);
    }

    public static LumineEconomyBridge getInstance() { return instance; }

    public OkHttpClient getHttpClient() { return httpClient; }

    public ScoreboardSyncService getSyncService() { return syncService; }
}
