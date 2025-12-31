package com.grapelemon.lumineeconomybridge.shop.market;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class MarketListener implements Listener {

    private final MarketManager marketManager;

    public MarketListener(MarketManager marketManager) {
        this.marketManager = marketManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory inv = event.getInventory();
        InventoryHolder holder = inv.getHolder();
        if (!(holder instanceof MarketMenuHolder menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == 45 && menu.hasPreviousPage()) {
            marketManager.openMarket(player, menu.getPage() - 1);
            return;
        }
        if (slot == 53 && menu.hasNextPage()) {
            marketManager.openMarket(player, menu.getPage() + 1);
            return;
        }
        String shopId = menu.getShopId(slot);
        if (shopId != null) {
            player.closeInventory();
            marketManager.openShopFromMarket(player, shopId);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MarketMenuHolder) {
            event.setCancelled(true);
        }
    }
}
