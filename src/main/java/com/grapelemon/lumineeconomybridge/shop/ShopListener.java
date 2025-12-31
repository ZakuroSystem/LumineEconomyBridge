package com.grapelemon.lumineeconomybridge.shop;

import com.grapelemon.lumineeconomybridge.Lang;
import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import com.grapelemon.lumineeconomybridge.sync.ScoreboardUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.ChatColor;
import org.bukkit.scoreboard.Scoreboard;
import java.time.Instant;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;

public class ShopListener implements Listener {
    private final LumineEconomyBridge plugin;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final NamespacedKey keyShop;
    private final NamespacedKey keyId;
    private final NamespacedKey keyOwner;
    private final NamespacedKey keyHopper;
    private final NamespacedKey keyHopperSlot;
    private final NamespacedKey keyHopperItem;
    private final NamespacedKey keyHopperShopOwner;
    private final NamespacedKey keyHopperItemTag;
    private static final long CACHE_MS = 3000;
    private final Map<String, CacheEntry> itemCache = new ConcurrentHashMap<>();
    private final Map<UUID, PendingSale> pendingSales = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> hopperCooldowns = new ConcurrentHashMap<>();
    private static final long HOPPER_MIN_INTERVAL_MS = 600L;
    private static final long HOPPER_EMPTY_INTERVAL_MS = 3000L;

    private static class PendingSale {
        final String shopId;
        final String blob;
        final ItemStack item;
        final int qty;
        final long deadline;
        final ShopMenuHolder holder;
        PendingSale(String shopId, String blob, ItemStack item, int qty, long deadline, ShopMenuHolder holder) {
            this.shopId = shopId;
            this.blob = blob;
            this.item = item;
            this.qty = qty;
            this.deadline = deadline;
            this.holder = holder;
        }
    }

    private static class HopperBinding {
        final int slot;
        final String itemKey;

        HopperBinding(int slot, String itemKey) {
            this.slot = slot;
            this.itemKey = itemKey;
        }
    }

    private static class HopperData {
        final String shopId;
        final String ownerUuid;
        final String shopOwnerUuid;
        final List<HopperBinding> bindings;
        final Location location;

        HopperData(String shopId, String ownerUuid, String shopOwnerUuid, List<HopperBinding> bindings, Location location) {
            this.shopId = shopId;
            this.ownerUuid = ownerUuid;
            this.shopOwnerUuid = shopOwnerUuid;
            this.bindings = bindings;
            this.location = location;
        }

        List<String> itemKeys() {
            List<String> keys = new ArrayList<>();
            for (HopperBinding binding : bindings) {
                keys.add(binding.itemKey);
            }
            return keys;
        }
    }

    public ShopListener(LumineEconomyBridge plugin) {
        this.plugin = plugin;
        this.keyShop = new NamespacedKey(plugin, "le_shop");
        this.keyId = new NamespacedKey(plugin, "shop_id");
        this.keyOwner = new NamespacedKey(plugin, "owner_uuid");
        this.keyHopper = new NamespacedKey(plugin, "le_shop_hopper");
        this.keyHopperSlot = new NamespacedKey(plugin, "le_shop_hopper_slot");
        this.keyHopperItem = new NamespacedKey(plugin, "le_shop_hopper_item");
        this.keyHopperShopOwner = new NamespacedKey(plugin, "shop_owner_uuid");
        this.keyHopperItemTag = new NamespacedKey(plugin, "le_shop_item_keys");
    }

    private static class CacheEntry {
        final JsonObject data;
        final long timestamp;
        CacheEntry(JsonObject data, long timestamp) { this.data = data; this.timestamp = timestamp; }
    }

    private ItemStack itemFromBase64(String data) {
        byte[] bytes = Base64.getDecoder().decode(data);
        return ItemStack.deserializeBytes(bytes);
    }

    private ItemStack prepareForSerialization(ItemStack item) {
        ItemStack clone = item.clone();
        clone.setAmount(1);
        try {
            ItemMeta meta = clone.getItemMeta();
            if (meta != null) {
                PersistentDataContainer container = meta.getPersistentDataContainer();
                container.remove(keyHopperItemTag);
                container.remove(keyShop);
                container.remove(keyId);
                container.remove(keyOwner);
                container.remove(keyHopper);
                container.remove(keyHopperSlot);
                container.remove(keyHopperItem);
                container.remove(keyHopperShopOwner);
                clone.setItemMeta(meta);
            }
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().fine("Skipping meta cleanup for item due to invalid attribute data: " + ex.getMessage());
        }
        return clone;
    }

    private String itemToBase64(ItemStack item) {
        ItemStack clone = prepareForSerialization(item);
        return Base64.getEncoder().encodeToString(clone.serializeAsBytes());
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        ItemMeta meta = e.getItemInHand().getItemMeta();
        if (meta == null) return;
        PersistentDataContainer c = meta.getPersistentDataContainer();
        if (!c.has(keyShop, PersistentDataType.BYTE) && !c.has(keyHopper, PersistentDataType.BYTE)) return;
        Player player = e.getPlayer();
        String expected = c.get(keyOwner, PersistentDataType.STRING);
        String shopOwnerBound = c.get(keyHopperShopOwner, PersistentDataType.STRING);
        String placer = player.getUniqueId().toString();
        boolean hasOverride = plugin.hasBypass(player) || player.isOp() || player.hasPermission("lumineeconomy.admin");
        boolean matchesIssuer = expected != null && expected.equals(placer);
        boolean matchesShopOwner = shopOwnerBound != null && shopOwnerBound.equals(placer);
        if (!hasOverride && !matchesIssuer && !matchesShopOwner) {
            e.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You are not the owner / あなたはオーナーではありません" + ChatColor.RESET);
            return;
        }
        String shopId = c.get(keyId, PersistentDataType.STRING);
        BlockState state = e.getBlockPlaced().getState();
        if (state instanceof TileState tile) {
            PersistentDataContainer tc = tile.getPersistentDataContainer();
            if (c.has(keyShop, PersistentDataType.BYTE)) {
                tc.set(keyShop, PersistentDataType.BYTE, (byte)1);
            }
            if (c.has(keyHopper, PersistentDataType.BYTE)) {
                tc.set(keyHopper, PersistentDataType.BYTE, (byte)1);
                String slotList = c.get(keyHopperSlot, PersistentDataType.STRING);
                if (slotList != null && !slotList.isEmpty()) {
                    tc.set(keyHopperSlot, PersistentDataType.STRING, slotList);
                } else {
                    Integer slot = c.get(keyHopperSlot, PersistentDataType.INTEGER);
                    if (slot != null) tc.set(keyHopperSlot, PersistentDataType.INTEGER, slot);
                }
                String itemKeys = c.get(keyHopperItem, PersistentDataType.STRING);
                if (itemKeys != null) {
                    tc.set(keyHopperItem, PersistentDataType.STRING, itemKeys);
                }
                if (shopOwnerBound != null && !shopOwnerBound.isEmpty()) {
                    tc.set(keyHopperShopOwner, PersistentDataType.STRING, shopOwnerBound);
                }
            }
            if (shopId != null) tc.set(keyId, PersistentDataType.STRING, shopId);
            tc.set(keyOwner, PersistentDataType.STRING, expected);
            tile.update(true);
        }
        if (!c.has(keyShop, PersistentDataType.BYTE)) {
            return;
        }
        OkHttpClient http = plugin.getHttpClient();
        if (http == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("shop_id", shopId);
        payload.put("owner_uuid", expected);
        payload.put("placer_uuid", placer);
        Location loc = e.getBlockPlaced().getLocation();
        payload.put("world", loc.getWorld().getName());
        payload.put("x", loc.getBlockX());
        payload.put("y", loc.getBlockY());
        payload.put("z", loc.getBlockZ());
        payload.put("timestamp", System.currentTimeMillis() / 1000);
        Request req = new Request.Builder()
                .url(plugin.getBaseUrl() + "/api/shop/place")
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException ex) {
                plugin.getLogger().warning("Shop place failed: " + ex.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                response.close();
            }
        });
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        if (b.getType() == Material.HOPPER) {
            BlockState state = b.getState();
            if (state instanceof TileState tile) {
                PersistentDataContainer c = tile.getPersistentDataContainer();
                if (c.has(keyHopper, PersistentDataType.BYTE) && !hasHopperAccess(e.getPlayer(), c)) {
                    e.setCancelled(true);
                    e.getPlayer().sendMessage(ChatColor.RED + "You are not the owner / あなたはオーナーではありません" + ChatColor.RESET);
                }
            }
            return;
        }
        if (b.getType() != Material.BARREL) return;
        BlockState state = b.getState();
        if (!(state instanceof TileState tile)) return;
        PersistentDataContainer c = tile.getPersistentDataContainer();
        if (!c.has(keyShop, PersistentDataType.BYTE)) return;
        e.setCancelled(true);
        String shopId = c.get(keyId, PersistentDataType.STRING);
        if (shopId == null) return;
        openShop(e.getPlayer(), shopId);
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        InventoryHolder holder = e.getInventory().getHolder();
        if (!(holder instanceof Hopper hopper)) return;
        PersistentDataContainer c = hopper.getPersistentDataContainer();
        if (!c.has(keyHopper, PersistentDataType.BYTE)) return;
        if (hasHopperAccess(p, c)) return;
        e.setCancelled(true);
        p.sendMessage(ChatColor.RED + "You are not the owner / あなたはオーナーではありません" + ChatColor.RESET);
    }

    public void openShop(Player p, String shopId) {
        openShop(p, shopId, false);
    }

    public void openShop(Player p, String shopId, boolean ownerAsCustomer) {
        if (!plugin.isActive()) {
            p.sendMessage(Lang.get("error-unavailable"));
            return;
        }
        CacheEntry ce = itemCache.get(shopId);
        long now = System.currentTimeMillis();
        if (ce != null && now - ce.timestamp < CACHE_MS) {
            buildInventory(p, shopId, ce.data, ownerAsCustomer);
            sendPing(shopId);
            return;
        }
        OkHttpClient http = plugin.getHttpClient();
        sendPing(shopId);
        HttpUrl url = HttpUrl.parse(plugin.getBaseUrl() + "/api/shop/items").newBuilder()
                .addQueryParameter("shop_id", shopId)
                .build();
        Request req = new Request.Builder().url(url).build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                plugin.getLogger().warning("Fetch items failed: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                    if (!obj.has("status") || !"active".equals(obj.get("status").getAsString())) {
                        long last = obj.has("last_activity_at") ? obj.get("last_activity_at").getAsLong() : 0L;
                        String date = Instant.ofEpochSecond(last).toString();
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Inventory inv = Bukkit.createInventory(new ClosedMenuHolder(), 9, "Shop " + shopId);
                            ItemStack barrier = new ItemStack(Material.BARRIER);
                            ItemMeta bm = barrier.getItemMeta();
                            bm.setDisplayName("Closed");
                            bm.setLore(Collections.singletonList("Last active: " + date));
                            barrier.setItemMeta(bm);
                            inv.setItem(4, barrier);
                            p.openInventory(inv);
                        });
                        return;
                    }
                    itemCache.put(shopId, new CacheEntry(obj, System.currentTimeMillis()));
                    buildInventory(p, shopId, obj, ownerAsCustomer);
                    sendPing(shopId);
                    sendVisit(p, shopId);
                }
            }
        });
    }

    private void sendPing(String shopId) {
        OkHttpClient http = plugin.getHttpClient();
        Map<String, Object> ping = new HashMap<>();
        ping.put("shop_id", shopId);
        ping.put("timestamp", System.currentTimeMillis() / 1000);
        Request pingReq = new Request.Builder()
                .url(plugin.getBaseUrl() + "/api/shop/ping")
                .post(RequestBody.create(gson.toJson(ping), JSON))
                .build();
        http.newCall(pingReq).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { plugin.getLogger().warning("Ping failed: " + e.getMessage()); }
            @Override public void onResponse(Call call, Response res) throws IOException { res.close(); }
        });
    }

    private void sendVisit(Player p, String shopId) {
        OkHttpClient http = plugin.getHttpClient();
        Map<String, Object> payload = new HashMap<>();
        payload.put("player_uuid", p.getUniqueId().toString());
        payload.put("shop_id", shopId);
        payload.put("timestamp", System.currentTimeMillis() / 1000);
        Request req = new Request.Builder()
                .url(plugin.getBaseUrl() + "/api/shop/visit")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { }
            @Override public void onResponse(Call call, Response res) throws IOException { res.close(); }
        });
    }

    private void buildInventory(Player p, String shopId, JsonObject dataObj, boolean ownerAsCustomer) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            var arr = dataObj.getAsJsonArray("items");
            int size = ((arr.size() + 8) / 9) * 9;
            if (size < 9) size = 9;
            ShopMenuHolder holder = new ShopMenuHolder(shopId);
            holder.setOwnerAsCustomer(ownerAsCustomer);
            if (dataObj.has("trade_mode") && !dataObj.get("trade_mode").isJsonNull()) {
                holder.setTradeMode(dataObj.get("trade_mode").getAsString());
            }
            if (dataObj.has("owners")) {
                for (var o : dataObj.getAsJsonArray("owners")) {
                    holder.addOwnerUuid(o.getAsString());
                }
            }
            Inventory inv = Bukkit.createInventory(holder, size, "Shop " + shopId);
            holder.setInventory(inv);
            int idx = 0;
            for (var el : arr) {
                if (idx >= size - 1) break;
                JsonObject it = el.getAsJsonObject();
                String key = it.get("item_key").getAsString();
                String saleName = it.has("sale_name") ? it.get("sale_name").getAsString() : "";
                String blob = it.get("nbt_blob").getAsString();
                ItemStack item = itemFromBase64(blob);
                ItemStack raw = item.clone();
                ItemMeta meta = item.getItemMeta();
                int stock = it.get("stock").getAsInt();
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GREEN + "Name: " + ChatColor.YELLOW + saleName);
                lore.add(ChatColor.GREEN + "Stock: " + ChatColor.YELLOW + stock);
                JsonObject prices = it.getAsJsonObject("prices");
                Map<String, ShopItem.ShopPrice> priceMap = new HashMap<>();
                for (var en : prices.entrySet()) {
                    String currency = en.getKey();
                    JsonObject priceObj = en.getValue().getAsJsonObject();
                    Integer sellPrice = priceObj.has("sell") && !priceObj.get("sell").isJsonNull()
                            ? priceObj.get("sell").getAsInt() : null;
                    Integer buyPrice = priceObj.has("buy") && !priceObj.get("buy").isJsonNull()
                            ? priceObj.get("buy").getAsInt() : null;
                    if (sellPrice != null) {
                        lore.add(ChatColor.GREEN + currency + ChatColor.WHITE + " Sell: " + ChatColor.YELLOW + formatAmount(sellPrice));
                    }
                    if (buyPrice != null) {
                        lore.add(ChatColor.AQUA + currency + ChatColor.WHITE + " Buy: " + ChatColor.YELLOW + formatAmount(buyPrice));
                    }
                    priceMap.put(currency, new ShopItem.ShopPrice(sellPrice, buyPrice));
                }
                meta.setLore(lore);
                item.setItemMeta(meta);
                inv.setItem(idx, item);
                holder.getItems().put(idx, new ShopItem(key, saleName, item, raw, stock, priceMap));
                idx++;
            }
            p.openInventory(inv);
        });
    }

    private void refreshDisplay(ShopMenuHolder holder, int slot, ShopItem si) {
        ItemStack stack = holder.getInventory().getItem(slot);
        if (stack == null) return;
        ItemMeta meta = stack.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GREEN + "Name: " + ChatColor.YELLOW + si.getSaleName());
        lore.add(ChatColor.GREEN + "Stock: " + ChatColor.YELLOW + si.getStock());
        for (var en : si.getPrices().entrySet()) {
            ShopItem.ShopPrice price = en.getValue();
            if (price == null) continue;
            if (price.getSellPrice() != null) {
                lore.add(ChatColor.GREEN + en.getKey() + ChatColor.WHITE + " Sell: " + ChatColor.YELLOW + formatAmount(price.getSellPrice()));
            }
            if (price.getBuyPrice() != null) {
                lore.add(ChatColor.AQUA + en.getKey() + ChatColor.WHITE + " Buy: " + ChatColor.YELLOW + formatAmount(price.getBuyPrice()));
            }
        }
        meta.setLore(lore);
        stack.setItemMeta(meta);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        var holderObj = e.getInventory().getHolder();
        if (holderObj instanceof ConfirmMenuHolder ch) {
            e.setCancelled(true);
            Player p = (Player) e.getWhoClicked();
            ConfirmMenuHolder.ConfirmAction action = ch.getAction(e.getSlot());
            if (action != null) {
                if (action.getType() == ConfirmMenuHolder.ActionType.BUY) {
                    handlePurchase(p, ch, action.getQuantity());
                } else {
                    handleSell(p, ch, action.getQuantity());
                }
                return;
            }
            if (ch.isBackSlot(e.getSlot())) {
                p.openInventory(ch.getOrigin().getInventory());
            }
            return;
        }
        if (holderObj instanceof ClosedMenuHolder) {
            e.setCancelled(true);
            return;
        }
        if (!(holderObj instanceof ShopMenuHolder holder)) return;
        Player p = (Player) e.getWhoClicked();
        boolean isOwner = holder.isOwner(p.getUniqueId().toString());
        if (holder.isOwnerActingAsCustomer()) {
            isOwner = false;
        }
        if (e.getClickedInventory() == p.getInventory() && e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            ItemStack stack = e.getCurrentItem();
            if (stack == null) return;
            e.setCancelled(true);
            if (isOwner) {
                handleOwnerDeposit(p, holder, stack);
            } else {
                if (!holder.canPlayerSell()) {
                    p.sendMessage(ChatColor.RED + "This shop only sells items / このショップは販売専用です" + ChatColor.RESET);
                    return;
                }
                handlePlayerSell(p, holder, stack);
            }
            return;
        }
        if (e.getClickedInventory() == e.getView().getTopInventory() && holder.getItems().containsKey(e.getSlot())) {
            e.setCancelled(true);
            ShopItem si = holder.getItems().get(e.getSlot());
            if (isOwner) {
                handleOwnerWithdraw(p, holder, si, e.getSlot());
            } else {
                if (!holder.canPlayerPurchase()) {
                    p.sendMessage(ChatColor.RED + "This shop only buys items / このショップは買取専用です" + ChatColor.RESET);
                    return;
                }
                openTradeMenu(p, holder, e.getSlot(), si);
            }
            return;
        }
        e.setCancelled(true);
    }

    private void handlePurchase(Player p, ConfirmMenuHolder ch, int qty) {
        if (!ch.getOrigin().canPlayerPurchase()) {
            p.sendMessage(ChatColor.RED + "This shop only buys items / このショップは買取専用です" + ChatColor.RESET);
            return;
        }
        ShopItem si = ch.getItem();
        if (si.getStock() < qty) {
            p.sendMessage(ChatColor.RED + "Not enough stock / 在庫が不足しています" + ChatColor.RESET);
            return;
        }
        String currency = si.firstSellCurrency();
        if (currency == null) {
            p.sendMessage(ChatColor.RED + "No sale price available / 販売価格が設定されていません" + ChatColor.RESET);
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("player_uuid", p.getUniqueId().toString());
        payload.put("shop_id", ch.getShopId());
        payload.put("item_key", si.getItemKey());
        payload.put("qty", qty);
        payload.put("currency", currency);
        payload.put("timestamp", System.currentTimeMillis() / 1000);
        payload.put("client_tx_id", UUID.randomUUID().toString());
        Request req = new Request.Builder()
                .url(plugin.getBaseUrl() + "/api/shop/buy")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        plugin.getHttpClient().newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException ex) {
                plugin.getLogger().warning("Buy failed: " + ex.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (res.has("messages")) {
                            res.getAsJsonArray("messages").forEach(m -> {
                                JsonObject msg = m.getAsJsonObject();
                                String text = msg.has("text") ? ChatColor.translateAlternateColorCodes('&', msg.get("text").getAsString()) : "";
                                Player recv = p;
                                if (msg.has("player")) {
                                    try {
                                        UUID id = UUID.fromString(msg.get("player").getAsString());
                                        Player other = Bukkit.getPlayer(id);
                                        if (other != null) recv = other; else return;
                                    } catch (IllegalArgumentException ignored) { return; }
                                }
                                recv.sendMessage(text);
                            });
                        }
                        if (res.has("status") && "success".equals(res.get("status").getAsString())) {
                            if (res.has("grant")) {
                                res.getAsJsonArray("grant").forEach(g -> {
                                    JsonObject gg = g.getAsJsonObject();
                                    String token = gg.get("grant_token").getAsString();
                                    if (plugin.consumeGrantToken(token)) {
                                        String ik = gg.get("item_key").getAsString();
                                        int qty2 = gg.get("qty").getAsInt();
                                        if (si.getItemKey().equals(ik)) {
                                            giveStackedItems(p, si.getRawItem(), qty2);
                                        }
                                    }
                                });
                            }
                            if (res.has("scoreboards")) {
                                ScoreboardSyncService sync = plugin.getSyncService();
                                if (sync != null) {
                                    res.getAsJsonObject("scoreboards").entrySet().forEach(en -> {
                                        try {
                                            UUID pid = UUID.fromString(en.getKey());
                                            Player target = Bukkit.getPlayer(pid);
                                            if (target != null) {
                                                Map<String, Integer> updates = new HashMap<>();
                                                en.getValue().getAsJsonObject().entrySet().forEach(e1 -> updates.put(e1.getKey(), e1.getValue().getAsInt()));
                                                sync.applyFromPython(target, updates);
                                            }
                                        } catch (IllegalArgumentException ignored) {
                                        }
                                    });
                                }
                            }
                            si.setStock(Math.max(0, si.getStock() - qty));
                            refreshDisplay(ch.getOrigin(), ch.getSlot(), si);
                            p.openInventory(ch.getOrigin().getInventory());
                        }
                    });
                }
            }
        });
    }

    private void openTradeMenu(Player p, ShopMenuHolder holder, int slot, ShopItem si) {
        ConfirmMenuHolder menu = new ConfirmMenuHolder(holder.getShopId(), holder, slot, si);
        Inventory inv = Bukkit.createInventory(menu, 27, "Confirm");
        ItemStack preview = si.getRawItem().clone();
        inv.setItem(13, preview);

        int[] buyAmounts = {1, 4, 16, 64, 256};
        int[] buySlots = {0, 1, 2, 3, 4};
        for (int i = 0; i < buyAmounts.length; i++) {
            addBuyButton(inv, menu, holder, si, p, buySlots[i], buyAmounts[i], false);
        }
        int maxBuySlot = 5;
        int maxBuyQty = computeMaxBuyQuantity(p, si);
        addBuyButton(inv, menu, holder, si, p, maxBuySlot, maxBuyQty, true);

        int[] sellAmounts = {1, 4, 16, 64, 256};
        int[] sellSlots = {18, 19, 20, 21, 22};
        for (int i = 0; i < sellAmounts.length; i++) {
            addSellButton(inv, menu, holder, si, sellSlots[i], sellAmounts[i], p, false);
        }
        int sellAllSlot = 23;
        addSellButton(inv, menu, holder, si, sellAllSlot, countMatchingItems(p, si), p, true);

        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bm = back.getItemMeta();
        bm.setDisplayName(ChatColor.RED + "Back / 戻る");
        bm.setLore(Collections.singletonList(ChatColor.GRAY + "Return to listings / 一覧に戻ります"));
        back.setItemMeta(bm);
        int backSlot = 26;
        inv.setItem(backSlot, back);
        menu.setBackSlot(backSlot);

        p.openInventory(inv);
    }

    private void addBuyButton(Inventory inv, ConfirmMenuHolder menu, ShopMenuHolder holder, ShopItem si, Player p, int slot, int qty, boolean isMax) {
        String currency = si.firstSellCurrency();
        ShopItem.ShopPrice price = currency != null ? si.getPrices().get(currency) : null;
        Integer sellPrice = price != null ? price.getSellPrice() : null;
        List<String> errors = new ArrayList<>();
        if (!holder.canPlayerPurchase()) {
            errors.add("This shop only buys items / このショップは買取専用です");
        }
        if (currency == null || sellPrice == null || sellPrice <= 0) {
            errors.add("No sell price available / 販売価格が設定されていません");
        }
        int availableQty = qty;
        if (isMax) {
            availableQty = qty;
            if (availableQty <= 0) {
                errors.add("Nothing to buy / 購入できる商品がありません");
            }
        }
        int carryCapacity = computeMaxCarry(p, si);
        if (!isMax && availableQty > carryCapacity) {
            errors.add("Not enough inventory space / インベントリの空きが不足しています");
        }
        if (si.getStock() < availableQty) {
            errors.add("Not enough stock / 在庫が不足しています");
        }

        if (currency != null && sellPrice != null && sellPrice > 0) {
            int balance = getPlayerBalance(p, currency);
            if (!isMax && multiplyPrice(sellPrice, availableQty) > balance) {
                errors.add("Insufficient funds / 所持金が不足しています");
            }
        }

        boolean enabled = errors.isEmpty();
        ItemStack button = new ItemStack(enabled ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = button.getItemMeta();
        String qtyLabel = isMax ? "Max" : String.valueOf(qty);
        meta.setDisplayName(ChatColor.GREEN + "Buy ×" + (isMax ? availableQty : qtyLabel));
        List<String> lore = new ArrayList<>();
        if (enabled) {
            int total = multiplyPrice(sellPrice, availableQty);
            String currencyLabel = currency != null && !currency.isEmpty() ? " " + currency : "";
            lore.add(ChatColor.GREEN + "Cost: " + ChatColor.YELLOW + formatAmount(total) + currencyLabel);
            lore.add(ChatColor.DARK_GRAY + "Stock: " + ChatColor.GRAY + si.getStock());
            lore.add(ChatColor.DARK_GRAY + "Space: " + ChatColor.GRAY + carryCapacity);
            menu.registerAction(slot, ConfirmMenuHolder.ActionType.BUY, availableQty);
        } else {
            for (String err : errors) {
                lore.add(ChatColor.RED + err);
            }
        }
        meta.setLore(lore);
        button.setItemMeta(meta);
        inv.setItem(slot, button);
    }

    private void addSellButton(Inventory inv, ConfirmMenuHolder menu, ShopMenuHolder holder, ShopItem si, int slot, int qty, Player p, boolean sellAll) {
        String currency = si.firstBuyCurrency();
        ShopItem.ShopPrice price = currency != null ? si.getPrices().get(currency) : null;
        Integer buyPrice = price != null ? price.getBuyPrice() : null;
        List<String> errors = new ArrayList<>();
        if (!holder.canPlayerSell()) {
            errors.add("This shop only sells items / このショップは販売専用です");
        }
        if (currency == null || buyPrice == null || buyPrice <= 0) {
            errors.add("No buy price available / 買取価格が設定されていません");
        }
        int available = countMatchingItems(p, si);
        int actualQty = sellAll ? available : qty;
        if (actualQty <= 0) {
            errors.add("Not enough matching items / 手持ちの対象アイテムが不足しています");
        } else if (!sellAll && available < qty) {
            errors.add("Not enough matching items / 手持ちの対象アイテムが不足しています");
        }

        boolean enabled = errors.isEmpty();
        ItemStack button = new ItemStack(enabled ? Material.LIGHT_BLUE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = button.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Sell ×" + (sellAll ? actualQty : qty));
        List<String> lore = new ArrayList<>();
        if (enabled) {
            int total = multiplyPrice(buyPrice, actualQty);
            String currencyLabel = currency != null && !currency.isEmpty() ? " " + currency : "";
            lore.add(ChatColor.AQUA + "Payout: " + ChatColor.YELLOW + formatAmount(total) + currencyLabel);
            lore.add(ChatColor.DARK_GRAY + "You have: " + ChatColor.GRAY + available);
            menu.registerAction(slot, ConfirmMenuHolder.ActionType.SELL, actualQty);
        } else {
            for (String err : errors) {
                lore.add(ChatColor.RED + err);
            }
            lore.add(ChatColor.DARK_GRAY + "You have: " + ChatColor.GRAY + available);
        }
        meta.setLore(lore);
        button.setItemMeta(meta);
        inv.setItem(slot, button);
    }

    private void handlePlayerSell(Player p, ShopMenuHolder holder, ItemStack stack) {
        if (!holder.canPlayerSell()) {
            p.sendMessage(ChatColor.RED + "This shop only sells items / このショップは販売専用です" + ChatColor.RESET);
            return;
        }
        String blob = itemToBase64(stack);
        String key = sha256(Base64.getDecoder().decode(blob));
        for (var en : holder.getItems().entrySet()) {
            if (en.getValue().getItemKey().equals(key)) {
                openTradeMenu(p, holder, en.getKey(), en.getValue());
                return;
            }
        }
        p.sendMessage(ChatColor.RED + "This item cannot be sold here / このアイテムはここでは売れません" + ChatColor.RESET);
    }

    private void handleSell(Player p, ConfirmMenuHolder ch, int qty) {
        if (!ch.getOrigin().canPlayerSell()) {
            p.sendMessage(ChatColor.RED + "This shop only sells items / このショップは販売専用です" + ChatColor.RESET);
            return;
        }
        ShopItem si = ch.getItem();
        int available = countMatchingItems(p, si);
        if (available < qty) {
            p.sendMessage(ChatColor.RED + "Not enough matching items / 手持ちの対象アイテムが不足しています" + ChatColor.RESET);
            return;
        }
        String currency = si.firstBuyCurrency();
        if (currency == null) {
            p.sendMessage(ChatColor.RED + "No buy price available / 買取価格が設定されていません" + ChatColor.RESET);
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("player_uuid", p.getUniqueId().toString());
        payload.put("shop_id", ch.getShopId());
        payload.put("item_key", si.getItemKey());
        payload.put("qty", qty);
        payload.put("currency", currency);
        payload.put("timestamp", System.currentTimeMillis() / 1000);
        payload.put("client_tx_id", UUID.randomUUID().toString());
        Request req = new Request.Builder()
                .url(plugin.getBaseUrl() + "/api/shop/sell")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        plugin.getHttpClient().newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException ex) {
                plugin.getLogger().warning("Sell failed: " + ex.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (res.has("messages")) {
                            res.getAsJsonArray("messages").forEach(m -> {
                                JsonObject msg = m.getAsJsonObject();
                                String text = msg.has("text") ? ChatColor.translateAlternateColorCodes('&', msg.get("text").getAsString()) : "";
                                Player recv = p;
                                if (msg.has("player")) {
                                    try {
                                        UUID id = UUID.fromString(msg.get("player").getAsString());
                                        Player other = Bukkit.getPlayer(id);
                                        if (other != null) recv = other; else return;
                                    } catch (IllegalArgumentException ignored) { return; }
                                }
                                recv.sendMessage(text);
                            });
                        }
                        if (res.has("status") && "success".equals(res.get("status").getAsString())) {
                            removeMatchingItems(p, si, qty);
                            si.setStock(si.getStock() + qty);
                            refreshDisplay(ch.getOrigin(), ch.getSlot(), si);
                            p.openInventory(ch.getOrigin().getInventory());
                        }
                    });
                }
            }
        });
    }

    private int countMatchingItems(Player p, ShopItem si) {
        String key = si.getItemKey();
        int total = 0;
        for (ItemStack content : p.getInventory().getContents()) {
            if (content == null) continue;
            if (matchesShopItem(content, key)) {
                total += content.getAmount();
            }
        }
        return total;
    }

    private boolean matchesShopItem(ItemStack stack, String key) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        ItemStack sanitized = prepareForSerialization(stack);
        String other = sha256(sanitized.serializeAsBytes());
        return key.equals(other);
    }

    private void removeMatchingItems(Player p, ShopItem si, int qty) {
        String key = si.getItemKey();
        int remaining = qty;
        for (int i = 0; i < p.getInventory().getSize() && remaining > 0; i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (stack == null) continue;
            if (!matchesShopItem(stack, key)) continue;
            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() <= 0) {
                p.getInventory().setItem(i, null);
            } else {
                p.getInventory().setItem(i, stack);
            }
            remaining -= take;
        }
    }

    private int multiplyPrice(int unitPrice, int qty) {
        long result = (long) unitPrice * qty;
        if (result > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (result < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) result;
    }


    private void handleOwnerDeposit(Player p, ShopMenuHolder holder, ItemStack stack) {
        String blob = itemToBase64(stack);
        String key = sha256(Base64.getDecoder().decode(blob));
        ShopItem existing = holder.getItems().values().stream().filter(it -> it.getItemKey().equals(key)).findFirst().orElse(null);
        if (existing != null) {
            ItemStack refund = stack.clone();
            int qty = stack.getAmount();
            stack.setAmount(0);
            existing.setStock(existing.getStock() + qty);
            int slot = holder.getItems().entrySet().stream().filter(en -> en.getValue() == existing).map(Map.Entry::getKey).findFirst().orElse(-1);
            if (slot >= 0) refreshDisplay(holder, slot, existing);
            Map<String, Object> payload = new HashMap<>();
            payload.put("owner_uuid", p.getUniqueId().toString());
            payload.put("shop_id", holder.getShopId());
            payload.put("nbt_blob", blob);
            payload.put("qty", qty);
            payload.put("material", stack.getType().name());
            String dn = stack.getItemMeta() != null ? stack.getItemMeta().getDisplayName() : "";
            payload.put("display_name", dn);
            payload.put("sale_name", existing.getSaleName());
            String currency = existing.firstSellCurrency();
            if (currency == null) {
                currency = existing.firstBuyCurrency();
            }
            payload.put("currency", currency);
            ShopItem.ShopPrice priceInfo = currency != null ? existing.getPrices().get(currency) : null;
            int sellPrice = 0;
            if (priceInfo != null) {
                if (priceInfo.getSellPrice() != null) {
                    sellPrice = priceInfo.getSellPrice();
                } else if (priceInfo.getBuyPrice() != null) {
                    sellPrice = priceInfo.getBuyPrice();
                }
            }
            Integer buyPrice = priceInfo != null ? priceInfo.getBuyPrice() : null;
            payload.put("price", sellPrice);
            payload.put("sell_price", sellPrice);
            if (buyPrice != null) {
                payload.put("buy_price", buyPrice);
            }
            payload.put("timestamp", System.currentTimeMillis() / 1000);
            Request req = new Request.Builder()
                    .url(plugin.getBaseUrl() + "/api/shop/add_stock")
                    .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                    .post(RequestBody.create(gson.toJson(payload), JSON))
                    .build();
            plugin.getHttpClient().newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException ex) {
                    plugin.getLogger().warning("Add stock failed: " + ex.getMessage());
                    existing.setStock(existing.getStock() - qty);
                    if (slot >= 0) Bukkit.getScheduler().runTask(plugin, () -> refreshDisplay(holder, slot, existing));
                    Bukkit.getScheduler().runTask(plugin, () -> p.getInventory().addItem(refund));
                }
                @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
            });
        } else {
            ItemStack single = prepareForSerialization(stack);
            int qty = 1;
            int remain = stack.getAmount() - 1;
            stack.setAmount(Math.max(remain, 0));
            PendingSale pending = new PendingSale(holder.getShopId(), blob, single, qty, System.currentTimeMillis() + 20000, holder);
            pendingSales.put(p.getUniqueId(), pending);
            p.sendMessage(ChatColor.YELLOW + "Enter sale name, sell price, and optional buy price (e.g. apple 100 80) / 販売名と販売価格、必要に応じて買取価格を入力してください (例: apple 100 80)" + ChatColor.RESET);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                PendingSale ps = pendingSales.get(p.getUniqueId());
                if (ps != null && ps.deadline <= System.currentTimeMillis()) {
                    pendingSales.remove(p.getUniqueId());
                    p.sendMessage(ChatColor.RED + "Timed out / タイムアウトしました" + ChatColor.RESET);
                    p.getInventory().addItem(ps.item);
                }
            }, 20 * 20);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof ClosedMenuHolder) {
            e.setCancelled(true);
        }
    }

    private void handleOwnerWithdraw(Player p, ShopMenuHolder holder, ShopItem si, int slot) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("owner_uuid", p.getUniqueId().toString());
        payload.put("shop_id", holder.getShopId());
        payload.put("item_key", si.getItemKey());
        payload.put("qty", 1);
        payload.put("timestamp", System.currentTimeMillis() / 1000);
        Request req = new Request.Builder()
                .url(plugin.getBaseUrl() + "/api/shop/take_stock")
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        plugin.getHttpClient().newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException ex) {
                plugin.getLogger().warning("Take stock failed: " + ex.getMessage());
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                    String status = res.has("status") ? res.get("status").getAsString() : "";
                    if ("success".equals(status) && res.has("grant")) {
                                res.getAsJsonArray("grant").forEach(g -> {
                                    JsonObject gg = g.getAsJsonObject();
                                    String token = gg.get("grant_token").getAsString();
                                    if (plugin.consumeGrantToken(token)) {
                                        int qty = gg.get("qty").getAsInt();
                                        Bukkit.getScheduler().runTask(plugin, () -> giveStackedItems(p, si.getRawItem(), qty));
                                    }
                                });
                        si.setStock(Math.max(0, si.getStock() - 1));
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (si.getStock() <= 0) {
                                holder.getInventory().setItem(slot, null);
                                holder.getItems().remove(slot);
                            } else {
                                refreshDisplay(holder, slot, si);
                            }
                        });
                    }
                }
            }
        });
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        PendingSale ps = pendingSales.remove(e.getPlayer().getUniqueId());
        if (ps == null) return;
        e.setCancelled(true);
        String[] parts = e.getMessage().split(" ");
        if (parts.length < 2) {
            e.getPlayer().sendMessage(ChatColor.RED + "Cancelled / キャンセルされました" + ChatColor.RESET);
            Bukkit.getScheduler().runTask(plugin, () -> e.getPlayer().getInventory().addItem(ps.item));
            return;
        }
        String saleName = parts[0];
        int sellPrice;
        try { sellPrice = parseAmount(parts[1]); } catch (NumberFormatException ex) {
            e.getPlayer().sendMessage(ChatColor.RED + "Cancelled / キャンセルされました" + ChatColor.RESET);
            Bukkit.getScheduler().runTask(plugin, () -> e.getPlayer().getInventory().addItem(ps.item));
            return;
        }
        Integer parsedBuyPrice = null;
        if (parts.length >= 3) {
            try {
                parsedBuyPrice = parseAmount(parts[2]);
            } catch (NumberFormatException ex) {
                e.getPlayer().sendMessage(ChatColor.RED + "Cancelled / キャンセルされました" + ChatColor.RESET);
                Bukkit.getScheduler().runTask(plugin, () -> e.getPlayer().getInventory().addItem(ps.item));
                return;
            }
            if (parsedBuyPrice > sellPrice) {
                e.getPlayer().sendMessage(ChatColor.RED + "Buy price cannot exceed sell price / 買取額は販売額を超えられません" + ChatColor.RESET);
                Bukkit.getScheduler().runTask(plugin, () -> e.getPlayer().getInventory().addItem(ps.item));
                return;
            }
        }
        final String saleNameFinal = saleName;
        final int sellPriceFinal = sellPrice;
        final Integer buyPrice = parsedBuyPrice;
        Map<String, Object> payload = new HashMap<>();
        payload.put("owner_uuid", e.getPlayer().getUniqueId().toString());
        payload.put("shop_id", ps.shopId);
        payload.put("nbt_blob", ps.blob);
        payload.put("qty", ps.qty);
        payload.put("material", ps.item.getType().name());
        String dn = ps.item.getItemMeta() != null ? ps.item.getItemMeta().getDisplayName() : "";
        payload.put("display_name", dn);
        payload.put("sale_name", saleNameFinal);
        payload.put("price", sellPriceFinal);
        payload.put("sell_price", sellPriceFinal);
        if (buyPrice != null) {
            payload.put("buy_price", buyPrice);
        }
        payload.put("currency", null);
        payload.put("timestamp", System.currentTimeMillis() / 1000);
        Request req = new Request.Builder()
                .url(plugin.getBaseUrl() + "/api/shop/add_stock")
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        plugin.getHttpClient().newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException ex) {
                plugin.getLogger().warning("Add stock failed: " + ex.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> e.getPlayer().getInventory().addItem(ps.item));
            }
            @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
        });
        e.getPlayer().sendMessage(ChatColor.GREEN + "Registered / 登録しました" + ChatColor.RESET);
        ShopMenuHolder holder = ps.holder;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Inventory inv = holder.getInventory();
            int slot = inv.firstEmpty();
            if (slot >= 0) {
                Map<String, ShopItem.ShopPrice> priceMap = new HashMap<>();
                priceMap.put("", new ShopItem.ShopPrice(sellPriceFinal, buyPrice));
                ItemStack display = ps.item.clone();
                ItemMeta meta = display.getItemMeta();
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GREEN + "Name: " + ChatColor.YELLOW + saleNameFinal);
                lore.add(ChatColor.GREEN + "Stock: " + ChatColor.YELLOW + ps.qty);
                lore.add(ChatColor.GREEN + "Sell: " + ChatColor.YELLOW + formatAmount(sellPriceFinal));
                if (buyPrice != null) {
                    lore.add(ChatColor.AQUA + "Buy: " + ChatColor.YELLOW + formatAmount(buyPrice));
                }
                meta.setLore(lore);
                display.setItemMeta(meta);
                inv.setItem(slot, display);
                holder.getItems().put(slot, new ShopItem(sha256(Base64.getDecoder().decode(ps.blob)), saleNameFinal, display, ps.item, ps.qty, priceMap));
            }
        });
    }

    @EventHandler
    public void onMoveItem(InventoryMoveItemEvent e) {
        Inventory source = e.getSource();
        Inventory destination = e.getDestination();
        Inventory initiator = e.getInitiator();

        HopperData initiatingHopper = resolveHopper(initiator);
        HopperData sourceHopper = resolveHopper(source);
        HopperData destHopper = resolveHopper(destination);
        Set<String> itemTags = extractItemTags(e.getItem());

        if (initiatingHopper != null) {
            if (itemTags.isEmpty()) {
                if (!isShopInventory(source)) {
                    e.setCancelled(true);
                    return;
                }
            } else if (!matchesBoundItem(e.getItem(), initiatingHopper, itemTags)) {
                e.setCancelled(true);
                return;
            }
            if (destination.getHolder() instanceof Hopper) {
                if (destHopper == null || !sameHopperBinding(initiatingHopper, destHopper)) {
                    e.setCancelled(true);
                    return;
                }
            }
        } else {
            if (!itemTags.isEmpty()) {
                e.setCancelled(true);
                return;
            }
            if (sourceHopper != null || destHopper != null) {
                e.setCancelled(true);
                return;
            }
        }

        boolean sourceShop = isShopInventory(source);
        boolean destShop = isShopInventory(destination);
        if (!sourceShop && !destShop) {
            return;
        }
        if (destShop) {
            e.setCancelled(true);
            return;
        }
        if (!sourceShop) {
            e.setCancelled(true);
            return;
        }
        HopperData hopper = initiatingHopper;
        if (hopper == null) {
            e.setCancelled(true);
            return;
        }
        InventoryHolder holder = source.getHolder();
        if (!(holder instanceof TileState tile)) {
            e.setCancelled(true);
            return;
        }
        PersistentDataContainer c = tile.getPersistentDataContainer();
        String shopId = c.get(keyId, PersistentDataType.STRING);
        String ownerUuid = c.get(keyOwner, PersistentDataType.STRING);
        if (shopId == null || ownerUuid == null || !shopId.equals(hopper.shopId)) {
            e.setCancelled(true);
            return;
        }
        if (!ownerUuid.equals(hopper.shopOwnerUuid) && !ownerUuid.equals(hopper.ownerUuid)) {
            e.setCancelled(true);
            return;
        }
        if (hopper.location == null || hopper.location.getWorld() == null) {
            e.setCancelled(true);
            return;
        }
        String hopperKey = hopperKey(hopper.location);
        long now = System.currentTimeMillis();
        long next = hopperCooldowns.getOrDefault(hopperKey, 0L);
        if (next > now) {
            e.setCancelled(true);
            return;
        }
        hopperCooldowns.put(hopperKey, now + HOPPER_MIN_INTERVAL_MS);
        e.setCancelled(true);
        pullFromShop(hopper, hopperKey);
    }

    private boolean isShopInventory(Inventory inv) {
        if (inv == null) return false;
        var holder = inv.getHolder();
        if (holder instanceof TileState tile) {
            PersistentDataContainer c = tile.getPersistentDataContainer();
            return c.has(keyShop, PersistentDataType.BYTE);
        }
        return false;
    }

    private boolean matchesBoundItem(ItemStack stack, HopperData hopper, Set<String> tags) {
        if (stack == null || hopper == null) return false;
        if (stack.getType() == Material.AIR) return false;
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        for (HopperBinding binding : hopper.bindings) {
            if (tags.contains(binding.itemKey)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameHopperBinding(HopperData a, HopperData b) {
        if (a == null || b == null) return false;
        if (!Objects.equals(a.shopId, b.shopId) || !Objects.equals(a.ownerUuid, b.ownerUuid)) return false;
        if (a.bindings.size() != b.bindings.size()) return false;
        for (int i = 0; i < a.bindings.size(); i++) {
            HopperBinding left = a.bindings.get(i);
            HopperBinding right = b.bindings.get(i);
            if (left.slot != right.slot || !left.itemKey.equals(right.itemKey)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasHopperAccess(Player player, PersistentDataContainer container) {
        if (plugin.hasBypass(player) || player.isOp() || player.hasPermission("lumineeconomy.admin")) {
            return true;
        }
        String owner = container.get(keyOwner, PersistentDataType.STRING);
        String playerId = player.getUniqueId().toString();
        if (owner != null && owner.equals(playerId)) {
            return true;
        }
        String shopOwner = container.get(keyHopperShopOwner, PersistentDataType.STRING);
        return shopOwner != null && shopOwner.equals(playerId);
    }

    private HopperData resolveHopper(Inventory inv) {
        if (inv == null) return null;
        InventoryHolder holder = inv.getHolder();
        if (!(holder instanceof Hopper hopper)) return null;
        PersistentDataContainer c = hopper.getPersistentDataContainer();
        if (!c.has(keyHopper, PersistentDataType.BYTE)) return null;
        String shopId = c.get(keyId, PersistentDataType.STRING);
        String owner = c.get(keyOwner, PersistentDataType.STRING);
        if (shopId == null || owner == null) return null;
        String shopOwner = c.get(keyHopperShopOwner, PersistentDataType.STRING);
        if (shopOwner == null || shopOwner.isEmpty()) {
            shopOwner = owner;
        }
        List<HopperBinding> bindings = new ArrayList<>();
        String slotList = c.get(keyHopperSlot, PersistentDataType.STRING);
        String itemList = c.get(keyHopperItem, PersistentDataType.STRING);
        if (slotList != null && itemList != null) {
            String[] slotParts = slotList.split(",");
            String[] itemParts = itemList.split(",");
            if (slotParts.length == itemParts.length) {
                for (int i = 0; i < slotParts.length; i++) {
                    String sPart = slotParts[i].trim();
                    String iPart = itemParts[i].trim();
                    if (sPart.isEmpty() || iPart.isEmpty()) continue;
                    try {
                        int slot = Integer.parseInt(sPart);
                        bindings.add(new HopperBinding(slot, iPart));
                    } catch (NumberFormatException ignored) {
                        // skip invalid slot entry
                    }
                }
            }
        }
        if (bindings.isEmpty()) {
            Integer slot = c.get(keyHopperSlot, PersistentDataType.INTEGER);
            String singleKey = c.get(keyHopperItem, PersistentDataType.STRING);
            if (singleKey != null && singleKey.contains(",")) {
                String[] parts = singleKey.split(",");
                singleKey = parts.length > 0 ? parts[0].trim() : null;
            }
            if (slot != null && singleKey != null && !singleKey.isEmpty()) {
                bindings.add(new HopperBinding(slot, singleKey));
            }
        }
        if (bindings.isEmpty()) return null;
        Location loc = hopper.getLocation();
        return new HopperData(shopId, owner, shopOwner, bindings, loc != null ? loc.clone() : null);
    }

    private String hopperKey(Location loc) {
        if (loc == null || loc.getWorld() == null) return "";
        return loc.getWorld().getName() + ':' + loc.getBlockX() + ':' + loc.getBlockY() + ':' + loc.getBlockZ();
    }

    private void pullFromShop(HopperData hopper, String key) {
        OkHttpClient http = plugin.getHttpClient();
        if (http == null || !plugin.isActive()) {
            hopperCooldowns.put(key, System.currentTimeMillis() + HOPPER_EMPTY_INTERVAL_MS);
            return;
        }
        List<String> itemKeys = hopper.itemKeys();
        if (itemKeys.isEmpty()) {
            hopperCooldowns.put(key, System.currentTimeMillis() + HOPPER_EMPTY_INTERVAL_MS);
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        String apiOwner = (hopper.shopOwnerUuid != null && !hopper.shopOwnerUuid.isEmpty())
                ? hopper.shopOwnerUuid : hopper.ownerUuid;
        payload.put("owner_uuid", apiOwner);
        payload.put("shop_id", hopper.shopId);
        payload.put("item_keys", itemKeys);
        payload.put("qty", 1);
        payload.put("timestamp", System.currentTimeMillis() / 1000);
        Request req = new Request.Builder()
                .url(plugin.getBaseUrl() + "/api/shop/take_stock")
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        Location hopperLoc = hopper.location != null ? hopper.location.clone() : null;
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException ex) {
                plugin.getLogger().warning("Hopper withdraw failed: " + ex.getMessage());
                hopperCooldowns.put(key, System.currentTimeMillis() + HOPPER_EMPTY_INTERVAL_MS);
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                    String status = res.has("status") ? res.get("status").getAsString() : "";
                    if ("success".equals(status) && res.has("grant")) {
                        List<ItemStack> items = new ArrayList<>();
                        res.getAsJsonArray("grant").forEach(el -> {
                            JsonObject gg = el.getAsJsonObject();
                            String token = gg.get("grant_token").getAsString();
                            if (plugin.consumeGrantToken(token)) {
                                ItemStack item = itemFromBase64(gg.get("nbt_blob").getAsString());
                                item.setAmount(gg.get("qty").getAsInt());
                                items.add(item);
                            }
                        });
                        hopperCooldowns.put(key, System.currentTimeMillis() + HOPPER_MIN_INTERVAL_MS);
                        if (!items.isEmpty() && hopperLoc != null) {
                            Bukkit.getScheduler().runTask(plugin, () -> deliverToHopper(hopper, hopperLoc, items));
                        }
                    } else {
                        String reason = res.has("reason") ? res.get("reason").getAsString() : "";
                        long delay = "insufficient_stock".equals(reason) ? HOPPER_EMPTY_INTERVAL_MS : 1500L;
                        hopperCooldowns.put(key, System.currentTimeMillis() + delay);
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("Failed to process hopper response: " + ex.getMessage());
                    hopperCooldowns.put(key, System.currentTimeMillis() + HOPPER_EMPTY_INTERVAL_MS);
                }
            }
        });
    }

    private void deliverToHopper(HopperData hopperData, Location loc, List<ItemStack> items) {
        if (hopperData == null) {
            return;
        }
        Block block = loc.getBlock();
        if (!(block.getState() instanceof Hopper hopper)) {
            for (ItemStack stack : items) {
                loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), stack);
            }
            return;
        }
        tagItemsForBindings(items, hopperData.bindings);
        Inventory inv = hopper.getInventory();
        for (ItemStack stack : items) {
            Map<Integer, ItemStack> leftover = inv.addItem(stack);
            if (!leftover.isEmpty()) {
                leftover.values().forEach(rem -> loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), rem));
            }
        }
    }

    private void tagItemsForBindings(List<ItemStack> items, List<HopperBinding> bindings) {
        if (items == null || items.isEmpty() || bindings == null || bindings.isEmpty()) {
            return;
        }
        String tagValue = bindings.stream()
                .map(binding -> binding.itemKey)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
        if (tagValue.isEmpty()) {
            return;
        }
        for (ItemStack stack : items) {
            if (stack == null) {
                continue;
            }
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                continue;
            }
            meta.getPersistentDataContainer().set(keyHopperItemTag, PersistentDataType.STRING, tagValue);
            stack.setItemMeta(meta);
        }
    }

    private Set<String> extractItemTags(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return Collections.emptySet();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Collections.emptySet();
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        String raw = data.get(keyHopperItemTag, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        String[] parts = raw.split(",");
        Set<String> tags = new HashSet<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                tags.add(trimmed);
            }
        }
        return tags;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!isShopBlock(b)) return;
        TileState tile = (TileState) b.getState();
        String owner = tile.getPersistentDataContainer().get(keyOwner, PersistentDataType.STRING);
        String shopId = tile.getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
        if (owner != null && !e.getPlayer().getUniqueId().toString().equals(owner)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "You are not the owner / あなたはオーナーではありません" + ChatColor.RESET);
            return;
        }
        notifyRemove(owner, shopId);
        Player op = Bukkit.getPlayer(UUID.fromString(owner));
        if (op != null && op != e.getPlayer())
            op.sendMessage(ChatColor.RED + "Your shop " + ChatColor.YELLOW + shopId + ChatColor.RED + " was removed / あなたのショップが撤去されました" + ChatColor.RESET);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isShopBlock);
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isShopBlock);
    }

    private boolean isShopBlock(Block b) {
        if (b.getType() != Material.BARREL) return false;
        BlockState state = b.getState();
        if (state instanceof TileState tile) {
            return tile.getPersistentDataContainer().has(keyShop, PersistentDataType.BYTE);
        }
        return false;
    }

    private void notifyRemove(String owner, String shopId) {
        OkHttpClient http = plugin.getHttpClient();
        if (http == null || owner == null || shopId == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("owner_uuid", owner);
        payload.put("shop_id", shopId);
        Request req = new Request.Builder()
                .url(plugin.getBaseUrl() + "/api/shop/remove")
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException ex) { }
            @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
        });
    }

    private int getPlayerBalance(Player p, String currency) {
        Scoreboard sb = p.getScoreboard() != null ? p.getScoreboard() : Bukkit.getScoreboardManager().getMainScoreboard();
        String entry = p.getName();
        String objective = (currency == null || currency.isBlank()) ? "currency" : currency;
        int balance = ScoreboardUtil.readCurrency(sb, objective, entry);
        if (balance == 0) {
            if (!objective.startsWith("currency_")) {
                balance = ScoreboardUtil.readCurrency(sb, "currency_" + objective, entry);
            } else {
                balance = ScoreboardUtil.readCurrency(sb, objective.substring("currency_".length()), entry);
            }
        }
        return balance;
    }

    private int computeMaxCarry(Player p, ShopItem si) {
        ItemStack template = si.getRawItem();
        int maxStack = Math.max(1, template.getMaxStackSize());
        int total = 0;
        String key = si.getItemKey();
        for (ItemStack content : p.getInventory().getContents()) {
            if (content == null || content.getType() == Material.AIR) {
                total += maxStack;
                continue;
            }
            if (matchesShopItem(content, key) && content.getAmount() < maxStack) {
                total += (maxStack - content.getAmount());
            }
        }
        return total;
    }

    private int computeMaxBuyQuantity(Player p, ShopItem si) {
        String currency = si.firstSellCurrency();
        ShopItem.ShopPrice price = currency != null ? si.getPrices().get(currency) : null;
        Integer sellPrice = price != null ? price.getSellPrice() : null;
        if (currency == null || sellPrice == null || sellPrice <= 0) {
            return 0;
        }
        int balance = getPlayerBalance(p, currency);
        int affordable = sellPrice > 0 ? balance / sellPrice : 0;
        int stock = si.getStock();
        int carry = computeMaxCarry(p, si);
        return Math.max(0, Math.min(stock, Math.min(affordable, carry)));
    }

    private void giveStackedItems(Player p, ItemStack template, int totalAmount) {
        ItemStack base = template.clone();
        int maxStack = Math.max(1, base.getMaxStackSize());
        int remaining = totalAmount;
        while (remaining > 0) {
            int toGive = Math.min(maxStack, remaining);
            ItemStack portion = base.clone();
            portion.setAmount(toGive);
            p.getInventory().addItem(portion);
            remaining -= toGive;
        }
    }

    private int parseAmount(String s) throws NumberFormatException {
        return plugin.parseAmount(s);
    }

    private String formatAmount(int amount) {
        return plugin.formatAmountPlain(amount);
    }
}
