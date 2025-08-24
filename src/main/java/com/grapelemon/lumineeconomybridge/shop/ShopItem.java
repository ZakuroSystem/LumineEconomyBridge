package com.grapelemon.lumineeconomybridge.shop;

import org.bukkit.inventory.ItemStack;
import java.util.Map;

public class ShopItem {
    private final String itemKey;
    private final String saleName;
    private final ItemStack item;
    private final ItemStack rawItem;
    private final Map<String, Integer> prices;
    private final int priceQty;
    private int stock;

    public ShopItem(String itemKey, String saleName, ItemStack item, ItemStack rawItem, int stock, Map<String, Integer> prices, int priceQty) {
        this.itemKey = itemKey;
        this.saleName = saleName;
        this.item = item;
        this.rawItem = rawItem;
        this.stock = stock;
        this.prices = prices;
        this.priceQty = priceQty;
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

    public int getPriceQty() {
        return priceQty;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }
}
