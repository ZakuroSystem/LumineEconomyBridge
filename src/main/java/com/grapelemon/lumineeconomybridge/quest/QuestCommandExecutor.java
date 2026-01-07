package com.grapelemon.lumineeconomybridge.quest;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

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
            sender.sendMessage(ChatColor.RED + "このコマンドはプレイヤー専用です。/ Players only." + ChatColor.RESET);
            return true;
        }
        if (args.length == 0) {
            questManager.showQuestList(player);
            return true;
        }
        if (args.length == 1) {
            questManager.acceptQuest(player, args[0]);
            return true;
        }
        player.sendMessage(ChatColor.YELLOW + "Usage: /quest [questId]" + ChatColor.RESET);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return questManager.getActiveOfferIds();
        }
        return Collections.emptyList();
    }
}
