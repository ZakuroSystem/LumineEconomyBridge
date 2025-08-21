package com.grapelemon.lumineeconomybridge;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class LeTabCompleter implements TabCompleter {
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
              return Stream.of("rewrite", "start", "stop", "reload", "money", "deposit", "withdraw", "transfer", "balance", "currency", "setbalance", "history", "account", "undo", "redo", "help", "lang", "backup", "restore", "weblink", "shop")
                      .filter(s -> s.startsWith(args[0].toLowerCase()))
                      .toList();
        }
        if (args.length == 2) {
            String first = args[0].toLowerCase();
            if (first.equals("deposit") || first.equals("withdraw") || first.equals("transfer") || first.equals("setbalance") || first.equals("history")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                names.removeIf(n -> !n.toLowerCase().startsWith(args[1].toLowerCase()));
                return names;
            }
            if (first.equals("currency")) {
                return Stream.of("create", "supply", "default")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .toList();
            }
            if (first.equals("account")) {
                return Stream.of("create")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .toList();
            }
            if (first.equals("money")) {
                return Stream.of("give", "take", "pay", "top")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .toList();
            }
            if (first.equals("shop")) {
                return Stream.of("create", "add", "take", "price", "remove", "reopen", "help")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .toList();
            }
            if (first.equals("balance")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                names.removeIf(n -> !n.toLowerCase().startsWith(args[1].toLowerCase()));
                return names;
            }
            if (first.equals("lang")) {
                return Stream.of("en", "jp")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .toList();
            }
        }
        if (args.length == 3) {
            String first = args[0].toLowerCase();
            if (first.equals("currency") && args[1].equalsIgnoreCase("create")) {
                return Collections.singletonList("<symbol>");
            }
            if (first.equals("currency") && args[1].equalsIgnoreCase("default")) {
                return Collections.singletonList("<id>");
            }
            if (first.equals("money") && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("take") || args[1].equalsIgnoreCase("pay"))) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                names.removeIf(n -> !n.toLowerCase().startsWith(args[2].toLowerCase()));
                return names;
            }
            if (first.equals("balance")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                names.removeIf(n -> !n.toLowerCase().startsWith(args[2].toLowerCase()));
                return names;
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("money") && args[1].equalsIgnoreCase("pay")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            names.removeIf(n -> !n.toLowerCase().startsWith(args[3].toLowerCase()));
            return names;
        }
        return Collections.emptyList();
    }
}
