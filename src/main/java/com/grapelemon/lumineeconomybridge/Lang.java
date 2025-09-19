package com.grapelemon.lumineeconomybridge;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Lang {
    private static FileConfiguration config;

    public static void load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "lang.yml");
        if (!file.exists()) {
            plugin.saveResource("lang.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        try (InputStream defaultsStream = plugin.getResource("lang.yml")) {
            if (defaultsStream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defaultsStream, StandardCharsets.UTF_8));
                config.setDefaults(defaults);
                config.options().copyDefaults(true);
            }
        } catch (Exception ignored) {
        }
    }

    public static String get(String key) {
        String val = config.getString(key, key);
        return ChatColor.translateAlternateColorCodes('&', val);
    }
}
