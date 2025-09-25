package com.grapelemon.lumineeconomybridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LeTabCompleter implements TabCompleter {

    private final LumineEconomyBridge plugin;

    private static class ShopIdCache {
        List<String> ids = new ArrayList<>();
        long fetched = 0L;
    }

    private final Map<UUID, ShopIdCache> shopIdCache = new ConcurrentHashMap<>();

    private static class ItemCache {
        List<String> itemKeys = new ArrayList<>();
        List<String> saleNames = new ArrayList<>();
        long fetched = 0L;
    }

    private final Map<String, ItemCache> itemCache = new ConcurrentHashMap<>();

    public LeTabCompleter(LumineEconomyBridge plugin) {
        this.plugin = plugin;
    }

    private void refreshShopIds(Player p) {
        OkHttpClient http = plugin.getHttpClient();
        if (http == null) return;
        HttpUrl.Builder url = HttpUrl.parse(plugin.getBaseUrl() + "/api/shop/ids").newBuilder();
        if (!plugin.hasBypass(p) && !p.isOp() && !p.hasPermission("lumineeconomy.admin")) {
            url.addQueryParameter("owner_uuid", p.getUniqueId().toString());
        }
        Request req = new Request.Builder().url(url.build()).build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                    JsonArray arr = obj.has("ids") ? obj.getAsJsonArray("ids") : new JsonArray();
                    List<String> ids = new ArrayList<>();
                    for (JsonElement el : arr) ids.add(el.getAsString());
                    ShopIdCache cache = new ShopIdCache();
                    cache.ids = ids;
                    cache.fetched = System.currentTimeMillis();
                    shopIdCache.put(p.getUniqueId(), cache);
                }
            }
        });
    }

    private void refreshItems(String shopId) {
        OkHttpClient http = plugin.getHttpClient();
        if (http == null) return;
        HttpUrl url = HttpUrl.parse(plugin.getBaseUrl() + "/api/shop/items").newBuilder()
                .addQueryParameter("shop_id", shopId)
                .build();
        Request req = new Request.Builder().url(url).build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                    if (!obj.has("items")) return;
                    JsonArray arr = obj.getAsJsonArray("items");
                    List<String> keys = new ArrayList<>();
                    List<String> names = new ArrayList<>();
                    for (JsonElement el : arr) {
                        JsonObject it = el.getAsJsonObject();
                        keys.add(it.get("item_key").getAsString());
                        if (it.has("sale_name")) names.add(it.get("sale_name").getAsString());
                    }
                    ItemCache cache = new ItemCache();
                    cache.itemKeys = keys;
                    cache.saleNames = names;
                    cache.fetched = System.currentTimeMillis();
                    itemCache.put(shopId, cache);
                }
            }
        });
    }
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
              return Stream.of("rewrite", "start", "stop", "reload", "money", "deposit", "withdraw", "transfer", "balance", "currency", "setbalance", "history", "account", "undo", "redo", "help", "lang", "backup", "restore", "weblink", "shop", "pay", "wallet")
                      .filter(s -> s.startsWith(args[0].toLowerCase()))
                      .toList();
        }
        if (args.length == 2) {
            String first = args[0].toLowerCase();
            if (first.equals("deposit") || first.equals("withdraw") || first.equals("transfer") || first.equals("setbalance") || first.equals("history")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                names.removeIf(n -> !n.toLowerCase().startsWith(args[1].toLowerCase()));
                return names;
            }
            if (first.equals("currency")) {
                return Stream.of("create", "supply", "default", "manager", "tax", "treasury")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .toList();
            }
            if (first.equals("account")) {
                return Stream.of("create", "connect")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .toList();
            }
            if (first.equals("money")) {
                return Stream.of("give", "take", "pay", "top")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .toList();
            }
            if (first.equals("pay")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                names.removeIf(n -> !n.toLowerCase().startsWith(args[1].toLowerCase()));
                return names;
            }
            if (first.equals("shop")) {
                return Stream.of("gui", "create", "add", "take", "price", "buyprice", "autoprice", "autopricedisable", "remove", "partner", "account", "reopen", "hopper", "help")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .toList();
            }
            if (first.equals("balance")) {
                List<String> opts = new ArrayList<>();
                Stream.of("thy")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .forEach(opts::add);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String name = p.getName();
                    if (name.toLowerCase().startsWith(args[1].toLowerCase())) opts.add(name);
                }
                return opts;
            }
            if (first.equals("lang")) {
                return Stream.of("en", "jp")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .toList();
            }
        }
        if (args.length == 3) {
            String first = args[0].toLowerCase();
            if (first.equals("currency") && args[1].equalsIgnoreCase("create")) {
                return Collections.singletonList("<symbol>");
            }
            if (first.equals("currency") && args[1].equalsIgnoreCase("default")) {
                return Collections.singletonList("<id>");
            }
            if (first.equals("money") && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("take") || args[1].equalsIgnoreCase("pay"))) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                names.removeIf(n -> !n.toLowerCase().startsWith(args[2].toLowerCase()));
                return names;
            }
            if (first.equals("balance")) {
                boolean secondIsCurrency = Stream.of("thy")
                        .anyMatch(c -> c.equalsIgnoreCase(args[1]));
                if (secondIsCurrency) {
                    List<String> names = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        names.add(p.getName());
                    }
                    names.removeIf(n -> !n.toLowerCase().startsWith(args[2].toLowerCase()));
                    return names;
                }
            }
            if (first.equals("money") && args[1].equalsIgnoreCase("top")) {
                return Stream.of("thy")
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (first.equals("shop") && args[1].equalsIgnoreCase("partner")) {
                return Stream.of("add", "remove")
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (first.equals("shop")) {
                if (sender instanceof Player player) {
                    ShopIdCache cache = shopIdCache.get(player.getUniqueId());
                    long now = System.currentTimeMillis();
                    if (cache == null || now - cache.fetched > 5000) {
                        refreshShopIds(player);
                        cache = shopIdCache.get(player.getUniqueId());
                    }
                    List<String> ids = cache != null ? cache.ids : Collections.emptyList();
                    return ids.stream()
                            .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
                }
                return Collections.emptyList();
            }
        }
        if (args.length == 4) {
            if (args[0].equalsIgnoreCase("money") && args[1].equalsIgnoreCase("pay")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                names.removeIf(n -> !n.toLowerCase().startsWith(args[3].toLowerCase()));
                return names;
            }
            if (args[0].equalsIgnoreCase("money") && args[1].equalsIgnoreCase("top")) {
                return Collections.singletonList("1");
            }
            if (args[0].equalsIgnoreCase("shop") && args[1].equalsIgnoreCase("take")) {
                String shopId = args[2];
                ItemCache cache = itemCache.get(shopId);
                long now = System.currentTimeMillis();
                if (cache == null || now - cache.fetched > 5000) {
                    refreshItems(shopId);
                }
                if (cache != null) {
                    return cache.itemKeys.stream()
                            .filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase()))
                            .collect(Collectors.toList());
                }
            }
            if (args[0].equalsIgnoreCase("shop") && args[1].equalsIgnoreCase("hopper")) {
                String shopId = args[2];
                ItemCache cache = itemCache.get(shopId);
                long now = System.currentTimeMillis();
                if (cache == null || now - cache.fetched > 5000) {
                    refreshItems(shopId);
                    cache = itemCache.get(shopId);
                }
                if (cache != null && !cache.itemKeys.isEmpty()) {
                    List<String> slots = new ArrayList<>();
                    for (int i = 0; i < cache.itemKeys.size(); i++) {
                        slots.add(Integer.toString(i + 1));
                    }
                    String needle = args[3];
                    return slots.stream()
                            .filter(s -> needle.isEmpty() || s.startsWith(needle))
                            .collect(Collectors.toList());
                }
                return Collections.singletonList("<slot>");
            }
            if (args[0].equalsIgnoreCase("shop") && args[1].equalsIgnoreCase("account")) {
                return Collections.singletonList("<company>");
            }
            if (args[0].equalsIgnoreCase("shop") && args[1].equalsIgnoreCase("partner")) {
                if (sender instanceof Player player) {
                    ShopIdCache cache = shopIdCache.get(player.getUniqueId());
                    long now = System.currentTimeMillis();
                    if (cache == null || now - cache.fetched > 5000) {
                        refreshShopIds(player);
                        cache = shopIdCache.get(player.getUniqueId());
                    }
                    List<String> ids = cache != null ? cache.ids : Collections.emptyList();
                    return ids.stream()
                            .filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase()))
                            .collect(Collectors.toList());
                }
                return Collections.emptyList();
            }
            if (args[0].equalsIgnoreCase("shop") && (args[1].equalsIgnoreCase("price") || args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("autoprice") || args[1].equalsIgnoreCase("autopricedisable"))) {
                String shopId = args[2];
                ItemCache cache = itemCache.get(shopId);
                long now = System.currentTimeMillis();
                if (cache == null || now - cache.fetched > 5000) {
                    refreshItems(shopId);
                }
                if (cache != null) {
                    return cache.saleNames.stream()
                            .filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase()))
                            .collect(Collectors.toList());
                }
            }
        }
        if (args.length == 5) {
            if (args[0].equalsIgnoreCase("shop") && args[1].equalsIgnoreCase("partner")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[4].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }
}
