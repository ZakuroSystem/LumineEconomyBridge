package com.grapelemon.lumineeconomybridge.quest;

import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            List<QuestManager.QuestAssignment> assignments = new ArrayList<>();
            List<Map<?, ?>> storedAssignments = groupSection.getMapList("assignments");
            for (Map<?, ?> entry : storedAssignments) {
                String questId = entry.get("quest_id") != null ? entry.get("quest_id").toString() : "";
                long cycleIndex = parseCycleIndex(entry.get("cycle_index"));
                if (!questId.isBlank() && cycleIndex >= 0) {
                    assignments.add(new QuestManager.QuestAssignment(questId, cycleIndex));
                }
            }
            if (assignments.isEmpty()) {
                String questId = groupSection.getString("quest_id", "");
                long cycleIndex = groupSection.getLong("cycle_index", -1);
                if (!questId.isBlank() && cycleIndex >= 0) {
                    assignments.add(new QuestManager.QuestAssignment(questId, cycleIndex));
                }
            }
            if (!assignments.isEmpty()) {
                state.getAssignments().put(groupId, assignments);
            }
        }
        ConfigurationSection counters = root.getConfigurationSection("shop_trade_counts");
        if (counters != null) {
            for (String key : counters.getKeys(false)) {
                int count = counters.getInt(key, 0);
                if (count > 0) {
                    state.getShopTradeCounts().put(key, count);
                }
            }
        }

        ConfigurationSection cooldowns = root.getConfigurationSection("quest_cooldowns");
        if (cooldowns != null) {
            for (String questId : cooldowns.getKeys(false)) {
                long until = cooldowns.getLong(questId, 0L);
                if (until > 0L) {
                    state.getQuestCooldownUntil().put(questId, until);
                }
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
            String prefix = base + ".groups." + groupId;
            List<Map<String, Object>> storedAssignments = new ArrayList<>();
            for (QuestManager.QuestAssignment assignment : entry.getValue()) {
                Map<String, Object> record = new HashMap<>();
                record.put("quest_id", assignment.getQuestId());
                record.put("cycle_index", assignment.getCycleIndex());
                storedAssignments.add(record);
            }
            config.set(prefix + ".assignments", storedAssignments);
        }

        config.set(base + ".shop_trade_counts", null);
        for (var entry : state.getShopTradeCounts().entrySet()) {
            config.set(base + ".shop_trade_counts." + entry.getKey(), entry.getValue());
        }

        config.set(base + ".quest_cooldowns", null);
        for (var entry : state.getQuestCooldownUntil().entrySet()) {
            config.set(base + ".quest_cooldowns." + entry.getKey(), entry.getValue());
        }
    }

    private long parseCycleIndex(Object raw) {
        if (raw == null) {
            return -1;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
