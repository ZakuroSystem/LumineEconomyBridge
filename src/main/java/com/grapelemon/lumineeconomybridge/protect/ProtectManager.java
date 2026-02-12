package com.grapelemon.lumineeconomybridge.protect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import com.grapelemon.lumineeconomybridge.sync.ScoreboardUtil;
import okhttp3.*;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
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
    private static final long APPROVAL_TIMEOUT_TICKS = 20L * 60L;
    private static final long REMOVE_TIMEOUT_TICKS = 20L * 30L;
    private static final long DEFAULT_UPKEEP_INTERVAL_MINUTES = 60L * 24L;

    private final LumineEconomyBridge plugin;
    private final NamespacedKey wandKey;
    private final NamespacedKey leaseSignKey;
    private final NamespacedKey wandModeKey;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private final Map<String, ProtectionRegion> protections = new LinkedHashMap<>();
    private final Map<UUID, ProtectSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PendingName> pendingNames = new ConcurrentHashMap<>();
    private final Map<UUID, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<UUID, PendingRemoval> pendingRemoval = new ConcurrentHashMap<>();
    private final Map<UUID, PendingLease> pendingLeases = new ConcurrentHashMap<>();
    private final Map<UUID, PendingSignWizard> pendingSignWizards = new ConcurrentHashMap<>();
    private final Map<String, LeaseSignInfo> leaseSigns = new ConcurrentHashMap<>();
    private final Map<UUID, PendingLeaseConfirm> pendingLeaseConfirmations = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> ezResetSnapshots = new ConcurrentHashMap<>();

    private File dataFile;
    private File jsonDataFile;
    private FileConfiguration dataConfig;

    private int initialPricePerBlockUnits;
    private List<PricingBracket> initialPricingBrackets = Collections.emptyList();
    private int upkeepPricePerBlockUnits;
    private long upkeepIntervalMinutes;
    private BukkitTask upkeepTask;
    private String collectorAccount;
    private String currency;

    public ProtectManager(LumineEconomyBridge plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "protect_wand");
        this.leaseSignKey = new NamespacedKey(plugin, "protect_lease_sign");
        this.wandModeKey = new NamespacedKey(plugin, "protect_wand_mode");
        reload();
    }

    public NamespacedKey getWandKey() {
        return wandKey;
    }

    public synchronized void reload() {
        initialPricePerBlockUnits = 0;
        initialPricingBrackets = Collections.emptyList();
        upkeepPricePerBlockUnits = 0;
        upkeepIntervalMinutes = DEFAULT_UPKEEP_INTERVAL_MINUTES;
        collectorAccount = null;
        currency = null;
        FileConfiguration config = plugin.getConfig();
        if (config.isConfigurationSection("protect")) {
            String legacy = config.getString("protect.price_per_block", "0");
            String priceToken = config.getString("protect.initial_price_per_block", legacy);
            try {
                initialPricePerBlockUnits = Math.max(0, plugin.parseAmount(priceToken));
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("Invalid protect.initial_price_per_block value: " + priceToken + "; falling back to 0");
                initialPricePerBlockUnits = 0;
            }
            initialPricingBrackets = loadInitialPricingBrackets(config);

            String upkeepToken = config.getString("protect.upkeep_price_per_block", "0");
            try {
                upkeepPricePerBlockUnits = Math.max(0, plugin.parseAmount(upkeepToken));
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("Invalid protect.upkeep_price_per_block value: " + upkeepToken + "; falling back to 0");
                upkeepPricePerBlockUnits = 0;
            }
            upkeepIntervalMinutes = Math.max(1L, config.getLong("protect.upkeep_interval_minutes", DEFAULT_UPKEEP_INTERVAL_MINUTES));

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
        if (jsonDataFile == null) {
            jsonDataFile = new File(plugin.getDataFolder(), "protections.json");
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
        loadLeaseSigns();
        restartUpkeepTask();
    }

    private void restartUpkeepTask() {
        if (upkeepTask != null) {
            upkeepTask.cancel();
            upkeepTask = null;
        }
        if (upkeepPricePerBlockUnits <= 0 || upkeepIntervalMinutes <= 0) {
            return;
        }
        long period = Math.max(20L, upkeepIntervalMinutes * 60L * 20L);
        upkeepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::chargeUpkeepForOnlineOwners, period, period);
    }

    private List<PricingBracket> loadInitialPricingBrackets(FileConfiguration config) {
        List<PricingBracket> brackets = new ArrayList<>();
        List<Map<?, ?>> raw = config.getMapList("protect.initial_pricing_brackets");
        if (raw == null || raw.isEmpty()) {
            return defaultInitialPricingBrackets();
        }
        for (Map<?, ?> row : raw) {
            long min = parseLongSafe(row.get("min"), 0L);
            Long max = parseNullableLongSafe(row.get("max"));
            int rate = parseRateUnits(row.get("rate_per_block"));
            brackets.add(new PricingBracket(min, max, rate));
        }
        if (brackets.isEmpty()) {
            return defaultInitialPricingBrackets();
        }
        brackets.sort(Comparator.comparingLong(PricingBracket::minInclusive));
        return brackets;
    }

    private List<PricingBracket> defaultInitialPricingBrackets() {
        List<PricingBracket> defaults = new ArrayList<>();
        defaults.add(new PricingBracket(0L, 1000L, parseRateUnits("0.2")));
        defaults.add(new PricingBracket(1000L, 10000L, parseRateUnits("0.6")));
        defaults.add(new PricingBracket(10000L, 25000L, parseRateUnits("2.0")));
        defaults.add(new PricingBracket(25000L, 100000L, parseRateUnits("5.0")));
        defaults.add(new PricingBracket(100000L, null, parseRateUnits("10.0")));
        return defaults;
    }

    private long parseLongSafe(Object raw, long fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Long parseNullableLongSafe(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        if (text.isEmpty() || text.equalsIgnoreCase("null") || text.equals("-1")) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int parseRateUnits(Object raw) {
        if (raw == null) {
            return 0;
        }
        try {
            return Math.max(0, plugin.parseAmount(raw.toString()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private long computeInitialCostUnits(long volume) {
        if (volume <= 0) {
            return 0;
        }
        if (initialPricingBrackets == null || initialPricingBrackets.isEmpty()) {
            return volume * (long) initialPricePerBlockUnits;
        }
        long cost = 0L;
        for (PricingBracket bracket : initialPricingBrackets) {
            long start = Math.max(0L, bracket.minInclusive());
            Long maxRaw = bracket.maxExclusive();
            long end = maxRaw == null ? Long.MAX_VALUE : Math.max(start, maxRaw);
            if (volume <= start) {
                continue;
            }
            long spanEnd = Math.min(volume, end);
            long blocks = spanEnd - start;
            if (blocks <= 0) {
                continue;
            }
            cost += blocks * (long) Math.max(0, bracket.ratePerBlockUnits());
        }
        return cost;
    }

    private void chargeUpkeepForOnlineOwners() {
        Map<UUID, Integer> totals = new HashMap<>();
        synchronized (this) {
            for (ProtectionRegion region : protections.values()) {
                long fee = region.getVolume() * (long) upkeepPricePerBlockUnits;
                if (fee <= 0 || fee > Integer.MAX_VALUE) {
                    continue;
                }
                totals.merge(region.getOwner(), (int) fee, Integer::sum);
            }
        }
        for (Map.Entry<UUID, Integer> entry : totals.entrySet()) {
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (owner == null || !owner.isOnline()) {
                continue;
            }
            int fee = entry.getValue();
            chargePlayer(owner, fee, "upkeep", success -> {
                if (success) {
                    owner.sendMessage(ChatColor.YELLOW + "土地保護の継続費用を支払いました: "
                            + formatAmount(fee)
                            + (currency != null && !currency.isBlank() ? " " + currency : "") + ChatColor.RESET);
                }
            });
        }
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
                region.setMode(dataConfig.getString(path + "mode", "middle"));
                String renterRaw = dataConfig.getString(path + "renter");
                String renterName = dataConfig.getString(path + "renter_name", "");
                long rentUntil = dataConfig.getLong(path + "rent_until", 0L);
                int rentPrice = dataConfig.getInt(path + "rent_price", 0);
                if (renterRaw != null && !renterRaw.isBlank()) {
                    try {
                        UUID renterId = UUID.fromString(renterRaw);
                        region.setRental(renterId, renterName, rentUntil, rentPrice);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                protections.put(id, region);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private synchronized void loadLeaseSigns() {
        leaseSigns.clear();
        if (dataConfig == null || !dataConfig.isConfigurationSection("lease_signs")) {
            return;
        }
        for (String key : dataConfig.getConfigurationSection("lease_signs").getKeys(false)) {
            String base = "lease_signs." + key + ".";
            String regionId = dataConfig.getString(base + "region_id", "");
            String period = dataConfig.getString(base + "period", "");
            int price = dataConfig.getInt(base + "price", 0);
            String owner = dataConfig.getString(base + "owner_name", "");
            long created = dataConfig.getLong(base + "created", System.currentTimeMillis());
            if (regionId.isBlank()) {
                continue;
            }
            leaseSigns.put(key, new LeaseSignInfo(regionId, period, price, owner, created));
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
            dataConfig.set(base + "mode", region.getMode());
            if (region.hasActiveRental()) {
                dataConfig.set(base + "renter", region.getRenter().toString());
                dataConfig.set(base + "renter_name", region.getRenterName());
                dataConfig.set(base + "rent_until", region.getRentUntilEpochMillis());
                dataConfig.set(base + "rent_price", region.getRentPriceUnits());
            }
        }
        dataConfig.set("lease_signs", null);
        for (Map.Entry<String, LeaseSignInfo> entry : leaseSigns.entrySet()) {
            String base = "lease_signs." + entry.getKey() + ".";
            LeaseSignInfo info = entry.getValue();
            dataConfig.set(base + "region_id", info.regionId());
            dataConfig.set(base + "period", info.periodRaw());
            dataConfig.set(base + "price", info.priceUnits());
            dataConfig.set(base + "owner_name", info.ownerName());
            dataConfig.set(base + "created", info.createdAt());
        }
        try {
            dataConfig.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save protections.yml: " + ex.getMessage());
        }
        saveProtectionsJson();
    }

    private synchronized void saveProtectionsJson() {
        if (jsonDataFile == null) {
            return;
        }
        try {
            plugin.getDataFolder().mkdirs();
            List<Map<String, Object>> list = new ArrayList<>();
            for (ProtectionRegion region : protections.values()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", region.getId());
                row.put("owner", region.getOwner().toString());
                row.put("world", region.getWorldName());
                row.put("minX", region.getMinX());
                row.put("minY", region.getMinY());
                row.put("minZ", region.getMinZ());
                row.put("maxX", region.getMaxX());
                row.put("maxY", region.getMaxY());
                row.put("maxZ", region.getMaxZ());
                row.put("created", region.getCreatedAt());
                row.put("mode", region.getMode());
                if (region.hasActiveRental()) {
                    row.put("renter", region.getRenter().toString());
                    row.put("renter_name", region.getRenterName());
                    row.put("rent_until", region.getRentUntilEpochMillis());
                    row.put("rent_price", region.getRentPriceUnits());
                }
                list.add(row);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("protections", list);
            root.put("lease_signs", leaseSigns);
            java.nio.file.Files.writeString(jsonDataFile.toPath(), gson.toJson(root), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save protections.json: " + ex.getMessage());
        }
    }

    public synchronized List<String> listIdsForPlayer(UUID uuid) {
        return protections.values().stream()
                .filter(region -> region.getOwner().equals(uuid))
                .map(ProtectionRegion::getId)
                .collect(Collectors.toList());
    }

    public synchronized ProtectionRegion findRegionByLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        for (ProtectionRegion region : protections.values()) {
            if (region.contains(location)) {
                return region;
            }
        }
        return null;
    }

    public boolean canAccess(Player player, Location location) {
        return isActionAllowed(player, location, ProtectAction.INTERACT);
    }

    public boolean isActionAllowed(Player player, Location location, ProtectAction action) {
        if (player == null || location == null) {
            return true;
        }
        ProtectionRegion region;
        synchronized (this) {
            region = findRegionByLocation(location);
        }
        if (region == null) {
            return true;
        }
        if (region.getOwner().equals(player.getUniqueId()) || region.isRenter(player.getUniqueId())
                || player.isOp() || player.hasPermission("lumineeconomy.admin") || plugin.hasBypass(player)) {
            return true;
        }
        String mode = region.getMode();
        return switch (mode) {
            case "never" -> false;
            case "high" -> false;
            case "middle" -> action != ProtectAction.BREAK && action != ProtectAction.PLACE && action != ProtectAction.INTERACT && action != ProtectAction.INVENTORY && action != ProtectAction.DROP;
            case "low" -> action != ProtectAction.BREAK && action != ProtectAction.PLACE;
            case "pvp" -> action != ProtectAction.PVP;
            case "ezreset" -> true;
            default -> action != ProtectAction.BREAK && action != ProtectAction.PLACE && action != ProtectAction.INTERACT;
        };
    }

    public synchronized void startLeasePrompt(Player owner) {
        PendingLease existing = pendingLeases.remove(owner.getUniqueId());
        if (existing != null) {
            existing.timeout().cancel();
        }
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingLease pending = pendingLeases.remove(owner.getUniqueId());
            if (pending != null) {
                owner.sendMessage(ChatColor.RED + "貸出設定がタイムアウトしました。" + ChatColor.RESET);
            }
        }, NAME_TIMEOUT_TICKS);
        pendingLeases.put(owner.getUniqueId(), new PendingLease(LeaseStage.REGION_ID, null, null, 0, timeout));
        owner.sendMessage(ChatColor.GOLD + "貸し出す保護IDをチャットで入力してください。" + ChatColor.RESET);
        owner.sendMessage(ChatColor.AQUA + "Enter region id to lease in chat." + ChatColor.RESET);
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
        session.setGuiFlow(false);
        session.setProtectionMode("middle");
        sessions.put(player.getUniqueId(), session);
        cancelNamePrompt(player.getUniqueId());
        clearApprovalPrompt(player.getUniqueId());
        clearRemovalPrompt(player.getUniqueId());
        giveWand(player);
        player.sendMessage(ChatColor.GREEN + "保護領域の設定を開始しました。Breeze Rodで始点と終点を選択してください。" + ChatColor.RESET);
        player.sendMessage(ChatColor.AQUA + "Protection setup started. Use the Breeze Rod to mark the first and second corners." + ChatColor.RESET);
    }

    public void giveAdminProtectionWand(Player player, String mode) {
        startAdminSelection(player, normalizeMode(mode));
    }

    public synchronized void startAdminSelection(Player player, String mode) {
        ProtectSession session = new ProtectSession(player.getUniqueId(), null);
        session.setGuiFlow(false);
        session.setAdminSelection(true);
        session.setProtectionMode(mode);
        sessions.put(player.getUniqueId(), session);
        cancelNamePrompt(player.getUniqueId());
        clearApprovalPrompt(player.getUniqueId());
        clearRemovalPrompt(player.getUniqueId());
        giveWand(player, mode, true);
        player.sendMessage(ChatColor.GREEN + "管理者保護モード(" + mode + ")の棒を配布しました。" + ChatColor.RESET);
    }

    public synchronized void startSelectionFromGui(Player player) {
        ProtectSession session = new ProtectSession(player.getUniqueId(), null);
        session.setGuiFlow(true);
        session.setProtectionMode("middle");
        sessions.put(player.getUniqueId(), session);
        cancelNamePrompt(player.getUniqueId());
        clearApprovalPrompt(player.getUniqueId());
        clearRemovalPrompt(player.getUniqueId());
        giveWand(player);
        player.sendMessage(ChatColor.GREEN + "保護設定を開始しました。ロッドで2点を選択してください。" + ChatColor.RESET);
        player.sendMessage(ChatColor.YELLOW + "2点選択後に概算費用を表示し、チャットで OK 入力で確定します。" + ChatColor.RESET);
    }

    private void giveWand(Player player) {
        giveWand(player, "middle", false);
    }

    private void giveWand(Player player, String mode, boolean admin) {
        ItemStack wand = createWand(mode, admin);
        player.getInventory().addItem(wand);
    }

    private ItemStack createWand() {
        return createWand("middle", false);
    }

    private ItemStack createWand(String mode, boolean admin) {
        ItemStack stack = new ItemStack(admin ? org.bukkit.Material.STICK : org.bukkit.Material.BREEZE_ROD);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Protection Wand");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "左クリック: 始点 / Left click: first corner");
            lore.add(ChatColor.GRAY + "右クリック: 終点 / Right click: second corner");
            lore.add(ChatColor.DARK_AQUA + "mode: " + mode);
            if (admin) {
                lore.add(ChatColor.GOLD + "admin free selection");
            }
            meta.setLore(lore);
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(wandKey, PersistentDataType.BYTE, (byte) 1);
            container.set(wandModeKey, PersistentDataType.STRING, mode);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public synchronized void startLeaseSignWizard(Player owner) {
        PendingSignWizard existing = pendingSignWizards.remove(owner.getUniqueId());
        if (existing != null) {
            existing.timeout().cancel();
        }
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingSignWizard pending = pendingSignWizards.remove(owner.getUniqueId());
            if (pending != null) {
                owner.sendMessage(ChatColor.RED + "貸出看板作成がタイムアウトしました。" + ChatColor.RESET);
            }
        }, NAME_TIMEOUT_TICKS);
        pendingSignWizards.put(owner.getUniqueId(), new PendingSignWizard(SignWizardStage.REGION_ID, null, null, 0, timeout));
        owner.sendMessage(ChatColor.GOLD + "質問1: 貸し出す保護IDを入力してください。" + ChatColor.RESET);
    }

    public boolean isAwaitingSignWizard(UUID uuid) {
        return pendingSignWizards.containsKey(uuid);
    }

    public void handleSignWizardResponse(Player owner, String message) {
        PendingSignWizard pending = pendingSignWizards.get(owner.getUniqueId());
        if (pending == null) {
            return;
        }
        String input = message == null ? "" : message.trim();
        if (input.isEmpty()) {
            owner.sendMessage(ChatColor.RED + "入力が空です。" + ChatColor.RESET);
            return;
        }
        switch (pending.stage()) {
            case REGION_ID -> {
                ProtectionRegion region = findRegion(input);
                if (region == null || !region.getOwner().equals(owner.getUniqueId())) {
                    owner.sendMessage(ChatColor.RED + "指定IDはあなたの保護地ではありません。" + ChatColor.RESET);
                    return;
                }
                pendingSignWizards.put(owner.getUniqueId(), pending.next(SignWizardStage.PRICE, region.getId(), null, 0));
                owner.sendMessage(ChatColor.GOLD + "質問2: 貸出金額を入力してください。" + ChatColor.RESET);
            }
            case PRICE -> {
                int price;
                try {
                    price = Math.max(0, plugin.parseAmount(input));
                } catch (NumberFormatException ex) {
                    owner.sendMessage(ChatColor.RED + "金額の形式が不正です。" + ChatColor.RESET);
                    return;
                }
                pendingSignWizards.put(owner.getUniqueId(), pending.next(SignWizardStage.PERIOD, pending.regionId(), null, price));
                owner.sendMessage(ChatColor.GOLD + "質問3: 貸出期間を入力してください (例: 4d4h)。" + ChatColor.RESET);
            }
            case PERIOD -> {
                long duration = parseDurationMillis(input);
                if (duration <= 0) {
                    owner.sendMessage(ChatColor.RED + "期間形式が不正です。例: 1d, 6h, 4d4h" + ChatColor.RESET);
                    return;
                }
                PendingSignWizard done = pendingSignWizards.remove(owner.getUniqueId());
                if (done != null) {
                    done.timeout().cancel();
                }
                giveLeaseSign(owner, done.regionId(), input, done.priceUnits());
            }
        }
    }

    private void giveLeaseSign(Player owner, String regionId, String periodRaw, int priceUnits) {
        ItemStack sign = new ItemStack(org.bukkit.Material.OAK_SIGN);
        ItemMeta meta = sign.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.DARK_RED + "[LumineRoom] Lease Sign");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "1行目: 保護ID",
                    ChatColor.GRAY + "2行目: 期間(例 4d4h)",
                    ChatColor.GRAY + "3行目: 金額",
                    ChatColor.GRAY + "4行目: 貸出主"
            ));
            meta.getPersistentDataContainer().set(leaseSignKey, PersistentDataType.STRING, regionId + "|" + periodRaw + "|" + priceUnits);
            sign.setItemMeta(meta);
        }
        owner.getInventory().addItem(sign);
        owner.sendMessage(ChatColor.GREEN + "貸出看板アイテムを配布しました。設置して利用してください。" + ChatColor.RESET);
    }

    public boolean isLeaseSignTemplate(ItemStack stack) {
        if (stack == null || stack.getType() != org.bukkit.Material.OAK_SIGN || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(leaseSignKey, PersistentDataType.STRING);
    }

    public String getLeaseSignTemplateData(ItemStack stack) {
        if (!isLeaseSignTemplate(stack)) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(leaseSignKey, PersistentDataType.STRING);
    }

    public boolean isWand(ItemStack stack) {
        if (stack == null) {
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

    public String getWandMode(ItemStack stack) {
        if (!isWand(stack)) {
            return "middle";
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return "middle";
        }
        String mode = meta.getPersistentDataContainer().get(wandModeKey, PersistentDataType.STRING);
        return normalizeMode(mode);
    }

    public void applyWandMode(Player player, ItemStack stack) {
        ProtectSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.setProtectionMode(getWandMode(stack));
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
        if (session.isGuiFlow()) {
            promptForApproval(player, session);
        } else {
            player.sendMessage(ChatColor.YELLOW + "保護を確定するには /let protect add を実行してください。/ Run /let protect add to finish." + ChatColor.RESET);
        }
    }

    private void promptForApproval(Player player, ProtectSession session) {
        if (!session.hasBoth()) {
            return;
        }
        long volume = computeVolume(session.getFirst(), session.getSecond());
        if (volume <= 0) {
            player.sendMessage(ChatColor.RED + "範囲の計算に失敗しました。" + ChatColor.RESET);
            return;
        }
        long totalCost = computeInitialCostUnits(volume);
        if (totalCost < 0 || totalCost > Integer.MAX_VALUE) {
            player.sendMessage(ChatColor.RED + "保護費用が大きすぎます。範囲を小さくしてください。" + ChatColor.RESET);
            return;
        }
        int estimatedCost = (int) totalCost;
        if (currency != null && !currency.isBlank() && estimatedCost > 0) {
            Map<String, Integer> balances = ScoreboardUtil.readAllSync(player);
            Integer balance = balances.get(currency);
            if (balance != null && balance < estimatedCost) {
                player.sendMessage(ChatColor.RED + "残高不足のため承認できません。必要: "
                        + formatAmount(estimatedCost) + " " + currency
                        + " / 現在: " + formatAmount(balance) + " " + currency + ChatColor.RESET);
                return;
            }
        }
        PendingApproval existing = pendingApprovals.get(player.getUniqueId());
        if (existing != null) {
            existing.timeout().cancel();
        }
        session.setAwaitingApproval(true);
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ProtectSession current = sessions.get(player.getUniqueId());
            if (current != null && current.isAwaitingApproval()) {
                current.setAwaitingApproval(false);
                pendingApprovals.remove(player.getUniqueId());
                player.sendMessage(ChatColor.RED + "承認がタイムアウトしました。保護設定をやり直してください。" + ChatColor.RESET);
            }
        }, APPROVAL_TIMEOUT_TICKS);
        pendingApprovals.put(player.getUniqueId(), new PendingApproval(estimatedCost, timeout));
        String amount = formatAmount(estimatedCost);
        String suffix = (currency != null && !currency.isBlank()) ? (" " + currency) : "";
        player.sendMessage(ChatColor.GOLD + "暫定保護費用: " + amount + suffix + ChatColor.RESET);
        player.sendMessage(ChatColor.AQUA + "チャットで OK と入力すると確定します。/ Type OK in chat to confirm." + ChatColor.RESET);
    }

    private long computeVolume(Location first, Location second) {
        if (first == null || second == null) {
            return -1;
        }
        long dx = Math.abs((long) first.getBlockX() - second.getBlockX()) + 1L;
        long dy = Math.abs((long) first.getBlockY() - second.getBlockY()) + 1L;
        long dz = Math.abs((long) first.getBlockZ() - second.getBlockZ()) + 1L;
        return dx * dy * dz;
    }

    public void handleApprovalResponse(Player player, String message) {
        ProtectSession session = sessions.get(player.getUniqueId());
        PendingApproval pending = pendingApprovals.remove(player.getUniqueId());
        if (session == null || pending == null || !session.isAwaitingApproval()) {
            return;
        }
        pending.timeout().cancel();
        session.setAwaitingApproval(false);
        String normalized = message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
        if (!(normalized.equals("ok") || normalized.equals("はい") || normalized.equals("yes") || normalized.equals("y"))) {
            player.sendMessage(ChatColor.RED + "保護確定をキャンセルしました。終点を選び直すか /let protect add を使用してください。" + ChatColor.RESET);
            return;
        }
        String fallbackId = generateFallbackId(session);
        finalizeProtection(player, fallbackId, true);
    }

    private void clearApprovalPrompt(UUID playerId) {
        PendingApproval pending = pendingApprovals.remove(playerId);
        if (pending != null) {
            pending.timeout().cancel();
        }
        ProtectSession session = sessions.get(playerId);
        if (session != null) {
            session.setAwaitingApproval(false);
        }
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
        baseId = normalizeProtectionId(baseId);
        final String resolvedId = ensureUniqueId(baseId);
        long totalCost = computeInitialCostUnits(volume);
        if (totalCost < 0 || totalCost > Integer.MAX_VALUE) {
            player.sendMessage(ChatColor.RED + "保護費用が大きすぎます。範囲を小さくしてください。/ The protection fee is too large." + ChatColor.RESET);
            return;
        }
        int finalCost = session.isAdminSelection() ? 0 : (int) totalCost;
        session.setFinalizing(true);
        cancelNamePrompt(player.getUniqueId());
        chargePlayer(player, finalCost, resolvedId, success -> {
            if (!success) {
                session.setFinalizing(false);
                return;
            }
            ProtectionRegion region = new ProtectionRegion(resolvedId, player.getUniqueId(), worldName,
                    minX, minY, minZ, maxX, maxY, maxZ, System.currentTimeMillis());
            region.setMode(normalizeMode(session.getProtectionMode()));
            if ("ezreset".equals(region.getMode())) {
                ezResetSnapshots.put(region.getId(), captureSnapshot(region));
            }
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
        if (amountUnits <= 0) {
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


    private String formatAmount(int amount) {
        return plugin.formatAmountPlain(amount);
    }

    private boolean isIdValid(String candidate) {
        if (candidate == null) return false;
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return trimmed.length() <= 8;
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


    private String normalizeProtectionId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            value = "p" + Long.toHexString(System.currentTimeMillis());
        }
        value = value.replaceAll("[^A-Za-z0-9_-]", "");
        if (value.isEmpty()) {
            value = "p" + Long.toHexString(System.currentTimeMillis());
        }
        if (value.length() > 8) {
            value = value.substring(0, 8);
        }
        return value;
    }

    private synchronized String ensureUniqueId(String base) {
        String trimmed = normalizeProtectionId(base);
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

    public boolean isAwaitingLease(UUID uuid) {
        return pendingLeases.containsKey(uuid);
    }

    public void handleLeaseResponse(Player owner, String message) {
        PendingLease pending = pendingLeases.get(owner.getUniqueId());
        if (pending == null) {
            return;
        }
        String input = message == null ? "" : message.trim();
        if (input.isEmpty()) {
            owner.sendMessage(ChatColor.RED + "入力が空です。" + ChatColor.RESET);
            return;
        }
        switch (pending.stage()) {
            case REGION_ID -> {
                ProtectionRegion region = findRegion(input);
                if (region == null || !region.getOwner().equals(owner.getUniqueId())) {
                    owner.sendMessage(ChatColor.RED + "指定IDはあなたの保護ではありません。" + ChatColor.RESET);
                    return;
                }
                pendingLeases.put(owner.getUniqueId(), pending.next(LeaseStage.TARGET_NAME, region.getId(), null, 0));
                owner.sendMessage(ChatColor.GOLD + "貸出相手のユーザー名を入力してください。" + ChatColor.RESET);
            }
            case TARGET_NAME -> {
                pendingLeases.put(owner.getUniqueId(), pending.next(LeaseStage.EXPIRE_MINUTES, pending.regionId(), input, 0));
                owner.sendMessage(ChatColor.GOLD + "貸出期限(分)を入力してください。" + ChatColor.RESET);
            }
            case EXPIRE_MINUTES -> {
                int minutes;
                try {
                    minutes = Integer.parseInt(input);
                } catch (NumberFormatException ex) {
                    owner.sendMessage(ChatColor.RED + "数値(分)を入力してください。" + ChatColor.RESET);
                    return;
                }
                if (minutes <= 0) {
                    owner.sendMessage(ChatColor.RED + "1以上の分を入力してください。" + ChatColor.RESET);
                    return;
                }
                pendingLeases.put(owner.getUniqueId(), pending.next(LeaseStage.PRICE, pending.regionId(), pending.targetName(), minutes));
                owner.sendMessage(ChatColor.GOLD + "貸出金額を入力してください。" + ChatColor.RESET);
            }
            case PRICE -> {
                int price;
                try {
                    price = Math.max(0, plugin.parseAmount(input));
                } catch (NumberFormatException ex) {
                    owner.sendMessage(ChatColor.RED + "金額の形式が不正です。" + ChatColor.RESET);
                    return;
                }
                finalizeLease(owner, pending.regionId(), pending.targetName(), pending.expireMinutes(), price);
            }
        }
    }

    private void finalizeLease(Player owner, String regionId, String targetName, int expireMinutes, int priceUnits) {
        PendingLease pending = pendingLeases.remove(owner.getUniqueId());
        if (pending != null) {
            pending.timeout().cancel();
        }
        ProtectionRegion region = findRegion(regionId);
        if (region == null || !region.getOwner().equals(owner.getUniqueId())) {
            owner.sendMessage(ChatColor.RED + "保護が見つからないか権限がありません。" + ChatColor.RESET);
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            owner.sendMessage(ChatColor.RED + "対象プレイヤーがオンラインではありません。" + ChatColor.RESET);
            return;
        }
        long until = System.currentTimeMillis() + expireMinutes * 60_000L;
        region.setRental(target.getUniqueId(), target.getName(), until, priceUnits);
        saveProtections();
        if (priceUnits > 0) {
            chargePlayer(target, priceUnits, "rent:" + region.getId(), success -> {
                if (!success) {
                    region.clearRental();
                    saveProtections();
                    owner.sendMessage(ChatColor.RED + "貸出料金の決済に失敗しました。" + ChatColor.RESET);
                }
            });
        }
        owner.sendMessage(ChatColor.GREEN + "保護 " + region.getId() + " を " + target.getName() + " に貸し出しました。" + ChatColor.RESET);
        target.sendMessage(ChatColor.AQUA + "保護 " + region.getId() + " を利用可能になりました。期限: " + expireMinutes + "分" + ChatColor.RESET);
    }

    public void registerLeaseSign(org.bukkit.block.Sign sign, Player owner, String regionId, String periodRaw, int priceUnits) {
        String key = signKey(sign.getLocation());
        LeaseSignInfo info = new LeaseSignInfo(regionId, periodRaw, priceUnits, owner.getName(), System.currentTimeMillis());
        leaseSigns.put(key, info);
        sign.setLine(0, regionId);
        sign.setLine(1, periodRaw);
        sign.setLine(2, plugin.formatAmountPlain(priceUnits));
        sign.setLine(3, owner.getName());
        sign.update(true, false);
        saveProtections();
    }

    public boolean hasLeaseSign(org.bukkit.block.Sign sign) {
        return leaseSigns.containsKey(signKey(sign.getLocation()));
    }

    public void removeLeaseSign(org.bukkit.block.Sign sign) {
        leaseSigns.remove(signKey(sign.getLocation()));
        saveProtections();
    }

    public boolean canBreakLeaseSign(Player player, org.bukkit.block.Sign sign) {
        LeaseSignInfo info = leaseSigns.get(signKey(sign.getLocation()));
        if (info == null) {
            return true;
        }
        return player.getName().equalsIgnoreCase(info.ownerName()) || player.isOp() || player.hasPermission("lumineeconomy.admin") || plugin.hasBypass(player);
    }

    public void showLeaseSignInfo(Player player, org.bukkit.block.Sign sign) {
        LeaseSignInfo info = leaseSigns.get(signKey(sign.getLocation()));
        if (info == null) {
            return;
        }
        ProtectionRegion region = findRegion(info.regionId());
        if (region == null) {
            player.sendMessage(ChatColor.RED + "この看板の保護地が見つかりません。" + ChatColor.RESET);
            return;
        }
        player.sendMessage(ChatColor.DARK_RED + "[LumineRoom]" + ChatColor.GOLD + "| " + ChatColor.GRAY + "[貸出ID]:" + ChatColor.YELLOW + info.regionId());
        player.sendMessage(ChatColor.DARK_RED + "[LumineRoom]" + ChatColor.GOLD + "| " + ChatColor.GRAY + "[貸出金額]:" + ChatColor.YELLOW + formatAmount(info.priceUnits()));
        player.sendMessage(ChatColor.DARK_RED + "[LumineRoom]" + ChatColor.GOLD + "| " + ChatColor.GRAY + "[貸出期間]:" + ChatColor.YELLOW + info.periodRaw());
        String coord = region.getMinX()+","+region.getMinY()+","+region.getMinZ()+" から "+region.getMaxX()+","+region.getMaxY()+","+region.getMaxZ();
        player.sendMessage(ChatColor.DARK_RED + "[LumineRoom]" + ChatColor.GOLD + "| " + ChatColor.GRAY + "[貸出座標]:" + ChatColor.YELLOW + coord);
        if (region.hasActiveRental()) {
            long remain = Math.max(0L, region.getRentUntilEpochMillis() - System.currentTimeMillis());
            player.sendMessage(ChatColor.DARK_RED + "[LumineRoom]" + ChatColor.GOLD + "| " + ChatColor.GRAY + "[残り貸し出し時間]:" + ChatColor.YELLOW + formatRemaining(remain));
        } else {
            player.sendMessage(ChatColor.DARK_RED + "[LumineRoom]" + ChatColor.GOLD + "| " + ChatColor.GRAY + "[空室状況]:" + ChatColor.YELLOW + "空きあり");
        }
        player.sendMessage(ChatColor.DARK_RED + "[LumineRoom]" + ChatColor.GOLD + "| " + ChatColor.GRAY + "[貸出主]:" + ChatColor.YELLOW + info.ownerName());
        showRegionParticles(region);
    }

    public void promptLeaseBySign(Player player, org.bukkit.block.Sign sign) {
        LeaseSignInfo info = leaseSigns.get(signKey(sign.getLocation()));
        if (info == null) {
            return;
        }
        ProtectionRegion region = findRegion(info.regionId());
        if (region == null) {
            player.sendMessage(ChatColor.RED + "保護地が見つかりません。" + ChatColor.RESET);
            return;
        }
        if (region.hasActiveRental()) {
            player.sendMessage(ChatColor.RED + "この保護地は現在レンタル中です。" + ChatColor.RESET);
            return;
        }
        PendingLeaseConfirm old = pendingLeaseConfirmations.remove(player.getUniqueId());
        if (old != null) {
            old.timeout().cancel();
        }
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> pendingLeaseConfirmations.remove(player.getUniqueId()), 20L * 30L);
        pendingLeaseConfirmations.put(player.getUniqueId(), new PendingLeaseConfirm(info.regionId(), info.periodRaw(), info.priceUnits(), timeout));
        player.sendMessage(ChatColor.AQUA + "このスペースを " + formatAmount(info.priceUnits()) + " で " + info.periodRaw() + " レンタルしますか？OKと入力してください。" + ChatColor.RESET);
    }

    public boolean isAwaitingLeaseConfirm(UUID uuid) {
        return pendingLeaseConfirmations.containsKey(uuid);
    }

    public void handleLeaseConfirmResponse(Player player, String message) {
        PendingLeaseConfirm pending = pendingLeaseConfirmations.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }
        pending.timeout().cancel();
        String n = message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
        if (!(n.equals("ok") || n.equals("yes") || n.equals("はい") || n.equals("y"))) {
            player.sendMessage(ChatColor.RED + "レンタルをキャンセルしました。" + ChatColor.RESET);
            return;
        }
        ProtectionRegion region = findRegion(pending.regionId());
        if (region == null || region.hasActiveRental()) {
            player.sendMessage(ChatColor.RED + "レンタルできません。" + ChatColor.RESET);
            return;
        }
        long duration = parseDurationMillis(pending.periodRaw());
        if (duration <= 0) {
            player.sendMessage(ChatColor.RED + "看板の期間設定が不正です。" + ChatColor.RESET);
            return;
        }
        chargePlayer(player, pending.priceUnits(), "signrent:" + pending.regionId(), success -> {
            if (!success) {
                return;
            }
            region.setRental(player.getUniqueId(), player.getName(), System.currentTimeMillis() + duration, pending.priceUnits());
            saveProtections();
            player.sendMessage(ChatColor.GREEN + "レンタル開始しました。" + ChatColor.RESET);
        });
    }

    private long parseDurationMillis(String raw) {
        if (raw == null || raw.isBlank()) return -1L;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        long total = 0L;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([dh])").matcher(s);
        int consumed = 0;
        while (m.find()) {
            consumed += m.group(0).length();
            long v = Long.parseLong(m.group(1));
            String unit = m.group(2);
            total += unit.equals("d") ? v * 24L * 60L * 60L * 1000L : v * 60L * 60L * 1000L;
        }
        return consumed == s.length() ? total : -1L;
    }

    private String formatRemaining(long millis) {
        long sec = Math.max(0L, millis / 1000L);
        long d = sec / 86400L;
        long h = (sec % 86400L) / 3600L;
        long m = (sec % 3600L) / 60L;
        return d + "d " + h + "h " + m + "m";
    }

    private void showRegionParticles(ProtectionRegion region) {
        org.bukkit.World world = Bukkit.getWorld(region.getWorldName());
        if (world == null) return;
        int stepX = Math.max(1, (region.getMaxX() - region.getMinX()) / 6);
        int stepZ = Math.max(1, (region.getMaxZ() - region.getMinZ()) / 6);
        for (int t = 0; t <= 20; t++) {
            long delay = t * 20L;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (int x = region.getMinX(); x <= region.getMaxX(); x += stepX) {
                    for (int z = region.getMinZ(); z <= region.getMaxZ(); z += stepZ) {
                        world.spawnParticle(Particle.FLAME, x + 0.5, region.getMinY() + 0.1, z + 0.5, 1, 0, 0, 0, 0);
                        world.spawnParticle(Particle.FLAME, x + 0.5, region.getMaxY() + 0.1, z + 0.5, 1, 0, 0, 0, 0);
                    }
                }
            }, delay);
        }
    }

    public void resetProtection(Player sender, String regionId) {
        ProtectionRegion region = findRegion(regionId);
        if (region == null) {
            sender.sendMessage(ChatColor.RED + "保護が見つかりません。" + ChatColor.RESET);
            return;
        }
        if (!"ezreset".equals(region.getMode())) {
            sender.sendMessage(ChatColor.RED + "この保護はezresetではありません。" + ChatColor.RESET);
            return;
        }
        Map<String, String> snapshot = ezResetSnapshots.get(region.getId());
        if (snapshot == null || snapshot.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "リセット用スナップショットがありません。" + ChatColor.RESET);
            return;
        }
        org.bukkit.World world = Bukkit.getWorld(region.getWorldName());
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "ワールドが見つかりません。" + ChatColor.RESET);
            return;
        }
        snapshot.forEach((k, blockData) -> {
            String[] p = k.split(",", 3);
            int x = Integer.parseInt(p[0]);
            int y = Integer.parseInt(p[1]);
            int z = Integer.parseInt(p[2]);
            try {
                world.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(blockData), false);
            } catch (IllegalArgumentException ex) {
                world.getBlockAt(x, y, z).setType(org.bukkit.Material.AIR, false);
            }
        });
        sender.sendMessage(ChatColor.GREEN + "保護範囲を保存状態へリセットしました: " + region.getId() + ChatColor.RESET);
    }

    private Map<String, String> captureSnapshot(ProtectionRegion region) {
        Map<String, String> snapshot = new HashMap<>();
        org.bukkit.World world = Bukkit.getWorld(region.getWorldName());
        if (world == null) {
            return snapshot;
        }
        for (int x = region.getMinX(); x <= region.getMaxX(); x++) {
            for (int y = region.getMinY(); y <= region.getMaxY(); y++) {
                for (int z = region.getMinZ(); z <= region.getMaxZ(); z++) {
                    snapshot.put(x + "," + y + "," + z, world.getBlockAt(x, y, z).getBlockData().getAsString());
                }
            }
        }
        return snapshot;
    }

    public String normalizeMode(String modeRaw) {
        if (modeRaw == null) {
            return "middle";
        }
        String m = modeRaw.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "never", "high", "middle", "low", "pvp", "ezreset" -> m;
            default -> "middle";
        };
    }

    private String signKey(Location location) {
        return location.getWorld().getName() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private void clearRemovalPrompt(UUID playerId) {
        PendingRemoval pending = pendingRemoval.remove(playerId);
        if (pending != null) {
            pending.timeout.cancel();
        }
    }

    private void clearLeasePrompt(UUID playerId) {
        PendingLease pending = pendingLeases.remove(playerId);
        if (pending != null) {
            pending.timeout().cancel();
        }
    }

    private void clearSignWizardPrompt(UUID playerId) {
        PendingSignWizard pending = pendingSignWizards.remove(playerId);
        if (pending != null) {
            pending.timeout().cancel();
        }
    }

    private void clearLeaseConfirmPrompt(UUID playerId) {
        PendingLeaseConfirm pending = pendingLeaseConfirmations.remove(playerId);
        if (pending != null) {
            pending.timeout().cancel();
        }
    }

    public void cancelAll(Player player) {
        cancelNamePrompt(player.getUniqueId());
        clearApprovalPrompt(player.getUniqueId());
        clearRemovalPrompt(player.getUniqueId());
        clearLeasePrompt(player.getUniqueId());
        clearSignWizardPrompt(player.getUniqueId());
        clearLeaseConfirmPrompt(player.getUniqueId());
        sessions.remove(player.getUniqueId());
    }

    public boolean isAwaitingName(UUID uuid) {
        ProtectSession session = sessions.get(uuid);
        return session != null && session.isAwaitingName();
    }

    public boolean isAwaitingApproval(UUID uuid) {
        ProtectSession session = sessions.get(uuid);
        return session != null && session.isAwaitingApproval();
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

    public enum ProtectAction { BREAK, PLACE, INTERACT, INVENTORY, DROP, PVP, ENTER }

    private record PricingBracket(long minInclusive, Long maxExclusive, int ratePerBlockUnits) {}

    private record PendingName(BukkitTask timeout) {}

    private record PendingApproval(int estimatedCost, BukkitTask timeout) {}

    private record LeaseSignInfo(String regionId, String periodRaw, int priceUnits, String ownerName, long createdAt) {}

    private enum SignWizardStage {
        REGION_ID, PRICE, PERIOD
    }

    private record PendingSignWizard(SignWizardStage stage, String regionId, String periodRaw, int priceUnits, BukkitTask timeout) {
        PendingSignWizard next(SignWizardStage nextStage, String nextRegionId, String nextPeriodRaw, int nextPrice) {
            return new PendingSignWizard(nextStage, nextRegionId, nextPeriodRaw, nextPrice, timeout);
        }
    }

    private record PendingLeaseConfirm(String regionId, String periodRaw, int priceUnits, BukkitTask timeout) {}

    private enum LeaseStage {
        REGION_ID, TARGET_NAME, EXPIRE_MINUTES, PRICE
    }

    private record PendingLease(LeaseStage stage, String regionId, String targetName, int expireMinutes, BukkitTask timeout) {
        PendingLease next(LeaseStage nextStage, String nextRegionId, String nextTargetName, int nextExpireMinutes) {
            return new PendingLease(nextStage, nextRegionId, nextTargetName, nextExpireMinutes, timeout);
        }
    }

    private record PendingRemoval(ProtectionRegion region, BukkitTask timeout) {}
}
