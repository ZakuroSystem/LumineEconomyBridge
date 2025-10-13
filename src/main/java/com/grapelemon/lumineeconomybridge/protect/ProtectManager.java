package com.grapelemon.lumineeconomybridge.protect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import okhttp3.*;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ProtectManager {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long NAME_TIMEOUT_TICKS = 20L * 60L;
    private static final long REMOVE_TIMEOUT_TICKS = 20L * 30L;

    private final LumineEconomyBridge plugin;
    private final NamespacedKey wandKey;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private final Map<String, ProtectionRegion> protections = new LinkedHashMap<>();
    private final Map<UUID, ProtectSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PendingName> pendingNames = new ConcurrentHashMap<>();
    private final Map<UUID, PendingRemoval> pendingRemoval = new ConcurrentHashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;

    private int pricePerBlockUnits;
    private String collectorAccount;
    private String currency;

    public ProtectManager(LumineEconomyBridge plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "protect_wand");
        reload();
    }

    public NamespacedKey getWandKey() {
        return wandKey;
    }

    public synchronized void reload() {
        pricePerBlockUnits = 0;
        collectorAccount = null;
        currency = null;
        FileConfiguration config = plugin.getConfig();
        if (config.isConfigurationSection("protect")) {
            String priceToken = config.getString("protect.price_per_block", "0");
            try {
                pricePerBlockUnits = Math.max(0, plugin.parseAmount(priceToken));
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("Invalid protect.price_per_block value: " + priceToken + "; falling back to 0");
                pricePerBlockUnits = 0;
            }
            String collector = config.getString("protect.collect_account", "treasury");
            if (collector != null && !collector.isBlank()) {
                collectorAccount = collector.trim();
            } else {
                collectorAccount = "treasury";
            }
            String currencyToken = config.getString("protect.currency", "");
            if (currencyToken != null && !currencyToken.isBlank()) {
                currency = currencyToken.trim();
            }
        }
        if (dataFile == null) {
            dataFile = new File(plugin.getDataFolder(), "protections.yml");
        }
        if (!dataFile.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException ex) {
                plugin.getLogger().warning("Failed to create protections.yml: " + ex.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        loadProtections();
    }

    private synchronized void loadProtections() {
        protections.clear();
        if (dataConfig == null) {
            return;
        }
        if (!dataConfig.isConfigurationSection("protections")) {
            return;
        }
        for (String id : dataConfig.getConfigurationSection("protections").getKeys(false)) {
            String path = "protections." + id + ".";
            String owner = dataConfig.getString(path + "owner");
            String world = dataConfig.getString(path + "world");
            int minX = dataConfig.getInt(path + "minX");
            int minY = dataConfig.getInt(path + "minY");
            int minZ = dataConfig.getInt(path + "minZ");
            int maxX = dataConfig.getInt(path + "maxX");
            int maxY = dataConfig.getInt(path + "maxY");
            int maxZ = dataConfig.getInt(path + "maxZ");
            long createdAt = dataConfig.getLong(path + "created", System.currentTimeMillis());
            try {
                UUID ownerId = owner != null ? UUID.fromString(owner) : null;
                if (ownerId == null || world == null) {
                    continue;
                }
                ProtectionRegion region = new ProtectionRegion(id, ownerId, world,
                        minX, minY, minZ, maxX, maxY, maxZ, createdAt);
                protections.put(id, region);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private synchronized void saveProtections() {
        if (dataConfig == null) {
            dataConfig = new YamlConfiguration();
        }
        dataConfig.set("protections", null);
        for (Map.Entry<String, ProtectionRegion> entry : protections.entrySet()) {
            ProtectionRegion region = entry.getValue();
            String base = "protections." + entry.getKey() + ".";
            dataConfig.set(base + "owner", region.getOwner().toString());
            dataConfig.set(base + "world", region.getWorldName());
            dataConfig.set(base + "minX", region.getMinX());
            dataConfig.set(base + "minY", region.getMinY());
            dataConfig.set(base + "minZ", region.getMinZ());
            dataConfig.set(base + "maxX", region.getMaxX());
            dataConfig.set(base + "maxY", region.getMaxY());
            dataConfig.set(base + "maxZ", region.getMaxZ());
            dataConfig.set(base + "created", region.getCreatedAt());
        }
        try {
            dataConfig.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save protections.yml: " + ex.getMessage());
        }
    }

    public synchronized List<String> listIdsForPlayer(UUID uuid) {
        return protections.values().stream()
                .filter(region -> region.getOwner().equals(uuid))
                .map(ProtectionRegion::getId)
                .collect(Collectors.toList());
    }

    public synchronized ProtectionRegion findRegion(String id) {
        if (id == null) return null;
        for (Map.Entry<String, ProtectionRegion> entry : protections.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(id)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public synchronized void startSelection(Player player, String requestedId) {
        ProtectSession session = new ProtectSession(player.getUniqueId(), requestedId);
        sessions.put(player.getUniqueId(), session);
        cancelNamePrompt(player.getUniqueId());
        clearRemovalPrompt(player.getUniqueId());
        giveWand(player);
        player.sendMessage(ChatColor.GREEN + "保護領域の設定を開始しました。Breeze Rodで始点と終点を選択してください。" + ChatColor.RESET);
        player.sendMessage(ChatColor.AQUA + "Protection setup started. Use the Breeze Rod to mark the first and second corners." + ChatColor.RESET);
    }

    private void giveWand(Player player) {
        ItemStack wand = createWand();
        player.getInventory().addItem(wand);
    }

    private ItemStack createWand() {
        ItemStack stack = new ItemStack(org.bukkit.Material.BREEZE_ROD);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Protection Wand");
            List<String> lore = Arrays.asList(
                    ChatColor.GRAY + "左クリック: 始点 / Left click: first corner",
                    ChatColor.GRAY + "右クリック: 終点 / Right click: second corner"
            );
            meta.setLore(lore);
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(wandKey, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public boolean isWand(ItemStack stack) {
        if (stack == null || stack.getType() != org.bukkit.Material.BREEZE_ROD) {
            return false;
        }
        if (!stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return container.has(wandKey, PersistentDataType.BYTE);
    }

    public void recordFirst(Player player, Location location) {
        ProtectSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.setFirst(location.clone());
        player.sendMessage(ChatColor.GREEN + "始点を設定しました: " + formatLocation(location));
        player.sendMessage(ChatColor.AQUA + "First corner set: " + formatLocation(location));
    }

    public void recordSecond(Player player, Location location) {
        ProtectSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.getFirst() == null) {
            player.sendMessage(ChatColor.RED + "先に始点を選択してください。/ Select the first corner first." + ChatColor.RESET);
            return;
        }
        if (!Objects.equals(session.getFirst().getWorld(), location.getWorld())) {
            player.sendMessage(ChatColor.RED + "始点と終点は同じワールドで選択してください。/ Corners must be in the same world." + ChatColor.RESET);
            return;
        }
        session.setSecond(location.clone());
        player.sendMessage(ChatColor.GREEN + "終点を設定しました: " + formatLocation(location));
        player.sendMessage(ChatColor.AQUA + "Second corner set: " + formatLocation(location));
        player.sendMessage(ChatColor.YELLOW + "保護を確定するには /let protect add を実行してください。/ Run /let protect add to finish." + ChatColor.RESET);
    }

    private String formatLocation(Location loc) {
        return "(" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")";
    }

    public void promptForName(Player player) {
        ProtectSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.hasBoth()) {
            player.sendMessage(ChatColor.RED + "始点と終点を選択してから実行してください。/ Select both corners first." + ChatColor.RESET);
            return;
        }
        if (session.isFinalizing()) {
            player.sendMessage(ChatColor.RED + "既に処理中です。/ A finalize request is already running." + ChatColor.RESET);
            return;
        }
        session.setAwaitingName(true);
        PendingName existing = pendingNames.get(player.getUniqueId());
        if (existing != null) {
            existing.timeout.cancel();
        }
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ProtectSession current = sessions.get(player.getUniqueId());
            if (current != null && current.isAwaitingName()) {
                String fallback = generateFallbackId(current);
                finalizeProtection(player, fallback, true);
            }
        }, NAME_TIMEOUT_TICKS);
        pendingNames.put(player.getUniqueId(), new PendingName(timeout));
        player.sendMessage(ChatColor.GOLD + "保護名をチャットで入力してください (1分以内)。" + ChatColor.RESET);
        player.sendMessage(ChatColor.AQUA + "Please type the protection name in chat (within 1 minute)." + ChatColor.RESET);
    }

    private void cancelNamePrompt(UUID playerId) {
        PendingName existing = pendingNames.remove(playerId);
        if (existing != null) {
            existing.timeout.cancel();
        }
        ProtectSession session = sessions.get(playerId);
        if (session != null) {
            session.setAwaitingName(false);
        }
    }

    public void handleNameResponse(Player player, String message) {
        ProtectSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.isAwaitingName()) {
            return;
        }
        cancelNamePrompt(player.getUniqueId());
        String trimmed = message == null ? "" : message.trim();
        if (!isIdValid(trimmed)) {
            player.sendMessage(ChatColor.RED + "入力された保護名は使用できません。始点の座標を利用します。" + ChatColor.RESET);
            player.sendMessage(ChatColor.RED + "The provided name is invalid. Using the first corner coordinates instead." + ChatColor.RESET);
            String fallback = generateFallbackId(session);
            finalizeProtection(player, fallback, true);
        } else {
            finalizeProtection(player, trimmed, false);
        }
    }

    public void finalizeProtection(Player player, String requestedId, boolean autoGenerated) {
        ProtectSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.hasBoth()) {
            player.sendMessage(ChatColor.RED + "保護範囲が未設定です。/ No selection available." + ChatColor.RESET);
            return;
        }
        if (session.isFinalizing()) {
            player.sendMessage(ChatColor.RED + "既に処理中です。/ A finalize request is already running." + ChatColor.RESET);
            return;
        }
        Location first = session.getFirst();
        Location second = session.getSecond();
        if (first == null || second == null) {
            player.sendMessage(ChatColor.RED + "保護範囲が未設定です。/ No selection available." + ChatColor.RESET);
            return;
        }
        if (!Objects.equals(first.getWorld(), second.getWorld())) {
            player.sendMessage(ChatColor.RED + "始点と終点は同じワールドで選択してください。/ Corners must be in the same world." + ChatColor.RESET);
            return;
        }
        String worldName = Objects.requireNonNull(first.getWorld()).getName();
        int minX = Math.min(first.getBlockX(), second.getBlockX());
        int minY = Math.min(first.getBlockY(), second.getBlockY());
        int minZ = Math.min(first.getBlockZ(), second.getBlockZ());
        int maxX = Math.max(first.getBlockX(), second.getBlockX());
        int maxY = Math.max(first.getBlockY(), second.getBlockY());
        int maxZ = Math.max(first.getBlockZ(), second.getBlockZ());
        long volume = ((long) maxX - minX + 1L) * ((long) maxY - minY + 1L) * ((long) maxZ - minZ + 1L);
        if (volume <= 0) {
            player.sendMessage(ChatColor.RED + "保護範囲の計算に失敗しました。/ Failed to compute selection volume." + ChatColor.RESET);
            return;
        }
        String baseId = requestedId != null ? requestedId.trim() : "";
        if (!isIdValid(baseId)) {
            baseId = generateFallbackId(session);
        }
        final String resolvedId = ensureUniqueId(baseId);
        long totalCost = volume * (long) pricePerBlockUnits;
        if (totalCost < 0 || totalCost > Integer.MAX_VALUE) {
            player.sendMessage(ChatColor.RED + "保護費用が大きすぎます。範囲を小さくしてください。/ The protection fee is too large." + ChatColor.RESET);
            return;
        }
        int finalCost = (int) totalCost;
        session.setFinalizing(true);
        cancelNamePrompt(player.getUniqueId());
        chargePlayer(player, finalCost, resolvedId, success -> {
            if (!success) {
                session.setFinalizing(false);
                return;
            }
            ProtectionRegion region = new ProtectionRegion(resolvedId, player.getUniqueId(), worldName,
                    minX, minY, minZ, maxX, maxY, maxZ, System.currentTimeMillis());
            synchronized (ProtectManager.this) {
                protections.put(resolvedId, region);
                saveProtections();
            }
            removeWand(player);
            sessions.remove(player.getUniqueId());
            session.setFinalizing(false);
            player.sendMessage(ChatColor.GREEN + "保護を登録しました: " + resolvedId + ChatColor.RESET);
            player.sendMessage(ChatColor.AQUA + "Protection registered as '" + resolvedId + "'." + ChatColor.RESET);
            if (autoGenerated) {
                player.sendMessage(ChatColor.GRAY + "(自動決定されたIDです。/ The ID was generated automatically.)" + ChatColor.RESET);
            }
        });
    }

    private void removeWand(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (isWand(stack)) {
                int amount = stack.getAmount();
                if (amount <= 1) {
                    player.getInventory().setItem(slot, null);
                } else {
                    stack.setAmount(amount - 1);
                    player.getInventory().setItem(slot, stack);
                }
                player.updateInventory();
                break;
            }
        }
    }

    private void chargePlayer(Player player, int amountUnits, String referenceId, Consumer<Boolean> callback) {
        if (amountUnits <= 0 || pricePerBlockUnits <= 0) {
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(true));
            return;
        }
        if (collectorAccount == null || collectorAccount.isBlank()) {
            player.sendMessage(ChatColor.RED + "支払い先アカウントが設定されていません。/ No collector account configured." + ChatColor.RESET);
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(false));
            return;
        }
        if (!plugin.isActive() || plugin.getHttpClient() == null || plugin.getSyncService() == null) {
            player.sendMessage(ChatColor.RED + "経済システムに接続できません。/ Economy bridge unavailable." + ChatColor.RESET);
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(false));
            return;
        }
        ScoreboardSyncService sync = plugin.getSyncService();
        sync.flush(player);
        OkHttpClient http = plugin.getHttpClient();
        String baseUrl = plugin.getBaseUrl();

        List<String> tokens = new ArrayList<>();
        tokens.add("pay");
        tokens.add(collectorAccount);
        tokens.add(plugin.formatAmountPlain(amountUnits));
        if (currency != null && !currency.isBlank()) {
            tokens.add(currency);
            tokens.add(buildReference(referenceId));
        } else {
            tokens.add(buildReference(referenceId));
        }
        String command = "/" + String.join(" ", tokens);

        Map<String, Object> payload = new HashMap<>();
        payload.put("player", player.getUniqueId().toString());
        payload.put("executor", player.getName());
        payload.put("command", command);
        payload.put("timestamp", System.currentTimeMillis() / 1000);
        Location loc = player.getLocation();
        Map<String, Object> locMap = new HashMap<>();
        locMap.put("world", loc.getWorld() != null ? loc.getWorld().getName() : "");
        locMap.put("x", loc.getX());
        locMap.put("y", loc.getY());
        locMap.put("z", loc.getZ());
        payload.put("location", locMap);

        Request req = new Request.Builder()
                .url(baseUrl + "/api/message")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();

        http.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                plugin.getLogger().warning("Protect payment failed: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(ChatColor.RED + "決済に失敗しました。/ Failed to charge for protection." + ChatColor.RESET);
                    callback.accept(false);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                    boolean ok = obj.has("status") && "success".equalsIgnoreCase(obj.get("status").getAsString());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        dispatchMessages(player, obj);
                        applyScoreboards(player, obj);
                        if (!ok) {
                            player.sendMessage(ChatColor.RED + "保護費用の支払いに失敗しました。/ Protection fee payment failed." + ChatColor.RESET);
                        }
                        callback.accept(ok);
                    });
                }
            }
        });
    }

    private String buildReference(String referenceId) {
        String base = "protect:" + referenceId;
        if (base.length() <= 64) {
            return base;
        }
        return base.substring(0, 64);
    }

    private void dispatchMessages(Player player, JsonObject obj) {
        if (!obj.has("messages")) {
            return;
        }
        obj.getAsJsonArray("messages").forEach(el -> {
            JsonObject msg = el.getAsJsonObject();
            String text = msg.has("text") ? ChatColor.translateAlternateColorCodes('&', msg.get("text").getAsString()) : "";
            String target = msg.has("target") ? msg.get("target").getAsString() : "chat";
            Player recv = player;
            if (msg.has("player")) {
                try {
                    UUID id = UUID.fromString(msg.get("player").getAsString());
                    Player other = Bukkit.getPlayer(id);
                    if (other != null) {
                        recv = other;
                    } else {
                        return;
                    }
                } catch (IllegalArgumentException ignored) {
                    return;
                }
            }
            Player finalRecv = recv;
            switch (target.toLowerCase(Locale.ROOT)) {
                case "actionbar" -> finalRecv.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
                case "title" -> finalRecv.sendTitle(text,
                        msg.has("subtitle") ? ChatColor.translateAlternateColorCodes('&', msg.get("subtitle").getAsString()) : "",
                        10, 40, 10);
                default -> finalRecv.sendMessage(text);
            }
        });
    }

    private void applyScoreboards(Player executor, JsonObject obj) {
        ScoreboardSyncService sync = plugin.getSyncService();
        if (sync == null) return;
        if (obj.has("scoreboards")) {
            obj.getAsJsonObject("scoreboards").entrySet().forEach(entry -> {
                try {
                    UUID id = UUID.fromString(entry.getKey());
                    Player target = Bukkit.getPlayer(id);
                    if (target != null) {
                        Map<String, Integer> updates = new HashMap<>();
                        entry.getValue().getAsJsonObject().entrySet().forEach(e -> updates.put(e.getKey(), e.getValue().getAsInt()));
                        sync.applyFromPython(target, updates);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            });
        } else if (obj.has("scoreboard")) {
            Map<String, Integer> updates = new HashMap<>();
            obj.getAsJsonObject("scoreboard").entrySet().forEach(e -> updates.put(e.getKey(), e.getValue().getAsInt()));
            if (executor != null) {
                sync.applyFromPython(executor, updates);
            }
        }
    }

    private boolean isIdValid(String candidate) {
        if (candidate == null) return false;
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return trimmed.length() <= 64;
    }

    private String generateFallbackId(ProtectSession session) {
        if (isIdValid(session.getInitialId())) {
            return session.getInitialId().trim();
        }
        Location base = session.getFirst();
        if (base == null) {
            return "protect-" + System.currentTimeMillis();
        }
        return "x" + base.getBlockX() + "y" + base.getBlockY() + "z" + base.getBlockZ();
    }

    private synchronized String ensureUniqueId(String base) {
        String trimmed = base.trim();
        if (trimmed.isEmpty()) {
            trimmed = "protect";
        }
        String candidate = trimmed;
        int suffix = 2;
        while (hasIdIgnoreCase(candidate)) {
            candidate = trimmed + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private synchronized boolean hasIdIgnoreCase(String candidate) {
        for (String existing : protections.keySet()) {
            if (existing.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    public void requestRemoval(Player player, String id) {
        ProtectionRegion region = findRegion(id);
        if (region == null) {
            player.sendMessage(ChatColor.RED + "指定された保護は存在しません。/ Protection not found." + ChatColor.RESET);
            return;
        }
        if (!region.getOwner().equals(player.getUniqueId()) && !plugin.hasBypass(player) && !player.isOp() && !player.hasPermission("lumineeconomy.admin")) {
            player.sendMessage(ChatColor.RED + "この保護を削除する権限がありません。/ You do not own this protection." + ChatColor.RESET);
            return;
        }
        PendingRemoval existing = pendingRemoval.get(player.getUniqueId());
        if (existing != null) {
            existing.timeout.cancel();
        }
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingRemoval pending = pendingRemoval.remove(player.getUniqueId());
            if (pending != null) {
                player.sendMessage(ChatColor.RED + "削除がタイムアウトしました。/ Removal request timed out." + ChatColor.RESET);
            }
        }, REMOVE_TIMEOUT_TICKS);
        pendingRemoval.put(player.getUniqueId(), new PendingRemoval(region, timeout));
        player.sendMessage(ChatColor.GOLD + region.getId() + " を本当に削除しますか？チャットで YES / はい と入力してください。" + ChatColor.RESET);
        player.sendMessage(ChatColor.AQUA + "Type YES to confirm removing " + region.getId() + " within 30 seconds." + ChatColor.RESET);
    }

    public void handleRemovalResponse(Player player, String message) {
        PendingRemoval pending = pendingRemoval.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }
        pending.timeout.cancel();
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("yes") || normalized.equals("y") || normalized.equals("はい") || normalized.equals("hai") || normalized.equals("はい。")) {
            synchronized (this) {
                protections.remove(pending.region().getId());
                saveProtections();
            }
            player.sendMessage(ChatColor.GREEN + "保護を削除しました。/ Protection removed." + ChatColor.RESET);
        } else {
            player.sendMessage(ChatColor.RED + "削除をキャンセルしました。/ Removal cancelled." + ChatColor.RESET);
            pendingRemoval.remove(player.getUniqueId());
        }
    }

    private void clearRemovalPrompt(UUID playerId) {
        PendingRemoval pending = pendingRemoval.remove(playerId);
        if (pending != null) {
            pending.timeout.cancel();
        }
    }

    public void cancelAll(Player player) {
        cancelNamePrompt(player.getUniqueId());
        clearRemovalPrompt(player.getUniqueId());
        sessions.remove(player.getUniqueId());
    }

    public boolean isAwaitingName(UUID uuid) {
        ProtectSession session = sessions.get(uuid);
        return session != null && session.isAwaitingName();
    }

    public boolean isAwaitingRemoval(UUID uuid) {
        return pendingRemoval.containsKey(uuid);
    }

    public boolean hasSession(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    public boolean isSelectionComplete(UUID uuid) {
        ProtectSession session = sessions.get(uuid);
        return session != null && session.hasBoth();
    }

    public void updateInitialId(Player player, String id) {
        ProtectSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.setInitialId(id);
        }
    }

    private record PendingName(BukkitTask timeout) {}

    private record PendingRemoval(ProtectionRegion region, BukkitTask timeout) {}
}
