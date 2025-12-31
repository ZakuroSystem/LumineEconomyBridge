package com.grapelemon.lumineeconomybridge.shop.market;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MarketMenuHolder implements InventoryHolder {

    private final int page;
    private final int totalPages;
    private final Map<Integer, String> slotToShop = new HashMap<>();
    private Inventory inventory;

    public MarketMenuHolder(int page, int totalPages) {
        this.page = page;
        this.totalPages = totalPages;
    }

    public void bindShopSlot(int slot, String shopId) {
        if (shopId != null) {
            slotToShop.put(slot, shopId);
        }
    }

    public String getShopId(int slot) {
        return slotToShop.get(slot);
    }

    public int getPage() {
        return page;
    }

    public boolean hasNextPage() {
        return page < totalPages - 1;
    }

    public boolean hasPreviousPage() {
        return page > 0;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
