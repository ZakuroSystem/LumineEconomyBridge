package com.grapelemon.lumineeconomybridge.protect;

import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

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
        ItemStack item = event.getItem();
        Block clicked = event.getClickedBlock();

        if (!manager.isWand(item)) {
            if (clicked != null && clicked.getState() instanceof Sign sign && event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                if (manager.hasLeaseSign(sign)) {
                    event.setCancelled(true);
                    manager.showLeaseSignInfo(player, sign);
                    manager.promptLeaseBySign(player, sign);
                    return;
                }
            }
            if (clicked != null && (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_BLOCK)) {
                if (!manager.isActionAllowed(player, clicked.getLocation(), ProtectManager.ProtectAction.INTERACT)) {
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
        if (clicked == null) {
            return;
        }
        manager.applyWandMode(player, item);
        event.setCancelled(true);
        Location loc = clicked.getLocation();
        if (action == Action.LEFT_CLICK_BLOCK) {
            manager.recordFirst(player, loc);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            manager.recordSecond(player, loc);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        String data = manager.getLeaseSignTemplateData(hand);
        if (data == null) {
            return;
        }
        String[] parts = data.split("\\|", 3);
        if (parts.length < 3) {
            return;
        }
        String regionId = parts[0];
        String periodRaw = parts[1];
        int priceUnits;
        try {
            priceUnits = Integer.parseInt(parts[2]);
        } catch (NumberFormatException ex) {
            priceUnits = 0;
        }
        if (!(event.getBlock().getState() instanceof Sign sign)) {
            return;
        }
        manager.registerLeaseSign(sign, event.getPlayer(), regionId, periodRaw, priceUnits);
        event.setLine(0, regionId);
        event.setLine(1, periodRaw);
        event.setLine(2, String.valueOf(priceUnits));
        event.setLine(3, event.getPlayer().getName());
        event.getPlayer().sendMessage(ChatColor.GREEN + "貸出看板を作成しました。" + ChatColor.RESET);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getState() instanceof Sign sign && manager.hasLeaseSign(sign)) {
            if (!manager.canBreakLeaseSign(event.getPlayer(), sign)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "この看板は設置者のみ破壊できます。" + ChatColor.RESET);
                return;
            }
            manager.removeLeaseSign(sign);
        }
        if (!manager.isActionAllowed(event.getPlayer(), event.getBlock().getLocation(), ProtectManager.ProtectAction.BREAK)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "この保護エリアではブロック破壊できません。" + ChatColor.RESET);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!manager.isActionAllowed(event.getPlayer(), event.getBlock().getLocation(), ProtectManager.ProtectAction.PLACE)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "この保護エリアではブロック設置できません。" + ChatColor.RESET);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            if (!manager.isActionAllowed(player, player.getLocation(), ProtectManager.ProtectAction.INVENTORY)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "この保護エリアではインベントリ操作できません。" + ChatColor.RESET);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (!manager.isActionAllowed(player, player.getLocation(), ProtectManager.ProtectAction.INVENTORY)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!manager.isActionAllowed(event.getPlayer(), event.getPlayer().getLocation(), ProtectManager.ProtectAction.DROP)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "この保護エリアではアイテムドロップできません。" + ChatColor.RESET);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim && event.getDamager() instanceof Player attacker) {
            if (!manager.isActionAllowed(attacker, victim.getLocation(), ProtectManager.ProtectAction.PVP)) {
                event.setCancelled(true);
                attacker.sendMessage(ChatColor.RED + "この保護エリアではPvPできません。" + ChatColor.RESET);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (!manager.isActionAllowed(event.getPlayer(), to, ProtectManager.ProtectAction.ENTER)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "この保護エリアには立ち入れません。" + ChatColor.RESET);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        boolean awaitingName = manager.isAwaitingName(player.getUniqueId());
        boolean awaitingApproval = manager.isAwaitingApproval(player.getUniqueId());
        boolean awaitingRemoval = manager.isAwaitingRemoval(player.getUniqueId());
        boolean awaitingLease = manager.isAwaitingLease(player.getUniqueId());
        boolean awaitingSignWizard = manager.isAwaitingSignWizard(player.getUniqueId());
        boolean awaitingLeaseConfirm = manager.isAwaitingLeaseConfirm(player.getUniqueId());
        if (!awaitingName && !awaitingApproval && !awaitingRemoval && !awaitingLease && !awaitingSignWizard && !awaitingLeaseConfirm) {
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
            } else if (awaitingSignWizard) {
                manager.handleSignWizardResponse(player, message);
            } else if (awaitingLeaseConfirm) {
                manager.handleLeaseConfirmResponse(player, message);
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
