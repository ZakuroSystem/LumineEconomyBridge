package com.grapelemon.lumineeconomybridge.shop;

import com.grapelemon.lumineeconomybridge.Lang;
import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import com.google.gson.Gson;
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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import java.time.Instant;

import java.io.IOException;
import java.util.*;

public class ShopListener implements Listener {
    private final LumineEconomyBridge plugin;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final Gson gson = new Gson();
    private final NamespacedKey keyShop;
    private final NamespacedKey keyId;

    public ShopListener(LumineEconomyBridge plugin) {
        this.plugin = plugin;
        this.keyShop = new NamespacedKey(plugin, "le_shop");
        this.keyId = new NamespacedKey(plugin, "shop_id");
    }

    private ItemStack itemFromBase64(String data) {
        byte[] bytes = Base64.getDecoder().decode(data);
        return ItemStack.deserializeBytes(bytes);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        ItemMeta meta = e.getItemInHand().getItemMeta();
        if (meta == null) return;
        PersistentDataContainer c = meta.getPersistentDataContainer();
        if (!c.has(keyShop, PersistentDataType.BYTE)) return;
        String shopId = c.get(keyId, PersistentDataType.STRING);
        BlockState state = e.getBlockPlaced().getState();
        if (state instanceof TileState tile) {
            PersistentDataContainer tc = tile.getPersistentDataContainer();
            tc.set(keyShop, PersistentDataType.BYTE, (byte)1);
            if (shopId != null) tc.set(keyId, PersistentDataType.STRING, shopId);
            tile.update(true);
        }
        OkHttpClient http = plugin.getHttpClient();
        if (http == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("shop_id", shopId);
        payload.put("owner_uuid", e.getPlayer().getUniqueId().toString());
        Location loc = e.getBlockPlaced().getLocation();
        payload.put("world", loc.getWorld().getName());
        payload.put("x", loc.getBlockX());
        payload.put("y", loc.getBlockY());
        payload.put("z", loc.getBlockZ());
        payload.put("timestamp", System.currentTimeMillis() / 1000);
        Request req = new Request.Builder()
                .url(plugin.getBaseUrl() + "/api/shop/place")
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
        OkHttpClient http = plugin.getHttpClient();
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
                            Inventory inv = Bukkit.createInventory(null, 9, "Shop " + shopId);
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
                    JsonObject dataObj = obj;
                    // ping to keep shop active
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
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        var arr = dataObj.getAsJsonArray("items");
                        int size = ((arr.size() + 1 + 8) / 9) * 9;
                        if (size < 9) size = 9;
                        ShopMenuHolder holder = new ShopMenuHolder(shopId);
                        Inventory inv = Bukkit.createInventory(holder, size, "Shop " + shopId);
                        holder.setInventory(inv);
                        int idx = 0;
                        for (var el : arr) {
                            if (idx >= size - 1) break;
                            JsonObject it = el.getAsJsonObject();
                            String key = it.get("item_key").getAsString();
                            String blob = it.get("nbt_blob").getAsString();
                            ItemStack item = itemFromBase64(blob);
                            ItemMeta meta = item.getItemMeta();
                            List<String> lore = new ArrayList<>();
                            lore.add("Stock: " + it.get("stock").getAsInt());
                            JsonObject prices = it.getAsJsonObject("prices");
                            Map<String, Integer> priceMap = new HashMap<>();
                            for (var en : prices.entrySet()) {
                                lore.add(en.getKey() + ": " + en.getValue().getAsInt());
                                priceMap.put(en.getKey(), en.getValue().getAsInt());
                            }
                            meta.setLore(lore);
                            item.setItemMeta(meta);
                            inv.setItem(idx, item);
                            holder.getItems().put(idx, new ShopItem(key, item, priceMap));
                            idx++;
                        }
                        ItemStack confirm = new ItemStack(Material.EMERALD);
                        ItemMeta cm = confirm.getItemMeta();
                        cm.setDisplayName("Purchase");
                        confirm.setItemMeta(cm);
                        inv.setItem(size - 1, confirm);
                        p.openInventory(inv);
                    });
                }
            }
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof ShopMenuHolder holder)) return;
        e.setCancelled(true);
        Player p = (Player) e.getWhoClicked();
        if (e.getSlot() == e.getInventory().getSize() - 1) {
            ShopItem si = holder.getSelectedItem();
            if (si == null) return;
            if (e.isRightClick() && !e.isShiftClick()) {
                List<String> curList = new ArrayList<>(si.getPrices().keySet());
                if (curList.isEmpty()) return;
                String cur = holder.getCurrency();
                int idx = cur == null ? -1 : curList.indexOf(cur);
                idx = (idx + 1) % curList.size();
                holder.setCurrency(curList.get(idx));
                refreshConfirm(holder);
                return;
            }
            if (e.isShiftClick()) {
                int q = holder.getQuantity();
                if (e.isRightClick()) q--; else q++;
                holder.setQuantity(q);
                refreshConfirm(holder);
                return;
            }
            String currency = holder.getCurrency();
            if (currency == null) {
                currency = si.getPrices().keySet().stream().findFirst().orElse(null);
                if (currency == null) return;
            }
            int qty = holder.getQuantity();
            Map<String, Object> payload = new HashMap<>();
            payload.put("player_uuid", p.getUniqueId().toString());
            payload.put("shop_id", holder.getShopId());
            payload.put("item_key", si.getItemKey());
            payload.put("qty", qty);
            payload.put("currency", currency);
            payload.put("timestamp", System.currentTimeMillis() / 1000);
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
                                    String text = msg.has("text") ? msg.get("text").getAsString() : "";
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
                                        String ik = gg.get("item_key").getAsString();
                                        int qty2 = gg.get("qty").getAsInt();
                                        ShopItem item = holder.getItems().values().stream().filter(it -> it.getItemKey().equals(ik)).findFirst().orElse(null);
                                        if (item != null) {
                                            ItemStack stack = item.getItem().clone();
                                            stack.setAmount(qty2);
                                            p.getInventory().addItem(stack);
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
                        });
                    }
                }
            });
        } else if (holder.getItems().containsKey(e.getSlot())) {
            holder.setSelected(e.getSlot());
            holder.setQuantity(1);
            ShopItem si = holder.getSelectedItem();
            String cur = si.getPrices().keySet().stream().findFirst().orElse(null);
            holder.setCurrency(cur);
            refreshConfirm(holder);
        }
    }

    private void refreshConfirm(ShopMenuHolder holder) {
        Inventory inv = holder.getInventory();
        if (inv == null) return;
        int slot = inv.getSize() - 1;
        ItemStack confirm = inv.getItem(slot);
        if (confirm == null || confirm.getType() != Material.EMERALD) {
            confirm = new ItemStack(Material.EMERALD);
        }
        ItemMeta cm = confirm.getItemMeta();
        List<String> lore = new ArrayList<>();
        ShopItem si = holder.getSelectedItem();
        if (si != null && holder.getCurrency() != null) {
            int unit = si.getPrices().getOrDefault(holder.getCurrency(), 0);
            int total = unit * holder.getQuantity();
            lore.add("Qty: " + holder.getQuantity());
            lore.add(holder.getCurrency() + ": " + total);
            cm.setDisplayName("Purchase");
        } else {
            cm.setDisplayName("Purchase");
        }
        cm.setLore(lore);
        confirm.setItemMeta(cm);
        inv.setItem(slot, confirm);
    }
}
