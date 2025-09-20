package com.grapelemon.lumineeconomybridge.shop;

import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ShopItem {
    private final String itemKey;
    private final String saleName;
    private final ItemStack item;
    private final ItemStack rawItem;
    private final Map<String, ShopPrice> prices;
    private int stock;

    public ShopItem(String itemKey, String saleName, ItemStack item, ItemStack rawItem, int stock, Map<String, ShopPrice> prices) {
        this.itemKey = itemKey;
        this.saleName = saleName;
        this.item = item;
        this.rawItem = rawItem;
        this.stock = stock;
        this.prices = prices != null ? prices : new HashMap<>();
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

    public Map<String, ShopPrice> getPrices() {
        return prices;
    }

    public ShopPrice getOrCreatePrice(String currency) {
        return prices.computeIfAbsent(currency, k -> new ShopPrice());
    }

    public String firstSellCurrency() {
        for (Map.Entry<String, ShopPrice> entry : prices.entrySet()) {
            ShopPrice price = entry.getValue();
            if (price != null && price.getSellPrice() != null && price.getSellPrice() > 0) {
                return entry.getKey();
            }
        }
        return null;
    }

    public String firstBuyCurrency() {
        for (Map.Entry<String, ShopPrice> entry : prices.entrySet()) {
            ShopPrice price = entry.getValue();
            if (price != null && price.getBuyPrice() != null && price.getBuyPrice() > 0) {
                return entry.getKey();
            }
        }
        return null;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }

    public static class ShopPrice {
        private Integer sellPrice;
        private Integer buyPrice;

        public ShopPrice() {
        }

        public ShopPrice(Integer sellPrice, Integer buyPrice) {
            this.sellPrice = sellPrice;
            this.buyPrice = buyPrice;
        }

        public Integer getSellPrice() {
            return sellPrice;
        }

        public void setSellPrice(Integer sellPrice) {
            this.sellPrice = sellPrice;
        }

        public Integer getBuyPrice() {
            return buyPrice;
        }

        public void setBuyPrice(Integer buyPrice) {
            this.buyPrice = buyPrice;
        }

        @Override
        public String toString() {
            return "ShopPrice{" +
                    "sellPrice=" + Objects.toString(sellPrice) +
                    ", buyPrice=" + Objects.toString(buyPrice) +
                    '}';
        }
    }
}
