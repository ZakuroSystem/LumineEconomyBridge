package com.grapelemon.lumineeconomybridge.cash;

import org.bukkit.Bukkit;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

import com.grapelemon.lumineeconomybridge.cash.CashEventAction;
import static com.grapelemon.lumineeconomybridge.cash.CashEventAction.*;

public class PaperNoteListener implements Listener {
    private final PaperCurrencyService service;
    private final int moveThrottleSq;
    private final Map<UUID, Location> lastMove = new HashMap<>();
    private final Map<UUID, List<ItemStack>> noteCache = new HashMap<>();

    public PaperNoteListener(PaperCurrencyService service, int moveThrottle) {
        this.service = service;
        this.moveThrottleSq = moveThrottle * moveThrottle;
    }

    private void rescan(Player p) {
        UUID id = p.getUniqueId();
        List<ItemStack> notes = new ArrayList<>();
        for (ItemStack s : p.getInventory().getContents()) {
            if (containsNote(s)) {
                notes.add(s);
            }
        }
        if (notes.isEmpty()) {
            noteCache.remove(id);
            lastMove.remove(id);
        } else {
            noteCache.put(id, notes);
        }
    }

    private void scheduleRescan(Player p) {
        Bukkit.getScheduler().runTask(service.getPlugin(), () -> rescan(p));
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
        Location loc = p.getLocation();
        if (loc != null) {
            service.trackItem(stack, p.getUniqueId().toString(), DROP, loc);
        }
        scheduleRescan(p);
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent e) {
        ItemStack stack = e.getItem().getItemStack();
        Player p = e.getPlayer();
        Location loc = p.getLocation();
        if (loc != null) {
            service.trackItem(stack, p.getUniqueId().toString(), PICKUP, loc);
        }
        scheduleRescan(p);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        String actor = killer != null ? killer.getUniqueId().toString() : "";
        Location loc = victim.getLocation();
        if (loc != null) {
            for (ItemStack s : e.getDrops()) {
                service.trackItem(s, actor, DROP, loc);
            }
        }
        noteCache.remove(victim.getUniqueId());
        lastMove.remove(victim.getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location to = e.getTo();
        if (to == null) return;
        UUID id = p.getUniqueId();
        Location last = lastMove.get(id);
        if (last != null && last.getWorld().equals(to.getWorld()) && last.distanceSquared(to) < moveThrottleSq) return;
        List<ItemStack> notes = noteCache.get(id);
        if (notes == null) {
            rescan(p);
            notes = noteCache.get(id);
            if (notes == null) return;
        }
        for (ItemStack s : notes) {
            service.trackItem(s, id.toString(), MOVE, to);
        }
        lastMove.put(id, to.clone());
    }

    @EventHandler
    public void onChestMove(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack stack = e.getCurrentItem();
        if (stack == null) return;
        // Ignore clicks when no container is open
        Inventory top = e.getView().getTopInventory();
        if (top == null || top.getType() == InventoryType.PLAYER) return;
        int raw = e.getRawSlot();
        if (raw < 0) return;
        CashEventAction action = null;
        if (e.isShiftClick()) {
            action = raw < top.getSize() ? RETRIEVE : STORE;
        }
        if (action != null) {
            Location loc = p.getLocation();
            if (loc != null) {
                service.trackItem(stack, p.getUniqueId().toString(), action, loc);
            }
        }
        scheduleRescan(p);
    }

    @EventHandler
    public void onDespawn(ItemDespawnEvent e) {
        ItemStack stack = e.getEntity().getItemStack();
        Location loc = e.getLocation();
        if (loc != null) {
            service.trackItem(stack, "", DESTROY, loc);
        }
    }

    @EventHandler
    public void onInventoryMove(InventoryMoveItemEvent e) {
        ItemStack stack = e.getItem();
        Location from = holderLocation(e.getSource().getHolder());
        Location to = holderLocation(e.getDestination().getHolder());
        if (from != null) {
            service.trackItem(stack, "", RETRIEVE, from);
        }
        if (to != null) {
            service.trackItem(stack, "", STORE, to);
        }
    }

    @EventHandler
    public void onFramePlace(HangingPlaceEvent e) {
        if (!(e.getEntity() instanceof ItemFrame frame)) return;
        ItemStack stack = frame.getItem();
        String actor = e.getPlayer() != null ? e.getPlayer().getUniqueId().toString() : "";
        Location loc = frame.getLocation();
        if (loc != null) {
            service.trackItem(stack, actor, STORE, loc);
        }
        if (e.getPlayer() != null) {
            scheduleRescan(e.getPlayer());
        }
    }

    @EventHandler
    public void onFrameBreak(HangingBreakEvent e) {
        if (!(e.getEntity() instanceof ItemFrame frame)) return;
        String actor = "";
        if (e instanceof HangingBreakByEntityEvent by && by.getRemover() instanceof Player p) {
            actor = p.getUniqueId().toString();
        }
        ItemStack stack = frame.getItem();
        Location loc = frame.getLocation();
        if (loc != null) {
            service.trackItem(stack, actor, RETRIEVE, loc);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        BlockState st = e.getBlock().getState();
        if (!(st instanceof InventoryHolder holder)) return;
        Player p = e.getPlayer();
        Location loc = e.getBlock().getLocation();
        if (loc == null) return;
        for (ItemStack s : holder.getInventory().getContents()) {
            service.trackItem(s, p.getUniqueId().toString(), RETRIEVE, loc);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        for (BlockState st : e.getChunk().getTileEntities()) {
            if (st instanceof Chest chest) {
                Location loc = chest.getLocation();
                if (loc != null) {
                    for (ItemStack s : chest.getBlockInventory().getContents()) {
                        service.trackItem(s, "", STORE, loc);
                    }
                }
            } else if (st instanceof InventoryHolder holder) {
                Location loc = st.getLocation();
                if (loc != null) {
                    for (ItemStack s : holder.getInventory().getContents()) {
                        service.trackItem(s, "", STORE, loc);
                    }
                }
            }
        }
    }
}
