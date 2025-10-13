package com.grapelemon.lumineeconomybridge;

import com.grapelemon.lumineeconomybridge.protect.ProtectManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class LetTabCompleter implements TabCompleter {
    private final ProtectManager protectManager;

    public LetTabCompleter(ProtectManager protectManager) {
        this.protectManager = protectManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(Arrays.asList("protect"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("protect")) {
            return filter(Arrays.asList("add", "remove"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("protect")) {
            if (args[1].equalsIgnoreCase("remove")) {
                UUID uuid = player.getUniqueId();
                return filter(protectManager.listIdsForPlayer(uuid), args[2]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> options, String token) {
        if (token == null || token.isEmpty()) {
            return new ArrayList<>(options);
        }
        String lower = token.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
