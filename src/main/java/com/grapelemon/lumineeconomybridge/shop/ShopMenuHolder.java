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
    private final java.util.Set<String> ownerUuids = new java.util.HashSet<>();
    private String tradeMode = "both";

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

    public boolean isOwner(String uuid) {
        return ownerUuids.contains(uuid);
    }

    public void addOwnerUuid(String uuid) {
        ownerUuids.add(uuid);
    }

    public void setTradeMode(String mode) {
        this.tradeMode = mode != null ? mode : "both";
    }

    public String getTradeMode() {
        return tradeMode;
    }

    public boolean canPlayerPurchase() {
        return !"buy".equalsIgnoreCase(tradeMode);
    }

    public boolean canPlayerSell() {
        return !"sell".equalsIgnoreCase(tradeMode);
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
