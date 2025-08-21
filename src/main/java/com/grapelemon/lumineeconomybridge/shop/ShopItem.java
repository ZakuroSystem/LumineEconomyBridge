package com.grapelemon.lumineeconomybridge.shop;

import org.bukkit.inventory.ItemStack;
import java.util.Map;

public class ShopItem {
    private final String itemKey;
    private final String saleName;
    private final ItemStack item;
    private final ItemStack rawItem;
    private final Map<String, Integer> prices;

    public ShopItem(String itemKey, String saleName, ItemStack item, ItemStack rawItem, Map<String, Integer> prices) {
        this.itemKey = itemKey;
        this.saleName = saleName;
        this.item = item;
        this.rawItem = rawItem;
        this.prices = prices;
    }

    public String getItemKey() {
        return itemKey;
    }

    public String getSaleName() {
        return saleName;
    }

    public ItemStack getItem() {
        return item;
    }

    public ItemStack getRawItem() {
        return rawItem;
    }

    public Map<String, Integer> getPrices() {
        return prices;
    }
}
