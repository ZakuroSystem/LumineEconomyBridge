package com.grapelemon.lumineeconomybridge.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

public class ShopMenuHolder implements InventoryHolder {
    private final String shopId;
    private final Map<Integer, ShopItem> items = new HashMap<>();
    private int selected = -1;
    private Inventory inventory;

    public ShopMenuHolder(String shopId) {
        this.shopId = shopId;
    }

    public String getShopId() {
        return shopId;
    }

    public Map<Integer, ShopItem> getItems() {
        return items;
    }

    public int getSelected() {
        return selected;
    }

    public void setSelected(int slot) {
        this.selected = slot;
    }

    public ShopItem getSelectedItem() {
        return items.get(selected);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
