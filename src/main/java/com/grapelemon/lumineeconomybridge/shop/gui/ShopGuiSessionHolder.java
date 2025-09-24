package com.grapelemon.lumineeconomybridge.shop.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Inventory holder tagging inventories managed by the shop GUI system.
 */
public class ShopGuiSessionHolder implements InventoryHolder {

    private final UUID playerId;

    public ShopGuiSessionHolder(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    @Override
    public Inventory getInventory() {
        return null; // Inventories are built externally and supplied to Bukkit directly.
    }
}
