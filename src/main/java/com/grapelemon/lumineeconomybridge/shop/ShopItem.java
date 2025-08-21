package com.grapelemon.lumineeconomybridge.shop;

import org.bukkit.inventory.ItemStack;
import java.util.Map;

public class ShopItem {
    private final String itemKey;
    private final ItemStack item;
    private final Map<String, Integer> prices;

    public ShopItem(String itemKey, ItemStack item, Map<String, Integer> prices) {
        this.itemKey = itemKey;
        this.item = item;
        this.prices = prices;
    }

    public String getItemKey() {
        return itemKey;
    }

    public ItemStack getItem() {
        return item;
    }

    public Map<String, Integer> getPrices() {
        return prices;
    }
}
