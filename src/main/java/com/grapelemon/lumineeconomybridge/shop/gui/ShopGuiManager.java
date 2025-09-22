package com.grapelemon.lumineeconomybridge.shop.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.grapelemon.lumineeconomybridge.shop.ShopItem;
import com.grapelemon.lumineeconomybridge.shop.ShopMenuHolder;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Provides the inventory based navigation shell that lets players jump from the
 * command to a list of their shops and open the existing editing inventory
 * shipped with {@link ShopMenuHolder}.
 */
public class ShopGuiManager {

    private final LumineEconomyBridge plugin;
    private final Map<UUID, ShopGuiSession> sessions = new HashMap<>();

    public ShopGuiManager(LumineEconomyBridge plugin) {
        this.plugin = plugin;
    }

    public void openMainMenu(Player player) {
        ShopGuiSession session = sessions.computeIfAbsent(player.getUniqueId(), id -> new ShopGuiSession(player));
        session.showMainMenu();
    }

    public void reopen(Player player) {
        ShopGuiSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.reopen();
        }
    }

    public void handleClick(Player player, int slot) {
        ShopGuiSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.execute(slot);
        }
    }

    public void close(Player player) {
        ShopGuiSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.clear();
        }
    }

    public boolean isTracking(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    private ItemStack button(Material material, String title, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(title);
            if (lore.length > 0) {
                List<String> lines = new ArrayList<>();
                for (String line : lore) {
                    lines.add(line);
                }
                meta.setLore(lines);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void requestOwnedShops(Player player, Consumer<List<String>> success, Consumer<String> error) {
        OkHttpClient client = plugin.getHttpClient();
        if (client == null || !plugin.isActive()) {
            error.accept(ChatColor.RED + "Backend unavailable" + ChatColor.RESET);
            return;
        }
        HttpUrl url = HttpUrl.parse(plugin.getBaseUrl() + "/api/shop/ids").newBuilder()
                .addQueryParameter("owner_uuid", player.getUniqueId().toString())
                .build();
        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                error.accept(ChatColor.RED + "Failed to load shops" + ChatColor.RESET);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        error.accept(ChatColor.RED + "Failed to load shops" + ChatColor.RESET);
                        return;
                    }
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    List<String> result = new ArrayList<>();
                    if (json.has("ids") && json.get("ids").isJsonArray()) {
                        JsonArray arr = json.getAsJsonArray("ids");
                        for (JsonElement element : arr) {
                            if (element != null && element.isJsonPrimitive()) {
                                String id = element.getAsString();
                                if (id != null && !id.isBlank()) {
                                    result.add(id);
                                }
                            }
                        }
                    }
                    success.accept(result);
                }
            }
        });
    }

    private void requestShop(Player player, String shopId, Consumer<JsonObject> success, Consumer<String> error) {
        OkHttpClient client = plugin.getHttpClient();
        if (client == null || !plugin.isActive()) {
            error.accept(ChatColor.RED + "Backend unavailable" + ChatColor.RESET);
            return;
        }
        HttpUrl url = HttpUrl.parse(plugin.getBaseUrl() + "/api/shop/items").newBuilder()
                .addQueryParameter("shop_id", shopId)
                .build();
        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                error.accept(ChatColor.RED + "Failed to load shop" + ChatColor.RESET);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        error.accept(ChatColor.RED + "Failed to load shop" + ChatColor.RESET);
                        return;
                    }
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    if (!json.has("status") || !"active".equalsIgnoreCase(json.get("status").getAsString())) {
                        error.accept(ChatColor.RED + "Shop is not active" + ChatColor.RESET);
                        return;
                    }
                    success.accept(json);
                }
            }
        });
    }

    private void openShopInventory(Player player, String shopId, JsonObject data) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            sessions.remove(player.getUniqueId());
            JsonArray items = data.has("items") && data.get("items").isJsonArray()
                    ? data.getAsJsonArray("items") : new JsonArray();
            int size = Math.max(9, ((items.size() + 8) / 9) * 9);
            ShopMenuHolder holder = new ShopMenuHolder(shopId);
            if (data.has("trade_mode") && !data.get("trade_mode").isJsonNull()) {
                holder.setTradeMode(data.get("trade_mode").getAsString());
            }
            if (data.has("owners") && data.get("owners").isJsonArray()) {
                for (JsonElement owner : data.getAsJsonArray("owners")) {
                    if (owner != null && owner.isJsonPrimitive()) {
                        holder.addOwnerUuid(owner.getAsString());
                    }
                }
            }
            Inventory inv = Bukkit.createInventory(holder, size, ChatColor.GOLD + "Shop " + shopId);
            holder.setInventory(inv);
            int slot = 0;
            for (JsonElement element : items) {
                if (slot >= size) break;
                JsonObject obj = element.getAsJsonObject();
                String itemKey = obj.get("item_key").getAsString();
                String saleName = obj.has("sale_name") && !obj.get("sale_name").isJsonNull()
                        ? obj.get("sale_name").getAsString() : itemKey;
                String blob = obj.get("nbt_blob").getAsString();
                ItemStack stack = itemFromBase64(blob);
                ItemStack raw = stack.clone();
                int stock = obj.get("stock").getAsInt();
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GREEN + "Name: " + ChatColor.YELLOW + saleName);
                lore.add(ChatColor.GREEN + "Stock: " + ChatColor.YELLOW + stock);
                Map<String, ShopItem.ShopPrice> priceMap = new HashMap<>();
                if (obj.has("prices") && obj.get("prices").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject("prices").entrySet()) {
                        String currency = entry.getKey();
                        JsonObject price = entry.getValue().getAsJsonObject();
                        Integer sellPrice = price.has("sell") && !price.get("sell").isJsonNull()
                                ? price.get("sell").getAsInt() : null;
                        Integer buyPrice = price.has("buy") && !price.get("buy").isJsonNull()
                                ? price.get("buy").getAsInt() : null;
                        String currencyText = currency == null || currency.isBlank()
                                ? ChatColor.GRAY + "(default)"
                                : ChatColor.WHITE + currency;
                        if (sellPrice != null) {
                            lore.add(ChatColor.GREEN + "Sell: " + ChatColor.YELLOW + plugin.formatAmountPlain(sellPrice)
                                    + ChatColor.WHITE + " " + currencyText);
                        }
                        if (buyPrice != null) {
                            lore.add(ChatColor.AQUA + "Buy: " + ChatColor.YELLOW + plugin.formatAmountPlain(buyPrice)
                                    + ChatColor.WHITE + " " + currencyText);
                        }
                        priceMap.put(currency, new ShopItem.ShopPrice(sellPrice, buyPrice));
                    }
                }
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    meta.setLore(lore);
                    stack.setItemMeta(meta);
                }
                inv.setItem(slot, stack);
                holder.getItems().put(slot, new ShopItem(itemKey, saleName, stack, raw, stock, priceMap));
                slot++;
            }
            player.openInventory(inv);
        });
    }

    private ItemStack itemFromBase64(String data) {
        byte[] bytes = java.util.Base64.getDecoder().decode(data);
        return ItemStack.deserializeBytes(bytes);
    }

    private final class ShopGuiSession {
        private final UUID playerId;
        private Inventory inventory;
        private final Map<Integer, Runnable> actions = new HashMap<>();
        private List<String> shopIds = new ArrayList<>();
        private int page = 0;

        private ShopGuiSession(Player player) {
            this.playerId = player.getUniqueId();
        }

        private Player player() {
            return Bukkit.getPlayer(playerId);
        }

        private void showMainMenu() {
            Player player = player();
            if (player == null) return;
            Inventory menu = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 27,
                    ChatColor.DARK_AQUA + "Shop Manager");
            menu.setItem(11, button(Material.CHEST, ChatColor.GOLD + "My Shops",
                    ChatColor.GRAY + "View and edit owned shops"));
            actions.clear();
            actions.put(11, this::openShopList);
            menu.setItem(15, button(Material.BOOK, ChatColor.GREEN + "Help",
                    ChatColor.GRAY + "Use /le shop help for details"));
            this.inventory = menu;
            player.openInventory(menu);
        }

        private void reopen() {
            Player player = player();
            if (player == null) return;
            if (inventory != null) {
                player.openInventory(inventory);
            } else {
                showMainMenu();
            }
        }

        private void execute(int slot) {
            Runnable action = actions.get(slot);
            if (action != null) {
                action.run();
            }
        }

        private void openShopList() {
            Player player = player();
            if (player == null) return;
            Inventory loading = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 27,
                    ChatColor.GOLD + "Loading shops...");
            loading.setItem(13, button(Material.CLOCK, ChatColor.YELLOW + "Loading..."));
            player.openInventory(loading);
            this.inventory = loading;
            actions.clear();
            requestOwnedShops(player, ids -> Bukkit.getScheduler().runTask(plugin, () -> {
                shopIds = ids;
                page = 0;
                renderShopList();
            }), message -> Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(message);
                showMainMenu();
            }));
        }

        private void renderShopList() {
            Player player = player();
            if (player == null) return;
            Inventory listInv = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 54,
                    ChatColor.GOLD + "Your Shops");
            actions.clear();
            listInv.setItem(45, button(Material.ARROW, ChatColor.YELLOW + "Back"));
            actions.put(45, this::showMainMenu);
            listInv.setItem(53, button(Material.CLOCK, ChatColor.GREEN + "Refresh"));
            actions.put(53, this::openShopList);
            int start = page * 36;
            int end = Math.min(start + 36, shopIds.size());
            int slot = 0;
            if (shopIds.isEmpty()) {
                listInv.setItem(22, button(Material.BARRIER, ChatColor.RED + "No shops",
                        ChatColor.GRAY + "Create a shop with /le shop create"));
            } else {
                for (int i = start; i < end; i++) {
                    String id = shopIds.get(i);
                    listInv.setItem(slot, button(Material.CHEST, ChatColor.GOLD + id,
                            ChatColor.GRAY + "Click to open"));
                    final String shopId = id;
                    actions.put(slot, () -> openShop(shopId));
                    slot++;
                    if (slot % 9 == 0) {
                        slot += 9;
                    }
                }
            }
            if (start > 0) {
                listInv.setItem(48, button(Material.ARROW, ChatColor.YELLOW + "Previous"));
                actions.put(48, () -> {
                    page = Math.max(0, page - 1);
                    renderShopList();
                });
            }
            if (end < shopIds.size()) {
                listInv.setItem(50, button(Material.ARROW, ChatColor.YELLOW + "Next"));
                actions.put(50, () -> {
                    page = page + 1;
                    renderShopList();
                });
            }
            this.inventory = listInv;
            player.openInventory(listInv);
        }

        private void openShop(String shopId) {
            Player player = player();
            if (player == null) return;
            player.sendMessage(ChatColor.YELLOW + "Opening shop " + shopId + ChatColor.RESET);
            requestShop(player, shopId, data -> openShopInventory(player, shopId, data),
                    message -> Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(message)));
        }

        private void clear() {
            actions.clear();
            inventory = null;
        }
    }
}
