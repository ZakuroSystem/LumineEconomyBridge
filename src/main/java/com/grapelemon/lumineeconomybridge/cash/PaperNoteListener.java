package com.grapelemon.lumineeconomybridge.cash;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;

public class PaperNoteListener implements Listener {
    private final PaperCurrencyService service;

    public PaperNoteListener(PaperCurrencyService service) {
        this.service = service;
    }

    private String loc(Location l) {
        return l.getWorld().getName()+","+l.getBlockX()+","+l.getBlockY()+","+l.getBlockZ();
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack stack = e.getItemDrop().getItemStack();
        if (!service.isNote(stack)) return;
        Player p = e.getPlayer();
        service.sendEvent(p.getUniqueId().toString(), "drop", stack, p.getLocation());
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent e) {
        ItemStack stack = e.getItem().getItemStack();
        if (!service.isNote(stack)) return;
        Player p = e.getPlayer();
        service.sendEvent(p.getUniqueId().toString(), "pickup", stack, p.getLocation());
    }

    @EventHandler
    public void onChestMove(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        ItemStack stack = e.getCurrentItem();
        if (stack == null || !service.isNote(stack)) return;
        Player p = (Player) e.getWhoClicked();
        String action = e.getInventory().getType() == InventoryType.CHEST ? "store" : "retrieve";
        service.sendEvent(p.getUniqueId().toString(), action, stack, p.getLocation());
    }

    @EventHandler
    public void onDespawn(ItemDespawnEvent e) {
        ItemStack stack = e.getEntity().getItemStack();
        if (!service.isNote(stack)) return;
        service.sendEvent("", "destroy", stack, e.getLocation());
    }
}
