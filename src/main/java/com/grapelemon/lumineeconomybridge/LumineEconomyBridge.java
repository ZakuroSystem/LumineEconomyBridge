package com.grapelemon.lumineeconomybridge;

import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import com.grapelemon.lumineeconomybridge.map.MapColorService;
import com.grapelemon.lumineeconomybridge.map.SnapshotService;
import com.grapelemon.lumineeconomybridge.map.TileDebounceManager;
import com.grapelemon.lumineeconomybridge.map.BlockEventListener;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import com.grapelemon.lumineeconomybridge.shop.ShopListener;
import com.grapelemon.lumineeconomybridge.cash.PaperCurrencyService;
import com.grapelemon.lumineeconomybridge.cash.PaperNoteListener;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LumineEconomyBridge extends JavaPlugin {

    private static LumineEconomyBridge instance;
    private OkHttpClient httpClient;
    private ScoreboardSyncService syncService;
    private BukkitTask syncTask;
    private BukkitTask settleTask;
    private BukkitTask retryTask;
    private LeCommandExecutor executor;

    private String baseUrl;
    private int timeout = 2000;
    private long syncInterval = 10L;

    private MapColorService mapColorService;
    private SnapshotService snapshotService;
    private TileDebounceManager tileDebounceManager;

    private final Map<String, Long> grantTokens = new ConcurrentHashMap<>();
    private Map<String, Boolean> commandPermissions = new HashMap<>();
    private PaperCurrencyService cashService;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        Lang.load(this);
        saveResource("permission_confg.txt", false);
        loadPermissions();
        baseUrl = getConfig().getString("api.base_url", "http://127.0.0.1:8000");
        timeout = getConfig().getInt("api.timeout", timeout);
        syncInterval = getConfig().getLong("sync.interval", syncInterval);

        executor = new LeCommandExecutor(this);
        getCommand("le").setExecutor(executor);
        getCommand("le").setTabCompleter(new LeTabCompleter(this));
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new ShopListener(this), this);

        startBridge();
        startMapColorService();
        startTileUpdates();
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
            timeout = cfg.has("timeout") ? cfg.get("timeout").getAsInt() : timeout;
            syncInterval = cfg.has("sync_interval") ? cfg.get("sync_interval").getAsLong() : syncInterval;

            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                    .readTimeout(timeout, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                    .build();
            syncService = new ScoreboardSyncService(httpClient, baseUrl, this);
            cashService = new PaperCurrencyService(this, httpClient, baseUrl);
            getServer().getPluginManager().registerEvents(new PaperNoteListener(cashService), this);

            for (Player p : Bukkit.getOnlinePlayers()) {
                syncService.seed(p);
            }
            long period = syncInterval * 20L;
            syncTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> syncService.tickAll(), period, period);
            long settlePeriod = 20L * 20L;
            settleTask = Bukkit.getScheduler().runTaskTimer(this, () -> syncService.settleAll(), settlePeriod, settlePeriod);
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
        if (settleTask != null) { settleTask.cancel(); settleTask = null; }
        syncService = null;
        httpClient = null;
        getLogger().info("LumineEconomyBridge stopped.");
    }

    private void startMapColorService() {
        if (mapColorService != null) return;
        int port = getConfig().getInt("mapcolor.port", 8765);
        String token = getConfig().getString("api.token", "");
        try {
            mapColorService = new MapColorService(this, token, port);
            mapColorService.start();
            getLogger().info("server_version=" + getServer().getVersion() +
                    " palette_len=" + mapColorService.getPaletteLength());
        } catch (IOException e) {
            getLogger().warning("failed to start mapcolor service: " + e.getMessage());
        }
    }

    private void startTileUpdates() {
        String token = getConfig().getString("api.token", "");
        int debounce = getConfig().getInt("mapcolor.debounce_ms", 1000);
        snapshotService = new SnapshotService(this, token, baseUrl);
        tileDebounceManager = new TileDebounceManager(this, snapshotService, debounce);
        getServer().getPluginManager().registerEvents(new BlockEventListener(tileDebounceManager), this);
    }

    @Override
    public void onDisable() {
        stopBridge();
        if (mapColorService != null) {
            mapColorService.stop();
            mapColorService = null;
        }
        snapshotService = null;
        tileDebounceManager = null;
    }

    public void reloadBridge() {
        reloadConfig();
        baseUrl = getConfig().getString("api.base_url", baseUrl);
        timeout = getConfig().getInt("api.timeout", timeout);
        syncInterval = getConfig().getLong("sync.interval", syncInterval);
        Lang.load(this);
        loadPermissions();
        stopBridge();
        startBridge();
    }

    public static LumineEconomyBridge getInstance() { return instance; }

    public OkHttpClient getHttpClient() { return httpClient; }

    public ScoreboardSyncService getSyncService() { return syncService; }

    public PaperCurrencyService getCashService() { return cashService; }

    public String getBaseUrl() { return baseUrl; }

    public boolean isActive() { return syncService != null; }

    public boolean consumeGrantToken(String token) {
        long now = System.currentTimeMillis();
        grantTokens.entrySet().removeIf(e -> e.getValue() < now);
        if (grantTokens.containsKey(token)) return false;
        grantTokens.put(token, now + 30000);
        return true;
    }

    public void loadPermissions() {
        File file = new File(getDataFolder(), "permission_confg.txt");
        commandPermissions.clear();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    commandPermissions.put(parts[0].toLowerCase(), parts[1].equalsIgnoreCase("admin"));
                }
            }
        } catch (IOException e) {
            getLogger().warning("Failed to load permission config: " + e.getMessage());
        }
    }

    public boolean requiresAdmin(String cmd) {
        return commandPermissions.getOrDefault(cmd.toLowerCase(), true);
    }
}
