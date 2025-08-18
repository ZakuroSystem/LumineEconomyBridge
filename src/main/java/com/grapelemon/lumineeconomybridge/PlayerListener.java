package com.grapelemon.lumineeconomybridge;

import com.grapelemon.lumineeconomybridge.sync.ScoreboardSyncService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener implements Listener {

    private final ScoreboardSyncService sync;

    public PlayerListener(ScoreboardSyncService sync) {
        this.sync = sync;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        sync.seed(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        sync.cleanup(e.getPlayer().getUniqueId());
    }
}
