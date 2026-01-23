package com.grapelemon.lumineeconomybridge;

import com.grapelemon.lumineeconomybridge.protect.ProtectManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;

import java.util.Arrays;

public class LetCommandExecutor implements CommandExecutor {
    private final ProtectManager protectManager;

    public LetCommandExecutor(ProtectManager protectManager) {
        this.protectManager = protectManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /let <player> protect <add|remove> ..." + ChatColor.RESET);
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player " + args[0] + " is not online" + ChatColor.RESET);
                return true;
            }
            String[] delegated = Arrays.copyOfRange(args, 1, args.length);
            if (delegated.length == 0) {
                sendHelp(target);
                sender.sendMessage(ChatColor.GREEN + "Sent help to " + target.getName() + ChatColor.RESET);
                return true;
            }
            handlePlayerCommand(target, delegated);
            sender.sendMessage(ChatColor.GREEN + "Executed as " + target.getName() + ChatColor.RESET);
            return true;
        }

        handlePlayerCommand(player, args);
        return true;
    }

    private void handlePlayerCommand(Player player, String[] args) {
        if (args.length == 0) {
            sendHelp(player);
            return;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("protect")) {
            handleProtect(player, args);
            return;
        }
        sendHelp(player);
    }

    private void handleProtect(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /" + "let protect add [id]" + ChatColor.RESET);
            player.sendMessage(ChatColor.YELLOW + "       /let protect remove <id>" + ChatColor.RESET);
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "add" -> handleProtectAdd(player, args);
            case "remove" -> handleProtectRemove(player, args);
            default -> player.sendMessage(ChatColor.RED + "不明な操作です。/ Unknown protect action." + ChatColor.RESET);
        }
    }

    private void handleProtectAdd(Player player, String[] args) {
        if (args.length >= 3) {
            String id = args[2];
            if (protectManager.isSelectionComplete(player.getUniqueId())) {
                protectManager.finalizeProtection(player, id, false);
            } else {
                protectManager.startSelection(player, id);
            }
            protectManager.updateInitialId(player, id);
        } else {
            if (!protectManager.hasSession(player.getUniqueId())) {
                protectManager.startSelection(player, null);
                return;
            }
            if (protectManager.isSelectionComplete(player.getUniqueId())) {
                protectManager.promptForName(player);
            } else {
                player.sendMessage(ChatColor.YELLOW + "Breeze Rodで2点を選択してください。/ Select both corners with the Breeze Rod." + ChatColor.RESET);
            }
        }
    }

    private void handleProtectRemove(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /let protect remove <id>" + ChatColor.RESET);
            return;
        }
        protectManager.requestRemoval(player, args[2]);
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.AQUA + "=== /let Commands ===" + ChatColor.RESET);
        player.sendMessage(ChatColor.YELLOW + "/let protect add [id]" + ChatColor.WHITE + " - 保護範囲を選択 / Start a protection selection" + ChatColor.RESET);
        player.sendMessage(ChatColor.YELLOW + "/let protect remove <id>" + ChatColor.WHITE + " - 保護を解除 / Remove a protection" + ChatColor.RESET);
    }
}
