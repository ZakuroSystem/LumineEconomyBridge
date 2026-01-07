package com.grapelemon.lumineeconomybridge.shop.market;

import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.grapelemon.lumineeconomybridge.shop.ShopListener;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class MarketManager {

    private static final int PAGE_SIZE = 45;

    private final LumineEconomyBridge plugin;
    private final ShopListener shopListener;
    private final File dataFile;
    private final YamlConfiguration config;
    private final Set<String> marketShops = new LinkedHashSet<>();
    private MarketFees fees;

    public MarketManager(LumineEconomyBridge plugin, ShopListener shopListener) {
        this.plugin = plugin;
        this.shopListener = shopListener;
        this.dataFile = new File(plugin.getDataFolder(), "market.yml");
        this.config = YamlConfiguration.loadConfiguration(dataFile);
        reload();
    }

    public void reload() {
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to create market.yml: " + e.getMessage());
            }
        }
        marketShops.clear();
        marketShops.addAll(config.getStringList("shops"));
        loadFees();
    }

    public List<String> getMarketShops() {
        return Collections.unmodifiableList(new ArrayList<>(marketShops));
    }

    public boolean isListed(String shopId) {
        return marketShops.contains(shopId);
    }

    public boolean addShop(String shopId) {
        if (shopId == null || shopId.isBlank()) {
            return false;
        }
        boolean added = marketShops.add(shopId);
        if (added) {
            save();
        }
        return added;
    }

    public boolean removeShop(String shopId) {
        if (shopId == null || shopId.isBlank()) {
            return false;
        }
        boolean removed = marketShops.remove(shopId);
        if (removed) {
            save();
        }
        return removed;
    }

    public MarketFees getFees() {
        return fees;
    }

    public void requestPlayerListing(Player player, String shopId, Runnable success) {
        if (shopId == null || shopId.isBlank()) {
            player.sendMessage(ChatColor.YELLOW + "ショップIDを指定してください。" + ChatColor.RESET);
            return;
        }
        if (isListed(shopId)) {
            player.sendMessage(ChatColor.YELLOW + "既にマーケットに掲載されています。" + ChatColor.RESET);
            if (success != null) {
                success.run();
            }
            return;
        }
        validateOwnership(player, shopId, owns -> {
            if (!owns) {
                player.sendMessage(ChatColor.RED + "このショップをマーケットに掲載する権限がありません。" + ChatColor.RESET);
                return;
            }
            boolean added = addShop(shopId);
            if (added) {
                player.sendMessage(ChatColor.GREEN + "ショップ " + ChatColor.YELLOW + shopId + ChatColor.GREEN
                        + " をマーケットに掲載しました。" + ChatColor.RESET);
                MarketFees marketFees = fees;
                if (marketFees != null) {
                    player.sendMessage(ChatColor.GRAY + "出店料: " + ChatColor.YELLOW + formatAmount(marketFees.listingFee())
                            + ChatColor.GRAY + " / 維持費: " + ChatColor.YELLOW + formatAmount(marketFees.upkeepAmount())
                            + ChatColor.GRAY + " (" + marketFees.upkeepInterval() + ") / 取引手数料: "
                            + ChatColor.YELLOW + marketFees.feePercent() + "%" + ChatColor.RESET);
                }
                if (success != null) {
                    success.run();
                }
            } else {
                player.sendMessage(ChatColor.YELLOW + "マーケットへの登録に失敗しました。" + ChatColor.RESET);
            }
        }, error -> player.sendMessage(ChatColor.RED + error + ChatColor.RESET));
    }

    public void removeListing(Player player, String shopId, Runnable success) {
        if (!isListed(shopId)) {
            player.sendMessage(ChatColor.YELLOW + "このショップはマーケットに掲載されていません。" + ChatColor.RESET);
            return;
        }
        validateOwnership(player, shopId, owns -> {
            if (!owns) {
                player.sendMessage(ChatColor.RED + "このショップの掲載を管理する権限がありません。" + ChatColor.RESET);
                return;
            }
            boolean removed = removeShop(shopId);
            if (removed) {
                player.sendMessage(ChatColor.GREEN + "マーケットから削除しました。" + ChatColor.RESET);
                if (success != null) {
                    success.run();
                }
            } else {
                player.sendMessage(ChatColor.YELLOW + "マーケットから削除できませんでした。" + ChatColor.RESET);
            }
        }, error -> player.sendMessage(ChatColor.RED + error + ChatColor.RESET));
    }

    private void validateOwnership(Player player, String shopId, Consumer<Boolean> result, Consumer<String> errorHandler) {
        if (player == null) {
            errorHandler.accept("プレイヤーのみが実行できます。");
            return;
        }
        if (plugin.getHttpClient() == null || !plugin.isActive()) {
            errorHandler.accept("バックエンドに接続できません。後で再試行してください。");
            return;
        }
        HttpUrl base = HttpUrl.parse(plugin.getBaseUrl() + "/api/shop/items");
        if (base == null) {
            errorHandler.accept("APIエンドポイントが無効です。");
            return;
        }
        HttpUrl url = base.newBuilder()
                .addQueryParameter("shop_id", shopId)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .build();
        plugin.getHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> errorHandler.accept("ショップ情報の取得に失敗しました。"));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        Bukkit.getScheduler().runTask(plugin,
                                () -> errorHandler.accept("ショップ情報の取得に失敗しました。"));
                        return;
                    }
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    if (json.has("reason") && "shop_not_found".equalsIgnoreCase(json.get("reason").getAsString())) {
                        Bukkit.getScheduler().runTask(plugin,
                                () -> errorHandler.accept("ショップが見つかりませんでした。"));
                        return;
                    }
                    if (json.has("owners") && json.get("owners").isJsonArray()) {
                        JsonArray owners = json.getAsJsonArray("owners");
                        String uuid = player.getUniqueId().toString();
                        for (JsonElement owner : owners) {
                            if (owner != null && owner.isJsonPrimitive() && uuid.equalsIgnoreCase(owner.getAsString())) {
                                Bukkit.getScheduler().runTask(plugin, () -> result.accept(true));
                                return;
                            }
                        }
                        Bukkit.getScheduler().runTask(plugin, () -> result.accept(false));
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> result.accept(false));
                }
            }
        });
    }

    private void loadFees() {
        FileConfiguration cfg = plugin.getConfig();
        int listingFee = cfg.getInt("market.listing_fee", 0);
        int upkeepAmount = cfg.getInt("market.upkeep.amount", 0);
        String upkeepInterval = cfg.getString("market.upkeep.interval", "weekly");
        int feePercent = cfg.getInt("market.trade_fee_percent", 0);
        this.fees = new MarketFees(listingFee, upkeepAmount, upkeepInterval, feePercent);
    }

    private String formatAmount(int amount) {
        return plugin.formatAmountPlain(amount);
    }

    private void save() {
        config.set("shops", new ArrayList<>(marketShops));
        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save market.yml: " + e.getMessage());
        }
    }

    public void openMarket(Player player) {
        openMarket(player, 0);
    }

    public void openMarket(Player player, int page) {
        if (marketShops.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "マーケットに登録されたショップはありません。" + ChatColor.RESET);
            return;
        }
        int totalPages = (marketShops.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int targetPage = Math.max(0, Math.min(page, Math.max(totalPages - 1, 0)));
        Inventory inv = Bukkit.createInventory(new MarketMenuHolder(targetPage, totalPages), 54,
                ChatColor.GOLD + "Market");
        if (inv.getHolder() instanceof MarketMenuHolder holder) {
            holder.setInventory(inv);
            populate(inv, holder, targetPage);
        }
        player.openInventory(inv);
    }

    private void populate(Inventory inv, MarketMenuHolder holder, int page) {
        inv.clear();
        List<String> shops = new ArrayList<>(marketShops);
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, shops.size());
        int slot = 0;
        for (int i = start; i < end && slot < PAGE_SIZE; i++) {
            String shopId = shops.get(i);
            ItemStack icon = new ItemStack(Material.BARREL);
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(ChatColor.GREEN + shopId);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "クリックで開く / Open");
            meta.setLore(lore);
            icon.setItemMeta(meta);
            inv.setItem(slot, icon);
            holder.bindShopSlot(slot, shopId);
            slot++;
        }

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.setDisplayName(" ");
        filler.setItemMeta(fm);
        for (int i = PAGE_SIZE; i < 54; i++) {
            inv.setItem(i, filler);
        }

        if (holder.hasPreviousPage()) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta pm = prev.getItemMeta();
            pm.setDisplayName(ChatColor.YELLOW + "前のページ / Previous");
            prev.setItemMeta(pm);
            inv.setItem(45, prev);
        }
        if (holder.hasNextPage()) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nm = next.getItemMeta();
            nm.setDisplayName(ChatColor.YELLOW + "次のページ / Next");
            next.setItemMeta(nm);
            inv.setItem(53, next);
        }
    }

    public void openShopFromMarket(Player player, String shopId) {
        shopListener.openShop(player, shopId, true);
    }

    public record MarketFees(int listingFee, int upkeepAmount, String upkeepInterval, int feePercent) {
    }
}
