package com.grapelemon.lumineeconomybridge.cash;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;

public class PaperNoteListener implements Listener {
    private final PaperCurrencyService service;

    public PaperNoteListener(PaperCurrencyService service) {
        this.service = service;
    }

    private Location holderLocation(InventoryHolder holder) {
        if (holder instanceof BlockState bs) {
            return bs.getLocation();
        }
        if (holder instanceof DoubleChest dc) {
            return dc.getLocation();
        }
        return null;
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack stack = e.getItemDrop().getItemStack();
        Player p = e.getPlayer();
        service.trackItem(stack, p.getUniqueId().toString(), "drop", p.getLocation());
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent e) {
        ItemStack stack = e.getItem().getItemStack();
        Player p = e.getPlayer();
        service.trackItem(stack, p.getUniqueId().toString(), "pickup", p.getLocation());
    }

    @EventHandler
    public void onChestMove(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        ItemStack stack = e.getCurrentItem();
        if (stack == null) return;
        Player p = (Player) e.getWhoClicked();
        InventoryType type = e.getInventory().getType();
        String action;
        switch (type) {
            case CHEST:
            case BARREL:
            case SHULKER_BOX:
            case HOPPER:
            case DISPENSER:
            case DROPPER:
                action = "store";
                break;
            default:
                action = "retrieve";
        }
        service.trackItem(stack, p.getUniqueId().toString(), action, p.getLocation());
    }

    @EventHandler
    public void onDespawn(ItemDespawnEvent e) {
        ItemStack stack = e.getEntity().getItemStack();
        service.trackItem(stack, "", "destroy", e.getLocation());
    }

    @EventHandler
    public void onInventoryMove(InventoryMoveItemEvent e) {
        ItemStack stack = e.getItem();
        Location from = holderLocation(e.getSource().getHolder());
        Location to = holderLocation(e.getDestination().getHolder());
        service.trackItem(stack, "", "retrieve", from);
        service.trackItem(stack, "", "store", to);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        BlockState st = e.getBlock().getState();
        if (!(st instanceof InventoryHolder holder)) return;
        Player p = e.getPlayer();
        for (ItemStack s : holder.getInventory().getContents()) {
            service.trackItem(s, p.getUniqueId().toString(), "retrieve", e.getBlock().getLocation());
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        for (BlockState st : e.getChunk().getTileEntities()) {
            if (st instanceof Chest chest) {
                for (ItemStack s : chest.getBlockInventory().getContents()) {
                    service.trackItem(s, "", "store", chest.getLocation());
                }
            } else if (st instanceof InventoryHolder holder) {
                for (ItemStack s : holder.getInventory().getContents()) {
                    service.trackItem(s, "", "store", st.getLocation());
                }
            }
        }
    }
}
