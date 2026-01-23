package com.grapelemon.lumineeconomybridge.quest;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class QuestCommandExecutor implements CommandExecutor, TabCompleter {
    private final QuestManager questManager;

    public QuestCommandExecutor(QuestManager questManager) {
        this.questManager = questManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /quest <player> [questId]" + ChatColor.RESET);
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player " + args[0] + " is not online" + ChatColor.RESET);
                return true;
            }
            String[] delegated = Arrays.copyOfRange(args, 1, args.length);
            handlePlayerCommand(target, delegated);
            sender.sendMessage(ChatColor.GREEN + "Executed as " + target.getName() + ChatColor.RESET);
            return true;
        }
        handlePlayerCommand(player, args);
        return true;
    }

    private void handlePlayerCommand(Player player, String[] args) {
        if (args.length == 0) {
            questManager.showQuestList(player);
            return;
        }
        if (args.length == 1) {
            questManager.acceptQuest(player, args[0]);
            return;
        }
        player.sendMessage(ChatColor.YELLOW + "Usage: /quest [questId]" + ChatColor.RESET);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return questManager.getActiveOfferIds();
        }
        return Collections.emptyList();
    }
}
