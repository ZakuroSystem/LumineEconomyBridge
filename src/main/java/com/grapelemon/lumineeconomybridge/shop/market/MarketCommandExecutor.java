package com.grapelemon.lumineeconomybridge.shop.market;

import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MarketCommandExecutor implements CommandExecutor, TabCompleter {

    private final LumineEconomyBridge plugin;
    private final MarketManager marketManager;

    public MarketCommandExecutor(LumineEconomyBridge plugin, MarketManager marketManager) {
        this.plugin = plugin;
        this.marketManager = marketManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                marketManager.openMarket(player);
            } else {
                sender.sendMessage(ChatColor.RED + "プレイヤーのみがマーケットを開けます。" + ChatColor.RESET);
            }
            return true;
        }

        if (!hasAdminPermission(sender)) {
            sender.sendMessage(ChatColor.RED + "No permission" + ChatColor.RESET);
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("add")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /market add <shop>" + ChatColor.RESET);
                return true;
            }
            String shopId = args[1];
            if (marketManager.addShop(shopId)) {
                sender.sendMessage(ChatColor.GREEN + "Added " + ChatColor.YELLOW + shopId + ChatColor.GREEN + " to the market." + ChatColor.RESET);
            } else {
                sender.sendMessage(ChatColor.YELLOW + "That shop is already listed or invalid." + ChatColor.RESET);
            }
            return true;
        }

        if (sub.equals("remove")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /market remove <shop>" + ChatColor.RESET);
                return true;
            }
            String shopId = args[1];
            if (marketManager.removeShop(shopId)) {
                sender.sendMessage(ChatColor.GREEN + "Removed " + ChatColor.YELLOW + shopId + ChatColor.GREEN + " from the market." + ChatColor.RESET);
            } else {
                sender.sendMessage(ChatColor.YELLOW + "That shop is not in the market." + ChatColor.RESET);
            }
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: /market [add|remove] <shop>" + ChatColor.RESET);
        return true;
    }

    private boolean hasAdminPermission(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        return plugin.hasBypass(player) || player.isOp()
                || player.hasPermission("lumineeconomy.admin")
                || player.hasPermission("lumineeconomy.market.admin");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!hasAdminPermission(sender)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if ("add".startsWith(args[0].toLowerCase())) subs.add("add");
            if ("remove".startsWith(args[0].toLowerCase())) subs.add("remove");
            return subs;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            List<String> shops = new ArrayList<>(marketManager.getMarketShops());
            shops.removeIf(s -> !s.toLowerCase().startsWith(args[1].toLowerCase()));
            return shops;
        }
        return Collections.emptyList();
    }
}
