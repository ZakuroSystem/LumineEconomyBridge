package com.grapelemon.lumineeconomybridge.protect;

import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class ProtectListener implements Listener {
    private final LumineEconomyBridge plugin;
    private final ProtectManager manager;

    public ProtectListener(LumineEconomyBridge plugin, ProtectManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!manager.isWand(event.getItem())) {
            Block clicked = event.getClickedBlock();
            if (clicked != null && (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_BLOCK)) {
                if (!manager.canAccess(player, clicked.getLocation())) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "この保護エリアは利用できません。/ Protected area." + ChatColor.RESET);
                }
            }
            return;
        }
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        event.setCancelled(true);
        Location loc = clicked.getLocation();
        if (action == Action.LEFT_CLICK_BLOCK) {
            manager.recordFirst(player, loc);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            manager.recordSecond(player, loc);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!manager.canAccess(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "この保護エリアではブロック破壊できません。" + ChatColor.RESET);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!manager.canAccess(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "この保護エリアではブロック設置できません。" + ChatColor.RESET);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        boolean awaitingName = manager.isAwaitingName(player.getUniqueId());
        boolean awaitingApproval = manager.isAwaitingApproval(player.getUniqueId());
        boolean awaitingRemoval = manager.isAwaitingRemoval(player.getUniqueId());
        boolean awaitingLease = manager.isAwaitingLease(player.getUniqueId());
        if (!awaitingName && !awaitingApproval && !awaitingRemoval && !awaitingLease) {
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (awaitingName) {
                player.sendMessage(ChatColor.GRAY + "入力を受け取りました..." + ChatColor.RESET);
                manager.handleNameResponse(player, message);
            } else if (awaitingApproval) {
                manager.handleApprovalResponse(player, message);
            } else if (awaitingLease) {
                manager.handleLeaseResponse(player, message);
            } else if (awaitingRemoval) {
                manager.handleRemovalResponse(player, message);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.cancelAll(event.getPlayer());
    }
}
