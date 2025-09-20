package com.grapelemon.lumineeconomybridge;

import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.math.BigDecimal;
import java.text.DecimalFormat;

public class LeCommandExecutor implements CommandExecutor {

    private final LumineEconomyBridge plugin;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final DecimalFormat AMT_FMT = new DecimalFormat("0.###");
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public LeCommandExecutor(LumineEconomyBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get("player-only"));
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        if (plugin.requiresAdmin(sub) && !p.hasPermission("lumineeconomy.admin")
                && !sub.equalsIgnoreCase("money") && !sub.equalsIgnoreCase("currency")) {
            p.sendMessage(ChatColor.RED + "No permission" + ChatColor.RESET);
            return true;
        }

        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "rewrite" -> {
                    if (!plugin.isActive()) {
                        p.sendMessage(Lang.get("error-unavailable"));
                        return true;
                    }
                    plugin.getSyncService().rewriteAll();
                    p.sendActionBar(Lang.get("sync-requested"));
                    return true;
                }
                case "start" -> {
                    plugin.startBridge();
                    p.sendMessage(Lang.get("bridge-starting"));
                    return true;
                }
                case "stop" -> {
                    plugin.stopBridge();
                    p.sendMessage(Lang.get("bridge-stopped"));
                    return true;
                }
                case "reload" -> {
                    plugin.reloadBridge();
                    p.sendMessage(Lang.get("bridge-reloaded"));
                    return true;
                }
                case "help" -> {
                    sendHelp(p);
                    return true;
                }
                case "admin" -> {
                    if (!plugin.isActive() || plugin.getHttpClient() == null) {
                        p.sendMessage(Lang.get("error-unavailable"));
                        return true;
                    }
                    if (args.length >= 3 && args[1].equalsIgnoreCase("add")) {
                        String target = args[2];
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("name", target);
                        Request req = new Request.Builder()
                                .url(plugin.getBaseUrl() + "/api/admin/add")
                                .post(RequestBody.create(gson.toJson(payload), JSON))
                                .build();
                        plugin.getHttpClient().newCall(req).enqueue(new Callback() {
                            @Override public void onFailure(Call call, IOException ex) {
                                plugin.getLogger().warning("Add admin failed: " + ex.getMessage());
                                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
                            }

                            @Override public void onResponse(Call call, Response response) throws IOException {
                                try (response) {
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if (response.isSuccessful()) {
                                            p.sendMessage(ChatColor.GREEN + "Admin added" + ChatColor.RESET);
                                        } else {
                                            p.sendMessage(ChatColor.RED + "Failed" + ChatColor.RESET);
                                        }
                                    });
                                }
                            }
                        });
                    } else {
                        p.sendMessage(ChatColor.YELLOW + "Usage: /le admin add <player>" + ChatColor.RESET);
                    }
                    return true;
                }
                case "search" -> {
                    if (!plugin.isActive() || plugin.getHttpClient() == null) {
                        p.sendMessage(Lang.get("error-unavailable"));
                        return true;
                    }
                    if (args.length >= 2) {
                        String item = args[1];
                        HttpUrl.Builder url = HttpUrl.parse(plugin.getBaseUrl() + "/api/shops/search").newBuilder();
                        url.addQueryParameter("item", item);
                        if (args.length >= 3) url.addQueryParameter("currency", args[2]);
                        if (args.length >= 4) url.addQueryParameter("min_price", args[3]);
                        if (args.length >= 5) url.addQueryParameter("max_price", args[4]);
                        Request req = new Request.Builder().url(url.build()).get().build();
                        plugin.getHttpClient().newCall(req).enqueue(new Callback() {
                            @Override public void onFailure(Call call, IOException ex) {
                                plugin.getLogger().warning("Search failed: " + ex.getMessage());
                                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
                            }

                            @Override public void onResponse(Call call, Response response) throws IOException {
                                try (response) {
                                    String body = response.body() != null ? response.body().string() : "[]";
                                    var arr = JsonParser.parseString(body).getAsJsonArray();
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if (arr.size() == 0) {
                                            p.sendMessage(ChatColor.YELLOW + "No results / 該当なし" + ChatColor.RESET);
                                        } else {
                                            int limit = Math.min(10, arr.size());
                                            for (int i = 0; i < limit; i++) {
                                                JsonObject r = arr.get(i).getAsJsonObject();
                                                int price = r.get("price").getAsInt();
                                                String msg = ChatColor.GREEN + r.get("item").getAsString() + ChatColor.WHITE +
                                                        " @ " + ChatColor.YELLOW + formatAmount(price) + " " +
                                                        r.get("currency").getAsString() + ChatColor.WHITE + " - " +
                                                        ChatColor.AQUA + r.get("shop_id").getAsString() + ChatColor.WHITE +
                                                        " (" + r.get("world").getAsString() + " " + r.get("x").getAsInt() +
                                                        "," + r.get("y").getAsInt() + "," + r.get("z").getAsInt() + ")";
                                                p.sendMessage(msg);
                                            }
                                        }
                                    });
                                }
                            }
                        });
                    } else {
                        p.sendMessage(ChatColor.YELLOW + "Usage: /le search <item> [currency] [min] [max]" + ChatColor.RESET);
                    }
                    return true;
                }
                case "cash" -> {
                    if (!plugin.isActive() || plugin.getCashService() == null) {
                        p.sendMessage(Lang.get("error-unavailable"));
                        return true;
                    }
                    if (args.length >= 3 && args[1].equalsIgnoreCase("issue")) {
                        int amt;
                        try {
                            amt = parseAmount(args[2]);
                        } catch (NumberFormatException ex) {
                            p.sendMessage(ChatColor.RED + "Invalid amount" + ChatColor.RESET);
                            return true;
                        }
                        String currency = args.length >= 4 ? args[3] : "thy";
                        ItemStack note = plugin.getCashService().issue(p, currency, amt);
                        p.getInventory().addItem(note);
                        p.sendMessage(ChatColor.GREEN + "Issued note" + ChatColor.RESET);
                    } else {
                        p.sendMessage(ChatColor.YELLOW + "Usage: /le cash issue <amount> [currency]" + ChatColor.RESET);
                    }
                    return true;
                }
                case "shop" -> {
                    if (!plugin.isActive() || plugin.getHttpClient() == null) {
                        p.sendMessage(Lang.get("error-unavailable"));
                        return true;
                    }
                    if (args.length == 1) {
                        p.sendMessage(ChatColor.YELLOW + "Usage: /le shop create <id>" + ChatColor.RESET);
                        p.sendMessage(ChatColor.GRAY + "Use /le shop help for more commands" + ChatColor.RESET);
                        return true;
                    } else if (args.length >= 2 && args[1].equalsIgnoreCase("help")) {
                        p.sendMessage(ChatColor.GREEN + "/le shop create " + ChatColor.YELLOW + "<id> " + ChatColor.GRAY + "- Create a shop barrel / ショップ樽を作成");
                        p.sendMessage(ChatColor.GREEN + "/le shop add " + ChatColor.YELLOW + "<id> <qty> <price> <name> " + ChatColor.GRAY + "- Deposit item / 在庫追加");
                        p.sendMessage(ChatColor.GREEN + "/le shop take " + ChatColor.YELLOW + "<id> <item> <qty> " + ChatColor.GRAY + "- Withdraw stock / 在庫回収");
                        p.sendMessage(ChatColor.GREEN + "/le shop price " + ChatColor.YELLOW + "<id> <name> <currency> <amount> [<currency> <amount>...] " + ChatColor.GRAY + "- Set price / 価格設定");
                        p.sendMessage(ChatColor.GREEN + "/le shop remove " + ChatColor.YELLOW + "<id> <name> [refund] " + ChatColor.GRAY + "- Remove item / 在庫削除");
                        p.sendMessage(ChatColor.GREEN + "/le shop remove " + ChatColor.YELLOW + "<id> [refund] " + ChatColor.GRAY + "- Remove shop / 撤去");
                        p.sendMessage(ChatColor.GREEN + "/le shop reopen " + ChatColor.YELLOW + "<id> " + ChatColor.GRAY + "- Reopen suspended shop / 再開");
                        p.sendMessage(ChatColor.GREEN + "/le shop partner add " + ChatColor.YELLOW + "<id> <player> " + ChatColor.GRAY + "- Add co-owner / 共同オーナー追加");
                        p.sendMessage(ChatColor.GREEN + "/le shop partner remove " + ChatColor.YELLOW + "<id> <player> " + ChatColor.GRAY + "- Remove co-owner / 共同オーナー削除");
                        p.sendMessage(ChatColor.GREEN + "/le shop account " + ChatColor.YELLOW + "<id> <company> " + ChatColor.GRAY + "- Set payout account / 取引口座設定");
                        p.sendMessage(ChatColor.GREEN + "/le shop hopper " + ChatColor.YELLOW + "<id> <slot> " + ChatColor.GRAY + "- Issue hopper / ホッパー付与");
                        p.sendMessage(ChatColor.GREEN + "/le shop publish " + ChatColor.YELLOW + "<id> " + ChatColor.GRAY + "- List shop / 掲載");
                        p.sendMessage(ChatColor.GREEN + "/le shop hide " + ChatColor.YELLOW + "<id> " + ChatColor.GRAY + "- Unlist shop / 非掲載");
                        p.sendMessage(ChatColor.GREEN + "/le shop search " + ChatColor.YELLOW + "<item> [currency] [min] [max]" + ChatColor.GRAY + "- Search shops / 検索");
                    } else if (args.length >= 3 && args[1].equalsIgnoreCase("create")) {
                        if (args.length != 3) {
                            p.sendMessage(ChatColor.YELLOW + "Usage: /le shop create <id>" + ChatColor.RESET);
                            return true;
                        }
                        String shopId = args[2];
                        String ownerUuid = p.getUniqueId().toString();
                        if (!canCreateShop(ownerUuid, shopId)) {
                            p.sendMessage(ChatColor.RED + "Shop ID unavailable / 使用できません" + ChatColor.RESET);
                            return true;
                        }
                        ItemStack barrel = new ItemStack(Material.BARREL);
                        ItemMeta meta = barrel.getItemMeta();
                        PersistentDataContainer c = meta.getPersistentDataContainer();
                        NamespacedKey keyShop = new NamespacedKey(plugin, "le_shop");
                        NamespacedKey keyId = new NamespacedKey(plugin, "shop_id");
                        NamespacedKey keyOwner = new NamespacedKey(plugin, "owner_uuid");
                        c.set(keyShop, PersistentDataType.BYTE, (byte)1);
                        c.set(keyId, PersistentDataType.STRING, shopId);
                        c.set(keyOwner, PersistentDataType.STRING, ownerUuid);
                        if (meta instanceof BlockStateMeta bsm) {
                            BlockState state = bsm.getBlockState();
                            if (state instanceof TileState tile) {
                                PersistentDataContainer tc = tile.getPersistentDataContainer();
                                tc.set(keyShop, PersistentDataType.BYTE, (byte)1);
                                tc.set(keyId, PersistentDataType.STRING, shopId);
                                tc.set(keyOwner, PersistentDataType.STRING, ownerUuid);
                                tile.update(true);
                                bsm.setBlockState(tile);
                            }
                        }
                        barrel.setItemMeta(meta);
                        p.getInventory().addItem(barrel);
                        args = new String[]{"shop", "quick", shopId};
                    } else if (args.length >= 4 && args[1].equalsIgnoreCase("hopper")) {
                        if (args.length != 4) {
                            p.sendMessage(ChatColor.YELLOW + "Usage: /le shop hopper <id> <slots>" + ChatColor.RESET);
                            return true;
                        }
                        String shopId = args[2];
                        String slotArg = args[3];
                        String[] parts = slotArg.split(",");
                        Set<Integer> slotIndices = new LinkedHashSet<>();
                        for (String part : parts) {
                            String trimmed = part.trim();
                            if (trimmed.isEmpty()) continue;
                            try {
                                slotIndices.add(Integer.parseInt(trimmed));
                            } catch (NumberFormatException ex) {
                                p.sendMessage(ChatColor.RED + "Invalid slot / スロット番号が不正です" + ChatColor.RESET);
                                return true;
                            }
                        }
                        if (slotIndices.isEmpty()) {
                            p.sendMessage(ChatColor.RED + "Invalid slot / スロット番号が不正です" + ChatColor.RESET);
                            return true;
                        }
                        JsonObject shopData = fetchShop(shopId);
                        if (shopData == null) {
                            p.sendMessage(ChatColor.RED + "Unable to fetch shop / ショップ情報を取得できません" + ChatColor.RESET);
                            return true;
                        }
                        if (!isDirectOwner(p, shopData)) {
                            p.sendMessage(ChatColor.RED + "Not your shop / 自分のショップではありません" + ChatColor.RESET);
                            return true;
                        }
                        if (!shopData.has("items")) {
                            p.sendMessage(ChatColor.RED + "No items available / アイテムがありません" + ChatColor.RESET);
                            return true;
                        }
                        JsonArray items = shopData.getAsJsonArray("items");
                        List<Integer> orderedSlots = new ArrayList<>(slotIndices);
                        List<String> itemKeys = new ArrayList<>();
                        for (int slotIndex : orderedSlots) {
                            if (slotIndex < 0 || slotIndex >= items.size()) {
                                p.sendMessage(ChatColor.RED + "Slot out of range / スロット番号が不正です" + ChatColor.RESET);
                                return true;
                            }
                            JsonObject item = items.get(slotIndex).getAsJsonObject();
                            itemKeys.add(item.get("item_key").getAsString());
                        }
                        String ownerUuid = p.getUniqueId().toString();
                        NamespacedKey hopperKey = new NamespacedKey(plugin, "le_shop_hopper");
                        NamespacedKey keyId = new NamespacedKey(plugin, "shop_id");
                        NamespacedKey keyOwner = new NamespacedKey(plugin, "owner_uuid");
                        NamespacedKey keySlot = new NamespacedKey(plugin, "le_shop_hopper_slot");
                        NamespacedKey keyItem = new NamespacedKey(plugin, "le_shop_hopper_item");
                        ItemStack hopper = new ItemStack(Material.HOPPER);
                        ItemMeta meta = hopper.getItemMeta();
                        PersistentDataContainer container = meta.getPersistentDataContainer();
                        container.set(hopperKey, PersistentDataType.BYTE, (byte) 1);
                        container.set(keyId, PersistentDataType.STRING, shopId);
                        container.set(keyOwner, PersistentDataType.STRING, ownerUuid);
                        String slotList = orderedSlots.stream().map(String::valueOf).collect(Collectors.joining(","));
                        container.set(keySlot, PersistentDataType.STRING, slotList);
                        container.set(keyItem, PersistentDataType.STRING, String.join(",", itemKeys));
                        meta.setDisplayName(ChatColor.GOLD + "Shop Hopper" + ChatColor.RESET);
                        hopper.setItemMeta(meta);
                        p.getInventory().addItem(hopper);
                        p.sendMessage(ChatColor.GREEN + "Issued hopper for slots " + ChatColor.YELLOW + slotList + ChatColor.GREEN + " / ホッパーを付与しました" + ChatColor.RESET);
                    } else if (args.length >= 6 && args[1].equalsIgnoreCase("add")) {
                        String shopId = args[2];
                        if (!hasShopPermission(p, shopId)) {
                            p.sendMessage(ChatColor.RED + "No permission" + ChatColor.RESET);
                            return true;
                        }
                        int qty;
                        int price;
                        try { qty = Integer.parseInt(args[3]); } catch (NumberFormatException ex) { p.sendMessage(ChatColor.RED + "Invalid quantity / 数量が不正です" + ChatColor.RESET); return true; }
                        try { price = parseAmount(args[4]); } catch (NumberFormatException ex) { p.sendMessage(ChatColor.RED + "Invalid price / 価格が不正です" + ChatColor.RESET); return true; }
                        String saleName = String.join(" ", java.util.Arrays.copyOfRange(args,5,args.length));
                        ItemStack hand = p.getInventory().getItemInMainHand();
                        if (hand.getType() == Material.AIR) { p.sendMessage(ChatColor.RED + "Hold item in hand / 手にアイテムを持ってください" + ChatColor.RESET); return true; }
                        if (hand.getAmount() < qty) { p.sendMessage(ChatColor.RED + "Not enough items / アイテムが不足しています" + ChatColor.RESET); return true; }
                        String blob = itemToBase64(hand);
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("owner_uuid", p.getUniqueId().toString());
                        payload.put("shop_id", shopId);
                        payload.put("nbt_blob", blob);
                        payload.put("material", hand.getType().name());
                        ItemMeta hm = hand.getItemMeta();
                        if (hm != null && hm.hasDisplayName()) payload.put("display_name", hm.getDisplayName());
                        payload.put("qty", qty);
                        payload.put("price", price);
                        payload.put("sale_name", saleName);
                        Request req = new Request.Builder()
                                .url(plugin.getBaseUrl() + "/api/shop/add_stock")
                                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                                .post(RequestBody.create(gson.toJson(payload), JSON))
                                .build();
                        plugin.getHttpClient().newCall(req).enqueue(new Callback() {
                            @Override public void onFailure(Call call, IOException ex) {
                                plugin.getLogger().warning("Add stock failed: " + ex.getMessage());
                                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
                            }
                            @Override public void onResponse(Call call, Response response) throws IOException {
                                try (response) {
                                    String body = response.body() != null ? response.body().string() : "{}";
                                    JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if ("success".equals(res.get("status").getAsString())) {
                                            hand.setAmount(hand.getAmount() - qty);
                                            p.getInventory().setItemInMainHand(hand.getAmount() > 0 ? hand : null);
                                            p.sendMessage(ChatColor.GREEN + "Stock added: " + ChatColor.YELLOW + saleName + ChatColor.GREEN + " / 在庫を追加しました" + ChatColor.RESET);
                                        } else {
                                            p.sendMessage(ChatColor.RED + "Failed: " + ChatColor.YELLOW + res.get("reason").getAsString() + ChatColor.RED + " / 失敗しました" + ChatColor.RESET);
                                        }
                                    });
                                }
                            }
                        });
                    } else if (args.length >= 5 && args[1].equalsIgnoreCase("take")) {
                        String shopId = args[2];
                        if (!hasShopPermission(p, shopId)) {
                            p.sendMessage(ChatColor.RED + "No permission" + ChatColor.RESET);
                            return true;
                        }
                        String itemKey = args[3];
                        int qty;
                        try { qty = Integer.parseInt(args[4]); } catch (NumberFormatException ex) { p.sendMessage(ChatColor.RED + "Invalid quantity / 数量が不正です" + ChatColor.RESET); return true; }
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("owner_uuid", p.getUniqueId().toString());
                        payload.put("shop_id", shopId);
                        payload.put("item_key", itemKey);
                        payload.put("qty", qty);
                        Request req = new Request.Builder()
                                .url(plugin.getBaseUrl() + "/api/shop/take_stock")
                                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                                .post(RequestBody.create(gson.toJson(payload), JSON))
                                .build();
                        plugin.getHttpClient().newCall(req).enqueue(new Callback() {
                            @Override public void onFailure(Call call, IOException ex) {
                                plugin.getLogger().warning("Take stock failed: " + ex.getMessage());
                                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
                            }
                            @Override public void onResponse(Call call, Response response) throws IOException {
                                try (response) {
                                    String body = response.body() != null ? response.body().string() : "{}";
                                    JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if ("success".equals(res.get("status").getAsString())) {
                                            if (res.has("grant")) {
                                                res.getAsJsonArray("grant").forEach(g -> {
                                                    JsonObject gg = g.getAsJsonObject();
                                                    String token = gg.get("grant_token").getAsString();
                                                    if (plugin.consumeGrantToken(token)) {
                                                        ItemStack item = itemFromBase64(gg.get("nbt_blob").getAsString());
                                                        item.setAmount(gg.get("qty").getAsInt());
                                                        p.getInventory().addItem(item);
                                                    }
                                                });
                                            }
                                            p.sendMessage(ChatColor.GREEN + "Stock taken / 在庫を回収しました" + ChatColor.RESET);
                                        } else {
                                            p.sendMessage(ChatColor.RED + "Failed: " + ChatColor.YELLOW + res.get("reason").getAsString() + ChatColor.RED + " / 失敗しました" + ChatColor.RESET);
                                        }
                                    });
                                }
                            }
                        });
                    } else if (args.length >= 5 && args[1].equalsIgnoreCase("partner")) {
                        String action = args[2];
                        String shopId = args[3];
                        if (!hasShopPermission(p, shopId)) {
                            p.sendMessage(ChatColor.RED + "No permission" + ChatColor.RESET);
                            return true;
                        }
                        String target = args[4];
                        java.util.UUID uuid = Bukkit.getOfflinePlayer(target).getUniqueId();
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("owner_uuid", p.getUniqueId().toString());
                        payload.put("shop_id", shopId);
                        payload.put("target_uuid", uuid.toString());
                        String path = action.equalsIgnoreCase("add") ? "/api/shop/add_owner" : "/api/shop/remove_owner";
                        Request req = new Request.Builder()
                                .url(plugin.getBaseUrl() + path)
                                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                                .post(RequestBody.create(gson.toJson(payload), JSON))
                                .build();
                        plugin.getHttpClient().newCall(req).enqueue(new Callback() {
                            @Override public void onFailure(Call call, IOException ex) {
                                plugin.getLogger().warning("Partner failed: " + ex.getMessage());
                                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
                            }
                            @Override public void onResponse(Call call, Response response) throws IOException {
                                try (response) {
                                    String body = response.body() != null ? response.body().string() : "{}";
                                    JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if ("success".equals(res.get("status").getAsString())) {
                                            p.sendMessage(ChatColor.GREEN + "Done / 完了しました" + ChatColor.RESET);
                                        } else {
                                            p.sendMessage(ChatColor.RED + "Failed / 失敗しました" + ChatColor.RESET);
                                        }
                                    });
                                }
                            }
                        });
                    } else if (args.length >= 4 && args[1].equalsIgnoreCase("account")) {
                        String shopId = args[2];
                        if (!hasShopPermission(p, shopId)) {
                            p.sendMessage(ChatColor.RED + "No permission" + ChatColor.RESET);
                            return true;
                        }
                        String accountId = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).trim();
                        if (accountId.isEmpty()) {
                            p.sendMessage(ChatColor.YELLOW + "Usage: /le shop account <id> <company>" + ChatColor.RESET);
                            return true;
                        }
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("owner_uuid", p.getUniqueId().toString());
                        payload.put("shop_id", shopId);
                        payload.put("account_id", accountId);
                        Request req = new Request.Builder()
                                .url(plugin.getBaseUrl() + "/api/shop/account")
                                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                                .post(RequestBody.create(gson.toJson(payload), JSON))
                                .build();
                        plugin.getHttpClient().newCall(req).enqueue(new Callback() {
                            @Override public void onFailure(Call call, IOException ex) {
                                plugin.getLogger().warning("Account update failed: " + ex.getMessage());
                                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
                            }

                            @Override public void onResponse(Call call, Response response) throws IOException {
                                try (response) {
                                    String body = response.body() != null ? response.body().string() : "{}";
                                    JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        String status = res.has("status") ? res.get("status").getAsString() : "error";
                                        if ("success".equalsIgnoreCase(status)) {
                                            p.sendMessage(ChatColor.GREEN + "Shop account updated / 取引口座を更新しました" + ChatColor.RESET);
                                        } else {
                                            String reason = res.has("reason") && !res.get("reason").isJsonNull()
                                                    ? res.get("reason").getAsString() : "unknown";
                                            String display;
                                            switch (reason) {
                                                case "not_owner" -> display = "No permission / 権限がありません";
                                                case "invalid_account" -> display = "Account not found / 口座が存在しません";
                                                case "no_access" -> display = "Account access not delegated / 利用権がありません";
                                                default -> display = "Failed / 失敗しました";
                                            }
                                            p.sendMessage(ChatColor.RED + display + ChatColor.RESET);
                                        }
                                    });
                                }
                            }
                        });
                    } else if (args.length >= 3 && args[1].equalsIgnoreCase("reopen")) {
                        String shopId = args[2];
                        if (!hasShopPermission(p, shopId)) {
                            p.sendMessage(ChatColor.RED + "No permission" + ChatColor.RESET);
                            return true;
                        }
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("owner_uuid", p.getUniqueId().toString());
                        payload.put("shop_id", shopId);
                        payload.put("timestamp", System.currentTimeMillis() / 1000);
                        Request req = new Request.Builder()
                                .url(plugin.getBaseUrl() + "/api/shop/reopen")
                                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                                .post(RequestBody.create(gson.toJson(payload), JSON))
                                .build();
                        plugin.getHttpClient().newCall(req).enqueue(new Callback() {
                            @Override public void onFailure(Call call, IOException ex) {
                                plugin.getLogger().warning("Reopen failed: " + ex.getMessage());
                                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
                            }
                            @Override public void onResponse(Call call, Response response) throws IOException {
                                try (response) {
                                    String body = response.body() != null ? response.body().string() : "{}";
                                    JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if ("success".equals(res.get("status").getAsString())) {
                                            p.sendMessage(ChatColor.GREEN + "Reopened / 再開しました" + ChatColor.RESET);
                                        } else {
                                            p.sendMessage(ChatColor.RED + "Failed: " + ChatColor.YELLOW + res.get("reason").getAsString() + ChatColor.RED + " / 失敗しました" + ChatColor.RESET);
                                        }
                                    });
                                }
                            }
                        });
                    } else if (args.length >= 3 && args[1].equalsIgnoreCase("publish")) {
                        String shopId = args[2];
                        if (!hasShopPermission(p, shopId)) {
                            p.sendMessage(ChatColor.RED + "No permission" + ChatColor.RESET);
                            return true;
                        }
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("owner_uuid", p.getUniqueId().toString());
                        payload.put("shop_id", shopId);
                        payload.put("listed", true);
                        Request req = new Request.Builder()
                                .url(plugin.getBaseUrl() + "/api/shop/listing")
                                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                                .post(RequestBody.create(gson.toJson(payload), JSON))
                                .build();
                        plugin.getHttpClient().newCall(req).enqueue(new Callback() {
                            @Override public void onFailure(Call call, IOException ex) {
                                plugin.getLogger().warning("Listing failed: " + ex.getMessage());
                                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
                            }
                            @Override public void onResponse(Call call, Response response) throws IOException {
                                try (response) {
                                    String body = response.body() != null ? response.body().string() : "{}";
                                    JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if ("success".equals(res.get("status").getAsString())) {
                                            p.sendMessage(ChatColor.GREEN + "Shop listed / 掲載しました" + ChatColor.RESET);
                                        } else {
                                            p.sendMessage(ChatColor.RED + "Failed / 失敗しました" + ChatColor.RESET);
                                        }
                                    });
                                }
                            }
                        });
                    } else if (args.length >= 3 && args[1].equalsIgnoreCase("hide")) {
                        String shopId = args[2];
                        if (!hasShopPermission(p, shopId)) {
                            p.sendMessage(ChatColor.RED + "No permission" + ChatColor.RESET);
                            return true;
                        }
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("owner_uuid", p.getUniqueId().toString());
                        payload.put("shop_id", shopId);
                        payload.put("listed", false);
                        Request req = new Request.Builder()
                                .url(plugin.getBaseUrl() + "/api/shop/listing")
                                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                                .post(RequestBody.create(gson.toJson(payload), JSON))
                                .build();
                        plugin.getHttpClient().newCall(req).enqueue(new Callback() {
                            @Override public void onFailure(Call call, IOException ex) {
                                plugin.getLogger().warning("Listing failed: " + ex.getMessage());
                                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
                            }
                            @Override public void onResponse(Call call, Response response) throws IOException {
                                try (response) {
                                    String body = response.body() != null ? response.body().string() : "{}";
                                    JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if ("success".equals(res.get("status").getAsString())) {
                                            p.sendMessage(ChatColor.GREEN + "Shop hidden / 非掲載にしました" + ChatColor.RESET);
                                        } else {
                                            p.sendMessage(ChatColor.RED + "Failed / 失敗しました" + ChatColor.RESET);
                                        }
                                    });
                                }
                            }
                        });
                    } else if (args.length >= 3 && args[1].equalsIgnoreCase("search")) {
                        String item = args[2];
                        HttpUrl.Builder url = HttpUrl.parse(plugin.getBaseUrl() + "/api/shops/search").newBuilder();
                        url.addQueryParameter("item", item);
                        if (args.length >= 4) url.addQueryParameter("currency", args[3]);
                        if (args.length >= 5) url.addQueryParameter("min_price", args[4]);
                        if (args.length >= 6) url.addQueryParameter("max_price", args[5]);
                        Request req = new Request.Builder().url(url.build()).get().build();
                        plugin.getHttpClient().newCall(req).enqueue(new Callback() {
                            @Override public void onFailure(Call call, IOException ex) {
                                plugin.getLogger().warning("Search failed: " + ex.getMessage());
                                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
                            }
                            @Override public void onResponse(Call call, Response response) throws IOException {
                                try (response) {
                                    String body = response.body() != null ? response.body().string() : "[]";
                                    var arr = JsonParser.parseString(body).getAsJsonArray();
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if (arr.size() == 0) {
                                            p.sendMessage(ChatColor.YELLOW + "No results / 該当なし" + ChatColor.RESET);
                                        } else {
                                            int limit = Math.min(10, arr.size());
                                            for (int i = 0; i < limit; i++) {
                                                JsonObject r = arr.get(i).getAsJsonObject();
                                                int price = r.get("price").getAsInt();
                                                String msg = ChatColor.GREEN + r.get("item").getAsString() + ChatColor.WHITE + " @ " + ChatColor.YELLOW + formatAmount(price) + " " + r.get("currency").getAsString() + ChatColor.WHITE + " - " + ChatColor.AQUA + r.get("shop_id").getAsString() + ChatColor.WHITE + " (" + r.get("world").getAsString() + " " + r.get("x").getAsInt() + "," + r.get("y").getAsInt() + "," + r.get("z").getAsInt() + ")";
                                                p.sendMessage(msg);
                                            }
                                        }
                                    });
                                }
                            }
                        });
                    } else if (args.length >= 3 && args[1].equalsIgnoreCase("remove")) {
                        String shopId = args[2];
                        if (!hasShopPermission(p, shopId)) {
                            p.sendMessage(ChatColor.RED + "No permission" + ChatColor.RESET);
                            return true;
                        }
                        if (args.length >= 4 && !args[3].equalsIgnoreCase("refund")) {
                            String saleName = args[3];
                            boolean refund = args.length >= 5 && args[4].equalsIgnoreCase("refund");
                            Map<String, Object> payload = new HashMap<>();
                            payload.put("owner_uuid", p.getUniqueId().toString());
                            payload.put("shop_id", shopId);
                            payload.put("sale_name", saleName);
                            payload.put("refund", refund);
                            Request req = new Request.Builder()
                                    .url(plugin.getBaseUrl() + "/api/shop/remove_item")
                                    .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                                    .post(RequestBody.create(gson.toJson(payload), JSON))
                                    .build();
                            plugin.getHttpClient().newCall(req).enqueue(new Callback() {
                                @Override public void onFailure(Call call, IOException ex) {
                                    plugin.getLogger().warning("Remove item failed: " + ex.getMessage());
                                    Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
                                }
                                @Override public void onResponse(Call call, Response response) throws IOException {
                                    try (response) {
                                        String body = response.body() != null ? response.body().string() : "{}";
                                        JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                                        Bukkit.getScheduler().runTask(plugin, () -> {
                                            if ("success".equals(res.get("status").getAsString())) {
                                                if (refund && res.has("grant")) {
                                                    res.getAsJsonArray("grant").forEach(g -> {
                                                        JsonObject gg = g.getAsJsonObject();
                                                        String token = gg.get("grant_token").getAsString();
                                                        if (plugin.consumeGrantToken(token)) {
                                                            ItemStack item = itemFromBase64(gg.get("nbt_blob").getAsString());
                                                            item.setAmount(gg.get("qty").getAsInt());
                                                            p.getInventory().addItem(item);
                                                        }
                                                    });
                                                }
                                                p.sendMessage(ChatColor.GREEN + "Item removed: " + ChatColor.YELLOW + saleName + ChatColor.GREEN + " / 在庫を削除しました" + ChatColor.RESET);
                                            } else {
                                                p.sendMessage(ChatColor.RED + "Failed: " + ChatColor.YELLOW + res.get("reason").getAsString() + ChatColor.RED + " / 失敗しました" + ChatColor.RESET);
                                            }
                                        });
                                    }
                                }
                            });
                        } else {
                            boolean refund = args.length >= 4 && args[3].equalsIgnoreCase("refund");
                            Map<String, Object> payload = new HashMap<>();
                            payload.put("owner_uuid", p.getUniqueId().toString());
                            payload.put("shop_id", shopId);
                            payload.put("refund", refund);
                            Request req = new Request.Builder()
                                    .url(plugin.getBaseUrl() + "/api/shop/remove")
                                    .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                                    .post(RequestBody.create(gson.toJson(payload), JSON))
                                    .build();
                            plugin.getHttpClient().newCall(req).enqueue(new Callback() {
                                @Override public void onFailure(Call call, IOException ex) {
                                    plugin.getLogger().warning("Remove failed: " + ex.getMessage());
                                    Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
                                }
                                @Override public void onResponse(Call call, Response response) throws IOException {
                                    try (response) {
                                        String body = response.body() != null ? response.body().string() : "{}";
                                        JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                                        Bukkit.getScheduler().runTask(plugin, () -> {
                                            if ("success".equals(res.get("status").getAsString())) {
                                                if (refund && res.has("grant")) {
                                                    res.getAsJsonArray("grant").forEach(g -> {
                                                        JsonObject gg = g.getAsJsonObject();
                                                        String token = gg.get("grant_token").getAsString();
                                                        if (plugin.consumeGrantToken(token)) {
                                                            ItemStack item = itemFromBase64(gg.get("nbt_blob").getAsString());
                                                            item.setAmount(gg.get("qty").getAsInt());
                                                            p.getInventory().addItem(item);
                                                        }
                                                    });
                                                }
                                                if (res.has("location")) {
                                                    JsonObject loc = res.getAsJsonObject("location");
                                                    String world = loc.get("world").getAsString();
                                                    int x = loc.get("x").getAsInt();
                                                    int y = loc.get("y").getAsInt();
                                                    int z = loc.get("z").getAsInt();
                                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                                        var w = Bukkit.getWorld(world);
                                                        if (w != null) {
                                                            w.getBlockAt(x, y, z).setType(Material.AIR);
                                                        }
                                                    });
                                                }
                                                p.sendMessage(ChatColor.GREEN + "Removed / 撤去しました" + ChatColor.RESET);
                                            } else {
                                                p.sendMessage(ChatColor.RED + "Failed: " + ChatColor.YELLOW + res.get("reason").getAsString() + ChatColor.RED + " / 失敗しました" + ChatColor.RESET);
                                            }
                                        });
                                    }
                                }
                            });
                        }
                    } else if (args.length >= 6 && args[1].equalsIgnoreCase("price")) {
                        String shopId = args[2];
                        if (!hasShopPermission(p, shopId)) {
                            p.sendMessage(ChatColor.RED + "No permission" + ChatColor.RESET);
                            return true;
                        }
                        String saleName = args[3];
                        if ((args.length - 4) % 2 != 0) {
                            p.sendMessage(ChatColor.RED + "Usage: /le shop price <id> <name> <currency> <amount> [<currency> <amount>...] / 使い方: /le shop price <id> <name> <currency> <amount> [<currency> <amount>...]" + ChatColor.RESET);
                            return true;
                        }
                        for (int i = 4; i < args.length; i += 2) {
                            String currency = args[i];
                            int amount;
                            try { amount = parseAmount(args[i + 1]); } catch (NumberFormatException ex) { p.sendMessage(ChatColor.RED + "Invalid amount / 無効な金額です" + ChatColor.RESET); return true; }
                            Map<String, Object> payload = new HashMap<>();
                            payload.put("owner_uuid", p.getUniqueId().toString());
                            payload.put("shop_id", shopId);
                            payload.put("sale_name", saleName);
                            payload.put("currency", currency);
                            payload.put("price", amount);
                            Request req = new Request.Builder()
                                    .url(plugin.getBaseUrl() + "/api/shop/set_price")
                                    .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                                    .post(RequestBody.create(gson.toJson(payload), JSON))
                                    .build();
                            final String fCurrency = currency;
                            final int fAmount = amount;
                            plugin.getHttpClient().newCall(req).enqueue(new Callback() {
                                @Override public void onFailure(Call call, IOException ex) {
                                    plugin.getLogger().warning("Set price failed: " + ex.getMessage());
                                    Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Lang.get("error-unavailable")));
                                }
                                @Override public void onResponse(Call call, Response response) throws IOException {
                                    try (response) {
                                        String body = response.body() != null ? response.body().string() : "{}";
                                        JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                                        Bukkit.getScheduler().runTask(plugin, () -> {
                                            if ("success".equals(res.get("status").getAsString())) {
                                                p.sendMessage(ChatColor.GREEN + "Price updated / 価格を更新しました" + ChatColor.RESET);
                                            Inventory top = p.getOpenInventory().getTopInventory();
                                                if (top.getHolder() instanceof com.grapelemon.lumineeconomybridge.shop.ShopMenuHolder holder && holder.getShopId().equals(shopId)) {
                                                    for (Map.Entry<Integer, com.grapelemon.lumineeconomybridge.shop.ShopItem> en : holder.getItems().entrySet()) {
                                                        if (en.getValue().getSaleName().equals(saleName)) {
                                                            en.getValue().getPrices().put(fCurrency, fAmount);
                                                            ItemStack stack = top.getItem(en.getKey());
                                                            if (stack != null) {
                                                                ItemMeta meta = stack.getItemMeta();
                                                                java.util.List<String> lore = new java.util.ArrayList<>();
                                                                lore.add(ChatColor.GREEN + "Name: " + ChatColor.YELLOW + en.getValue().getSaleName());
                                                                lore.add(ChatColor.GREEN + "Stock: " + ChatColor.YELLOW + en.getValue().getStock());
                                                                for (Map.Entry<String, Integer> pp : en.getValue().getPrices().entrySet()) {
                                                                    lore.add(ChatColor.GREEN + pp.getKey() + ChatColor.WHITE + ": " + ChatColor.YELLOW + formatAmount(pp.getValue()));
                                                                }
                                                                meta.setLore(lore);
                                                                stack.setItemMeta(meta);
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                p.sendMessage(ChatColor.RED + "Failed: " + ChatColor.YELLOW + res.get("reason").getAsString() + ChatColor.RED + " / 失敗しました" + ChatColor.RESET);
                                            }
                                        });
                                    }
                                }
                            });
                        }
                    } else {
                        p.sendMessage(ChatColor.YELLOW + "Usage: /le shop <create|add|take|price|remove|reopen|help> ... / 使い方: /le shop <create|add|take|price|remove|reopen|help> ..." + ChatColor.RESET);
                    }
                    return true;
                }
            }
        }

        if (args.length == 0) {
            sendHelp(p);
            return true;
        }

        if (!plugin.isActive()) {
            p.sendMessage(Lang.get("error-unavailable"));
            return true;
        }

        ScoreboardSyncService sync = plugin.getSyncService();
        OkHttpClient http = plugin.getHttpClient();
        String baseUrl = plugin.getBaseUrl();

        // Flush only this player's delta to keep scoreboard intact without a full rewrite
        sync.flush(p);

        Map<String, Object> payload = new HashMap<>();
        payload.put("player", p.getUniqueId().toString());
        payload.put("executor", p.getName());
        payload.put("command", "/" + String.join(" ", args));
        payload.put("timestamp", System.currentTimeMillis() / 1000);
        Location loc = p.getLocation();
        Map<String, Object> locMap = new HashMap<>();
        locMap.put("world", loc.getWorld().getName());
        locMap.put("x", loc.getX());
        locMap.put("y", loc.getY());
        locMap.put("z", loc.getZ());
        payload.put("location", locMap);

        Request req = new Request.Builder()
                .url(baseUrl + "/api/message")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();

        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                plugin.getLogger().warning("Message send failed: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin,
                        () -> p.sendMessage(Lang.get("error-unavailable")));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject res = JsonParser.parseString(body).getAsJsonObject();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                          if (res.has("messages")) {
                              res.getAsJsonArray("messages").forEach(el -> {
                                  JsonObject msg = el.getAsJsonObject();
                                  String text = msg.has("text") ? ChatColor.translateAlternateColorCodes('&', msg.get("text").getAsString()) : "";
                                  String target = msg.has("target") ? msg.get("target").getAsString() : "chat";
                                  long delay = msg.has("delay") ? msg.get("delay").getAsLong() : 0;
                                  Player recv = p;
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
                                  String subtitle = msg.has("subtitle") ? ChatColor.translateAlternateColorCodes('&', msg.get("subtitle").getAsString()) : "";
                                  Runnable task = switch (target.toLowerCase()) {
                                      case "actionbar" -> () -> finalRecv.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
                                      case "title" -> () -> finalRecv.sendTitle(text, subtitle, 10, 40, 10);
                                      default -> () -> finalRecv.sendMessage(text);
                                  };
                                  if (delay > 0) {
                                      Bukkit.getScheduler().runTaskLater(plugin, task, delay * 20L);
                                  } else {
                                      task.run();
                                  }
                              });
                          }
                        if (res.has("scoreboards")) {
                            res.getAsJsonObject("scoreboards").entrySet().forEach(en -> {
                                try {
                                    UUID pid = UUID.fromString(en.getKey());
                                    Player target = Bukkit.getPlayer(pid);
                                    if (target != null) {
                                        JsonObject sb = en.getValue().getAsJsonObject();
                                        Map<String, Integer> updates = new HashMap<>();
                                        sb.entrySet().forEach(e -> updates.put(e.getKey(), e.getValue().getAsInt()));
                                        sync.applyFromPython(target, updates);
                                    }
                                } catch (IllegalArgumentException ignored) {}
                            });
                        } else if (res.has("scoreboard")) {
                            JsonObject sb = res.getAsJsonObject("scoreboard");
                            Map<String, Integer> updates = new HashMap<>();
                            sb.entrySet().forEach(e -> updates.put(e.getKey(), e.getValue().getAsInt()));
                            sync.applyFromPython(p, updates);
                        }
                    });
                    plugin.getLogger().fine("Message handled for " + p.getName());
                }
            }
        });

        p.sendActionBar(Lang.get("send-pending"));
        return true;
    }

    private void sendHelp(Player p) {
        String[][] playerCommands = new String[][] {
                {"help", "/le help", "", "Show this help / ヘルプを表示"},
                {"wallet", "/le wallet", "", "View your balances / 自分の残高を表示"},
                {"balance", "/le balance", "[currency] [player]", "Check balances / 残高を確認"},
                {"pay", "/le pay", "<player> <amount> [currency]", "Pay another player / プレイヤーへ送金"},
                {"deposit", "/le deposit", "<src> <dst> <currency> <amount>", "Deposit funds / 入金処理"},
                {"withdraw", "/le withdraw", "<src> <dst> <currency> <amount>", "Withdraw funds / 出金処理"},
                {"transfer", "/le transfer", "<src> <dst> <currency> <amount>", "Transfer between accounts / 口座間振替"},
                {"search", "/le search", "<item> [currency] [min] [max]", "Search public shops / ショップを検索"},
                {"shop", "/le shop help", "", "Shop commands / ショップ操作一覧"},
                {"lang", "/le lang", "<locale>", "Switch plugin language / 言語を切り替え"},
                {"weblink", "/le weblink", "", "Generate web link token / Web連携トークン発行"}
        };

        String[][] adminCommands = new String[][] {
                {"rewrite", "/le rewrite", "", "Rewrite all scoreboards / 全スコアボードを再同期"},
                {"start", "/le start", "", "Start the bridge / ブリッジを開始"},
                {"stop", "/le stop", "", "Stop the bridge / ブリッジを停止"},
                {"reload", "/le reload", "", "Reload configuration / 設定を再読み込み"},
                {"admin", "/le admin add", "<player>", "Grant web admin access / ダッシュボード管理者を追加"},
                {"cash", "/le cash issue", "<amount> [currency]", "Issue paper cash / 紙幣を発行"},
                {"money", "/le money", "<give|take|pay|top> ...", "Manage balances / 残高を管理"},
                {"currency", "/le currency", "<create|supply|default|manager|tax|treasury> ...", "Manage currencies / 通貨を管理"},
                {"setbalance", "/le setbalance", "<player> <currency> <amount>", "Set a player's balance / 残高を直接設定"},
                {"history", "/le history", "<player> [limit]", "Review transactions / 取引履歴を確認"},
                {"account", "/le account", "<create|connect> ...", "Manage system accounts / システム口座を管理"},
                {"backup", "/le <backup|restore>", "[file]", "Backup or restore the database / データベースをバックアップ・復元"},
                {"undo", "/le <undo|redo>", "", "Undo or redo recent operations / 操作を取り消し・やり直し"}
        };

        p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "LumineEconomy Bridge Help" + ChatColor.RESET);
        p.sendMessage(ChatColor.GRAY + "Filtered by your permissions / 権限に応じて表示しています" + ChatColor.RESET);

        boolean printedPlayerHeader = false;
        for (String[] entry : playerCommands) {
            if (canUseCommand(p, entry[0])) {
                if (!printedPlayerHeader) {
                    p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Player Commands / プレイヤー向け" + ChatColor.RESET);
                    printedPlayerHeader = true;
                }
                sendHelpLine(p, entry[1], entry[2], entry[3]);
            }
        }

        boolean printedAdminHeader = false;
        for (String[] entry : adminCommands) {
            if (canUseCommand(p, entry[0])) {
                if (!printedAdminHeader) {
                    p.sendMessage("");
                    p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Admin Commands / 管理者向け" + ChatColor.RESET);
                    printedAdminHeader = true;
                }
                sendHelpLine(p, entry[1], entry[2], entry[3]);
            }
        }
    }

    private void sendHelpLine(Player p, String command, String args, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.GREEN).append(command);
        if (!args.isEmpty()) {
            sb.append(" ").append(ChatColor.YELLOW).append(args);
        }
        sb.append(" ").append(ChatColor.GRAY).append("- ").append(description).append(ChatColor.RESET);
        p.sendMessage(sb.toString());
    }

    private boolean canUseCommand(Player p, String commandKey) {
        if (commandKey == null || commandKey.isEmpty()) {
            return true;
        }
        if (!plugin.requiresAdmin(commandKey)) {
            return true;
        }
        if (p.hasPermission("lumineeconomy.admin")) {
            return true;
        }
        return commandKey.equalsIgnoreCase("money") || commandKey.equalsIgnoreCase("currency");
    }

    private boolean canCreateShop(String ownerUuid, String shopId) {
        OkHttpClient http = plugin.getHttpClient();
        if (http == null) return false;
        HttpUrl url = HttpUrl.parse(plugin.getBaseUrl() + "/api/shop/items").newBuilder()
                .addQueryParameter("shop_id", shopId)
                .build();
        Request req = new Request.Builder().url(url).build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                plugin.getLogger().warning("Shop ID check failed with status " + res.code());
                return true;
            }
            String body = res.body() != null ? res.body().string() : "{}";
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("owners")) {
                if (obj.getAsJsonArray("owners").size() == 0) {
                    purgeShop(shopId);
                    return true; // orphan removed, id available
                }
                for (JsonElement el : obj.getAsJsonArray("owners")) {
                    if (ownerUuid.equalsIgnoreCase(el.getAsString())) {
                        return true; // owner already has this shop
                    }
                }
                return false; // shop exists but owned by others
            }
            if (obj.has("reason") && "shop_not_found".equals(obj.get("reason").getAsString())) {
                return true; // id available
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Shop ID check failed: " + ex.getMessage());
            return true;
        }
        return true;
    }

    private boolean hasShopPermission(Player p, String shopId) {
        if (p.isOp() || p.hasPermission("lumineeconomy.admin")) return true;
        OkHttpClient http = plugin.getHttpClient();
        if (http == null) return false;
        HttpUrl url = HttpUrl.parse(plugin.getBaseUrl() + "/api/shop/items").newBuilder()
                .addQueryParameter("shop_id", shopId)
                .build();
        Request req = new Request.Builder().url(url).build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) return false;
            String body = res.body() != null ? res.body().string() : "{}";
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("owners")) {
                if (obj.getAsJsonArray("owners").size() == 0) {
                    purgeShop(shopId);
                    return false;
                }
                for (JsonElement el : obj.getAsJsonArray("owners")) {
                    if (p.getUniqueId().toString().equalsIgnoreCase(el.getAsString())) {
                        return true;
                    }
                }
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Shop permission check failed: " + ex.getMessage());
        }
        return false;
    }

    private JsonObject fetchShop(String shopId) {
        OkHttpClient http = plugin.getHttpClient();
        if (http == null) return null;
        HttpUrl url = HttpUrl.parse(plugin.getBaseUrl() + "/api/shop/items").newBuilder()
                .addQueryParameter("shop_id", shopId)
                .build();
        Request req = new Request.Builder().url(url).build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                plugin.getLogger().warning("Fetch shop failed with status " + res.code());
                return null;
            }
            String body = res.body() != null ? res.body().string() : "{}";
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (IOException ex) {
            plugin.getLogger().warning("Fetch shop failed: " + ex.getMessage());
            return null;
        }
    }

    private boolean isDirectOwner(Player p, JsonObject shopData) {
        if (shopData == null || !shopData.has("owners")) {
            return false;
        }
        String uuid = p.getUniqueId().toString();
        for (JsonElement el : shopData.getAsJsonArray("owners")) {
            if (uuid.equalsIgnoreCase(el.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private void purgeShop(String shopId) {
        OkHttpClient http = plugin.getHttpClient();
        if (http == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("shop_id", shopId);
        Request req = new Request.Builder()
                .url(plugin.getBaseUrl() + "/api/shop/remove")
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                plugin.getLogger().warning("Failed to purge shop " + shopId + ": " + res.code());
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to purge shop " + shopId + ": " + ex.getMessage());
        }
    }

    private int parseAmount(String s) throws NumberFormatException {
        BigDecimal bd = new BigDecimal(s);
        bd = bd.movePointRight(3);
        return bd.intValueExact();
    }

    private String formatAmount(int amount) {
        return AMT_FMT.format(amount / 1000.0);
    }

    private String computeItemKey(ItemStack item) {
        try {
            ItemStack clone = item.clone();
            clone.setAmount(1);
            byte[] bytes = clone.serializeAsBytes();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private String itemToBase64(ItemStack item) {
        ItemStack clone = item.clone();
        clone.setAmount(1);
        return Base64.getEncoder().encodeToString(clone.serializeAsBytes());
    }

    private ItemStack itemFromBase64(String data) {
        byte[] bytes = Base64.getDecoder().decode(data);
        return ItemStack.deserializeBytes(bytes);
    }
}
