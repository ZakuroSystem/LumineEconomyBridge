package com.grapelemon.lumineeconomybridge;

import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import io.papermc.paper.event.player.PlayerScoreChangeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

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
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ScoreboardSyncService sync = plugin.getSyncService();
        if (sync != null) {
            sync.cleanup(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onScoreChange(PlayerScoreChangeEvent e) {
        ScoreboardSyncService sync = plugin.getSyncService();
        if (sync == null) return;
        String obj = e.getObjective().getName();
        if (!obj.equalsIgnoreCase("currency1") && !obj.equalsIgnoreCase("currency2")) return;
        sync.sendDelta(e.getPlayer());
    }
}
