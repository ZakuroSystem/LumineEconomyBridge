package com.grapelemon.lumineeconomybridge.shop.market;

import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.grapelemon.lumineeconomybridge.shop.ShopListener;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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

public class MarketManager {

    private static final int PAGE_SIZE = 45;

    private final LumineEconomyBridge plugin;
    private final ShopListener shopListener;
    private final File dataFile;
    private final YamlConfiguration config;
    private final Set<String> marketShops = new LinkedHashSet<>();

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
    }

    public List<String> getMarketShops() {
        return Collections.unmodifiableList(new ArrayList<>(marketShops));
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
}
