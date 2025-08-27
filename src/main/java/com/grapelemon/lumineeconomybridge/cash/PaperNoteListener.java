package com.grapelemon.lumineeconomybridge.cash;

import org.bukkit.entity.Player;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

public class PaperNoteListener implements Listener {
    private final PaperCurrencyService service;
    private final int moveThrottleSq;
    private final Map<UUID, Location> lastMove = new HashMap<>();

    public PaperNoteListener(PaperCurrencyService service, int moveThrottle) {
        this.service = service;
        this.moveThrottleSq = moveThrottle * moveThrottle;
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

    private boolean containsNote(ItemStack stack) {
        if (stack == null) return false;
        if (service.isNote(stack)) return true;
        if (stack.getType().name().endsWith("SHULKER_BOX")) {
            ItemMeta meta = stack.getItemMeta();
            if (meta instanceof BlockStateMeta bsm) {
                BlockState st = bsm.getBlockState();
                if (st instanceof ShulkerBox box) {
                    for (ItemStack s : box.getInventory().getContents()) {
                        if (containsNote(s)) return true;
                    }
                }
            }
        }
        return false;
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
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        String actor = killer != null ? killer.getUniqueId().toString() : "";
        Location loc = victim.getLocation();
        for (ItemStack s : e.getDrops()) {
            service.trackItem(s, actor, "drop", loc);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location to = e.getTo();
        if (to == null) return;
        UUID id = p.getUniqueId();
        Location last = lastMove.get(id);
        if (last != null && last.getWorld().equals(to.getWorld()) && last.distanceSquared(to) < moveThrottleSq) return;
        boolean has = false;
        for (ItemStack s : p.getInventory().getContents()) {
            if (containsNote(s)) {
                has = true;
                service.trackItem(s, id.toString(), "move", to);
            }
        }
        if (has) {
            lastMove.put(id, to.clone());
        } else {
            lastMove.remove(id);
        }
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
    public void onFramePlace(HangingPlaceEvent e) {
        if (!(e.getEntity() instanceof ItemFrame frame)) return;
        ItemStack stack = frame.getItem();
        String actor = e.getPlayer() != null ? e.getPlayer().getUniqueId().toString() : "";
        service.trackItem(stack, actor, "store", frame.getLocation());
    }

    @EventHandler
    public void onFrameBreak(HangingBreakEvent e) {
        if (!(e.getEntity() instanceof ItemFrame frame)) return;
        String actor = "";
        if (e instanceof HangingBreakByEntityEvent by && by.getRemover() instanceof Player p) {
            actor = p.getUniqueId().toString();
        }
        ItemStack stack = frame.getItem();
        service.trackItem(stack, actor, "retrieve", frame.getLocation());
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
