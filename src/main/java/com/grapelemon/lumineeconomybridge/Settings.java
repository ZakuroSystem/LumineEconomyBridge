package com.grapelemon.lumineeconomybridge;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Settings {
    private static FileConfiguration config;

    public static void load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "settings.yml");
        if (!file.exists()) {
            plugin.saveResource("settings.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        try (InputStream defaultsStream = plugin.getResource("settings.yml")) {
            if (defaultsStream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defaultsStream, StandardCharsets.UTF_8));
                config.setDefaults(defaults);
                config.options().copyDefaults(true);
                try {
                    config.save(file);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean getBoolean(String key, boolean fallback) {
        if (config == null) {
            return fallback;
        }
        return config.getBoolean(key, fallback);
    }

    public static int getInt(String key, int fallback) {
        if (config == null) {
            return fallback;
        }
        return config.getInt(key, fallback);
    }

    public static String getString(String key, String fallback) {
        if (config == null) {
            return fallback;
        }
        return config.getString(key, fallback);
    }
}
