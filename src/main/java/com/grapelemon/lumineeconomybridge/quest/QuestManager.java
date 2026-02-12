package com.grapelemon.lumineeconomybridge.quest;

import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.grapelemon.lumineeconomybridge.Lang;
import com.grapelemon.lumineeconomybridge.Settings;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class QuestManager {
    private static final long REMINDER_CHECK_INTERVAL_TICKS = 20L * 5L;
    private final LumineEconomyBridge plugin;
    private QuestConfig config;
    private QuestStateStore stateStore;
    private BukkitTask reminderTask;
    private boolean reminderEnabled;
    private int reminderIntervalMinutes;
    private int reminderChatThreshold;
    private ReminderCondition reminderCondition;
    private String reminderMessage;
    private final Map<UUID, ReminderState> reminderStates = new ConcurrentHashMap<>();

    public QuestManager(LumineEconomyBridge plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.config = loadConfig(plugin.getConfig());
        if (stateStore == null) {
            stateStore = new QuestStateStore(plugin);
        }
        stateStore.load();
        reloadReminderSettings();
    }

    public LumineEconomyBridge getPlugin() {
        return plugin;
    }

    public void shutdown() {
        if (stateStore != null) {
            stateStore.save();
        }
        if (reminderTask != null) {
            reminderTask.cancel();
            reminderTask = null;
        }
    }

    public void showQuestList(Player player) {
        if (config == null || config.groups.isEmpty()) {
            player.sendMessage(Lang.get("quest.no_config"));
            return;
        }
        PlayerQuestState playerState = stateStore.get(player.getUniqueId());
        ZonedDateTime now = ZonedDateTime.now(config.zoneId);
        clearExpiredStates(playerState, now);

        player.sendMessage(Lang.get("quest.list.header"));
        boolean anyActive = false;
        for (QuestGroup group : config.groups.values()) {
            ActiveOffer active = getActiveOffer(group, now);
            if (active == null) {
                continue;
            }
            anyActive = true;
            String title = colorize(Lang.get("quest.list.group_title")
                    .replace("{name}", group.name)
                    .replace("{id}", group.id));
            player.sendMessage(title);
            List<QuestAssignment> assignments = playerState.assignments.get(group.id);
            int acceptedCount = countAssignments(assignments, active.offer.id);
            if (acceptedCount > 0) {
                QuestProgress progress = evaluateProgress(player, active.offer);
                player.sendMessage(colorize(Lang.get("quest.list.accepted")
                        .replace("{quest}", formatQuestName(active.offer))
                        .replace("{count}", String.valueOf(acceptedCount))));
                player.sendMessage(colorize(Lang.get("quest.list.progress").replace("{progress}", progress.label)));
                player.sendMessage(colorize(Lang.get("quest.list.remaining")
                        .replace("{remaining}", formatRemaining(active.endsAt, now))));
            } else {
                player.sendMessage(colorize(Lang.get("quest.list.available")
                        .replace("{quest}", formatQuestName(active.offer))));
                player.sendMessage(colorize(Lang.get("quest.list.remaining")
                        .replace("{remaining}", formatRemaining(active.endsAt, now))));
            }
        }
        if (!anyActive) {
            player.sendMessage(Lang.get("quest.list.none_available"));
        }
        stateStore.saveState(playerState);
        stateStore.save();
    }

    public List<QuestDisplayEntry> getActiveQuestEntries(Player player) {
        if (config == null || config.groups.isEmpty()) {
            return Collections.emptyList();
        }
        PlayerQuestState playerState = stateStore.get(player.getUniqueId());
        ZonedDateTime now = ZonedDateTime.now(config.zoneId);
        boolean updated = clearExpiredStates(playerState, now);
        List<QuestDisplayEntry> entries = new ArrayList<>();
        for (QuestGroup group : config.groups.values()) {
            ActiveOffer active = getActiveOffer(group, now);
            if (active == null) {
                continue;
            }
            List<QuestAssignment> assignments = playerState.assignments.get(group.id);
            int acceptedCount = countAssignments(assignments, active.offer.id);
            QuestProgress progressState = evaluateProgress(player, active.offer);
            String progress = acceptedCount > 0 ? progressState.label : null;
            entries.add(new QuestDisplayEntry(
                    active.offer.id,
                    colorize(active.offer.displayName),
                    colorize(active.offer.description),
                    group.name,
                    formatRemaining(active.endsAt, now),
                    acceptedCount,
                    progress,
                    progressState.complete
            ));
        }
        if (updated) {
            stateStore.saveState(playerState);
            stateStore.save();
        }
        return entries;
    }

    public void acceptQuest(Player player, String questId) {
        if (config == null || config.groups.isEmpty()) {
            player.sendMessage(Lang.get("quest.no_config"));
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(config.zoneId);
        PlayerQuestState playerState = stateStore.get(player.getUniqueId());
        clearExpiredStates(playerState, now);
        stateStore.saveState(playerState);
        stateStore.save();

        ActiveOffer target = null;
        QuestGroup targetGroup = null;
        for (QuestGroup group : config.groups.values()) {
            ActiveOffer active = getActiveOffer(group, now);
            if (active != null && active.offer.id.equalsIgnoreCase(questId)) {
                target = active;
                targetGroup = group;
                break;
            }
        }
        if (target == null || targetGroup == null) {
            player.sendMessage(Lang.get("quest.accept.not_available"));
            return;
        }
        List<QuestAssignment> assignments = playerState.assignments.computeIfAbsent(targetGroup.id, key -> new ArrayList<>());
        int limit = targetGroup.rules.acceptLimitPerPlayer;
        if (limit <= 0) {
            player.sendMessage(Lang.get("quest.accept.disabled"));
            return;
        }
        if (assignments.size() >= limit) {
            if (limit <= 1) {
                player.sendMessage(Lang.get("quest.accept.already"));
            } else {
                player.sendMessage(Lang.get("quest.accept.limit"));
            }
            return;
        }
        assignments.add(new QuestAssignment(target.offer.id, target.cycleIndex));
        stateStore.saveState(playerState);
        stateStore.save();
        player.sendMessage(colorize(Lang.get("quest.accept.success")
                .replace("{quest}", formatQuestName(target.offer))));
        player.sendMessage(colorize(Lang.get("quest.accept.remaining")
                .replace("{remaining}", formatRemaining(target.endsAt, now))));
    }

    public void submitQuestReport(Player player, String questId) {
        if (config == null || config.groups.isEmpty()) {
            player.sendMessage(Lang.get("quest.no_config"));
            return;
        }
        PlayerQuestState playerState = stateStore.get(player.getUniqueId());
        ZonedDateTime now = ZonedDateTime.now(config.zoneId);
        boolean updated = clearExpiredStates(playerState, now);
        QuestGroup targetGroup = null;
        ActiveOffer target = null;
        for (QuestGroup group : config.groups.values()) {
            ActiveOffer active = getActiveOffer(group, now);
            if (active != null && active.offer.id.equalsIgnoreCase(questId)) {
                targetGroup = group;
                target = active;
                break;
            }
        }
        if (target == null || targetGroup == null) {
            player.sendMessage(Lang.get("quest.report.not_available"));
            if (updated) {
                stateStore.saveState(playerState);
                stateStore.save();
            }
            return;
        }
        List<QuestAssignment> assignments = playerState.assignments.get(targetGroup.id);
        QuestAssignment assignment = findAssignment(assignments, target.offer.id);
        if (assignment == null) {
            player.sendMessage(Lang.get("quest.report.not_accepted"));
            if (updated) {
                stateStore.saveState(playerState);
                stateStore.save();
            }
            return;
        }
        QuestProgress progress = evaluateProgress(player, target.offer);
        if (!progress.complete) {
            player.sendMessage(colorize(Lang.get("quest.report.not_complete")
                    .replace("{progress}", progress.label)));
            if (updated) {
                stateStore.saveState(playerState);
                stateStore.save();
            }
            return;
        }
        if (!completeQuest(player, targetGroup, target.offer, progress)) {
            player.sendMessage(Lang.get("quest.report.consume_failed"));
            return;
        }
        assignments.remove(assignment);
        if (assignments.isEmpty()) {
            playerState.assignments.remove(targetGroup.id);
        }
        stateStore.saveState(playerState);
        stateStore.save();
    }


    public boolean isAccepted(Player player, String questId) {
        if (config == null || config.groups.isEmpty()) {
            return false;
        }
        PlayerQuestState state = stateStore.get(player.getUniqueId());
        ZonedDateTime now = ZonedDateTime.now(config.zoneId);
        clearExpiredStates(state, now);
        for (QuestGroup group : config.groups.values()) {
            ActiveOffer active = getActiveOffer(group, now);
            if (active == null || !active.offer.id.equalsIgnoreCase(questId)) {
                continue;
            }
            List<QuestAssignment> assignments = state.assignments.get(group.id);
            return findAssignment(assignments, active.offer.id) != null;
        }
        return false;
    }

    public List<String> getActiveOfferIds() {
        if (config == null || config.groups.isEmpty()) {
            return Collections.emptyList();
        }
        ZonedDateTime now = ZonedDateTime.now(config.zoneId);
        List<String> ids = new ArrayList<>();
        for (QuestGroup group : config.groups.values()) {
            ActiveOffer active = getActiveOffer(group, now);
            if (active != null) {
                ids.add(active.offer.id);
            }
        }
        return ids;
    }

    private boolean clearExpiredStates(PlayerQuestState state, ZonedDateTime now) {
        Map<String, List<QuestAssignment>> updated = new HashMap<>();
        for (QuestGroup group : config.groups.values()) {
            long currentCycle = computeCycleIndex(group.schedule, now);
            List<QuestAssignment> assignments = state.assignments.get(group.id);
            if (assignments == null || assignments.isEmpty()) {
                continue;
            }
            if (!group.rules.expireOnCycleChange) {
                updated.put(group.id, assignments);
                continue;
            }
            List<QuestAssignment> filtered = new ArrayList<>();
            for (QuestAssignment assignment : assignments) {
                if (currentCycle >= 0 && assignment.cycleIndex == currentCycle) {
                    filtered.add(assignment);
                }
            }
            if (!filtered.isEmpty()) {
                updated.put(group.id, filtered);
            }
        }
        boolean changed = !updated.equals(state.assignments);
        state.assignments.clear();
        state.assignments.putAll(updated);
        return changed;
    }

    private ActiveOffer getActiveOffer(QuestGroup group, ZonedDateTime now) {
        long cycleIndex = computeCycleIndex(group.schedule, now);
        if (cycleIndex < 0 || group.offers.isEmpty()) {
            return null;
        }
        int offerIndex = selectOfferIndex(group, cycleIndex);
        QuestOffer offer = group.offers.get(offerIndex);
        ZonedDateTime endAt = group.schedule.start.plusHours((cycleIndex + 1) * group.schedule.periodHours);
        return new ActiveOffer(offer, cycleIndex, endAt);
    }

    private int selectOfferIndex(QuestGroup group, long cycleIndex) {
        long seed = hashSeed(config.seed, cycleIndex, group.id);
        return (int) Math.floorMod(seed, group.offers.size());
    }

    private long computeCycleIndex(QuestSchedule schedule, ZonedDateTime now) {
        if (schedule.periodHours <= 0) {
            return -1;
        }
        if (now.isBefore(schedule.start)) {
            return -1;
        }
        Duration elapsed = Duration.between(schedule.start, now);
        return elapsed.toHours() / schedule.periodHours;
    }

    private QuestProgress evaluateProgress(Player player, QuestOffer offer) {
        if (offer.conditions.items.isEmpty()) {
            return new QuestProgress(false, Lang.get("quest.progress.no_conditions"));
        }
        PlayerInventory inventory = player.getInventory();
        List<String> parts = new ArrayList<>();
        boolean complete = offer.conditions.op == ConditionOp.AND;
        for (QuestItem item : offer.conditions.items) {
            int count = countMaterial(inventory, item.type);
            String label = item.displayName + " " + count + "/" + item.amount;
            parts.add(label);
            if (offer.conditions.op == ConditionOp.AND) {
                if (count < item.amount) {
                    complete = false;
                }
            } else {
                if (count >= item.amount) {
                    complete = true;
                }
            }
        }
        String suffix = offer.conditions.op == ConditionOp.OR ? Lang.get("quest.progress.or_suffix") : "";
        return new QuestProgress(complete, String.join(", ", parts) + suffix);
    }

    private boolean completeQuest(Player player, QuestGroup group, QuestOffer offer, QuestProgress progress) {
        if (group.rules.consumeItemsOnComplete) {
            if (!consumeItems(player.getInventory(), offer)) {
                return false;
            }
        }
        grantRewards(player, offer.rewards);
        player.sendMessage(colorize(Lang.get("quest.complete").replace("{quest}", formatQuestName(offer))));
        return true;
    }

    private boolean consumeItems(PlayerInventory inventory, QuestOffer offer) {
        if (offer.conditions.items.isEmpty()) {
            return true;
        }
        if (offer.conditions.op == ConditionOp.AND) {
            for (QuestItem item : offer.conditions.items) {
                if (countMaterial(inventory, item.type) < item.amount) {
                    return false;
                }
            }
            for (QuestItem item : offer.conditions.items) {
                removeMaterial(inventory, item.type, item.amount);
            }
            return true;
        }
        for (QuestItem item : offer.conditions.items) {
            if (countMaterial(inventory, item.type) >= item.amount) {
                removeMaterial(inventory, item.type, item.amount);
                return true;
            }
        }
        return false;
    }

    private void grantRewards(Player player, QuestRewards rewards) {
        if (rewards.money > 0) {
            RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (provider != null) {
                provider.getProvider().depositPlayer(player, rewards.money);
            } else {
                player.sendMessage(Lang.get("quest.economy_unavailable"));
            }
        }
        if (!rewards.items.isEmpty()) {
            for (QuestRewardItem rewardItem : rewards.items) {
                ItemStack stack = new ItemStack(rewardItem.type, rewardItem.amount);
                if (rewardItem.nbt != null && !rewardItem.nbt.isBlank()) {
                    try {
                        stack = Bukkit.getUnsafe().modifyItemStack(stack, rewardItem.nbt);
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("Invalid quest reward NBT: " + ex.getMessage());
                    }
                }
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
                if (!overflow.isEmpty()) {
                    overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                }
            }
        }
    }

    private int countMaterial(PlayerInventory inventory, Material material) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private void removeMaterial(PlayerInventory inventory, Material material, int amount) {
        int remaining = amount;
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (item == null || item.getType() != material) continue;
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            if (item.getAmount() <= 0) {
                inventory.setItem(i, null);
            } else {
                inventory.setItem(i, item);
            }
            remaining -= take;
            if (remaining <= 0) {
                return;
            }
        }
    }

    private String formatRemaining(ZonedDateTime endsAt, ZonedDateTime now) {
        Duration remaining = Duration.between(now, endsAt);
        if (remaining.isNegative()) {
            return "0m";
        }
        long totalSeconds = remaining.getSeconds();
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0 || days > 0) sb.append(hours).append("h ");
        if (minutes > 0 || hours > 0 || days > 0) sb.append(minutes).append("m ");
        if (days == 0 && hours == 0) sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    private QuestConfig loadConfig(FileConfiguration cfg) {
        ConfigurationSection root = cfg.getConfigurationSection("quest");
        if (root == null) {
            return new QuestConfig("default", ZoneId.systemDefault(), Collections.emptyMap());
        }
        String seed = root.getString("seed", "default");
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(root.getString("timezone", "UTC"));
        } catch (Exception ex) {
            zoneId = ZoneId.systemDefault();
        }
        ConfigurationSection groupsSection = root.getConfigurationSection("groups");
        Map<String, QuestGroup> groups = new HashMap<>();
        if (groupsSection != null) {
            for (String groupId : groupsSection.getKeys(false)) {
                ConfigurationSection groupSection = groupsSection.getConfigurationSection(groupId);
                if (groupSection == null) continue;
                QuestGroup group = parseGroup(groupId, groupSection, zoneId);
                if (group != null) {
                    groups.put(groupId, group);
                }
            }
        }
        return new QuestConfig(seed, zoneId, groups);
    }

    private QuestGroup parseGroup(String groupId, ConfigurationSection groupSection, ZoneId zoneId) {
        String name = groupSection.getString("name", groupId);
        ConfigurationSection scheduleSection = groupSection.getConfigurationSection("schedule");
        if (scheduleSection == null) {
            plugin.getLogger().warning("Quest group missing schedule: " + groupId);
            return null;
        }
        String startRaw = scheduleSection.getString("start", "");
        ZonedDateTime start;
        try {
            start = ZonedDateTime.parse(startRaw).withZoneSameInstant(zoneId);
        } catch (DateTimeParseException ex) {
            plugin.getLogger().warning("Invalid quest schedule start for " + groupId + ": " + startRaw);
            return null;
        }
        long periodHours = scheduleSection.getLong("period_hours", 0);
        ConfigurationSection rulesSection = groupSection.getConfigurationSection("rules");
        QuestRules rules = new QuestRules(
                rulesSection != null ? rulesSection.getInt("accept_limit_per_player", 1) : 1,
                rulesSection == null || rulesSection.getBoolean("expire_on_cycle_change", true),
                rulesSection == null || rulesSection.getBoolean("consume_items_on_complete", true)
        );
        List<QuestOffer> offers = new ArrayList<>();
        List<Map<?, ?>> offerMaps = groupSection.getMapList("offers");
        for (Map<?, ?> entry : offerMaps) {
            QuestOffer offer = parseOffer(entry);
            if (offer != null) {
                offers.add(offer);
            }
        }
        if (offers.isEmpty()) {
            plugin.getLogger().warning("Quest group has no offers: " + groupId);
        }
        return new QuestGroup(groupId, name, new QuestSchedule(start, periodHours), rules, offers);
    }

    private QuestOffer parseOffer(Map<?, ?> map) {
        Object idRaw = map.get("id");
        if (idRaw == null) {
            return null;
        }
        String id = idRaw.toString();
        String displayName = Objects.toString(map.get("name"), "");
        if (displayName.isBlank()) {
            displayName = Objects.toString(map.get("display_name"), "");
        }
        if (displayName.isBlank()) {
            displayName = id;
        }
        String description = Objects.toString(map.get("description"), "");
        if (description.isBlank()) {
            description = Objects.toString(map.get("desc"), "");
        }
        QuestConditions conditions = parseConditions(map.get("conditions"));
        QuestRewards rewards = parseRewards(map.get("rewards"));
        return new QuestOffer(id, displayName, description, conditions, rewards);
    }

    private QuestConditions parseConditions(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return new QuestConditions(ConditionOp.AND, Collections.emptyList());
        }
        String opRaw = Objects.toString(map.get("op"), "AND");
        ConditionOp op = opRaw.equalsIgnoreCase("OR") ? ConditionOp.OR : ConditionOp.AND;
        List<QuestItem> items = new ArrayList<>();
        Object itemRaw = map.get("items");
        if (itemRaw instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> itemMap)) continue;
                QuestItem item = parseQuestItem(itemMap);
                if (item != null) {
                    items.add(item);
                }
            }
        }
        return new QuestConditions(op, items);
    }

    private QuestRewards parseRewards(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return new QuestRewards(0, Collections.emptyList());
        }
        int money = 0;
        Object moneyRaw = map.get("money");
        if (moneyRaw != null) {
            try {
                money = Integer.parseInt(moneyRaw.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        List<QuestRewardItem> items = new ArrayList<>();
        Object itemsRaw = map.get("items");
        if (itemsRaw instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> itemMap)) continue;
                QuestRewardItem reward = parseRewardItem(itemMap);
                if (reward != null) {
                    items.add(reward);
                }
            }
        }
        return new QuestRewards(money, items);
    }

    private QuestItem parseQuestItem(Map<?, ?> map) {
        String typeRaw = Objects.toString(map.get("type"), "");
        int amount = parseAmount(map.get("amount"), 1);
        Material material = parseMaterial(typeRaw);
        if (material == null) {
            plugin.getLogger().warning("Invalid quest item type: " + typeRaw);
            return null;
        }
        return new QuestItem(material, amount, material.name().toLowerCase(Locale.ROOT));
    }

    private QuestRewardItem parseRewardItem(Map<?, ?> map) {
        String typeRaw = Objects.toString(map.get("type"), "");
        int amount = parseAmount(map.get("amount"), 1);
        Material material = parseMaterial(typeRaw);
        if (material == null) {
            plugin.getLogger().warning("Invalid reward item type: " + typeRaw);
            return null;
        }
        String nbt = Objects.toString(map.get("nbt"), "");
        return new QuestRewardItem(material, amount, nbt);
    }

    private int parseAmount(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.toString()));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Material parseMaterial(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(raw);
        if (material != null) {
            return material;
        }
        String normalized = raw.toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
        material = Material.matchMaterial(normalized);
        if (material != null) {
            return material;
        }
        NamespacedKey key = NamespacedKey.fromString(raw);
        if (key != null) {
            return Material.matchMaterial(key.getKey().toUpperCase(Locale.ROOT));
        }
        return null;
    }

    private long hashSeed(String seed, long cycleIndex, String groupId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(seed.getBytes(StandardCharsets.UTF_8));
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(cycleIndex).array());
            digest.update(groupId.getBytes(StandardCharsets.UTF_8));
            byte[] bytes = digest.digest();
            return ByteBuffer.wrap(bytes, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException ex) {
            return Objects.hash(seed, cycleIndex, groupId);
        }
    }

    private static class QuestConfig {
        private final String seed;
        private final ZoneId zoneId;
        private final Map<String, QuestGroup> groups;

        private QuestConfig(String seed, ZoneId zoneId, Map<String, QuestGroup> groups) {
            this.seed = seed;
            this.zoneId = zoneId;
            this.groups = groups;
        }
    }

    private static class QuestGroup {
        private final String id;
        private final String name;
        private final QuestSchedule schedule;
        private final QuestRules rules;
        private final List<QuestOffer> offers;

        private QuestGroup(String id, String name, QuestSchedule schedule, QuestRules rules, List<QuestOffer> offers) {
            this.id = id;
            this.name = name;
            this.schedule = schedule;
            this.rules = rules;
            this.offers = offers;
        }
    }

    private static class QuestSchedule {
        private final ZonedDateTime start;
        private final long periodHours;

        private QuestSchedule(ZonedDateTime start, long periodHours) {
            this.start = start;
            this.periodHours = periodHours;
        }
    }

    private static class QuestRules {
        private final int acceptLimitPerPlayer;
        private final boolean expireOnCycleChange;
        private final boolean consumeItemsOnComplete;

        private QuestRules(int acceptLimitPerPlayer, boolean expireOnCycleChange, boolean consumeItemsOnComplete) {
            this.acceptLimitPerPlayer = acceptLimitPerPlayer;
            this.expireOnCycleChange = expireOnCycleChange;
            this.consumeItemsOnComplete = consumeItemsOnComplete;
        }
    }

    private static class QuestOffer {
        private final String id;
        private final String displayName;
        private final String description;
        private final QuestConditions conditions;
        private final QuestRewards rewards;

        private QuestOffer(String id, String displayName, String description, QuestConditions conditions, QuestRewards rewards) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.conditions = conditions;
            this.rewards = rewards;
        }
    }

    private static class QuestConditions {
        private final ConditionOp op;
        private final List<QuestItem> items;

        private QuestConditions(ConditionOp op, List<QuestItem> items) {
            this.op = op;
            this.items = items;
        }
    }

    private static class QuestItem {
        private final Material type;
        private final int amount;
        private final String displayName;

        private QuestItem(Material type, int amount, String displayName) {
            this.type = type;
            this.amount = amount;
            this.displayName = displayName;
        }
    }

    private static class QuestRewards {
        private final int money;
        private final List<QuestRewardItem> items;

        private QuestRewards(int money, List<QuestRewardItem> items) {
            this.money = money;
            this.items = items;
        }
    }

    private static class QuestRewardItem {
        private final Material type;
        private final int amount;
        private final String nbt;

        private QuestRewardItem(Material type, int amount, String nbt) {
            this.type = type;
            this.amount = amount;
            this.nbt = nbt;
        }
    }

    private static class ActiveOffer {
        private final QuestOffer offer;
        private final long cycleIndex;
        private final ZonedDateTime endsAt;

        private ActiveOffer(QuestOffer offer, long cycleIndex, ZonedDateTime endsAt) {
            this.offer = offer;
            this.cycleIndex = cycleIndex;
            this.endsAt = endsAt;
        }
    }

    private static class QuestProgress {
        private final boolean complete;
        private final String label;

        private QuestProgress(boolean complete, String label) {
            this.complete = complete;
            this.label = label;
        }
    }

    enum ConditionOp {
        AND,
        OR
    }

    static class PlayerQuestState {
        private final UUID playerId;
        private final Map<String, List<QuestAssignment>> assignments = new HashMap<>();

        PlayerQuestState(UUID playerId) {
            this.playerId = playerId;
        }

        UUID getPlayerId() {
            return playerId;
        }

        Map<String, List<QuestAssignment>> getAssignments() {
            return assignments;
        }
    }

    static class QuestAssignment {
        private final String questId;
        private final long cycleIndex;

        QuestAssignment(String questId, long cycleIndex) {
            this.questId = questId;
            this.cycleIndex = cycleIndex;
        }

        String getQuestId() {
            return questId;
        }

        long getCycleIndex() {
            return cycleIndex;
        }
    }

    public void checkQuestCompletion(Player player) {
        if (config == null || config.groups.isEmpty()) {
            return;
        }
        PlayerQuestState playerState = stateStore.get(player.getUniqueId());
        ZonedDateTime now = ZonedDateTime.now(config.zoneId);
        boolean updated = clearExpiredStates(playerState, now);
        for (QuestGroup group : config.groups.values()) {
            ActiveOffer active = getActiveOffer(group, now);
            if (active == null) {
                continue;
            }
            List<QuestAssignment> assignments = playerState.assignments.get(group.id);
            if (assignments == null || assignments.isEmpty()) {
                continue;
            }
            boolean removedAny = false;
            while (true) {
                QuestAssignment matching = findAssignment(assignments, active.offer.id);
                if (matching == null) {
                    break;
                }
                QuestProgress progress = evaluateProgress(player, active.offer);
                if (!progress.complete) {
                    break;
                }
                if (!completeQuest(player, group, active.offer, progress)) {
                    break;
                }
                assignments.remove(matching);
                removedAny = true;
                updated = true;
            }
            if (removedAny && assignments.isEmpty()) {
                playerState.assignments.remove(group.id);
            }
        }
        if (updated) {
            stateStore.saveState(playerState);
            stateStore.save();
        }
    }

    private int countAssignments(List<QuestAssignment> assignments, String questId) {
        if (assignments == null) {
            return 0;
        }
        int count = 0;
        for (QuestAssignment assignment : assignments) {
            if (assignment.questId.equals(questId)) {
                count++;
            }
        }
        return count;
    }

    private QuestAssignment findAssignment(List<QuestAssignment> assignments, String questId) {
        if (assignments == null) {
            return null;
        }
        for (QuestAssignment assignment : assignments) {
            if (assignment.questId.equals(questId)) {
                return assignment;
            }
        }
        return null;
    }

    private void startReminderTask() {
        if (reminderTask != null) {
            reminderTask.cancel();
            reminderTask = null;
        }
        if (!reminderEnabled) {
            return;
        }
        if (reminderIntervalMinutes <= 0 && reminderChatThreshold <= 0) {
            return;
        }
        reminderTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            for (Player player : Bukkit.getOnlinePlayers()) {
                maybeSendReminder(player, now);
            }
        }, REMINDER_CHECK_INTERVAL_TICKS, REMINDER_CHECK_INTERVAL_TICKS);
    }

    public void recordChat(Player player) {
        if (!reminderEnabled || reminderChatThreshold <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        ReminderState state = getOrCreateReminderState(player.getUniqueId(), now);
        state.chatCount++;
        maybeSendReminder(player, now);
    }

    private String colorize(String message) {
        if (message == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void reloadReminderSettings() {
        reminderEnabled = Settings.getBoolean("quest_reminder.enabled", true);
        reminderIntervalMinutes = Math.max(0, Settings.getInt("quest_reminder.interval_minutes", 30));
        reminderChatThreshold = Math.max(0, Settings.getInt("quest_reminder.chat_count_threshold", 0));
        String conditionRaw = Settings.getString("quest_reminder.condition", "OR");
        reminderCondition = ReminderCondition.fromString(conditionRaw);
        reminderMessage = Settings.getString("quest_reminder.message", Lang.get("quest.reminder"));
        startReminderTask();
    }

    private void maybeSendReminder(Player player, long now) {
        if (!reminderEnabled) {
            return;
        }
        String message = reminderMessage != null ? reminderMessage : "";
        if (message.isBlank()) {
            return;
        }
        boolean timeEnabled = reminderIntervalMinutes > 0;
        boolean chatEnabled = reminderChatThreshold > 0;
        if (!timeEnabled && !chatEnabled) {
            return;
        }
        ReminderState state = getOrCreateReminderState(player.getUniqueId(), now);
        boolean timeMet = timeEnabled && now - state.lastReminderMillis >= reminderIntervalMinutes * 60_000L;
        boolean chatMet = chatEnabled && state.chatCount >= reminderChatThreshold;
        boolean shouldSend;
        if (reminderCondition == ReminderCondition.AND) {
            shouldSend = (!timeEnabled || timeMet) && (!chatEnabled || chatMet);
        } else {
            shouldSend = (timeEnabled && timeMet) || (chatEnabled && chatMet);
        }
        if (!shouldSend) {
            return;
        }
        player.sendMessage(colorize(message));
        state.lastReminderMillis = now;
        state.chatCount = 0;
    }

    private String formatQuestName(QuestOffer offer) {
        return colorize(offer.displayName);
    }

    public String resolveQuestDisplayName(String questId) {
        if (config == null || config.groups.isEmpty()) {
            return colorize(questId);
        }
        for (QuestGroup group : config.groups.values()) {
            for (QuestOffer offer : group.offers) {
                if (offer.id.equalsIgnoreCase(questId)) {
                    return colorize(offer.displayName);
                }
            }
        }
        return colorize(questId);
    }

    public static class QuestDisplayEntry {
        public final String id;
        public final String displayName;
        public final String description;
        public final String groupName;
        public final String remaining;
        public final int acceptedCount;
        public final String progress;
        public final boolean complete;

        public QuestDisplayEntry(String id, String displayName, String description, String groupName, String remaining, int acceptedCount, String progress, boolean complete) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.groupName = groupName;
            this.remaining = remaining;
            this.acceptedCount = acceptedCount;
            this.progress = progress;
            this.complete = complete;
        }
    }

    private ReminderState getOrCreateReminderState(UUID playerId, long now) {
        return reminderStates.computeIfAbsent(playerId, id -> new ReminderState(now));
    }

    public void removeReminderState(UUID playerId) {
        reminderStates.remove(playerId);
    }

    private enum ReminderCondition {
        AND,
        OR;

        private static ReminderCondition fromString(String raw) {
            if (raw == null) {
                return OR;
            }
            return raw.equalsIgnoreCase("AND") ? AND : OR;
        }
    }

    private static class ReminderState {
        private long lastReminderMillis;
        private int chatCount;

        private ReminderState(long now) {
            this.lastReminderMillis = now;
        }
    }
}
