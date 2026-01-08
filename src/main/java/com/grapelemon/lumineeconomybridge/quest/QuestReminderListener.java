package com.grapelemon.lumineeconomybridge.quest;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class QuestReminderListener implements Listener {
    private final QuestManager questManager;

    public QuestReminderListener(QuestManager questManager) {
        this.questManager = questManager;
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(questManager.getPlugin(), () -> questManager.recordChat(player));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        questManager.removeReminderState(event.getPlayer().getUniqueId());
    }
}
