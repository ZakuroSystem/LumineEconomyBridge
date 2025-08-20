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
            return Stream.of("rewrite", "start", "stop", "reload", "pay", "currency", "setbalance", "history")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2) {
            String first = args[0].toLowerCase();
            if (first.equals("pay") || first.equals("setbalance") || first.equals("history")) {
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
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("currency") && args[1].equalsIgnoreCase("create")) {
            return Collections.singletonList("<symbol>");
        }
        return Collections.emptyList();
    }
}
