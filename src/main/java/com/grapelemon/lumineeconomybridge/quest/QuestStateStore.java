package com.grapelemon.lumineeconomybridge.quest;

import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class QuestStateStore {
    private final LumineEconomyBridge plugin;
    private final File file;
    private YamlConfiguration config;

    public QuestStateStore(LumineEconomyBridge plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "quest_state.yml");
    }

    public void load() {
        if (!file.exists()) {
            config = new YamlConfiguration();
            return;
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        if (config == null) {
            return;
        }
        try {
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                plugin.getLogger().warning("Failed to create quest data folder");
            }
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save quest state: " + ex.getMessage());
        }
    }

    public QuestManager.PlayerQuestState get(UUID playerId) {
        QuestManager.PlayerQuestState state = new QuestManager.PlayerQuestState(playerId);
        if (config == null) {
            return state;
        }
        ConfigurationSection root = config.getConfigurationSection("players." + playerId);
        if (root == null) {
            return state;
        }
        ConfigurationSection groups = root.getConfigurationSection("groups");
        if (groups == null) {
            return state;
        }
        for (String groupId : groups.getKeys(false)) {
            ConfigurationSection groupSection = groups.getConfigurationSection(groupId);
            if (groupSection == null) continue;
            String questId = groupSection.getString("quest_id", "");
            long cycleIndex = groupSection.getLong("cycle_index", -1);
            if (!questId.isBlank() && cycleIndex >= 0) {
                state.getAssignments().put(groupId, new QuestManager.QuestAssignment(questId, cycleIndex));
            }
        }
        return state;
    }

    public void saveState(QuestManager.PlayerQuestState state) {
        if (config == null) {
            config = new YamlConfiguration();
        }
        String base = "players." + state.getPlayerId();
        config.set(base + ".groups", null);
        for (var entry : state.getAssignments().entrySet()) {
            String groupId = entry.getKey();
            QuestManager.QuestAssignment assignment = entry.getValue();
            String prefix = base + ".groups." + groupId;
            config.set(prefix + ".quest_id", assignment.getQuestId());
            config.set(prefix + ".cycle_index", assignment.getCycleIndex());
        }
    }
}
