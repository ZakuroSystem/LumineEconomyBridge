package com.grapelemon.lumineeconomybridge.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class ConfirmMenuHolder implements InventoryHolder {
    private final String shopId;
    private final ShopItem item;
    private final ShopMenuHolder origin;
    private final int slot;
    private final boolean selling;
    private final int step;
    private final int maxQty;
    private int qty;

    public ConfirmMenuHolder(String shopId, ShopMenuHolder origin, int slot, ShopItem item, boolean selling, int step, int maxQty) {
        this.shopId = shopId;
        this.origin = origin;
        this.slot = slot;
        this.item = item;
        this.selling = selling;
        this.step = step;
        this.maxQty = maxQty;
        this.qty = step;
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

    public int getQty() {
        return qty;
    }

    public void adjustQty(int delta) {
        int newQty = qty + delta * step;
        if (newQty >= step && newQty <= maxQty) {
            qty = newQty;
        }
    }

    public int getStep() { return step; }

    public int getMaxQty() { return maxQty; }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
