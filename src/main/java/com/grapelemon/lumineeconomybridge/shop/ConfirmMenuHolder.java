package com.grapelemon.lumineeconomybridge.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class ConfirmMenuHolder implements InventoryHolder {
    private final String shopId;
    private final ShopItem item;
    private final ShopMenuHolder origin;
    private final int slot;
    private final boolean selling;

    public ConfirmMenuHolder(String shopId, ShopMenuHolder origin, int slot, ShopItem item, boolean selling) {
        this.shopId = shopId;
        this.origin = origin;
        this.slot = slot;
        this.item = item;
        this.selling = selling;
    }

    public String getShopId() {
        return shopId;
    }

    public ShopItem getItem() {
        return item;
    }

    public ShopMenuHolder getOrigin() {
        return origin;
    }

    public int getSlot() {
        return slot;
    }

    public boolean isSelling() {
        return selling;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
