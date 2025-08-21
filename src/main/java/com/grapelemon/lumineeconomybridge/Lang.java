package com.grapelemon.lumineeconomybridge;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class Lang {
    private static FileConfiguration config;

    public static void load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "lang.yml");
        if (!file.exists()) {
            plugin.saveResource("lang.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    public static String get(String key) {
        String val = config.getString(key, key);
        return ChatColor.translateAlternateColorCodes('&', val);
    }
}
