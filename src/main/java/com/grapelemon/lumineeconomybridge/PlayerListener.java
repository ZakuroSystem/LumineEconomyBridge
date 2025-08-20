package com.grapelemon.lumineeconomybridge;

import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import com.grapelemon.lumineeconomybridge.Lang;

public class PlayerListener implements Listener {

    private final LumineEconomyBridge plugin;

    public PlayerListener(LumineEconomyBridge plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        ScoreboardSyncService sync = plugin.getSyncService();
        if (sync != null) {
            sync.seed(e.getPlayer());
        }
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                e.getPlayer().sendMessage(Lang.get("lang.jp_hint")), 5 * 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ScoreboardSyncService sync = plugin.getSyncService();
        if (sync != null) {
            sync.cleanup(e.getPlayer().getUniqueId());
        }
    }

}
