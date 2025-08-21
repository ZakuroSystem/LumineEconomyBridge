package com.grapelemon.lumineeconomybridge.map;

import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Debounce tile updates and coalesce multiple block events.
 */
public class TileDebounceManager {
    private final JavaPlugin plugin;
    private final SnapshotService snapshotService;
    private final long debounceMs;
    private final Map<String, Long> dirty = new ConcurrentHashMap<>();

    public TileDebounceManager(JavaPlugin plugin, SnapshotService snapshotService, long debounceMs) {
        this.plugin = plugin;
        this.snapshotService = snapshotService;
        this.debounceMs = debounceMs;
        new BukkitRunnable() {
            @Override
            public void run() {
                flush();
            }
        }.runTaskTimerAsynchronously(plugin, 20L, 20L);
    }

    private String key(World w, int tx, int tz) {
        return w.getName() + ":" + tx + ":" + tz;
    }

    public void markDirty(World w, int tx, int tz) {
        dirty.put(key(w, tx, tz), System.currentTimeMillis());
    }

    private void flush() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : dirty.entrySet()) {
            if (now - e.getValue() >= debounceMs) {
                String[] parts = e.getKey().split(":");
                World w = plugin.getServer().getWorld(parts[0]);
                int tx = Integer.parseInt(parts[1]);
                int tz = Integer.parseInt(parts[2]);
                if (w != null) {
                    snapshotService.sendTile(w, tx, tz);
                }
                dirty.remove(e.getKey());
            }
        }
    }
}
