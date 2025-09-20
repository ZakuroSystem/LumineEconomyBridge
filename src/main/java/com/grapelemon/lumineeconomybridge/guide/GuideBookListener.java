package com.grapelemon.lumineeconomybridge.guide;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.grapelemon.lumineeconomybridge.Lang;
import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.grapelemon.lumineeconomybridge.guide.GuideBookMenuHolder.GuideAction;
import com.grapelemon.lumineeconomybridge.guide.GuideBookMenuHolder.MenuType;
import com.grapelemon.lumineeconomybridge.sync.ScoreboardUtil;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationAbandonedEvent;
import org.bukkit.conversations.ConversationAbandonedListener;
import org.bukkit.conversations.ConversationFactory;
import org.bukkit.conversations.ConversationContext;
import org.bukkit.conversations.Prompt;
import org.bukkit.conversations.StringPrompt;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class GuideBookListener implements Listener {

    private static final int MENU_SIZE = 9;
    private static final int BOOK_VERSION = 1;

    private final LumineEconomyBridge plugin;
    private final NamespacedKey guideKey;
    private final Gson gson = new Gson();
    private final ConversationFactory shopIdFactory;

    public GuideBookListener(LumineEconomyBridge plugin) {
        this.plugin = plugin;
        this.guideKey = new NamespacedKey(plugin, "guide_book");
        this.shopIdFactory = new ConversationFactory(plugin)
                .withModality(false)
                .withLocalEcho(false)
                .withTimeout(60)
                .withEscapeSequence("cancel")
                .addConversationAbandonedListener(new ConversationAbandonedListener() {
                    @Override
                    public void conversationAbandoned(ConversationAbandonedEvent event) {
                        if (event.gracefulExit()) {
                            return;
                        }
                        ConversationContext context = event.getContext();
                        if (context == null) {
                            return;
                        }
                        if (context.getForWhom() instanceof Player player) {
                            Bukkit.getScheduler().runTask(plugin, () ->
                                    player.sendMessage(Lang.get("guide.shop.cancelled")));
                        }
                    }
                });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ensureGuideBook(event.getPlayer());
    }

    public void distributeToOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ensureGuideBook(player);
        }
    }

    private void ensureGuideBook(Player player) {
        boolean found = false;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isGuideBook(stack)) {
                updateGuideMeta(stack);
                found = true;
                break;
            }
        }
        if (!found) {
            ItemStack book = createGuideBook();
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(book);
            if (!leftover.isEmpty()) {
                leftover.values().forEach(item ->
                        player.getWorld().dropItemNaturally(player.getLocation(), item));
            }
        }
    }

    private ItemStack createGuideBook() {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        ItemMeta meta = item.getItemMeta();
        applyGuideMeta(meta);
        item.setItemMeta(meta);
        return item;
    }

    private void updateGuideMeta(ItemStack item) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer container = meta.getPersistentDataContainer();
        Integer version = container.get(guideKey, PersistentDataType.INTEGER);
        if (version == null || version < BOOK_VERSION) {
            applyGuideMeta(meta);
            item.setItemMeta(meta);
        }
    }

    private void applyGuideMeta(ItemMeta meta) {
        if (meta == null) {
            return;
        }
        meta.setDisplayName(Lang.get("guide.book_name"));
        List<String> lore = new ArrayList<>();
        lore.add(Lang.get("guide.book_lore_1"));
        lore.add(Lang.get("guide.book_lore_2"));
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(guideKey, PersistentDataType.INTEGER, BOOK_VERSION);
    }

    private boolean isGuideBook(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return container.has(guideKey, PersistentDataType.INTEGER);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!isGuideBook(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        openMainMenu(event.getPlayer());
    }

    private void openMainMenu(Player player) {
        GuideBookMenuHolder holder = new GuideBookMenuHolder(MenuType.MAIN);
        String title = Lang.get("guide.menu_title");
        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, title);
        holder.setInventory(inv);

        inv.setItem(2, buildMenuItem(Material.GOLD_INGOT,
                Lang.get("guide.option.balance.name"),
                Lang.get("guide.option.balance.lore_1"),
                Lang.get("guide.option.balance.lore_2")));
        holder.bind(2, GuideAction.CHECK_BALANCE);

        inv.setItem(4, buildMenuItem(Material.BARREL,
                Lang.get("guide.option.shop.name"),
                Lang.get("guide.option.shop.lore_1"),
                Lang.get("guide.option.shop.lore_2")));
        holder.bind(4, GuideAction.CREATE_SHOP);

        inv.setItem(6, buildMenuItem(Material.ENCHANTED_BOOK,
                Lang.get("guide.option.quests.name"),
                Lang.get("guide.option.quests.lore_1"),
                Lang.get("guide.option.quests.lore_2")));
        holder.bind(6, GuideAction.RECOMMENDED_QUESTS);

        player.openInventory(inv);
    }

    private ItemStack buildMenuItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        List<String> lore = new ArrayList<>();
        for (String line : loreLines) {
            if (line != null && !line.isEmpty()) {
                lore.add(line);
            }
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuideBookMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        GuideAction action = holder.getAction(rawSlot);
        if (action == null) {
            return;
        }
        switch (action) {
            case CHECK_BALANCE -> openBalanceMenu(player);
            case CREATE_SHOP -> {
                player.closeInventory();
                promptForShopId(player);
            }
            case RECOMMENDED_QUESTS -> openQuestMenu(player);
            case BACK_TO_MAIN -> openMainMenu(player);
            default -> {
            }
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GuideBookMenuHolder) {
            event.setCancelled(true);
        }
    }

    private void promptForShopId(Player player) {
        Conversation conversation = shopIdFactory.withFirstPrompt(new ShopIdPrompt()).buildConversation(player);
        conversation.begin();
    }

    private class ShopIdPrompt extends StringPrompt {
        @Override
        public String getPromptText(ConversationContext context) {
            return Lang.get("guide.shop.prompt");
        }

        @Override
        public Prompt acceptInput(ConversationContext context, String input) {
            if (input == null || input.trim().isEmpty()) {
                context.getForWhom().sendRawMessage(Lang.get("guide.shop.invalid"));
                return this;
            }
            String shopId = input.trim();
            context.setSessionData("guide_shop_id", shopId);
            context.getForWhom().sendRawMessage(Lang.get("guide.shop.creating").replace("{shop}", shopId));
            if (context.getForWhom() instanceof Player player) {
                Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("le shop create " + shopId));
            }
            return Prompt.END_OF_CONVERSATION;
        }
    }

    private void addBackButton(GuideBookMenuHolder holder, Inventory inv) {
        inv.setItem(0, buildMenuItem(Material.ARROW,
                Lang.get("guide.menu_back"),
                Lang.get("guide.menu_back_lore")));
        holder.bind(0, GuideAction.BACK_TO_MAIN);
    }

    private void openBalanceMenu(Player player) {
        Map<String, Integer> balances = ScoreboardUtil.readAllSync(player);
        int slotsNeeded = Math.max(1, balances.size()) + 1; // include back button
        int size = Math.min(54, Math.max(9, ((slotsNeeded + 8) / 9) * 9));
        GuideBookMenuHolder holder = new GuideBookMenuHolder(MenuType.BALANCE);
        Inventory inv = Bukkit.createInventory(holder, size, Lang.get("guide.balance.title"));
        holder.setInventory(inv);
        addBackButton(holder, inv);

        if (balances.isEmpty()) {
            int slot = Math.min(13, size - 1);
            inv.setItem(slot, buildMenuItem(Material.BARRIER,
                    Lang.get("guide.balance.none")));
        } else {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(balances.entrySet());
            entries.sort(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed());
            int slot = 1;
            for (Map.Entry<String, Integer> entry : entries) {
                while (slot < size && holder.getAction(slot) != null) {
                    slot++;
                }
                if (slot >= size) {
                    break;
                }
                inv.setItem(slot, createBalanceItem(entry.getKey(), entry.getValue()));
                slot++;
            }
        }
        player.openInventory(inv);
    }

    private ItemStack createBalanceItem(String currency, int rawAmount) {
        String name = Lang.get("guide.balance.item_name").replace("{currency}", currency);
        String amount = formatAmount(rawAmount);
        String lore = Lang.get("guide.balance.item_lore").replace("{amount}", amount);
        return buildMenuItem(Material.GOLD_NUGGET, name, lore);
    }

    private String formatAmount(int rawAmount) {
        BigDecimal decimal = BigDecimal.valueOf(rawAmount)
                .divide(BigDecimal.valueOf(1000), 3, RoundingMode.DOWN)
                .stripTrailingZeros();
        if (decimal.scale() < 0) {
            decimal = decimal.setScale(0, RoundingMode.UNNECESSARY);
        }
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        DecimalFormat format = new DecimalFormat("#,##0.###", symbols);
        return format.format(decimal);
    }

    private void openQuestMenu(Player player) {
        GuideBookMenuHolder holder = new GuideBookMenuHolder(MenuType.QUESTS);
        Inventory inv = Bukkit.createInventory(holder, 27, Lang.get("guide.quests.menu_title"));
        holder.setInventory(inv);
        addBackButton(holder, inv);

        inv.setItem(13, buildMenuItem(Material.PAPER, Lang.get("guide.quests.loading")));
        player.openInventory(inv);
        fetchRecommendedQuests(player, holder);
    }

    private void fetchRecommendedQuests(Player player, GuideBookMenuHolder holder) {
        if (!plugin.isActive() || plugin.getHttpClient() == null) {
            renderQuestError(player, holder, Lang.get("guide.quests.error"));
            return;
        }
        OkHttpClient client = plugin.getHttpClient();
        HttpUrl url = HttpUrl.parse(plugin.getBaseUrl() + "/api/quests/recommended").newBuilder()
                .addQueryParameter("player_uuid", player.getUniqueId().toString())
                .build();
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .get()
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException ex) {
                plugin.getLogger().warning("Failed to fetch quests: " + ex.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> renderQuestError(player, holder, Lang.get("guide.quests.error")));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        plugin.getLogger().warning("Quest API failed with status " + response.code());
                        Bukkit.getScheduler().runTask(plugin, () -> renderQuestError(player, holder, Lang.get("guide.quests.error")));
                        return;
                    }
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject obj = gson.fromJson(body, JsonObject.class);
                    List<String> quests = new ArrayList<>();
                    if (obj != null && obj.has("quests")) {
                        JsonArray arr = obj.getAsJsonArray("quests");
                        for (JsonElement el : arr) {
                            if (el.isJsonPrimitive()) {
                                quests.add(el.getAsString());
                            }
                        }
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> renderQuestList(player, holder, quests));
                }
            }
        });
    }

    private void renderQuestError(Player player, GuideBookMenuHolder holder, String message) {
        Inventory inv = holder.getInventory();
        if (!isHolderOpen(player, inv)) {
            return;
        }
        clearQuestSlots(holder, inv);
        inv.setItem(13, buildMenuItem(Material.BARRIER, message));
    }

    private void renderQuestList(Player player, GuideBookMenuHolder holder, List<String> questIds) {
        Inventory inv = holder.getInventory();
        if (!isHolderOpen(player, inv)) {
            return;
        }
        clearQuestSlots(holder, inv);
        if (questIds == null || questIds.isEmpty()) {
            inv.setItem(13, buildMenuItem(Material.PAPER, Lang.get("guide.quests.none")));
            return;
        }
        int[] slots = {10, 12, 14, 16, 19, 21, 23};
        int index = 0;
        for (String questId : questIds) {
            if (index >= slots.length) {
                break;
            }
            int slot = slots[index++];
            if (slot >= inv.getSize()) {
                continue;
            }
            inv.setItem(slot, createQuestItem(questId));
        }
    }

    private boolean isHolderOpen(Player player, Inventory inv) {
        return inv != null && player.getOpenInventory() != null
                && player.getOpenInventory().getTopInventory().equals(inv);
    }

    private void clearQuestSlots(GuideBookMenuHolder holder, Inventory inv) {
        if (inv == null) {
            return;
        }
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (holder.getAction(slot) == null) {
                inv.setItem(slot, null);
            }
        }
    }

    private ItemStack createQuestItem(String questId) {
        String name = Lang.get("guide.quests." + questId + ".name");
        String desc = Lang.get("guide.quests." + questId + ".description");
        String reward = Lang.get("guide.quests.reward_line");
        return buildMenuItem(Material.ENCHANTED_BOOK, name, desc, reward);
    }
}
