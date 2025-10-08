package com.grapelemon.lumineeconomybridge.shop;

import com.grapelemon.lumineeconomybridge.Lang;
import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
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
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.ChatColor;
import java.time.Instant;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;
import java.math.BigDecimal;
import java.text.DecimalFormat;

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
    private static final long CACHE_MS = 3000;
    private static final DecimalFormat AMT_FMT = new DecimalFormat("0.###");
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

    private static class HopperData {
        final String shopId;
        final String ownerUuid;
        final int slot;
        final String itemKey;
        final Location location;

        HopperData(String shopId, String ownerUuid, int slot, String itemKey, Location location) {
            this.shopId = shopId;
            this.ownerUuid = ownerUuid;
            this.slot = slot;
            this.itemKey = itemKey;
            this.location = location;
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

    private String itemToBase64(ItemStack item) {
        ItemStack clone = item.clone();
        clone.setAmount(1);
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
        String expected = c.get(keyOwner, PersistentDataType.STRING);
        String placer = e.getPlayer().getUniqueId().toString();
        if (expected == null || !placer.equals(expected)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "You are not the owner / あなたはオーナーではありません" + ChatColor.RESET);
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
                Integer slot = c.get(keyHopperSlot, PersistentDataType.INTEGER);
                if (slot != null) {
                    tc.set(keyHopperSlot, PersistentDataType.INTEGER, slot);
                } else {
                    String legacy = c.get(keyHopperSlot, PersistentDataType.STRING);
                    if (legacy != null && !legacy.isEmpty()) {
                        tc.set(keyHopperSlot, PersistentDataType.STRING, legacy);
                    }
                }
                String itemKey = c.get(keyHopperItem, PersistentDataType.STRING);
                if (itemKey != null && !itemKey.isEmpty()) {
                    tc.set(keyHopperItem, PersistentDataType.STRING, itemKey);
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
        if (b == null || b.getType() != Material.BARREL) return;
        BlockState state = b.getState();
        if (!(state instanceof TileState tile)) return;
        PersistentDataContainer c = tile.getPersistentDataContainer();
        if (!c.has(keyShop, PersistentDataType.BYTE)) return;
        e.setCancelled(true);
        String shopId = c.get(keyId, PersistentDataType.STRING);
        if (shopId == null) return;
        openShop(e.getPlayer(), shopId);
    }

    private void openShop(Player p, String shopId) {
        if (!plugin.isActive()) {
            p.sendMessage(Lang.get("error-unavailable"));
            return;
        }
        CacheEntry ce = itemCache.get(shopId);
        long now = System.currentTimeMillis();
        if (ce != null && now - ce.timestamp < CACHE_MS) {
            buildInventory(p, shopId, ce.data);
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
                    buildInventory(p, shopId, obj);
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

    private void buildInventory(Player p, String shopId, JsonObject dataObj) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            var arr = dataObj.getAsJsonArray("items");
            int size = ((arr.size() + 8) / 9) * 9;
            if (size < 9) size = 9;
            ShopMenuHolder holder = new ShopMenuHolder(shopId);
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
                Map<String, Integer> priceMap = new HashMap<>();
                for (var en : prices.entrySet()) {
                    int val = en.getValue().getAsInt();
                    lore.add(ChatColor.GREEN + en.getKey() + ChatColor.WHITE + ": " + ChatColor.YELLOW + formatAmount(val));
                    priceMap.put(en.getKey(), val);
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
            lore.add(ChatColor.GREEN + en.getKey() + ChatColor.WHITE + ": " + ChatColor.YELLOW + formatAmount(en.getValue()));
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
            if (e.getSlot() == 2) {
                if (ch.isSelling()) {
                    handleSell(p, ch);
                } else {
                    handlePurchase(p, ch);
                }
            } else if (e.getSlot() == 6) {
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
        if (e.getClickedInventory() == p.getInventory() && e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            ItemStack stack = e.getCurrentItem();
            if (stack == null) return;
            e.setCancelled(true);
            if (isOwner) {
                handleOwnerDeposit(p, holder, stack);
            } else {
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
                openConfirm(p, holder, e.getSlot(), si);
            }
            return;
        }
        e.setCancelled(true);
    }

    private void handlePurchase(Player p, ConfirmMenuHolder ch) {
        ShopItem si = ch.getItem();
        String currency = si.getPrices().keySet().stream().findFirst().orElse(null);
        if (currency == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("player_uuid", p.getUniqueId().toString());
        payload.put("shop_id", ch.getShopId());
        payload.put("item_key", si.getItemKey());
        payload.put("qty", 1);
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
                        if ("success".equals(res.get("status").getAsString())) {
                            if (res.has("grant")) {
                                res.getAsJsonArray("grant").forEach(g -> {
                                    JsonObject gg = g.getAsJsonObject();
                                    String token = gg.get("grant_token").getAsString();
                                    if (plugin.consumeGrantToken(token)) {
                                        String ik = gg.get("item_key").getAsString();
                                        int qty2 = gg.get("qty").getAsInt();
                                        if (si.getItemKey().equals(ik)) {
                                            ItemStack stack = si.getRawItem().clone();
                                            stack.setAmount(qty2);
                                            p.getInventory().addItem(stack);
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
                        }
                        si.setStock(si.getStock() - 1);
                        refreshDisplay(ch.getOrigin(), ch.getSlot(), si);
                        p.openInventory(ch.getOrigin().getInventory());
                    });
                }
            }
        });
    }

    private void openConfirm(Player p, ShopMenuHolder holder, int slot, ShopItem si) {
        Inventory inv = Bukkit.createInventory(new ConfirmMenuHolder(holder.getShopId(), holder, slot, si, false), 9, "Confirm");
        inv.setItem(4, si.getRawItem());
        ItemStack ok = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta om = ok.getItemMeta();
        om.setDisplayName(ChatColor.GREEN + "Buy");
        ok.setItemMeta(om);
        inv.setItem(2, ok);
        ItemStack cancel = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta cm = cancel.getItemMeta();
        cm.setDisplayName(ChatColor.RED + "Cancel");
        cancel.setItemMeta(cm);
        inv.setItem(6, cancel);
        p.openInventory(inv);
    }

    private void openSellConfirm(Player p, ShopMenuHolder holder, int slot, ShopItem si) {
        Inventory inv = Bukkit.createInventory(new ConfirmMenuHolder(holder.getShopId(), holder, slot, si, true), 9, "Confirm");
        inv.setItem(4, si.getRawItem());
        ItemStack ok = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta om = ok.getItemMeta();
        om.setDisplayName(ChatColor.GREEN + "Sell");
        ok.setItemMeta(om);
        inv.setItem(2, ok);
        ItemStack cancel = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta cm = cancel.getItemMeta();
        cm.setDisplayName(ChatColor.RED + "Cancel");
        cancel.setItemMeta(cm);
        inv.setItem(6, cancel);
        p.openInventory(inv);
    }

    private void handlePlayerSell(Player p, ShopMenuHolder holder, ItemStack stack) {
        String blob = itemToBase64(stack);
        String key = sha256(Base64.getDecoder().decode(blob));
        for (var en : holder.getItems().entrySet()) {
            if (en.getValue().getItemKey().equals(key)) {
                openSellConfirm(p, holder, en.getKey(), en.getValue());
                return;
            }
        }
        p.sendMessage(ChatColor.RED + "This item cannot be sold here / このアイテムはここでは売れません" + ChatColor.RESET);
    }

    private void handleSell(Player p, ConfirmMenuHolder ch) {
        ShopItem si = ch.getItem();
        String currency = si.getPrices().keySet().stream().findFirst().orElse(null);
        if (currency == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("player_uuid", p.getUniqueId().toString());
        payload.put("shop_id", ch.getShopId());
        payload.put("item_key", si.getItemKey());
        payload.put("qty", 1);
        payload.put("currency", currency);
        payload.put("timestamp", System.currentTimeMillis() / 1000);
        payload.put("client_tx_id", UUID.randomUUID().toString());
        Request req = new Request.Builder()
                .url(plugin.getBaseUrl() + "/api/shop/sell")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        ItemStack remove = si.getRawItem().clone();
        remove.setAmount(1);
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
                        if ("success".equals(res.get("status").getAsString())) {
                            p.getInventory().removeItem(remove);
                            si.setStock(si.getStock() + 1);
                            refreshDisplay(ch.getOrigin(), ch.getSlot(), si);
                            p.openInventory(ch.getOrigin().getInventory());
                        }
                    });
                }
            }
        });
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
            String currency = existing.getPrices().keySet().stream().findFirst().orElse(null);
            payload.put("currency", currency);
            int price = currency != null ? existing.getPrices().get(currency) : 0;
            payload.put("price", price);
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
            ItemStack single = stack.clone();
            single.setAmount(1);
            int qty = 1;
            int remain = stack.getAmount() - 1;
            stack.setAmount(Math.max(remain, 0));
            PendingSale pending = new PendingSale(holder.getShopId(), blob, single, qty, System.currentTimeMillis() + 20000, holder);
            pendingSales.put(p.getUniqueId(), pending);
            p.sendMessage(ChatColor.YELLOW + "Enter sale name and price (e.g. apple 100) / 販売名と金額を入力してください (例: apple 100)" + ChatColor.RESET);
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
                                ItemStack item = si.getRawItem().clone();
                                item.setAmount(gg.get("qty").getAsInt());
                                Bukkit.getScheduler().runTask(plugin, () -> p.getInventory().addItem(item));
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
        int price;
        try { price = parseAmount(parts[1]); } catch (NumberFormatException ex) {
            e.getPlayer().sendMessage(ChatColor.RED + "Cancelled / キャンセルされました" + ChatColor.RESET);
            Bukkit.getScheduler().runTask(plugin, () -> e.getPlayer().getInventory().addItem(ps.item));
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("owner_uuid", e.getPlayer().getUniqueId().toString());
        payload.put("shop_id", ps.shopId);
        payload.put("nbt_blob", ps.blob);
        payload.put("qty", ps.qty);
        payload.put("material", ps.item.getType().name());
        String dn = ps.item.getItemMeta() != null ? ps.item.getItemMeta().getDisplayName() : "";
        payload.put("display_name", dn);
        payload.put("sale_name", saleName);
        payload.put("price", price);
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
                Map<String, Integer> priceMap = new HashMap<>();
                priceMap.put("", price);
                ItemStack display = ps.item.clone();
                ItemMeta meta = display.getItemMeta();
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GREEN + "Name: " + ChatColor.YELLOW + saleName);
                lore.add(ChatColor.GREEN + "Stock: " + ChatColor.YELLOW + ps.qty);
                lore.add(ChatColor.GREEN + "Price: " + ChatColor.YELLOW + formatAmount(price));
                meta.setLore(lore);
                display.setItemMeta(meta);
                inv.setItem(slot, display);
                holder.getItems().put(slot, new ShopItem(sha256(Base64.getDecoder().decode(ps.blob)), saleName, display, ps.item, ps.qty, priceMap));
            }
        });
    }

    @EventHandler
    public void onMoveItem(InventoryMoveItemEvent e) {
        Inventory source = e.getSource();
        Inventory destination = e.getDestination();
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
        Inventory initiator = e.getInitiator();
        HopperData hopper = resolveHopper(initiator);
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
        if (shopId == null || ownerUuid == null || !shopId.equals(hopper.shopId) || !ownerUuid.equals(hopper.ownerUuid)) {
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

    private HopperData resolveHopper(Inventory inv) {
        if (inv == null) return null;
        InventoryHolder holder = inv.getHolder();
        if (!(holder instanceof Hopper hopper)) return null;
        PersistentDataContainer c = hopper.getPersistentDataContainer();
        if (!c.has(keyHopper, PersistentDataType.BYTE)) return null;
        String shopId = c.get(keyId, PersistentDataType.STRING);
        String owner = c.get(keyOwner, PersistentDataType.STRING);
        if (shopId == null || owner == null) return null;
        Integer slot = c.get(keyHopperSlot, PersistentDataType.INTEGER);
        if (slot == null) {
            String slotString = c.get(keyHopperSlot, PersistentDataType.STRING);
            if (slotString != null && !slotString.isEmpty()) {
                String[] parts = slotString.split(",");
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (trimmed.isEmpty()) continue;
                    try {
                        slot = Integer.parseInt(trimmed);
                        break;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        String itemKey = c.get(keyHopperItem, PersistentDataType.STRING);
        if (itemKey != null && itemKey.contains(",")) {
            String[] parts = itemKey.split(",");
            itemKey = parts.length > 0 ? parts[0].trim() : itemKey;
        }
        if (slot == null || itemKey == null || itemKey.isEmpty()) return null;
        Location loc = hopper.getLocation();
        return new HopperData(shopId, owner, slot, itemKey, loc != null ? loc.clone() : null);
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
        if (hopper.itemKey == null || hopper.itemKey.isEmpty()) {
            hopperCooldowns.put(key, System.currentTimeMillis() + HOPPER_EMPTY_INTERVAL_MS);
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("owner_uuid", hopper.ownerUuid);
        payload.put("shop_id", hopper.shopId);
        payload.put("item_key", hopper.itemKey);
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
                            Bukkit.getScheduler().runTask(plugin, () -> deliverToHopper(hopperLoc, items));
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

    private void deliverToHopper(Location loc, List<ItemStack> items) {
        Block block = loc.getBlock();
        if (!(block.getState() instanceof Hopper hopper)) {
            for (ItemStack stack : items) {
                loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), stack);
            }
            return;
        }
        Inventory inv = hopper.getInventory();
        for (ItemStack stack : items) {
            Map<Integer, ItemStack> leftover = inv.addItem(stack);
            if (!leftover.isEmpty()) {
                leftover.values().forEach(rem -> loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), rem));
            }
        }
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

    private int parseAmount(String s) throws NumberFormatException {
        BigDecimal bd = new BigDecimal(s);
        bd = bd.movePointRight(3);
        return bd.intValueExact();
    }

    private String formatAmount(int amount) {
        return AMT_FMT.format(amount / 1000.0);
    }
}
