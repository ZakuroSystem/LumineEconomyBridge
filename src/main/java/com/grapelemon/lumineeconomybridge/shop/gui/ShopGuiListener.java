package com.grapelemon.lumineeconomybridge.shop.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * Listener skeleton for the inventory GUI interactions.
 */
public class ShopGuiListener implements Listener {

    private final ShopGuiManager manager;

    public ShopGuiListener(ShopGuiManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof ShopGuiSessionHolder)) {
            return;
        }
        event.setCancelled(true);
        // TODO: delegate to the relevant controller when the layout is implemented.
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof ShopGuiSessionHolder)) {
            return;
        }
        manager.close(player);
    }
}
