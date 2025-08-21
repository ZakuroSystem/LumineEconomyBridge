package com.grapelemon.lumineeconomybridge;

import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.Map;
import java.util.UUID;

public class LeCommandExecutor implements CommandExecutor {

    private final LumineEconomyBridge plugin;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final Gson gson = new Gson();

    public LeCommandExecutor(LumineEconomyBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get("player-only"));
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
                case "shop" -> {
                    if (!plugin.isActive() || plugin.getHttpClient() == null) {
                        p.sendMessage(Lang.get("error-unavailable"));
                        return true;
                    }
                    if (args.length >= 2 && args[1].equalsIgnoreCase("help")) {
                        p.sendMessage(ChatColor.GREEN + "/le shop create " + ChatColor.YELLOW + "<id> " + ChatColor.GRAY + "- Create a shop barrel / ショップ樽を作成");
                        p.sendMessage(ChatColor.GREEN + "/le shop add " + ChatColor.YELLOW + "<id> <qty> " + ChatColor.GRAY + "- Deposit items / 在庫追加");
                        p.sendMessage(ChatColor.GREEN + "/le shop take " + ChatColor.YELLOW + "<id> <item> <qty> " + ChatColor.GRAY + "- Withdraw stock / 在庫回収");
                        p.sendMessage(ChatColor.GREEN + "/le shop price " + ChatColor.YELLOW + "<id> <hand|item> <currency> <amount> " + ChatColor.GRAY + "- Set price / 価格設定");
                        p.sendMessage(ChatColor.GREEN + "/le shop remove " + ChatColor.YELLOW + "<id> [refund] " + ChatColor.GRAY + "- Remove shop / 撤去");
                        p.sendMessage(ChatColor.GREEN + "/le shop reopen " + ChatColor.YELLOW + "<id> " + ChatColor.GRAY + "- Reopen suspended shop / 再開");
                    } else if (args.length >= 3 && args[1].equalsIgnoreCase("create")) {
                        String shopId = args[2];
                        ItemStack barrel = new ItemStack(Material.BARREL);
                        ItemMeta meta = barrel.getItemMeta();
                        PersistentDataContainer c = meta.getPersistentDataContainer();
                        NamespacedKey keyShop = new NamespacedKey(plugin, "le_shop");
                        NamespacedKey keyId = new NamespacedKey(plugin, "shop_id");
                        c.set(keyShop, PersistentDataType.BYTE, (byte)1);
                        c.set(keyId, PersistentDataType.STRING, shopId);
                        if (meta instanceof BlockStateMeta bsm) {
                            BlockState state = bsm.getBlockState();
                            if (state instanceof TileState tile) {
                                PersistentDataContainer tc = tile.getPersistentDataContainer();
                                tc.set(keyShop, PersistentDataType.BYTE, (byte)1);
                                tc.set(keyId, PersistentDataType.STRING, shopId);
                                tile.update(true);
                                bsm.setBlockState(tile);
                            }
                        }
                        barrel.setItemMeta(meta);
                        p.getInventory().addItem(barrel);
                        p.sendMessage(ChatColor.GREEN + "Shop barrel created: " + shopId);
                    } else if (args.length >= 4 && args[1].equalsIgnoreCase("add")) {
                        String shopId = args[2];
                        int qty;
                        try { qty = Integer.parseInt(args[3]); } catch (NumberFormatException ex) { p.sendMessage(ChatColor.RED + "Invalid qty"); return true; }
                        ItemStack hand = p.getInventory().getItemInMainHand();
                        if (hand.getType() == Material.AIR) { p.sendMessage(ChatColor.RED + "Hold item in hand"); return true; }
                        if (hand.getAmount() < qty) { p.sendMessage(ChatColor.RED + "Not enough items"); return true; }
                        String blob = itemToBase64(hand);
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("owner_uuid", p.getUniqueId().toString());
                        payload.put("shop_id", shopId);
                        payload.put("nbt_blob", blob);
                        payload.put("material", hand.getType().name());
                        ItemMeta hm = hand.getItemMeta();
                        if (hm != null && hm.hasDisplayName()) payload.put("display_name", hm.getDisplayName());
                        payload.put("qty", qty);
                        Request req = new Request.Builder()
                                .url(plugin.getBaseUrl() + "/api/shop/add_stock")
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
                                            if (res.has("item_key")) {
                                                p.sendMessage(ChatColor.GREEN + "Stock added: " + res.get("item_key").getAsString());
                                            } else {
                                                p.sendMessage(ChatColor.GREEN + "Stock added");
                                            }
                                        } else {
                                            p.sendMessage(ChatColor.RED + "Failed: " + res.get("reason").getAsString());
                                        }
                                    });
                                }
                            }
                        });
                    } else if (args.length >= 5 && args[1].equalsIgnoreCase("take")) {
                        String shopId = args[2];
                        String itemKey = args[3];
                        int qty;
                        try { qty = Integer.parseInt(args[4]); } catch (NumberFormatException ex) { p.sendMessage(ChatColor.RED + "Invalid qty"); return true; }
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("owner_uuid", p.getUniqueId().toString());
                        payload.put("shop_id", shopId);
                        payload.put("item_key", itemKey);
                        payload.put("qty", qty);
                        Request req = new Request.Builder()
                                .url(plugin.getBaseUrl() + "/api/shop/take_stock")
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
                                            p.sendMessage(ChatColor.GREEN + "Stock taken");
                                        } else {
                                            p.sendMessage(ChatColor.RED + "Failed: " + res.get("reason").getAsString());
                                        }
                                    });
                                }
                            }
                        });
                    } else if (args.length >= 3 && args[1].equalsIgnoreCase("reopen")) {
                        String shopId = args[2];
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("owner_uuid", p.getUniqueId().toString());
                        payload.put("shop_id", shopId);
                        payload.put("timestamp", System.currentTimeMillis() / 1000);
                        Request req = new Request.Builder()
                                .url(plugin.getBaseUrl() + "/api/shop/reopen")
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
                                            p.sendMessage("Reopened");
                                        } else {
                                            p.sendMessage("Failed: " + res.get("reason").getAsString());
                                        }
                                    });
                                }
                            }
                        });
                    } else if (args.length >= 3 && args[1].equalsIgnoreCase("remove")) {
                        String shopId = args[2];
                        boolean refund = args.length >= 4 && args[3].equalsIgnoreCase("refund");
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("shop_id", shopId);
                        payload.put("refund", refund);
                        Request req = new Request.Builder()
                                .url(plugin.getBaseUrl() + "/api/shop/remove")
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
                                            p.sendMessage(ChatColor.GREEN + "Removed");
                                        } else {
                                            p.sendMessage(ChatColor.RED + "Failed: " + res.get("reason").getAsString());
                                        }
                                    });
                                }
                            }
                        });
                    } else if (args.length >= 6 && args[1].equalsIgnoreCase("price")) {
                        String shopId = args[2];
                        String itemKey;
                        if (args[3].equalsIgnoreCase("hand")) {
                            ItemStack hand = p.getInventory().getItemInMainHand();
                            if (hand.getType() == Material.AIR) { p.sendMessage(ChatColor.RED + "Hold item in hand"); return true; }
                            itemKey = computeItemKey(hand);
                        } else {
                            itemKey = args[3];
                        }
                        String currency = args[4];
                        int amount;
                        try { amount = Integer.parseInt(args[5]); } catch (NumberFormatException ex) { p.sendMessage(ChatColor.RED + "Invalid amount"); return true; }
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("owner_uuid", p.getUniqueId().toString());
                        payload.put("shop_id", shopId);
                        payload.put("item_key", itemKey);
                        payload.put("currency", currency);
                        payload.put("price", amount);
                        Request req = new Request.Builder()
                                .url(plugin.getBaseUrl() + "/api/shop/set_price")
                                .post(RequestBody.create(gson.toJson(payload), JSON))
                                .build();
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
                                            p.sendMessage(ChatColor.GREEN + "Price updated");
                                        } else {
                                            p.sendMessage(ChatColor.RED + "Failed: " + res.get("reason").getAsString());
                                        }
                                    });
                                }
                            }
                        });
                    } else {
                        p.sendMessage(ChatColor.YELLOW + "Usage: /le shop <create|add|take|price|remove|reopen|help> ...");
                    }
                    return true;
                }
            }
        }

        if (args.length == 0) {
            p.sendMessage(Lang.get("usage"));
            return true;
        }

        if (!plugin.isActive()) {
            p.sendMessage(Lang.get("error-unavailable"));
            return true;
        }

        ScoreboardSyncService sync = plugin.getSyncService();
        OkHttpClient http = plugin.getHttpClient();
        String baseUrl = plugin.getBaseUrl();

        sync.seed(p); // 安全に初期化

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
