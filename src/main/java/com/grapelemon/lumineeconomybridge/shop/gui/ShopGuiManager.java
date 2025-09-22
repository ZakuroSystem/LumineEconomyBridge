package com.grapelemon.lumineeconomybridge.shop.gui;

import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Entry point for the upcoming inventory based shop management GUI.
 *
 * <p>This class currently exposes just enough structure for other
 * components to interact with while we prototype the rest of the user
 * experience. The intention is to grow this into a stateful manager that
 * keeps track of which players are editing which shops, delegates event
 * handling to dedicated view/controller objects, and talks to the backend
 * HTTP API.</p>
 */
public class ShopGuiManager {

    private final LumineEconomyBridge plugin;
    private final Map<UUID, Inventory> activeInventories = new HashMap<>();

    public ShopGuiManager(LumineEconomyBridge plugin) {
        this.plugin = plugin;
    }

    /**
     * Opens the main shop management landing screen for the player.
     *
     * <p>The actual layout will be implemented in a future revision. For
     * the skeleton we just reserve a generic container inventory so we can
     * verify listener wiring and navigation behaviour.</p>
     */
    public void openMainMenu(Player player) {
        Inventory menu = Bukkit.createInventory(new ShopGuiSessionHolder(player.getUniqueId()),
                InventoryType.CHEST);
        // TODO: populate the inventory with navigation buttons.
        player.openInventory(menu);
        activeInventories.put(player.getUniqueId(), menu);
    }

    /**
     * Placeholder that will eventually load the player's shops and build the
     * appropriate editor view.
     */
    public void openShopEditor(Player player, String shopId) {
        // TODO: add async loading of shop data and open an editor view.
    }

    public void close(Player player) {
        activeInventories.remove(player.getUniqueId());
        player.closeInventory();
    }

    public boolean isTracking(Player player) {
        return activeInventories.containsKey(player.getUniqueId());
    }

    LumineEconomyBridge getPlugin() {
        return plugin;
    }
}
