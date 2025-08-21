package com.grapelemon.lumineeconomybridge.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Inventory holder for closed shops. Cancels all interactions to prevent
 * players from removing the barrier indicator item.
 */
public class ClosedMenuHolder implements InventoryHolder {
    @Override
    public Inventory getInventory() {
        return null;
    }
}
