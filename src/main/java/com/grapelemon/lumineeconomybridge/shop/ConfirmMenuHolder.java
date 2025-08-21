package com.grapelemon.lumineeconomybridge.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class ConfirmMenuHolder implements InventoryHolder {
    private final String shopId;
    private final ShopItem item;

    public ConfirmMenuHolder(String shopId, ShopItem item) {
        this.shopId = shopId;
        this.item = item;
    }

    public String getShopId() {
        return shopId;
    }

    public ShopItem getItem() {
        return item;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
