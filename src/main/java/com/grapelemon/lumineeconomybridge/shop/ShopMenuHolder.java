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
    private String currency;
    private int quantity = 1;
    private String ownerUuid;

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

    public String getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(String ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = Math.max(1, quantity);
    }
}
