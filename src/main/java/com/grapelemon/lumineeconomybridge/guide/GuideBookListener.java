package com.grapelemon.lumineeconomybridge.guide;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.grapelemon.lumineeconomybridge.Lang;
import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.grapelemon.lumineeconomybridge.guide.GuideBookMenuHolder.GuideAction;
import com.grapelemon.lumineeconomybridge.quest.QuestManager;
import com.grapelemon.lumineeconomybridge.guide.GuideBookMenuHolder.MenuType;
import com.grapelemon.lumineeconomybridge.sync.ScoreboardUtil;

import java.io.IOException;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
    private final NamespacedKey questIdKey;
    private final Gson gson = new Gson();
    private final ConversationFactory shopIdFactory;

    public GuideBookListener(LumineEconomyBridge plugin) {
        this.plugin = plugin;
        this.guideKey = new NamespacedKey(plugin, "guide_book");
        this.questIdKey = new NamespacedKey(plugin, "guide_quest_id");
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

        inv.setItem(1, buildMenuItem(Material.GOLD_INGOT,
                Lang.get("guide.option.balance.name"),
                Lang.get("guide.option.balance.lore_1"),
                Lang.get("guide.option.balance.lore_2")));
        holder.bind(1, GuideAction.CHECK_BALANCE);

        inv.setItem(3, buildMenuItem(Material.BARREL,
                Lang.get("guide.option.shop.name"),
                Lang.get("guide.option.shop.lore_1"),
                Lang.get("guide.option.shop.lore_2")));
        holder.bind(3, GuideAction.CREATE_SHOP);

        inv.setItem(2, buildMenuItem(Material.EMERALD,
                Lang.get("guide.option.market.name"),
                Lang.get("guide.option.market.lore_1"),
                Lang.get("guide.option.market.lore_2")));
        holder.bind(2, GuideAction.OPEN_MARKET);

        inv.setItem(5, buildMenuItem(Material.CLOCK,
                Lang.get("guide.option.manage_shops.name"),
                Lang.get("guide.option.manage_shops.lore_1"),
                Lang.get("guide.option.manage_shops.lore_2")));
        holder.bind(5, GuideAction.MANAGE_SHOPS);

        inv.setItem(4, buildMenuItem(Material.NAME_TAG,
                Lang.get("guide.option.protect_rent.name"),
                Lang.get("guide.option.protect_rent.lore_1"),
                Lang.get("guide.option.protect_rent.lore_2")));
        holder.bind(4, GuideAction.RENT_OUT_PROTECTION);

        inv.setItem(6, buildMenuItem(Material.BREEZE_ROD,
                Lang.get("guide.option.protect.name"),
                Lang.get("guide.option.protect.lore_1"),
                Lang.get("guide.option.protect.lore_2")));
        holder.bind(6, GuideAction.PROTECT_LAND);

        inv.setItem(7, buildMenuItem(Material.ENCHANTED_BOOK,
                Lang.get("guide.option.quests.name"),
                Lang.get("guide.option.quests.lore_1"),
                Lang.get("guide.option.quests.lore_2")));
        holder.bind(7, GuideAction.RECOMMENDED_QUESTS);

        inv.setItem(8, buildMenuItem(Material.CHEST,
                Lang.get("guide.option.recommended_shops.name"),
                Lang.get("guide.option.recommended_shops.lore_1"),
                Lang.get("guide.option.recommended_shops.lore_2")));
        holder.bind(8, GuideAction.RECOMMENDED_SHOPS);

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
        if (holder.getMenuType() == MenuType.QUESTS) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && clicked.hasItemMeta()) {
                ItemMeta meta = clicked.getItemMeta();
                String questId = meta.getPersistentDataContainer().get(questIdKey, PersistentDataType.STRING);
                if (questId != null && !questId.isBlank()) {
                    QuestManager questManager = plugin.getQuestManager();
                    if (questManager != null) {
                        if (questManager.isAccepted(player, questId)) {
                            questManager.submitQuestReport(player, questId);
                        } else {
                            questManager.acceptQuest(player, questId);
                        }
                        Bukkit.getScheduler().runTask(plugin, () -> openQuestMenu(player));
                    }
                    return;
                }
            }
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
            case MANAGE_SHOPS -> {
                player.closeInventory();
                if (!plugin.isActive() || plugin.getHttpClient() == null || plugin.getShopGuiManager() == null) {
                    player.sendMessage(Lang.get("error-unavailable"));
                } else {
                    plugin.getShopGuiManager().openMainMenu(player);
                }
            }
            case OPEN_MARKET -> {
                player.closeInventory();
                if (plugin.getMarketManager() == null) {
                    player.sendMessage(Lang.get("error-unavailable"));
                } else {
                    plugin.getMarketManager().openMarket(player);
                }
            }
            case RECOMMENDED_QUESTS -> openQuestMenu(player);
            case RECOMMENDED_SHOPS -> openRecommendedShopMenu(player);
            case PROTECT_LAND -> {
                player.closeInventory();
                if (plugin.getProtectManager() == null) {
                    player.sendMessage(Lang.get("error-unavailable"));
                } else {
                    plugin.getProtectManager().startSelectionFromGui(player);
                }
            }
            case RENT_OUT_PROTECTION -> {
                player.closeInventory();
                if (plugin.getProtectManager() == null) {
                    player.sendMessage(Lang.get("error-unavailable"));
                } else {
                    plugin.getProtectManager().startLeaseSignWizard(player);
                }
            }
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
        return plugin.formatAmountGrouped(rawAmount);
    }

    private void openQuestMenu(Player player) {
        GuideBookMenuHolder holder = new GuideBookMenuHolder(MenuType.QUESTS);
        Inventory inv = Bukkit.createInventory(holder, 27, Lang.get("guide.quests.menu_title"));
        holder.setInventory(inv);
        addBackButton(holder, inv);
        renderQuestList(player, holder);
        player.openInventory(inv);
    }

    private void renderQuestList(Player player, GuideBookMenuHolder holder) {
        Inventory inv = holder.getInventory();
        clearContentSlots(holder, inv);
        QuestManager questManager = plugin.getQuestManager();
        if (questManager == null) {
            inv.setItem(13, buildMenuItem(Material.BARRIER, Lang.get("guide.quests.error")));
            return;
        }
        List<QuestManager.QuestDisplayEntry> quests = questManager.getActiveQuestEntries(player);
        if (quests.isEmpty()) {
            inv.setItem(13, buildMenuItem(Material.PAPER, Lang.get("guide.quests.none")));
            return;
        }
        int[] slots = {10, 12, 14, 16, 19, 21, 23};
        int index = 0;
        for (QuestManager.QuestDisplayEntry quest : quests) {
            if (index >= slots.length) {
                break;
            }
            int slot = slots[index++];
            if (slot >= inv.getSize()) {
                continue;
            }
            inv.setItem(slot, createQuestItem(quest));
        }
    }

    private void openRecommendedShopMenu(Player player) {
        GuideBookMenuHolder holder = new GuideBookMenuHolder(MenuType.SHOPS);
        Inventory inv = Bukkit.createInventory(holder, 27, Lang.get("guide.shops.menu_title"));
        holder.setInventory(inv);
        addBackButton(holder, inv);

        inv.setItem(13, buildMenuItem(Material.PAPER, Lang.get("guide.shops.loading")));
        player.openInventory(inv);
        fetchRecommendedShops(player, holder);
    }

    private void fetchRecommendedShops(Player player, GuideBookMenuHolder holder) {
        if (!plugin.isActive() || plugin.getHttpClient() == null) {
            renderShopError(player, holder, Lang.get("guide.shops.error"));
            return;
        }
        OkHttpClient client = plugin.getHttpClient();
        HttpUrl url = HttpUrl.parse(plugin.getBaseUrl() + "/api/shops/recommended").newBuilder()
                .addQueryParameter("limit", "5")
                .build();
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .get()
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException ex) {
                plugin.getLogger().warning("Failed to fetch shops: " + ex.getMessage());
                Bukkit.getScheduler().runTask(plugin, () ->
                        renderShopError(player, holder, Lang.get("guide.shops.error")));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        plugin.getLogger().warning("Shop API failed with status " + response.code());
                        Bukkit.getScheduler().runTask(plugin, () ->
                                renderShopError(player, holder, Lang.get("guide.shops.error")));
                        return;
                    }
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject obj = gson.fromJson(body, JsonObject.class);
                    List<RecommendedShop> shops = new ArrayList<>();
                    if (obj != null && obj.has("shops") && obj.get("shops").isJsonArray()) {
                        JsonArray arr = obj.getAsJsonArray("shops");
                        for (JsonElement element : arr) {
                            if (!element.isJsonObject()) {
                                continue;
                            }
                            JsonObject shopObj = element.getAsJsonObject();
                            RecommendedShop shopInfo = new RecommendedShop();
                            String shopId = safeString(shopObj, "shop_id");
                            if (!shopId.isEmpty()) {
                                shopInfo.shopId = shopId;
                            }
                            String mode = safeString(shopObj, "trade_mode");
                            if (!mode.isEmpty()) {
                                shopInfo.tradeMode = mode;
                            }
                            if (shopObj.has("locations") && shopObj.get("locations").isJsonArray()) {
                                JsonArray locArray = shopObj.getAsJsonArray("locations");
                                for (JsonElement locElement : locArray) {
                                    if (!locElement.isJsonObject()) {
                                        continue;
                                    }
                                    ShopLocation parsed = parseLocation(locElement.getAsJsonObject());
                                    if (parsed != null) {
                                        shopInfo.addLocation(parsed);
                                    }
                                }
                            }
                            if (shopInfo.locations.isEmpty()
                                    && shopObj.has("location")
                                    && shopObj.get("location").isJsonObject()) {
                                ShopLocation parsed = parseLocation(shopObj.getAsJsonObject("location"));
                                if (parsed != null) {
                                    shopInfo.addLocation(parsed);
                                }
                            }
                            if (shopObj.has("listings") && shopObj.get("listings").isJsonArray()) {
                                JsonArray listingsArr = shopObj.getAsJsonArray("listings");
                                for (JsonElement listingElement : listingsArr) {
                                    if (!listingElement.isJsonObject()) {
                                        continue;
                                    }
                                    JsonObject listingObj = listingElement.getAsJsonObject();
                                    TradeListing listing = new TradeListing();
                                    listing.name = safeString(listingObj, "name");
                                    if (listingObj.has("sell") && listingObj.get("sell").isJsonArray()) {
                                        JsonArray sellArr = listingObj.getAsJsonArray("sell");
                                        for (JsonElement priceElement : sellArr) {
                                            if (!priceElement.isJsonObject()) {
                                                continue;
                                            }
                                            JsonObject priceObj = priceElement.getAsJsonObject();
                                            String currency = safeString(priceObj, "currency");
                                            Integer amount = safeInt(priceObj, "amount");
                                            if (!currency.isEmpty() && amount != null) {
                                                listing.sellPrices.add(new PriceInfo(currency, amount));
                                            }
                                        }
                                    }
                                    if (listingObj.has("buy") && listingObj.get("buy").isJsonArray()) {
                                        JsonArray buyArr = listingObj.getAsJsonArray("buy");
                                        for (JsonElement priceElement : buyArr) {
                                            if (!priceElement.isJsonObject()) {
                                                continue;
                                            }
                                            JsonObject priceObj = priceElement.getAsJsonObject();
                                            String currency = safeString(priceObj, "currency");
                                            Integer amount = safeInt(priceObj, "amount");
                                            if (!currency.isEmpty() && amount != null) {
                                                listing.buyPrices.add(new PriceInfo(currency, amount));
                                            }
                                        }
                                    }
                                    if (!listing.sellPrices.isEmpty() || !listing.buyPrices.isEmpty()) {
                                        shopInfo.listings.add(listing);
                                    }
                                }
                            }
                            shops.add(shopInfo);
                        }
                    }
                    Bukkit.getScheduler().runTask(plugin, () ->
                            renderShopList(player, holder, shops));
                }
            }
        });
    }

    private void renderShopError(Player player, GuideBookMenuHolder holder, String message) {
        Inventory inv = holder.getInventory();
        if (!isHolderOpen(player, inv)) {
            return;
        }
        clearContentSlots(holder, inv);
        inv.setItem(13, buildMenuItem(Material.BARRIER, message));
    }

    private void renderShopList(Player player, GuideBookMenuHolder holder, List<RecommendedShop> shops) {
        Inventory inv = holder.getInventory();
        if (!isHolderOpen(player, inv)) {
            return;
        }
        clearContentSlots(holder, inv);
        if (shops == null || shops.isEmpty()) {
            inv.setItem(13, buildMenuItem(Material.PAPER, Lang.get("guide.shops.none")));
            return;
        }
        int[] slots = {10, 12, 14, 16, 19, 21, 23};
        int index = 0;
        for (RecommendedShop shop : shops) {
            if (index >= slots.length) {
                break;
            }
            int slot = slots[index++];
            if (slot >= inv.getSize()) {
                continue;
            }
            inv.setItem(slot, createShopRecommendationItem(shop));
        }
    }

    private ItemStack createShopRecommendationItem(RecommendedShop shop) {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        String name = Lang.get("guide.shops.item_title").replace("{shop}", shop.shopId);
        meta.setDisplayName(name);
        List<String> lore = new ArrayList<>();
        String mode = shop.tradeMode == null ? "both" : shop.tradeMode.toLowerCase(Locale.ROOT);
        String modeKey = switch (mode) {
            case "sell" -> "guide.shops.mode_sell";
            case "buy" -> "guide.shops.mode_buy";
            default -> "guide.shops.mode_both";
        };
        lore.add(Lang.get(modeKey));
        if (shop.locations.isEmpty()) {
            lore.add(Lang.get("guide.shops.location_unknown"));
        } else if (shop.locations.size() == 1) {
            lore.add(formatLocationLine("guide.shops.location", shop.locations.get(0)));
        } else {
            lore.add(Lang.get("guide.shops.locations_header"));
            int shown = 0;
            for (ShopLocation location : shop.locations) {
                if (shown >= 3) {
                    lore.add(Lang.get("guide.shops.more_locations"));
                    break;
                }
                lore.add(formatLocationLine("guide.shops.location_entry", location));
                shown++;
            }
            if (shown == 0) {
                lore.add(Lang.get("guide.shops.location_unknown"));
            }
        }
        if (shop.listings.isEmpty()) {
            lore.add(Lang.get("guide.shops.no_listings"));
        } else {
            int shown = 0;
            for (TradeListing listing : shop.listings) {
                if (shown >= 3) {
                    lore.add(Lang.get("guide.shops.more_items"));
                    break;
                }
                if (!listing.sellPrices.isEmpty()) {
                    String prices = formatPriceList(listing.sellPrices);
                    lore.add(Lang.get("guide.shops.sell_line")
                            .replace("{name}", listing.name)
                            .replace("{prices}", prices));
                }
                if (!listing.buyPrices.isEmpty()) {
                    String prices = formatPriceList(listing.buyPrices);
                    lore.add(Lang.get("guide.shops.buy_line")
                            .replace("{name}", listing.name)
                            .replace("{prices}", prices));
                }
                shown++;
            }
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private String formatLocationLine(String key, ShopLocation location) {
        return Lang.get(key)
                .replace("{world}", location.world)
                .replace("{x}", formatCoordinate(location.x))
                .replace("{y}", formatCoordinate(location.y))
                .replace("{z}", formatCoordinate(location.z));
    }

    private String formatCoordinate(Double value) {
        if (value == null) {
            return "-";
        }
        double rounded = Math.rint(value);
        if (Math.abs(value - rounded) < 0.05) {
            return String.format(Locale.US, "%.0f", rounded);
        }
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        DecimalFormat format = new DecimalFormat("#,##0.##", symbols);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }

    private String formatPriceList(List<PriceInfo> prices) {
        List<String> parts = new ArrayList<>();
        for (PriceInfo price : prices) {
            String amount = formatAmount(price.amount);
            String entry = ChatColor.WHITE + price.currency + ChatColor.GRAY + ": "
                    + ChatColor.YELLOW + amount + ChatColor.RESET;
            parts.add(entry);
        }
        return String.join(ChatColor.GRAY + ", ", parts);
    }

    private boolean isHolderOpen(Player player, Inventory inv) {
        return inv != null && player.getOpenInventory() != null
                && player.getOpenInventory().getTopInventory().equals(inv);
    }

    private void clearContentSlots(GuideBookMenuHolder holder, Inventory inv) {
        if (inv == null) {
            return;
        }
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (holder.getAction(slot) == null) {
                inv.setItem(slot, null);
            }
        }
    }

    private ItemStack createQuestItem(QuestManager.QuestDisplayEntry quest) {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(quest.displayName);
        List<String> lore = new ArrayList<>();
        lore.add(Lang.get("guide.quests.group").replace("{group}", quest.groupName));
        lore.add(Lang.get("guide.quests.remaining").replace("{remaining}", quest.remaining));
        if (quest.description != null && !quest.description.isBlank()) {
            lore.add(quest.description);
        }
        if (quest.acceptedCount > 0) {
            lore.add(Lang.get("guide.quests.accepted").replace("{count}", String.valueOf(quest.acceptedCount)));
            if (quest.progress != null && !quest.progress.isBlank()) {
                lore.add(Lang.get("guide.quests.progress").replace("{progress}", quest.progress));
            }
            lore.add(quest.complete ? Lang.get("guide.quests.ready_to_report") : Lang.get("guide.quests.not_ready"));
            lore.add(Lang.get("guide.quests.click_to_report"));
        } else {
            lore.add(Lang.get("guide.quests.available"));
            lore.add(Lang.get("guide.quests.click_to_accept"));
        }
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(questIdKey, PersistentDataType.STRING, quest.id);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private String safeString(JsonObject obj, String member) {
        if (obj == null || member == null || !obj.has(member)) {
            return "";
        }
        JsonElement element = obj.get(member);
        if (element != null && element.isJsonPrimitive()) {
            try {
                return element.getAsString();
            } catch (UnsupportedOperationException ignored) {
                return "";
            }
        }
        return "";
    }

    private Double safeDouble(JsonObject obj, String member) {
        if (obj == null || member == null || !obj.has(member)) {
            return null;
        }
        JsonElement element = obj.get(member);
        if (element != null && element.isJsonPrimitive()) {
            try {
                return element.getAsDouble();
            } catch (NumberFormatException | UnsupportedOperationException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer safeInt(JsonObject obj, String member) {
        if (obj == null || member == null || !obj.has(member)) {
            return null;
        }
        JsonElement element = obj.get(member);
        if (element != null && element.isJsonPrimitive()) {
            try {
                return element.getAsInt();
            } catch (NumberFormatException | UnsupportedOperationException ignored) {
                return null;
            }
        }
        return null;
    }

    private ShopLocation parseLocation(JsonObject locObj) {
        if (locObj == null) {
            return null;
        }
        ShopLocation location = new ShopLocation();
        String world = safeString(locObj, "world");
        if (!world.isEmpty()) {
            location.world = world;
        }
        location.x = safeDouble(locObj, "x");
        location.y = safeDouble(locObj, "y");
        location.z = safeDouble(locObj, "z");
        if (location.isComplete()) {
            return location;
        }
        return null;
    }

    private static class RecommendedShop {
        private String shopId = "?";
        private String tradeMode = "both";
        private final List<ShopLocation> locations = new ArrayList<>();
        private final List<TradeListing> listings = new ArrayList<>();

        private void addLocation(ShopLocation location) {
            if (location == null) {
                return;
            }
            for (ShopLocation existing : locations) {
                if (existing.sameLocation(location)) {
                    return;
                }
            }
            locations.add(location);
        }

    }

    private static class ShopLocation {
        private String world;
        private Double x;
        private Double y;
        private Double z;

        private boolean isComplete() {
            return world != null && !world.isEmpty() && x != null && y != null && z != null;
        }

        private boolean sameLocation(ShopLocation other) {
            return other != null
                    && Objects.equals(world, other.world)
                    && Objects.equals(x, other.x)
                    && Objects.equals(y, other.y)
                    && Objects.equals(z, other.z);
        }
    }

    private static class TradeListing {
        private String name = "";
        private final List<PriceInfo> sellPrices = new ArrayList<>();
        private final List<PriceInfo> buyPrices = new ArrayList<>();
    }

    private static class PriceInfo {
        private final String currency;
        private final int amount;

        private PriceInfo(String currency, int amount) {
            this.currency = currency;
            this.amount = amount;
        }
    }
}
