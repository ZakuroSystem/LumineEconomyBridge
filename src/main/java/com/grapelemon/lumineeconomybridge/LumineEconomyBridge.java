package com.grapelemon.lumineeconomybridge;

import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import com.grapelemon.lumineeconomybridge.map.SnapshotService;
import com.grapelemon.lumineeconomybridge.map.TileDebounceManager;
import com.grapelemon.lumineeconomybridge.map.BlockEventListener;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.grapelemon.lumineeconomybridge.shop.ShopListener;
import com.grapelemon.lumineeconomybridge.shop.gui.ShopGuiListener;
import com.grapelemon.lumineeconomybridge.shop.gui.ShopGuiManager;
import com.grapelemon.lumineeconomybridge.cash.PaperCurrencyService;
import com.grapelemon.lumineeconomybridge.cash.PaperNoteListener;
import com.grapelemon.lumineeconomybridge.guide.GuideBookListener;
import com.grapelemon.lumineeconomybridge.vault.VaultEconomyBridge;
import com.grapelemon.lumineeconomybridge.protect.ProtectManager;
import com.grapelemon.lumineeconomybridge.protect.ProtectListener;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.ServicePriority;
import net.milkbowl.vault.economy.Economy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class LumineEconomyBridge extends JavaPlugin {

    private static LumineEconomyBridge instance;
    private OkHttpClient httpClient;
    private ScoreboardSyncService syncService;
    private BukkitTask settleTask;
    private BukkitTask retryTask;
    private LeCommandExecutor executor;
    private VaultEconomyBridge vaultEconomy;
    private GuideBookListener guideBookListener;
    private ShopGuiManager shopGuiManager;
    private ProtectManager protectManager;

    private String baseUrl;
    private int timeout = 2000;

    private static final int DEFAULT_DECIMAL_PLACES = 2;
    private static final DecimalFormatSymbols DECIMAL_SYMBOLS = new DecimalFormatSymbols(Locale.US);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private int configuredDecimalPlaces = DEFAULT_DECIMAL_PLACES;
    private int decimalPlaces = DEFAULT_DECIMAL_PLACES;
    private int amountScale = computeScale(DEFAULT_DECIMAL_PLACES);

    private SnapshotService snapshotService;
    private TileDebounceManager tileDebounceManager;

    private final Map<String, Long> grantTokens = new ConcurrentHashMap<>();
    private Map<String, Boolean> commandPermissions = new HashMap<>();
    private PaperCurrencyService cashService;
    private final Set<String> bypassUsers = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadDecimalConfig();
        Lang.load(this);
        saveResource("permission_confg.txt", false);
        loadPermissions();
        baseUrl = normalizeBaseUrl(getConfig().getString("api.base_url", "http://127.0.0.1:8000"));
        timeout = getConfig().getInt("api.timeout", timeout);

        executor = new LeCommandExecutor(this);
        getCommand("le").setExecutor(executor);
        getCommand("le").setTabCompleter(new LeTabCompleter(this));
        protectManager = new ProtectManager(this);
        getCommand("let").setExecutor(new LetCommandExecutor(protectManager));
        getCommand("let").setTabCompleter(new LetTabCompleter(protectManager));
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new ShopListener(this), this);
        shopGuiManager = new ShopGuiManager(this);
        getServer().getPluginManager().registerEvents(new ShopGuiListener(shopGuiManager), this);
        getServer().getPluginManager().registerEvents(new ProtectListener(this, protectManager), this);
        guideBookListener = new GuideBookListener(this);
        getServer().getPluginManager().registerEvents(guideBookListener, this);
        guideBookListener.distributeToOnline();

        startBridge();
        startTileUpdates();
    }

    public ShopGuiManager getShopGuiManager() {
        return shopGuiManager;
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
            if (cfg.has("decimal_places")) {
                int backendPlaces = sanitizeDecimalPlaces(cfg.get("decimal_places").getAsInt());
                synchronized (this) {
                    updateDecimalState(backendPlaces);
                }
            }

            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                    .readTimeout(timeout, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                    .build();
            syncDecimalMode(httpClient);
            syncService = new ScoreboardSyncService(httpClient, baseUrl, this);
            cashService = new PaperCurrencyService(this, httpClient, baseUrl);
            getServer().getPluginManager().registerEvents(new PaperNoteListener(cashService), this);

            refreshBypassUsers();

            if (getConfig().getBoolean("vault.enabled", false) &&
                    Bukkit.getPluginManager().getPlugin("Vault") != null) {
                vaultEconomy = new VaultEconomyBridge(this);
                getServer().getServicesManager().register(Economy.class, vaultEconomy, this, ServicePriority.Lowest);
                getLogger().info("Registered Vault economy bridge");
            }

            for (Player p : Bukkit.getOnlinePlayers()) {
                syncService.seed(p);
            }
            long settlePeriod = 20L * 20L;
            settleTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                syncService.flushAll();
                cashService.flushAll();
            }, settlePeriod, settlePeriod);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                syncService.rewriteAll();
                cashService.flushAll();
            });

            getLogger().info("LumineEconomyBridge started. Endpoint = " + baseUrl);
            return true;
        } catch (IOException e) {
            getLogger().warning("Failed to fetch config: " + e.getMessage());
            return false;
        }
    }

    public void stopBridge() {
        if (retryTask != null) { retryTask.cancel(); retryTask = null; }
        if (settleTask != null) { settleTask.cancel(); settleTask = null; }
        if (vaultEconomy != null) {
            getServer().getServicesManager().unregister(Economy.class, vaultEconomy);
            vaultEconomy = null;
        }
        if (cashService != null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    cashService.flushEvents();
                    cashService.flushAll();
                } finally {
                    future.complete(null);
                }
            });
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException e) {
                getLogger().warning("Failed to flush cash data: " + e.getMessage());
            } catch (TimeoutException e) {
                getLogger().warning("Timed out while flushing cash data");
            }
            cashService = null;
        }
        syncService = null;
        httpClient = null;
        bypassUsers.clear();
        getLogger().info("LumineEconomyBridge stopped.");
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
        if (shopGuiManager != null) {
            shopGuiManager.shutdown();
        }
        snapshotService = null;
        tileDebounceManager = null;
    }

    public void reloadBridge() {
        reloadConfig();
        loadDecimalConfig();
        if (protectManager != null) {
            protectManager.reload();
        }
        baseUrl = normalizeBaseUrl(getConfig().getString("api.base_url", baseUrl));
        timeout = getConfig().getInt("api.timeout", timeout);
        Lang.load(this);
        loadPermissions();
        stopBridge();
        startBridge();
    }

    private String normalizeBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int minEnd = 0;
        int schemeIndex = trimmed.indexOf("://");
        if (schemeIndex >= 0) {
            minEnd = schemeIndex + 3;
        }

        String withoutTrailing = stripTrailingSlashes(trimmed, minEnd);

        if (withoutTrailing.length() - minEnd >= 4
                && withoutTrailing.regionMatches(true, withoutTrailing.length() - 4, "/api", 0, 4)) {
            String candidate = stripTrailingSlashes(withoutTrailing.substring(0, withoutTrailing.length() - 4), minEnd);
            if (!candidate.isEmpty()) {
                withoutTrailing = candidate;
            }
        }

        return withoutTrailing;
    }

    private String stripTrailingSlashes(String value, int minEnd) {
        int end = value.length();
        while (end > minEnd && value.charAt(end - 1) == '/') {
            end--;
        }
        return end <= 0 ? value : value.substring(0, end);
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

    public boolean hasBypass(Player player) {
        return player != null && hasBypass(player.getName());
    }

    public boolean hasBypass(String name) {
        if (name == null) return false;
        return bypassUsers.contains(name.toLowerCase(Locale.ROOT));
    }

    private int computeScale(int places) {
        int scale = 1;
        for (int i = 0; i < places; i++) {
            scale *= 10;
        }
        return scale;
    }

    private int sanitizeDecimalPlaces(int candidate) {
        if (candidate == 0 || candidate == 2 || candidate == 4) {
            return candidate;
        }
        getLogger().warning("Unsupported currency.decimal_places=" + candidate + ", falling back to " + DEFAULT_DECIMAL_PLACES);
        return DEFAULT_DECIMAL_PLACES;
    }

    private void loadDecimalConfig() {
        int raw = getConfig().getInt("currency.decimal_places", DEFAULT_DECIMAL_PLACES);
        int sanitized = sanitizeDecimalPlaces(raw);
        synchronized (this) {
            configuredDecimalPlaces = sanitized;
            updateDecimalState(sanitized);
        }
    }

    private void updateDecimalState(int places) {
        decimalPlaces = places;
        amountScale = computeScale(places);
    }

    private void syncDecimalMode(OkHttpClient client) {
        JsonObject payload = new JsonObject();
        payload.addProperty("decimal_places", configuredDecimalPlaces);
        Request request = new Request.Builder()
                .url(baseUrl + "/api/config/decimal")
                .addHeader("X-LE-Token", getConfig().getString("api.token", ""))
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                getLogger().warning("Failed to update decimal mode: status " + response.code());
                return;
            }
            String body = response.body() != null ? response.body().string() : "{}";
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("decimal_places")) {
                int reported = sanitizeDecimalPlaces(obj.get("decimal_places").getAsInt());
                int previous;
                synchronized (this) {
                    previous = decimalPlaces;
                    updateDecimalState(reported);
                }
                if (reported != previous) {
                    getLogger().info("Decimal places set to " + reported);
                }
            }
        } catch (IOException ex) {
            getLogger().warning("Failed to sync decimal mode: " + ex.getMessage());
        }
    }

    private String buildPlainPattern() {
        if (decimalPlaces == 0) {
            return "0";
        }
        StringBuilder pattern = new StringBuilder("0.");
        for (int i = 0; i < decimalPlaces; i++) {
            pattern.append('#');
        }
        return pattern.toString();
    }

    private String buildGroupedPattern() {
        if (decimalPlaces == 0) {
            return "#,##0";
        }
        StringBuilder pattern = new StringBuilder("#,##0.");
        for (int i = 0; i < decimalPlaces; i++) {
            pattern.append('#');
        }
        return pattern.toString();
    }

    public synchronized int parseAmount(String value) throws NumberFormatException {
        try {
            BigDecimal decimal = new BigDecimal(value);
            decimal = decimal.setScale(decimalPlaces, RoundingMode.UNNECESSARY);
            if (decimalPlaces > 0) {
                decimal = decimal.movePointRight(decimalPlaces);
            }
            return decimal.intValueExact();
        } catch (ArithmeticException ex) {
            throw new NumberFormatException(ex.getMessage());
        }
    }

    public synchronized String formatAmountPlain(int amount) {
        BigDecimal decimal = new BigDecimal(amount);
        if (decimalPlaces > 0) {
            decimal = decimal.movePointLeft(decimalPlaces);
        }
        DecimalFormat format = new DecimalFormat(buildPlainPattern(), DECIMAL_SYMBOLS);
        format.setRoundingMode(RoundingMode.UNNECESSARY);
        return format.format(decimal);
    }

    public synchronized String formatAmountGrouped(int amount) {
        BigDecimal decimal = new BigDecimal(amount);
        if (decimalPlaces > 0) {
            decimal = decimal.movePointLeft(decimalPlaces);
        }
        DecimalFormat format = new DecimalFormat(buildGroupedPattern(), DECIMAL_SYMBOLS);
        format.setRoundingMode(RoundingMode.UNNECESSARY);
        return format.format(decimal);
    }

    public synchronized int getDecimalPlaces() {
        return decimalPlaces;
    }

    private void refreshBypassUsers() {
        if (httpClient == null) {
            return;
        }
        Request req = new Request.Builder().url(baseUrl + "/api/admin/bypass").build();
        try (Response res = httpClient.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                getLogger().warning("Failed to fetch bypass list: status " + res.code());
                return;
            }
            String body = res.body() != null ? res.body().string() : "{}";
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (!obj.has("users") || !obj.get("users").isJsonArray()) {
                getLogger().warning("Bypass list missing 'users' array");
                return;
            }
            Set<String> names = new HashSet<>();
            obj.getAsJsonArray("users").forEach(el -> {
                if (el != null && el.isJsonPrimitive()) {
                    String entry = el.getAsString();
                    if (entry != null && !entry.isBlank()) {
                        names.add(entry.toLowerCase(Locale.ROOT));
                    }
                }
            });
            bypassUsers.clear();
            bypassUsers.addAll(names);
            getLogger().info("Loaded " + names.size() + " bypass accounts from backend");
        } catch (IOException ex) {
            getLogger().warning("Failed to load bypass list: " + ex.getMessage());
        }
    }
}
