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
            return Stream.of("rewrite", "start", "stop", "reload", "money", "deposit", "withdraw", "transfer", "balance", "currency", "setbalance", "history")
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
                return Stream.of("create")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .toList();
            }
            if (first.equals("money")) {
                return Stream.of("give", "take", "pay")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .toList();
            }
        }
        if (args.length == 3) {
            String first = args[0].toLowerCase();
            if (first.equals("currency") && args[1].equalsIgnoreCase("create")) {
                return Collections.singletonList("<symbol>");
            }
            if (first.equals("money")) {
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
