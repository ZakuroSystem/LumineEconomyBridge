package com.grapelemon.lumineeconomybridge.shop.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.grapelemon.lumineeconomybridge.shop.ShopItem;
import com.grapelemon.lumineeconomybridge.shop.ShopMenuHolder;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.OfflinePlayer;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides the inventory based navigation shell that lets players jump from the
 * command to a list of their shops and open the existing editing inventory
 * shipped with {@link ShopMenuHolder}.
 */
public class ShopGuiManager {

    private static final int PAGE_SIZE = 36;
    private static final long PROMPT_TIMEOUT_TICKS = 20L * 60;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final double TITLE_WIDTH_LIMIT = 26.0D;
    private static final double LORE_WIDTH_LIMIT = 26.0D;

    private final LumineEconomyBridge plugin;
    private final Map<UUID, ShopGuiSession> sessions = new HashMap<>();
    private final Map<UUID, ChatPrompt> prompts = new HashMap<>();
    private final NamespacedKey keyShop;
    private final NamespacedKey keyId;
    private final NamespacedKey keyOwner;
    private final NamespacedKey keyHopper;
    private final NamespacedKey keyHopperSlot;
    private final NamespacedKey keyHopperItem;
    private final NamespacedKey keyHopperShopOwner;

    public ShopGuiManager(LumineEconomyBridge plugin) {
        this.plugin = plugin;
        this.keyShop = new NamespacedKey(plugin, "le_shop");
        this.keyId = new NamespacedKey(plugin, "shop_id");
        this.keyOwner = new NamespacedKey(plugin, "owner_uuid");
        this.keyHopper = new NamespacedKey(plugin, "le_shop_hopper");
        this.keyHopperSlot = new NamespacedKey(plugin, "le_shop_hopper_slot");
        this.keyHopperItem = new NamespacedKey(plugin, "le_shop_hopper_item");
        this.keyHopperShopOwner = new NamespacedKey(plugin, "shop_owner_uuid");
    }

    private static final class ChatPrompt {
        private final ShopGuiSession session;
        private final Consumer<String> handler;
        private final Runnable cancelAction;
        private final BukkitTask timeout;

        private ChatPrompt(ShopGuiSession session, Consumer<String> handler, Runnable cancelAction, BukkitTask timeout) {
            this.session = session;
            this.handler = handler;
            this.cancelAction = cancelAction;
            this.timeout = timeout;
        }

        private void handleInput(String input) {
            session.finishPrompt(false);
            handler.accept(input);
        }

        private void cancelAndResume() {
            session.finishPrompt(true);
            if (cancelAction != null) {
                cancelAction.run();
            }
        }
    }

    private static final class ShopData {
        private final String shopId;
        private String status = "active";
        private String tradeMode = "both";
        private boolean listed = true;
        private String accountUuid;
        private String sortMode = "created";
        private SaleLimit saleLimit;
        private ShopSale sale;
        private final List<String> owners = new ArrayList<>();
        private final List<ShopEntry> entries = new ArrayList<>();

        private ShopData(String shopId) {
            this.shopId = shopId;
        }

        private String primaryOwner() {
            return owners.isEmpty() ? null : owners.get(0);
        }

        private List<String> coOwners() {
            if (owners.size() <= 1) {
                return Collections.emptyList();
            }
            return owners.subList(1, owners.size());
        }

        private ShopEntry entry(int index) {
            if (index < 0 || index >= entries.size()) {
                return null;
            }
            return entries.get(index);
        }
    }

    private static final class SaleLimit {
        private final int quantity;
        private final String period;

        private SaleLimit(int quantity, String period) {
            this.quantity = quantity;
            this.period = period;
        }
    }

    private static final class ShopSale {
        private final int pct;
        private final long endTs;

        private ShopSale(int pct, long endTs) {
            this.pct = pct;
            this.endTs = endTs;
        }
    }

    private static final class ShopEntry {
        private final ShopItem item;
        private final int slotIndex;

        private ShopEntry(ShopItem item, int slotIndex) {
            this.item = item;
            this.slotIndex = slotIndex;
        }
    }

    public void openMainMenu(Player player) {
        ShopGuiSession session = sessions.computeIfAbsent(player.getUniqueId(), id -> new ShopGuiSession(player));
        session.showMainMenu();
    }

    public void reopen(Player player) {
        ShopGuiSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.reopen();
        }
    }

    public void handleClick(Player player, int slot) {
        ShopGuiSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.execute(slot);
        }
    }

    public void close(Player player, boolean force) {
        UUID playerId = player.getUniqueId();
        ShopGuiSession session = sessions.get(playerId);
        if (session == null) {
            cancelPrompt(playerId, true);
            return;
        }
        if (!force && session.isAwaitingPrompt()) {
            session.dropInventoryReference();
            return;
        }
        sessions.remove(playerId);
        session.clear();
        cancelPrompt(playerId, true);
    }

    public boolean isTracking(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public boolean handleChat(Player player, String message) {
        UUID playerId = player.getUniqueId();
        ChatPrompt prompt = prompts.remove(playerId);
        if (prompt == null) {
            return false;
        }
        if (prompt.timeout != null) {
            prompt.timeout.cancel();
        }
        String trimmed = message.trim();
        if (trimmed.equalsIgnoreCase("cancel")) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                prompt.cancelAndResume();
                player.sendMessage(ChatColor.RED + "Cancelled / キャンセルしました" + ChatColor.RESET);
            });
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> prompt.handleInput(trimmed));
        }
        return true;
    }

    public void shutdown() {
        for (ShopGuiSession session : new ArrayList<>(sessions.values())) {
            Player player = session.player();
            if (player != null && player.isOnline()) {
                var view = player.getOpenInventory();
                if (view != null && view.getTopInventory().getHolder() instanceof ShopGuiSessionHolder) {
                    player.closeInventory();
                }
            }
            session.clear();
        }
        sessions.clear();
        for (UUID playerId : new ArrayList<>(prompts.keySet())) {
            cancelPrompt(playerId, true);
        }
    }

    public LumineEconomyBridge getPlugin() {
        return plugin;
    }

    private void cancelPrompt(UUID playerId, boolean runCancel) {
        ChatPrompt prompt = prompts.remove(playerId);
        if (prompt != null) {
            if (prompt.timeout != null) {
                prompt.timeout.cancel();
            }
            Runnable task = runCancel ? prompt::cancelAndResume : () -> prompt.session.finishPrompt(false);
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        }
    }

    private void beginPrompt(ShopGuiSession session, Player player, String instruction, Consumer<String> handler, Runnable cancelAction) {
        UUID playerId = player.getUniqueId();
        player.closeInventory();
        player.sendMessage(ChatColor.YELLOW + instruction + ChatColor.GRAY + " (type cancel to abort / キャンセルはcancel)" + ChatColor.RESET);
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ChatPrompt prompt = prompts.remove(playerId);
            if (prompt != null) {
                prompt.cancelAndResume();
                Player target = Bukkit.getPlayer(playerId);
                if (target != null) {
                    target.sendMessage(ChatColor.RED + "Timed out / タイムアウトしました" + ChatColor.RESET);
                }
            }
        }, PROMPT_TIMEOUT_TICKS);
        prompts.put(playerId, new ChatPrompt(session, handler, cancelAction, timeout));
    }

    private ItemStack button(Material material, String title, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(fitTitle(title));
            if (lore.length > 0) {
                List<String> lines = new ArrayList<>();
                for (String line : lore) {
                    lines.addAll(wrapLoreLines(line));
                }
                if (!lines.isEmpty()) {
                    meta.setLore(lines);
                }
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String fitTitle(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        List<Double> widthStack = new ArrayList<>();
        List<Integer> indexStack = new ArrayList<>();
        double width = 0;
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (c == ChatColor.COLOR_CHAR && i + 1 < title.length()) {
                builder.append(c).append(title.charAt(i + 1));
                i++;
                continue;
            }
            double glyph = glyphWidth(c);
            if (width + glyph > TITLE_WIDTH_LIMIT) {
                double ellipsisWidth = glyphWidth('…');
                if (widthStack.isEmpty()) {
                    String colors = ChatColor.getLastColors(builder.toString());
                    if (!colors.isEmpty()) {
                        builder.append(colors);
                    }
                    builder.append('…');
                    return builder.toString();
                }
                while (!widthStack.isEmpty() && width + ellipsisWidth > TITLE_WIDTH_LIMIT) {
                    width -= widthStack.remove(widthStack.size() - 1);
                    int removeIndex = indexStack.remove(indexStack.size() - 1);
                    builder.setLength(removeIndex);
                }
                String colors = ChatColor.getLastColors(builder.toString());
                if (!colors.isEmpty()) {
                    builder.append(colors);
                }
                builder.append('…');
                return builder.toString();
            }
            int indexBeforeChar = builder.length();
            builder.append(c);
            width += glyph;
            widthStack.add(glyph);
            indexStack.add(indexBeforeChar);
        }
        return builder.toString();
    }

    private List<String> wrapLoreLines(List<String> lines) {
        List<String> wrapped = new ArrayList<>();
        if (lines == null) {
            return wrapped;
        }
        for (String line : lines) {
            wrapped.addAll(wrapLoreLines(line));
        }
        return wrapped;
    }

    private List<String> wrapLoreLines(String line) {
        List<String> result = new ArrayList<>();
        if (line == null) {
            return result;
        }
        if (line.isEmpty()) {
            result.add("");
            return result;
        }
        StringBuilder current = new StringBuilder();
        double width = 0;
        int lastBreakPos = -1;
        boolean lastBreakWasSpace = false;
        CharCategory previousCategory = null;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ChatColor.COLOR_CHAR && i + 1 < line.length()) {
                char code = line.charAt(i + 1);
                current.append(c).append(code);
                i++;
                continue;
            }
            CharCategory category = categorize(c);
            if (category == CharCategory.SPACE && current.length() == 0) {
                continue;
            }
            if (category == CharCategory.SPACE) {
                lastBreakPos = current.length();
                lastBreakWasSpace = true;
                previousCategory = null;
            } else if (previousCategory != null && previousCategory != category) {
                lastBreakPos = current.length();
                lastBreakWasSpace = false;
            }
            double glyph = glyphWidth(c);
            if (width + glyph > LORE_WIDTH_LIMIT && current.length() > 0) {
                if (lastBreakPos >= 0) {
                    String lineText = current.substring(0, lastBreakPos);
                    result.add(lineText);
                    String remainder = current.substring(lastBreakPos);
                    if (lastBreakWasSpace) {
                        remainder = dropLeadingSpacesPreserveColors(remainder);
                    }
                    String carryColors = ChatColor.getLastColors(lineText);
                    current = new StringBuilder();
                    if (!carryColors.isEmpty()) {
                        current.append(carryColors);
                    }
                    if (!remainder.isEmpty()) {
                        current.append(remainder);
                    }
                    width = calculateWidth(remainder);
                    previousCategory = getLastCategory(remainder);
                    lastBreakPos = -1;
                    lastBreakWasSpace = false;
                    i--;
                    continue;
                }
                if (current.length() == 0) {
                    current.append(c);
                    width += glyph;
                    previousCategory = category == CharCategory.SPACE ? null : category;
                    continue;
                }
                result.add(current.toString());
                String carryColors = ChatColor.getLastColors(current.toString());
                current = new StringBuilder();
                if (!carryColors.isEmpty()) {
                    current.append(carryColors);
                }
                width = 0;
                previousCategory = null;
                lastBreakPos = -1;
                lastBreakWasSpace = false;
                i--;
                continue;
            }
            current.append(c);
            width += glyph;
            if (category != CharCategory.SPACE) {
                previousCategory = category;
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        } else if (result.isEmpty()) {
            result.add("");
        }
        return result;
    }

    private String dropLeadingSpacesPreserveColors(String text) {
        if (text.isEmpty()) {
            return text;
        }
        StringBuilder builder = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == ChatColor.COLOR_CHAR && index + 1 < text.length()) {
                builder.append(c).append(text.charAt(index + 1));
                index += 2;
                continue;
            }
            if (c == ' ') {
                index++;
                continue;
            }
            builder.append(text.substring(index));
            break;
        }
        return builder.toString();
    }

    private double calculateWidth(String text) {
        double value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ChatColor.COLOR_CHAR && i + 1 < text.length()) {
                i++;
                continue;
            }
            value += glyphWidth(c);
        }
        return value;
    }

    private CharCategory getLastCategory(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ChatColor.COLOR_CHAR && i - 1 >= 0) {
                i--;
                continue;
            }
            CharCategory category = categorize(c);
            if (category == CharCategory.SPACE) {
                return null;
            }
            return category;
        }
        return null;
    }

    private CharCategory categorize(char c) {
        if (c == ' ') {
            return CharCategory.SPACE;
        }
        if (c >= '0' && c <= '9') {
            return CharCategory.DIGIT;
        }
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
            return CharCategory.LATIN;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        if (block == Character.UnicodeBlock.HIRAGANA) {
            return CharCategory.HIRAGANA;
        }
        if (block == Character.UnicodeBlock.KATAKANA || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS) {
            return CharCategory.KATAKANA;
        }
        if (isKanjiBlock(block)) {
            return CharCategory.KANJI;
        }
        return CharCategory.OTHER;
    }

    private boolean isKanjiBlock(Character.UnicodeBlock block) {
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private enum CharCategory {
        SPACE,
        LATIN,
        DIGIT,
        HIRAGANA,
        KATAKANA,
        KANJI,
        OTHER
    }

    private double glyphWidth(char c) {
        if (Character.isISOControl(c) || c == '\n' || c == '\r') {
            return 0;
        }
        return isWideGlyph(c) ? 1.5D : 1.0D;
    }

    private boolean isWideGlyph(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_A
                || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_B
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }

    private HttpUrl.Builder apiUrlBuilder(String path) {
        String base = plugin.getBaseUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        HttpUrl baseUrl = HttpUrl.parse(base);
        if (baseUrl == null) {
            return null;
        }
        HttpUrl.Builder builder = baseUrl.newBuilder();
        if (path != null && !path.isBlank()) {
            String normalized = path.startsWith("/") ? path.substring(1) : path;
            if (!normalized.isEmpty()) {
                String[] segments = normalized.split("/");
                for (String segment : segments) {
                    if (!segment.isEmpty()) {
                        builder.addPathSegment(segment);
                    }
                }
            }
        }
        return builder;
    }

    private HttpUrl buildApiUrl(String path) {
        HttpUrl.Builder builder = apiUrlBuilder(path);
        return builder != null ? builder.build() : null;
    }

    private void requestOwnedShops(Player player, Consumer<List<String>> success, Consumer<String> error) {
        OkHttpClient client = plugin.getHttpClient();
        if (client == null || !plugin.isActive()) {
            error.accept(ChatColor.RED + "Backend unavailable" + ChatColor.RESET);
            return;
        }
        HttpUrl.Builder builder = apiUrlBuilder("/api/shop/ids");
        if (builder == null) {
            error.accept(ChatColor.RED + "Backend unavailable" + ChatColor.RESET);
            return;
        }
        HttpUrl url = builder
                .addQueryParameter("owner_uuid", player.getUniqueId().toString())
                .build();
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                error.accept(ChatColor.RED + "Failed to load shops" + ChatColor.RESET);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        error.accept(ChatColor.RED + "Failed to load shops" + ChatColor.RESET);
                        return;
                    }
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    List<String> result = new ArrayList<>();
                    if (json.has("ids") && json.get("ids").isJsonArray()) {
                        JsonArray arr = json.getAsJsonArray("ids");
                        for (JsonElement element : arr) {
                            if (element != null && element.isJsonPrimitive()) {
                                String id = element.getAsString();
                                if (id != null && !id.isBlank()) {
                                    result.add(id);
                                }
                            }
                        }
                    }
                    success.accept(result);
                }
            }
        });
    }

    private void requestShop(Player player, String shopId, Consumer<ShopData> success, Consumer<String> error) {
        OkHttpClient client = plugin.getHttpClient();
        if (client == null || !plugin.isActive()) {
            error.accept(ChatColor.RED + "Backend unavailable" + ChatColor.RESET);
            return;
        }
        HttpUrl.Builder builder = apiUrlBuilder("/api/shop/items");
        if (builder == null) {
            error.accept(ChatColor.RED + "Backend unavailable" + ChatColor.RESET);
            return;
        }
        HttpUrl url = builder
                .addQueryParameter("shop_id", shopId)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                error.accept(ChatColor.RED + "Failed to load shop" + ChatColor.RESET);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        error.accept(ChatColor.RED + "Failed to load shop" + ChatColor.RESET);
                        return;
                    }
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    if (json.has("reason") && "shop_not_found".equalsIgnoreCase(json.get("reason").getAsString())) {
                        error.accept(ChatColor.RED + "Shop not found" + ChatColor.RESET);
                        return;
                    }
                    ShopData data = new ShopData(shopId);
                    if (json.has("status") && !json.get("status").isJsonNull()) {
                        data.status = json.get("status").getAsString();
                    }
                    if (json.has("trade_mode") && !json.get("trade_mode").isJsonNull()) {
                        data.tradeMode = json.get("trade_mode").getAsString();
                    }
                    if (json.has("sort_mode") && !json.get("sort_mode").isJsonNull()) {
                        data.sortMode = json.get("sort_mode").getAsString();
                    }
                    if (json.has("sale_limit") && json.get("sale_limit").isJsonObject()) {
                        JsonObject limit = json.getAsJsonObject("sale_limit");
                        int qty = limit.has("quantity") ? limit.get("quantity").getAsInt() : 0;
                        String period = limit.has("period") && !limit.get("period").isJsonNull()
                                ? limit.get("period").getAsString() : null;
                        data.saleLimit = new SaleLimit(qty, period);
                    }
                    if (json.has("sale_pct") && !json.get("sale_pct").isJsonNull()) {
                        int pct = json.get("sale_pct").getAsInt();
                        long ends = json.has("sale_ends") && !json.get("sale_ends").isJsonNull()
                                ? json.get("sale_ends").getAsLong() : 0L;
                        data.sale = new ShopSale(pct, ends);
                    }
                    if (json.has("listed")) {
                        data.listed = json.get("listed").getAsBoolean();
                    }
                    if (json.has("account_uuid") && !json.get("account_uuid").isJsonNull()) {
                        data.accountUuid = json.get("account_uuid").getAsString();
                    }
                    if (json.has("owners") && json.get("owners").isJsonArray()) {
                        JsonArray arr = json.getAsJsonArray("owners");
                        for (JsonElement owner : arr) {
                            if (owner != null && owner.isJsonPrimitive()) {
                                String value = owner.getAsString();
                                if (value != null && !value.isBlank()) {
                                    data.owners.add(value);
                                }
                            }
                        }
                    }
                    if (json.has("items") && json.get("items").isJsonArray()) {
                        JsonArray items = json.getAsJsonArray("items");
                        int slot = 0;
                        for (JsonElement element : items) {
                            if (element == null || !element.isJsonObject()) continue;
                            JsonObject obj = element.getAsJsonObject();
                            if (!obj.has("item_key") || !obj.has("nbt_blob")) continue;
                            String itemKey = obj.get("item_key").getAsString();
                            String saleName = obj.has("sale_name") && !obj.get("sale_name").isJsonNull()
                                    ? obj.get("sale_name").getAsString() : itemKey;
                            String blob = obj.get("nbt_blob").getAsString();
                            ItemStack stack = itemFromBase64(blob);
                            ItemStack raw = stack.clone();
                            int stock = obj.has("stock") ? obj.get("stock").getAsInt() : 0;
                            List<String> lore = new ArrayList<>();
                            lore.add(ChatColor.GREEN + "Name: " + ChatColor.YELLOW + saleName);
                            lore.add(ChatColor.GREEN + "Stock: " + ChatColor.YELLOW + stock);
                            Map<String, ShopItem.ShopPrice> priceMap = new HashMap<>();
                            if (obj.has("prices") && obj.get("prices").isJsonObject()) {
                                for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject("prices").entrySet()) {
                                    String currency = entry.getKey();
                                    JsonObject price = entry.getValue().getAsJsonObject();
                                    Integer sellPrice = price.has("sell") && !price.get("sell").isJsonNull()
                                            ? price.get("sell").getAsInt() : null;
                                    Integer buyPrice = price.has("buy") && !price.get("buy").isJsonNull()
                                            ? price.get("buy").getAsInt() : null;
                                    String currencyText = currency == null || currency.isBlank()
                                            ? ChatColor.GRAY + "(default)"
                                            : ChatColor.WHITE + currency;
                                    ShopItem.ShopPrice priceData = new ShopItem.ShopPrice(sellPrice, buyPrice);
                                    if (sellPrice != null) {
                                        lore.add(ChatColor.GREEN + "Sell: " + ChatColor.YELLOW + plugin.formatAmountPlain(sellPrice)
                                                + ChatColor.WHITE + " " + currencyText);
                                    }
                                    if (buyPrice != null) {
                                        lore.add(ChatColor.AQUA + "Buy: " + ChatColor.YELLOW + plugin.formatAmountPlain(buyPrice)
                                                + ChatColor.WHITE + " " + currencyText);
                                    }
                                    if (price.has("autoprice") && price.get("autoprice").isJsonObject()) {
                                        JsonObject auto = price.getAsJsonObject("autoprice");
                                        int lower = auto.has("lower_threshold") ? auto.get("lower_threshold").getAsInt() : 0;
                                        int upper = auto.has("upper_threshold") ? auto.get("upper_threshold").getAsInt() : 0;
                                        int high = auto.has("high_price") ? auto.get("high_price").getAsInt() : 0;
                                        int low = auto.has("low_price") ? auto.get("low_price").getAsInt() : 0;
                                        priceData.setAutoprice(new ShopItem.AutoPriceConfig(lower, upper, high, low));
                                        lore.add(ChatColor.GOLD + "Autoprice: " + ChatColor.YELLOW + "ON" + ChatColor.WHITE + " " + currencyText);
                                    }
                                    priceMap.put(currency, priceData);
                                }
                            }
                            ItemMeta meta = stack.getItemMeta();
                            if (meta != null) {
                                meta.setLore(wrapLoreLines(lore));
                                stack.setItemMeta(meta);
                            }
                            ShopItem item = new ShopItem(itemKey, saleName, stack, raw, stock, priceMap);
                            data.entries.add(new ShopEntry(item, slot));
                            slot++;
                        }
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> success.accept(data));
                }
            }
        });
    }

    private void postJson(Player player, String path, JsonObject payload, Consumer<JsonObject> success, Consumer<String> error) {
        OkHttpClient client = plugin.getHttpClient();
        if (client == null || !plugin.isActive()) {
            error.accept(ChatColor.RED + "Backend unavailable" + ChatColor.RESET);
            return;
        }
        HttpUrl url = buildApiUrl(path);
        if (url == null) {
            error.accept(ChatColor.RED + "Backend unavailable" + ChatColor.RESET);
            return;
        }
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        error.accept(ChatColor.RED + "Request failed" + ChatColor.RESET));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                                error.accept(ChatColor.RED + "Request failed" + ChatColor.RESET));
                        return;
                    }
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    Bukkit.getScheduler().runTask(plugin, () -> success.accept(json));
                }
            }
        });
    }

    private void checkShopAvailability(Player player, String shopId, Consumer<Boolean> available, Consumer<String> error) {
        OkHttpClient client = plugin.getHttpClient();
        if (client == null || !plugin.isActive()) {
            error.accept(ChatColor.RED + "Backend unavailable" + ChatColor.RESET);
            return;
        }
        HttpUrl.Builder builder = apiUrlBuilder("/api/shop/items");
        if (builder == null) {
            error.accept(ChatColor.RED + "Backend unavailable" + ChatColor.RESET);
            return;
        }
        HttpUrl url = builder
                .addQueryParameter("shop_id", shopId)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Bukkit.getScheduler().runTask(plugin, () -> error.accept(ChatColor.RED + "Failed to validate ID" + ChatColor.RESET));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        Bukkit.getScheduler().runTask(plugin, () -> available.accept(true));
                        return;
                    }
                    String body = response.body() != null ? response.body().string() : "{}";
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    if (json.has("owners") && json.get("owners").isJsonArray()) {
                        JsonArray owners = json.getAsJsonArray("owners");
                        if (owners.size() == 0) {
                            Bukkit.getScheduler().runTask(plugin, () -> available.accept(true));
                            return;
                        }
                        String playerId = player.getUniqueId().toString();
                        for (JsonElement owner : owners) {
                            if (owner != null && owner.isJsonPrimitive() && playerId.equalsIgnoreCase(owner.getAsString())) {
                                Bukkit.getScheduler().runTask(plugin, () -> available.accept(true));
                                return;
                            }
                        }
                        Bukkit.getScheduler().runTask(plugin, () -> available.accept(false));
                        return;
                    }
                    if (json.has("reason") && "shop_not_found".equalsIgnoreCase(json.get("reason").getAsString())) {
                        Bukkit.getScheduler().runTask(plugin, () -> available.accept(true));
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> available.accept(false));
                }
            }
        });
    }

    private void giveShopBarrel(Player player, String shopId) {
        ItemStack barrel = new ItemStack(Material.BARREL);
        ItemMeta meta = barrel.getItemMeta();
        if (meta != null) {
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(keyShop, PersistentDataType.BYTE, (byte) 1);
            container.set(keyId, PersistentDataType.STRING, shopId);
            container.set(keyOwner, PersistentDataType.STRING, player.getUniqueId().toString());
            if (meta instanceof BlockStateMeta blockMeta) {
                BlockState state = blockMeta.getBlockState();
                if (state instanceof TileState tile) {
                    PersistentDataContainer tileContainer = tile.getPersistentDataContainer();
                    tileContainer.set(keyShop, PersistentDataType.BYTE, (byte) 1);
                    tileContainer.set(keyId, PersistentDataType.STRING, shopId);
                    tileContainer.set(keyOwner, PersistentDataType.STRING, player.getUniqueId().toString());
                    tile.update(true);
                    blockMeta.setBlockState(tile);
                }
                barrel.setItemMeta(blockMeta);
            } else {
                barrel.setItemMeta(meta);
            }
        }
        player.getInventory().addItem(barrel);
    }

    private void openShopInventory(Player player, ShopData data) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            sessions.remove(player.getUniqueId());
            int size = Math.max(9, ((data.entries.size() + 8) / 9) * 9);
            ShopMenuHolder holder = new ShopMenuHolder(data.shopId);
            holder.setTradeMode(data.tradeMode);
            for (String owner : data.owners) {
                holder.addOwnerUuid(owner);
            }
            Inventory inv = Bukkit.createInventory(holder, size, ChatColor.GOLD + "Shop " + data.shopId);
            holder.setInventory(inv);
            int slot = 0;
            for (ShopEntry entry : data.entries) {
                if (slot >= size) break;
                ShopItem item = entry.item;
                ItemStack display = item.getItem().clone();
                inv.setItem(slot, display);
                holder.getItems().put(slot, item);
                slot++;
            }
            player.openInventory(inv);
        });
    }

    private ItemStack itemFromBase64(String data) {
        byte[] bytes = java.util.Base64.getDecoder().decode(data);
        return ItemStack.deserializeBytes(bytes);
    }

    private final class ShopGuiSession {
        private final UUID playerId;
        private Inventory inventory;
        private final Map<Integer, Runnable> actions = new HashMap<>();
        private final Map<String, String> currencySelections = new HashMap<>();
        private List<String> shopIds = new ArrayList<>();
        private int page = 0;
        private ShopData currentShop;
        private Runnable reopenAction;
        private boolean closed;
        private boolean awaitingPrompt;
        private int pricePage;
        private ShopEntry priceEntry;
        private String priceCurrency;
        private boolean priceAutopriceView;
        private ShopItem.AutoPriceConfig autopriceWorking;
        private boolean autopriceNew;
        private boolean autopriceDirty;
        private SaleLimitDraft limitDraft;

        private static final class SaleLimitDraft {
            private int quantity;
            private String period;

            private SaleLimitDraft(int quantity, String period) {
                this.quantity = quantity;
                this.period = period;
            }
        }

        private ShopGuiSession(Player player) {
            this.playerId = player.getUniqueId();
            this.closed = false;
            this.awaitingPrompt = false;
            this.pricePage = 0;
            this.priceEntry = null;
            this.priceCurrency = null;
            this.priceAutopriceView = false;
            this.autopriceWorking = null;
            this.autopriceNew = false;
            this.autopriceDirty = false;
            this.limitDraft = null;
        }

        private Player player() {
            if (closed) {
                return null;
            }
            return Bukkit.getPlayer(playerId);
        }

        private void showMainMenu() {
            if (closed) {
                return;
            }
            Player player = player();
            if (player == null) return;
            Inventory menu = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 27,
                    ChatColor.DARK_AQUA + "Shop Manager");
            menu.setItem(11, button(Material.CHEST, ChatColor.GOLD + "My Shops",
                    ChatColor.GRAY + "View and edit owned shops"));
            actions.clear();
            actions.put(11, this::openShopList);
            menu.setItem(13, button(Material.BARREL, ChatColor.GREEN + "Create Shop",
                    ChatColor.GRAY + "Get a new shop barrel"));
            actions.put(13, this::promptCreateShop);
            menu.setItem(15, button(Material.EMERALD, ChatColor.AQUA + "Market",
                    ChatColor.GRAY + "Browse shared shops"));
            actions.put(15, () -> {
                Player pl = player();
                if (pl != null) {
                    plugin.getMarketManager().openMarket(pl);
                }
            });
            menu.setItem(17, button(Material.BOOK, ChatColor.GREEN + "Help",
                    ChatColor.GRAY + "Click to view shop commands"));
            actions.put(17, () -> {
                Player pl = player();
                if (pl != null) {
                    pl.closeInventory();
                    pl.performCommand("le shop help");
                }
            });
            this.inventory = menu;
            this.reopenAction = this::showMainMenu;
            player.openInventory(menu);
        }

        private void reopen() {
            if (closed) {
                return;
            }
            if (awaitingPrompt) {
                return;
            }
            Player player = player();
            if (player == null) return;
            if (inventory != null) {
                player.openInventory(inventory);
            } else if (reopenAction != null) {
                reopenAction.run();
            } else {
                showMainMenu();
            }
        }

        private void execute(int slot) {
            if (closed) {
                return;
            }
            Runnable action = actions.get(slot);
            if (action != null) {
                action.run();
            }
        }

        private void clearAutopriceDraft() {
            autopriceWorking = null;
            autopriceNew = false;
            autopriceDirty = false;
        }

        private SaleLimitDraft currentLimitDraft(ShopData data) {
            if (limitDraft == null) {
                int qty = data.saleLimit != null ? Math.max(0, data.saleLimit.quantity) : 0;
                String period = data.saleLimit != null && data.saleLimit.period != null && !data.saleLimit.period.isBlank()
                        ? data.saleLimit.period : "day";
                limitDraft = new SaleLimitDraft(qty, period);
            }
            return limitDraft;
        }

        private boolean startPrompt() {
            if (closed || awaitingPrompt) {
                return false;
            }
            ShopGuiManager.this.cancelPrompt(playerId, false);
            awaitingPrompt = true;
            return true;
        }

        private void finishPrompt(boolean reopen) {
            if (!awaitingPrompt) {
                if (reopen && !closed) {
                    reopen();
                }
                return;
            }
            awaitingPrompt = false;
            if (reopen && !closed) {
                reopen();
            }
        }

        private boolean isAwaitingPrompt() {
            return awaitingPrompt;
        }

        private void dropInventoryReference() {
            inventory = null;
        }

        private void handleShopLoaded(ShopData data) {
            if (closed) {
                return;
            }
            pricePage = 0;
            priceEntry = null;
            priceCurrency = null;
            priceAutopriceView = false;
            currencySelections.clear();
            limitDraft = null;
            this.currentShop = data;
            if (!"active".equalsIgnoreCase(data.status)) {
                showInactiveShop(data);
            } else {
                showShopDetails(data);
            }
        }

        private void showInactiveShop(ShopData data) {
            Player player = player();
            if (player == null) return;
            Inventory inv = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 27,
                    ChatColor.RED + "Shop " + data.shopId + " - " + data.status);
            actions.clear();
            inv.setItem(11, button(Material.BARRIER, ChatColor.RED + "Status: " + data.status.toUpperCase(),
                    ChatColor.GRAY + "Last activity: " + (data.status.equalsIgnoreCase("suspended")
                            ? ChatColor.YELLOW + "Suspended"
                            : ChatColor.YELLOW + "Pending")));
            inv.setItem(13, ownerSummary(data));
            inv.setItem(15, button(Material.LIME_DYE, ChatColor.GREEN + "Reopen",
                    ChatColor.GRAY + "Request reopening this shop"));
            actions.put(15, () -> requestReopen(data));
            inv.setItem(18, button(Material.ARROW, ChatColor.YELLOW + "Back"));
            actions.put(18, this::renderShopList);
            inv.setItem(26, button(Material.CLOCK, ChatColor.GREEN + "Refresh"));
            actions.put(26, () -> openShop(data.shopId));
            this.inventory = inv;
            this.reopenAction = () -> showInactiveShop(data);
            player.openInventory(inv);
        }

        private void showShopDetails(ShopData data) {
            Player player = player();
            if (player == null) return;
            Inventory inv = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 54,
                    ChatColor.GOLD + "Shop " + data.shopId);
            actions.clear();
            inv.setItem(4, shopInfo(data));
            inv.setItem(10, button(Material.CHEST, ChatColor.GOLD + "Manage Stock",
                    ChatColor.GRAY + "Open the trading inventory"));
            actions.put(10, () -> openStockEditor(data));
            inv.setItem(11, button(Material.GOLD_INGOT, ChatColor.GOLD + "Adjust Prices",
                    ChatColor.GRAY + "Edit sell/buy values"));
            actions.put(11, () -> openPriceManager(data));
            inv.setItem(12, button(data.listed ? Material.OAK_SIGN : Material.INK_SAC,
                    data.listed ? ChatColor.GREEN + "Listed" : ChatColor.RED + "Hidden",
                    ChatColor.GRAY + (data.listed ? "Click to hide from catalog" : "Click to publish")));
            actions.put(12, () -> toggleListing(data));
            inv.setItem(13, button(Material.COMPASS, ChatColor.AQUA + "Sort: " + formatSortMode(data.sortMode),
                    ChatColor.GRAY + "Cycle item ordering",
                    ChatColor.YELLOW + "Registered \u2192 Name \u2192 Price \u2192 Stock"));
            actions.put(13, () -> cycleSortMode(data));
            inv.setItem(14, button(Material.LEVER, ChatColor.AQUA + "Mode: " + data.tradeMode.toUpperCase(),
                    ChatColor.GRAY + "Toggle buy/sell permissions"));
            actions.put(14, () -> cycleTradeMode(data));
            inv.setItem(15, button(Material.PLAYER_HEAD, ChatColor.AQUA + "Buyer Limit",
                    ChatColor.GRAY + "Per-player cap:",
                    formatLimit(data.saleLimit)));
            actions.put(15, () -> openLimitManager(data));
            inv.setItem(16, button(Material.PLAYER_HEAD, ChatColor.AQUA + "Partners",
                    ChatColor.GRAY + "Manage co-owners"));
            actions.put(16, () -> openPartnerManager(data));
            inv.setItem(29, button(Material.CLOCK, ChatColor.LIGHT_PURPLE + "Sale",
                    ChatColor.GRAY + "Current: " + formatSale(data.sale),
                    ChatColor.YELLOW + "Click to schedule a discount"));
            actions.put(29, () -> promptSale(data));
            inv.setItem(28, button(Material.BOOK, ChatColor.YELLOW + "Payout Account",
                    ChatColor.GRAY + formatAccount(data)));
            actions.put(28, () -> promptAccount(data));
            inv.setItem(34, button(Material.TNT, ChatColor.DARK_RED + "Remove Shop",
                    ChatColor.GRAY + "Delete the shop barrel"));
            actions.put(34, () -> openRemoveConfirm(data));
            inv.setItem(45, button(Material.ARROW, ChatColor.YELLOW + "Back"));
            actions.put(45, this::renderShopList);
            inv.setItem(53, button(Material.CLOCK, ChatColor.GREEN + "Refresh"));
            actions.put(53, () -> openShop(data.shopId));
            this.inventory = inv;
            this.reopenAction = () -> showShopDetails(data);
            player.openInventory(inv);
        }

        private ItemStack shopInfo(ShopData data) {
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Status: " + ChatColor.YELLOW + data.status.toUpperCase());
            lore.add(ChatColor.GRAY + "Mode: " + ChatColor.YELLOW + data.tradeMode.toUpperCase());
            lore.add(ChatColor.GRAY + "Limit: " + formatLimit(data.saleLimit));
            lore.add(ChatColor.GRAY + "Sale: " + formatSale(data.sale));
            lore.add(ChatColor.GRAY + "Listed: " + (data.listed ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
            lore.add(ChatColor.GRAY + "Sort: " + ChatColor.YELLOW + formatSortMode(data.sortMode));
            lore.add(ChatColor.GRAY + "Items: " + ChatColor.YELLOW + data.entries.size());
            return button(Material.PAPER, ChatColor.AQUA + "Overview", lore.toArray(new String[0]));
        }

        private ItemStack ownerSummary(ShopData data) {
            List<String> lore = new ArrayList<>();
            if (data.owners.isEmpty()) {
                lore.add(ChatColor.RED + "No owners registered");
            } else {
                lore.add(ChatColor.GRAY + "Owners:");
                for (String owner : data.owners) {
                    lore.add(ChatColor.YELLOW + displayName(owner));
                }
            }
            return button(Material.WRITABLE_BOOK, ChatColor.AQUA + "Owners", lore.toArray(new String[0]));
        }

        private String formatAccount(ShopData data) {
            String account = data.accountUuid;
            if (account == null || account.isBlank() || account.equalsIgnoreCase(data.primaryOwner())) {
                return ChatColor.YELLOW + "Self";
            }
            return ChatColor.YELLOW + displayName(account);
        }

        private String formatSortMode(String mode) {
            String normalized = mode == null ? "created" : mode.toLowerCase();
            return switch (normalized) {
                case "name" -> "Name";
                case "price" -> "Price";
                case "stock" -> "Stock";
                default -> "Registered";
            };
        }

        private String formatLimit(SaleLimit limit) {
            if (limit == null || limit.quantity <= 0 || limit.period == null || limit.period.isBlank()) {
                return ChatColor.YELLOW + "Unlimited";
            }
            String periodLabel = switch (limit.period.toLowerCase()) {
                case "once" -> "Once";
                case "day" -> "Daily";
                case "week" -> "Weekly";
                case "month" -> "Monthly";
                default -> limit.period;
            };
            return ChatColor.YELLOW + Integer.toString(limit.quantity) + ChatColor.GRAY + " / " + ChatColor.YELLOW + periodLabel;
        }

        private String formatSale(ShopSale sale) {
            if (sale == null || sale.pct <= 0) {
                return ChatColor.YELLOW + "None";
            }
            String until = sale.endTs > 0
                    ? ChatColor.GRAY + "until " + ChatColor.YELLOW + Instant.ofEpochSecond(sale.endTs)
                    : ChatColor.GRAY + "active";
            return ChatColor.YELLOW + Integer.toString(sale.pct) + "% " + until;
        }

        private Long parseDuration(String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            Pattern pattern = Pattern.compile("(\\d+)([smhd]?)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(trimmed);
            long total = 0L;
            int matches = 0;
            int lastEnd = 0;
            while (matcher.find()) {
                if (matcher.start() != lastEnd) {
                    return null;
                }
                matches++;
                long value = Long.parseLong(matcher.group(1));
                String unit = matcher.group(2) != null ? matcher.group(2).toLowerCase() : "";
                long multiplier = switch (unit) {
                    case "m" -> 60L;
                    case "h" -> 3600L;
                    case "d" -> 86400L;
                    default -> 1L;
                };
                total += value * multiplier;
                lastEnd = matcher.end();
            }
            if (matches == 0 || lastEnd != trimmed.length() || total <= 0) {
                return null;
            }
            return total;
        }

        private String displayName(String raw) {
            if (raw == null || raw.isBlank()) {
                return ChatColor.DARK_GRAY + "unknown" + ChatColor.RESET;
            }
            try {
                UUID uuid = UUID.fromString(raw);
                OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                if (offline != null && offline.getName() != null && !offline.getName().isBlank()) {
                    return offline.getName();
                }
                return raw;
            } catch (IllegalArgumentException ex) {
                return raw;
            }
        }

        private void openStockEditor(ShopData data) {
            Player player = player();
            if (player == null) return;
            sessions.remove(playerId);
            clear();
            openShopInventory(player, data);
        }

        private void promptCreateShop() {
            Player player = player();
            if (player == null) return;
            if (!startPrompt()) {
                return;
            }
            beginPrompt(this, player,
                    "Enter a unique shop ID / ショップIDを入力してください",
                    input -> {
                        Player p = player();
                        if (p == null) return;
                        String id = input.trim();
                        if (id.isEmpty()) {
                            p.sendMessage(ChatColor.RED + "Shop ID cannot be empty / ショップIDを入力してください" + ChatColor.RESET);
                            showMainMenu();
                            return;
                        }
                        checkShopAvailability(p, id, available -> {
                            if (!available) {
                                p.sendMessage(ChatColor.RED + "Shop ID unavailable / 使用できません" + ChatColor.RESET);
                                showMainMenu();
                            } else {
                                giveShopBarrel(p, id);
                                p.sendMessage(ChatColor.GREEN + "Issued shop barrel / ショップ樽を付与しました" + ChatColor.RESET);
                                showMainMenu();
                            }
                        }, message -> {
                            p.sendMessage(message);
                            showMainMenu();
                        });
                    },
                    null);
        }

        private void toggleListing(ShopData data) {
            Player player = player();
            if (player == null) return;
            boolean desired = !data.listed;
            JsonObject payload = new JsonObject();
            payload.addProperty("owner_uuid", player.getUniqueId().toString());
            payload.addProperty("shop_id", data.shopId);
            payload.addProperty("listed", desired);
            postJson(player, "/api/shop/listing", payload, json -> {
                String status = json.has("status") ? json.get("status").getAsString() : "error";
                if ("success".equalsIgnoreCase(status)) {
                    player.sendMessage(ChatColor.GREEN + (desired ? "Shop published / 掲載しました" : "Shop hidden / 非掲載にしました") + ChatColor.RESET);
                    openShop(data.shopId);
                } else {
                    notifyFailure(player, json.has("reason") ? json.get("reason").getAsString() : "error");
                    showShopDetails(data);
                }
            }, msg -> {
                player.sendMessage(msg);
                showShopDetails(data);
            });
        }

        private void cycleSortMode(ShopData data) {
            Player player = player();
            if (player == null) return;
            String mode = data.sortMode == null ? "created" : data.sortMode.toLowerCase();
            String next = switch (mode) {
                case "created" -> "name";
                case "name" -> "price";
                case "price" -> "stock";
                default -> "created";
            };
            JsonObject payload = new JsonObject();
            payload.addProperty("owner_uuid", player.getUniqueId().toString());
            payload.addProperty("shop_id", data.shopId);
            payload.addProperty("sort_mode", next);
            postJson(player, "/api/shop/sort", payload, json -> {
                String status = json.has("status") ? json.get("status").getAsString() : "error";
                if ("success".equalsIgnoreCase(status)) {
                    player.sendMessage(ChatColor.GREEN + "Sort order updated / 並び順を更新しました" + ChatColor.RESET);
                    openShop(data.shopId);
                } else {
                    notifyFailure(player, json.has("reason") ? json.get("reason").getAsString() : "error");
                    showShopDetails(data);
                }
            }, msg -> {
                player.sendMessage(msg);
                showShopDetails(data);
            });
        }

        private void cycleTradeMode(ShopData data) {
            Player player = player();
            if (player == null) return;
            String mode = data.tradeMode == null ? "both" : data.tradeMode.toLowerCase();
            String next = switch (mode) {
                case "both" -> "sell";
                case "sell" -> "buy";
                default -> "both";
            };
            JsonObject payload = new JsonObject();
            payload.addProperty("owner_uuid", player.getUniqueId().toString());
            payload.addProperty("shop_id", data.shopId);
            payload.addProperty("mode", next);
            postJson(player, "/api/shop/mode", payload, json -> {
                String status = json.has("status") ? json.get("status").getAsString() : "error";
                if ("success".equalsIgnoreCase(status)) {
                    player.sendMessage(ChatColor.GREEN + "Trade mode updated / 種別を更新しました" + ChatColor.RESET);
                    openShop(data.shopId);
                } else {
                    notifyFailure(player, json.has("reason") ? json.get("reason").getAsString() : "error");
                    showShopDetails(data);
                }
            }, msg -> {
                player.sendMessage(msg);
                showShopDetails(data);
            });
        }

        private void promptAccount(ShopData data) {
            Player player = player();
            if (player == null) return;
            if (!startPrompt()) {
                return;
            }
            beginPrompt(this, player,
                    "Enter payout account (player name, UUID, or self) / 入金先口座を入力してください",
                    input -> {
                        Player p = player();
                        if (p == null) return;
                        String trimmed = input.trim();
                        if (trimmed.equalsIgnoreCase("self") || trimmed.equalsIgnoreCase("owner") || trimmed.equalsIgnoreCase("personal")) {
                            trimmed = "self";
                        }
                        JsonObject payload = new JsonObject();
                        payload.addProperty("owner_uuid", p.getUniqueId().toString());
                        payload.addProperty("shop_id", data.shopId);
                        payload.addProperty("account_id", trimmed);
                        postJson(p, "/api/shop/account", payload, json -> {
                            String status = json.has("status") ? json.get("status").getAsString() : "error";
                            if ("success".equalsIgnoreCase(status)) {
                                p.sendMessage(ChatColor.GREEN + "Account updated / 取引口座を更新しました" + ChatColor.RESET);
                                openShop(data.shopId);
                            } else {
                                notifyFailure(p, json.has("reason") ? json.get("reason").getAsString() : "error");
                                showShopDetails(data);
                            }
                        }, msg -> {
                            p.sendMessage(msg);
                            showShopDetails(data);
                        });
                    },
                    null);
        }

        private void promptSale(ShopData data) {
            Player player = player();
            if (player == null) return;
            if (!startPrompt()) {
                return;
            }
            beginPrompt(this, player,
                    "Enter duration and discount (e.g. 30m 15) / 期間と割引率を入力してください (例: 2h30m 25)",
                    input -> {
                        Player p = player();
                        if (p == null) return;
                        String[] parts = input.trim().split("\\s+");
                        if (parts.length < 2) {
                            p.sendMessage(ChatColor.RED + "Please provide duration and discount / 期間と割引率を入力してください" + ChatColor.RESET);
                            showShopDetails(data);
                            return;
                        }
                        Long duration = parseDuration(parts[0]);
                        if (duration == null || duration <= 0) {
                            p.sendMessage(ChatColor.RED + "Invalid duration / 期間の指定が不正です" + ChatColor.RESET);
                            showShopDetails(data);
                            return;
                        }
                        int discount;
                        try {
                            discount = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException ex) {
                            p.sendMessage(ChatColor.RED + "Invalid discount / 割引率が不正です" + ChatColor.RESET);
                            showShopDetails(data);
                            return;
                        }
                        if (discount <= 0 || discount >= 100) {
                            p.sendMessage(ChatColor.RED + "Discount must be between 1-99 / 1〜99の範囲で指定してください" + ChatColor.RESET);
                            showShopDetails(data);
                            return;
                        }
                        JsonObject payload = new JsonObject();
                        payload.addProperty("owner_uuid", p.getUniqueId().toString());
                        payload.addProperty("shop_id", data.shopId);
                        payload.addProperty("duration_seconds", duration);
                        payload.addProperty("pct", discount);
                        postJson(p, "/api/shop/sale", payload, json -> {
                            String status = json.has("status") ? json.get("status").getAsString() : "error";
                            if ("success".equalsIgnoreCase(status)) {
                                p.sendMessage(ChatColor.GREEN + "Sale started: " + discount + "%" + ChatColor.RESET);
                                openShop(data.shopId);
                            } else {
                                notifyFailure(p, json.has("reason") ? json.get("reason").getAsString() : "error");
                                showShopDetails(data);
                            }
                        }, msg -> {
                            p.sendMessage(msg);
                            showShopDetails(data);
                        });
                    },
                    () -> showShopDetails(data));
        }

        private void openPartnerManager(ShopData data) {
            Player player = player();
            if (player == null) return;
            Inventory inv = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 54,
                    ChatColor.AQUA + "Partners");
            actions.clear();
            List<String> partners = new ArrayList<>(data.coOwners());
            if (partners.isEmpty()) {
                inv.setItem(22, button(Material.BARRIER, ChatColor.RED + "No partners",
                        ChatColor.GRAY + "Click add to invite"));
            } else {
                int slot = 10;
                for (String partner : partners) {
                    ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                    ItemMeta meta = head.getItemMeta();
                    if (meta instanceof SkullMeta skull) {
                        try {
                            UUID uuid = UUID.fromString(partner);
                            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                            if (offline != null) {
                                skull.setOwningPlayer(offline);
                            }
                        } catch (IllegalArgumentException ignored) {
                        }
                        skull.setDisplayName(fitTitle(ChatColor.YELLOW + displayName(partner)));
                        skull.setLore(wrapLoreLines(List.of(ChatColor.RED + "Click to remove / 削除")));
                        head.setItemMeta(skull);
                    }
                    inv.setItem(slot, head);
                    final String target = partner;
                    actions.put(slot, () -> removePartner(data, target));
                    slot++;
                    if (slot == 17) slot = 19;
                    if (slot >= 35) break;
                }
            }
            inv.setItem(45, button(Material.ARROW, ChatColor.YELLOW + "Back"));
            actions.put(45, () -> showShopDetails(data));
            inv.setItem(50, button(Material.EMERALD, ChatColor.GREEN + "Add Partner",
                    ChatColor.GRAY + "Invite another player"));
            actions.put(50, () -> promptAddPartner(data));
            inv.setItem(53, button(Material.CLOCK, ChatColor.GREEN + "Refresh"));
            actions.put(53, () -> openShop(data.shopId));
            this.inventory = inv;
            this.reopenAction = () -> openPartnerManager(data);
            player.openInventory(inv);
        }

        private void promptAddPartner(ShopData data) {
            Player player = player();
            if (player == null) return;
            if (!startPrompt()) {
                return;
            }
            beginPrompt(this, player,
                    "Enter partner name or UUID / 追加するプレイヤー名またはUUIDを入力",
                    input -> {
                        Player p = player();
                        if (p == null) return;
                        String trimmed = input.trim();
                        UUID targetUuid = null;
                        try {
                            targetUuid = UUID.fromString(trimmed);
                        } catch (IllegalArgumentException ex) {
                            OfflinePlayer offline = Bukkit.getOfflinePlayer(trimmed);
                            if (offline != null) {
                                targetUuid = offline.getUniqueId();
                            }
                        }
                        if (targetUuid == null) {
                            p.sendMessage(ChatColor.RED + "Player not found / プレイヤーが見つかりません" + ChatColor.RESET);
                            openPartnerManager(data);
                            return;
                        }
                        JsonObject payload = new JsonObject();
                        payload.addProperty("owner_uuid", p.getUniqueId().toString());
                        payload.addProperty("shop_id", data.shopId);
                        payload.addProperty("target_uuid", targetUuid.toString());
                        postJson(p, "/api/shop/add_owner", payload, json -> {
                            String status = json.has("status") ? json.get("status").getAsString() : "error";
                            if ("success".equalsIgnoreCase(status)) {
                                p.sendMessage(ChatColor.GREEN + "Partner added / 共同オーナーを追加しました" + ChatColor.RESET);
                                openShop(data.shopId);
                            } else {
                                notifyFailure(p, json.has("reason") ? json.get("reason").getAsString() : "error");
                                openPartnerManager(data);
                            }
                        }, msg -> {
                            p.sendMessage(msg);
                            openPartnerManager(data);
                        });
                    },
                    null);
        }

        private void removePartner(ShopData data, String partnerUuid) {
            Player player = player();
            if (player == null) return;
            JsonObject payload = new JsonObject();
            payload.addProperty("owner_uuid", player.getUniqueId().toString());
            payload.addProperty("shop_id", data.shopId);
            payload.addProperty("target_uuid", partnerUuid);
            postJson(player, "/api/shop/remove_owner", payload, json -> {
                String status = json.has("status") ? json.get("status").getAsString() : "error";
                if ("success".equalsIgnoreCase(status)) {
                    player.sendMessage(ChatColor.GREEN + "Partner removed / 共同オーナーを削除しました" + ChatColor.RESET);
                    openShop(data.shopId);
                } else {
                    notifyFailure(player, json.has("reason") ? json.get("reason").getAsString() : "error");
                    openPartnerManager(data);
                }
            }, msg -> {
                player.sendMessage(msg);
                openPartnerManager(data);
            });
        }

        private void openLimitManager(ShopData data) {
            Player player = player();
            if (player == null) return;
            SaleLimitDraft draft = currentLimitDraft(data);
            Inventory inv = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 45,
                    ChatColor.GOLD + "Buyer Limit - " + data.shopId);
            actions.clear();
            inv.setItem(4, shopInfo(data));
            inv.setItem(19, limitButton("once", "Once", Material.NETHER_STAR, draft));
            actions.put(19, () -> {
                draft.period = "once";
                openLimitManager(data);
            });
            inv.setItem(20, limitButton("day", "Daily", Material.CLOCK, draft));
            actions.put(20, () -> {
                draft.period = "day";
                openLimitManager(data);
            });
            inv.setItem(21, limitButton("week", "Weekly", Material.CALIBRATED_SCULK_SENSOR, draft));
            actions.put(21, () -> {
                draft.period = "week";
                openLimitManager(data);
            });
            inv.setItem(22, limitButton("month", "Monthly", Material.AMETHYST_SHARD, draft));
            actions.put(22, () -> {
                draft.period = "month";
                openLimitManager(data);
            });
            inv.setItem(28, button(Material.PAPER, ChatColor.AQUA + "Quantity", ChatColor.GRAY + "Current: " + formatLimit(new SaleLimit(draft.quantity, draft.period)),
                    ChatColor.YELLOW + "Click to enter amount"));
            actions.put(28, () -> promptLimitQuantity(data));
            inv.setItem(30, button(Material.BARRIER, ChatColor.RED + "Disable Limit",
                    ChatColor.GRAY + "Set to unlimited"));
            actions.put(30, () -> {
                draft.quantity = 0;
                openLimitManager(data);
            });
            inv.setItem(34, button(Material.EMERALD_BLOCK, ChatColor.GREEN + "Save Limit",
                    ChatColor.GRAY + "Apply to this shop"));
            actions.put(34, () -> submitLimit(data));
            inv.setItem(36, button(Material.ARROW, ChatColor.YELLOW + "Back"));
            actions.put(36, () -> showShopDetails(data));
            inv.setItem(44, button(Material.CLOCK, ChatColor.GREEN + "Refresh"));
            actions.put(44, () -> openShop(data.shopId));
            this.inventory = inv;
            this.reopenAction = () -> openLimitManager(data);
            player.openInventory(inv);
        }

        private ItemStack limitButton(String period, String label, Material material, SaleLimitDraft draft) {
            boolean selected = period.equalsIgnoreCase(draft.period);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Select limit window");
            lore.add(selected ? ChatColor.GREEN + "Selected" : ChatColor.YELLOW + "Click to select");
            return button(material,
                    (selected ? ChatColor.GREEN : ChatColor.AQUA) + label,
                    lore.toArray(new String[0]));
        }

        private void promptLimitQuantity(ShopData data) {
            Player player = player();
            if (player == null) return;
            if (!startPrompt()) {
                return;
            }
            beginPrompt(this, player,
                    "Enter per-player quantity (0 = unlimited) / 個数を入力 (0で無制限)",
                    input -> {
                        Player p = player();
                        if (p == null) return;
                        int value;
                        try {
                            value = Integer.parseInt(input.trim());
                        } catch (NumberFormatException ex) {
                            p.sendMessage(ChatColor.RED + "Invalid number" + ChatColor.RESET);
                            openLimitManager(data);
                            return;
                        }
                        if (value < 0) {
                            p.sendMessage(ChatColor.RED + "Enter 0 or greater" + ChatColor.RESET);
                            openLimitManager(data);
                            return;
                        }
                        currentLimitDraft(data).quantity = value;
                        openLimitManager(data);
                    },
                    () -> openLimitManager(data));
        }

        private void submitLimit(ShopData data) {
            Player player = player();
            if (player == null) return;
            SaleLimitDraft draft = currentLimitDraft(data);
            JsonObject payload = new JsonObject();
            payload.addProperty("owner_uuid", player.getUniqueId().toString());
            payload.addProperty("shop_id", data.shopId);
            payload.addProperty("quantity", Math.max(0, draft.quantity));
            payload.addProperty("period", draft.period == null ? "day" : draft.period.toLowerCase());
            postJson(player, "/api/shop/limit", payload, json -> {
                String status = json.has("status") ? json.get("status").getAsString() : "error";
                if ("success".equalsIgnoreCase(status)) {
                    player.sendMessage(ChatColor.GREEN + "Buyer limit updated / 上限を更新しました" + ChatColor.RESET);
                    openShop(data.shopId);
                } else {
                    notifyFailure(player, json.has("reason") ? json.get("reason").getAsString() : "error");
                    openLimitManager(data);
                }
            }, msg -> {
                player.sendMessage(msg);
                openLimitManager(data);
            });
        }

        private void openPriceManager(ShopData data) {
            Player player = player();
            if (player == null) return;
            pricePage = 0;
            priceEntry = null;
            priceCurrency = null;
            priceAutopriceView = false;
            renderPriceManager(data);
        }

        private void renderPriceManager(ShopData data) {
            Player player = player();
            if (player == null) return;
            clearAutopriceDraft();
            Inventory inv = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 54,
                    ChatColor.GOLD + "Prices - " + data.shopId);
            actions.clear();
            inv.setItem(4, shopInfo(data));
            inv.setItem(45, button(Material.ARROW, ChatColor.YELLOW + "Back"));
            actions.put(45, () -> showShopDetails(data));
            inv.setItem(53, button(Material.CLOCK, ChatColor.GREEN + "Refresh"));
            actions.put(53, () -> openShop(data.shopId));
            List<ShopEntry> entries = new ArrayList<>(data.entries);
            if (entries.isEmpty()) {
                inv.setItem(22, button(Material.BARRIER, ChatColor.RED + "No items",
                        ChatColor.GRAY + "Add stock before adjusting prices"));
            } else {
                int maxPage = (entries.size() - 1) / PAGE_SIZE;
                if (pricePage < 0) {
                    pricePage = 0;
                }
                if (pricePage > maxPage) {
                    pricePage = maxPage;
                }
                int start = pricePage * PAGE_SIZE;
                int end = Math.min(start + PAGE_SIZE, entries.size());
                int index = 0;
                for (int i = start; i < end; i++) {
                    ShopEntry entry = entries.get(i);
                    int row = index / 9;
                    int col = index % 9;
                    int slot = 9 + row * 9 + col;
                    inv.setItem(slot, buildPriceDisplay(entry));
                    final ShopEntry target = entry;
                    actions.put(slot, () -> openPriceEditor(data, target));
                    index++;
                }
                if (start > 0) {
                    inv.setItem(48, button(Material.ARROW, ChatColor.YELLOW + "Previous"));
                    actions.put(48, () -> {
                        pricePage = Math.max(0, pricePage - 1);
                        renderPriceManager(data);
                    });
                } else {
                    inv.setItem(48, button(Material.GRAY_STAINED_GLASS_PANE,
                            ChatColor.DARK_GRAY + "Previous"));
                }
                if (end < entries.size()) {
                    inv.setItem(50, button(Material.ARROW, ChatColor.YELLOW + "Next"));
                    actions.put(50, () -> {
                        pricePage = pricePage + 1;
                        renderPriceManager(data);
                    });
                } else {
                    inv.setItem(50, button(Material.GRAY_STAINED_GLASS_PANE,
                            ChatColor.DARK_GRAY + "Next"));
                }
                inv.setItem(49, button(Material.NAME_TAG,
                        ChatColor.AQUA + "Page " + (pricePage + 1),
                        ChatColor.GRAY + "Items " + (start + 1) + "-" + end,
                        ChatColor.GRAY + "Total: " + entries.size()));
            }
            this.inventory = inv;
            this.reopenAction = () -> renderPriceManager(data);
            this.priceEntry = null;
            this.priceCurrency = null;
            this.priceAutopriceView = false;
            player.openInventory(inv);
        }

        private void populatePriceRow(Inventory inv, int baseSlot, boolean sell, ShopData data,
                                      ShopItem item, ShopItem.ShopPrice info, String currency) {
            boolean allowed = sell
                    ? !"buy".equalsIgnoreCase(data.tradeMode)
                    : !"sell".equalsIgnoreCase(data.tradeMode);
            if (!allowed) {
                ItemStack block = button(Material.BARRIER,
                        ChatColor.RED + (sell ? "Selling disabled" : "Buying disabled"),
                        ChatColor.GRAY + "Mode: " + data.tradeMode.toUpperCase());
                for (int i = 0; i < 9; i++) {
                    int slot = baseSlot + i;
                    if (i == 4) {
                        inv.setItem(slot, block);
                    } else {
                        inv.setItem(slot, button(Material.GRAY_STAINED_GLASS_PANE,
                                ChatColor.DARK_GRAY + "Unavailable"));
                    }
                }
                return;
            }
            Integer deltaPointOne = priceDelta("0.1");
            Integer deltaOne = priceDelta("1");
            Integer deltaTen = priceDelta("10");
            Integer deltaHundred = priceDelta("100");

            final ShopItem targetItem = item;
            final String targetCurrency = currency;
            final boolean targetSell = sell;

            inv.setItem(baseSlot, button(Material.RED_STAINED_GLASS_PANE,
                    ChatColor.RED + "-100",
                    ChatColor.GRAY + "Decrease by 100"));
            actions.put(baseSlot, () -> adjustPriceValue(data, targetItem, targetCurrency, targetSell,
                    deltaHundred != null ? -deltaHundred : 0));

            inv.setItem(baseSlot + 1, button(Material.RED_STAINED_GLASS_PANE,
                    ChatColor.RED + "-10",
                    ChatColor.GRAY + "Decrease by 10"));
            actions.put(baseSlot + 1, () -> adjustPriceValue(data, targetItem, targetCurrency, targetSell,
                    deltaTen != null ? -deltaTen : 0));

            inv.setItem(baseSlot + 2, button(Material.RED_STAINED_GLASS_PANE,
                    ChatColor.RED + "-1",
                    ChatColor.GRAY + "Decrease by 1"));
            actions.put(baseSlot + 2, () -> adjustPriceValue(data, targetItem, targetCurrency, targetSell,
                    deltaOne != null ? -deltaOne : 0));

            if (deltaPointOne != null) {
                inv.setItem(baseSlot + 3, button(Material.RED_STAINED_GLASS_PANE,
                        ChatColor.RED + "-0.1",
                        ChatColor.GRAY + "Decrease by 0.1"));
                actions.put(baseSlot + 3, () -> adjustPriceValue(data, targetItem, targetCurrency, targetSell,
                        -deltaPointOne));
            } else {
                inv.setItem(baseSlot + 3, button(Material.GRAY_STAINED_GLASS_PANE,
                        ChatColor.DARK_GRAY + "-0.1",
                        ChatColor.GRAY + "Decimal disabled"));
            }

            String title = sell ? "Sell" : "Buy";
            Integer currentValue = sell ? info.getSellPrice() : info.getBuyPrice();
            String formatted = currentValue != null
                    ? ChatColor.YELLOW + plugin.formatAmountPlain(currentValue)
                    : ChatColor.RED + "--";
            inv.setItem(baseSlot + 4, button(Material.PAPER,
                    ChatColor.AQUA + title + " price",
                    ChatColor.GRAY + "Current: " + formatted,
                    ChatColor.GRAY + "Currency: " + currencyDisplayName(currency)));

            if (deltaPointOne != null) {
                inv.setItem(baseSlot + 5, button(Material.LIME_STAINED_GLASS_PANE,
                        ChatColor.GREEN + "+0.1",
                        ChatColor.GRAY + "Increase by 0.1"));
                actions.put(baseSlot + 5, () -> adjustPriceValue(data, targetItem, targetCurrency, targetSell,
                        deltaPointOne));
            } else {
                inv.setItem(baseSlot + 5, button(Material.GRAY_STAINED_GLASS_PANE,
                        ChatColor.DARK_GRAY + "+0.1",
                        ChatColor.GRAY + "Decimal disabled"));
            }

            inv.setItem(baseSlot + 6, button(Material.LIME_STAINED_GLASS_PANE,
                    ChatColor.GREEN + "+1",
                    ChatColor.GRAY + "Increase by 1"));
            actions.put(baseSlot + 6, () -> adjustPriceValue(data, targetItem, targetCurrency, targetSell,
                    deltaOne != null ? deltaOne : 0));

            inv.setItem(baseSlot + 7, button(Material.LIME_STAINED_GLASS_PANE,
                    ChatColor.GREEN + "+10",
                    ChatColor.GRAY + "Increase by 10"));
            actions.put(baseSlot + 7, () -> adjustPriceValue(data, targetItem, targetCurrency, targetSell,
                    deltaTen != null ? deltaTen : 0));

            inv.setItem(baseSlot + 8, button(Material.LIME_STAINED_GLASS_PANE,
                    ChatColor.GREEN + "+100",
                    ChatColor.GRAY + "Increase by 100"));
            actions.put(baseSlot + 8, () -> adjustPriceValue(data, targetItem, targetCurrency, targetSell,
                    deltaHundred != null ? deltaHundred : 0));
        }

        private ItemStack buildAutopriceInfo(ShopItem.ShopPrice info, String currency) {
            if (!info.hasAutoprice()) {
                return button(Material.GRAY_STAINED_GLASS_PANE,
                        ChatColor.DARK_GRAY + "Autoprice",
                        ChatColor.GRAY + "Not configured for " + currencyDisplayName(currency),
                        ChatColor.YELLOW + "Use Enable Autoprice to configure");
            }
            ShopItem.AutoPriceConfig cfg = info.getAutoprice();
            return button(Material.CLOCK, ChatColor.GOLD + "Autoprice",
                    ChatColor.GRAY + "Currency: " + currencyDisplayName(currency),
                    ChatColor.GRAY + "Lower qty: " + ChatColor.YELLOW + cfg.getLowerThreshold(),
                    ChatColor.GRAY + "Upper qty: " + ChatColor.YELLOW + cfg.getUpperThreshold(),
                    ChatColor.GRAY + "Low stock price: " + ChatColor.YELLOW + plugin.formatAmountPlain(cfg.getHighPrice()),
                    ChatColor.GRAY + "High stock price: " + ChatColor.YELLOW + plugin.formatAmountPlain(cfg.getLowPrice()));
        }

        private ItemStack buildAutopriceEditorInfo(String currency, ShopItem.AutoPriceConfig cfg,
                                                   boolean active, boolean dirty) {
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Currency: " + currencyDisplayName(currency));
            lore.add(ChatColor.GRAY + "Lower qty: " + ChatColor.YELLOW + cfg.getLowerThreshold());
            lore.add(ChatColor.GRAY + "Upper qty: " + ChatColor.YELLOW + cfg.getUpperThreshold());
            lore.add(ChatColor.GRAY + "Low stock price: " + ChatColor.YELLOW + plugin.formatAmountPlain(cfg.getHighPrice()));
            lore.add(ChatColor.GRAY + "High stock price: " + ChatColor.YELLOW + plugin.formatAmountPlain(cfg.getLowPrice()));
            if (!active) {
                lore.add(ChatColor.GOLD + "Draft - not yet applied");
            } else if (dirty) {
                lore.add(ChatColor.GOLD + "Pending changes");
            } else {
                lore.add(ChatColor.GRAY + "No pending changes");
            }
            return button(Material.CLOCK, ChatColor.GOLD + "Autoprice", lore.toArray(new String[0]));
        }

        private void openAutopriceEditor(ShopData data, ShopItem item, ShopItem.ShopPrice info,
                                         String currency, boolean newSetup) {
            priceCurrency = currency;
            priceAutopriceView = true;
            if (newSetup || !info.hasAutoprice()) {
                autopriceWorking = initialAutopriceConfig(item, info);
                autopriceNew = true;
                autopriceDirty = true;
            } else {
                autopriceWorking = copyAutoprice(info.getAutoprice());
                autopriceNew = false;
                autopriceDirty = false;
            }
            renderAutopriceEditor(data);
        }

        private ShopItem.AutoPriceConfig initialAutopriceConfig(ShopItem item, ShopItem.ShopPrice info) {
            int stock = Math.max(0, item.getStock());
            int lower = stock;
            int upper = stock + 10;
            if (upper <= lower) {
                upper = lower + 1;
            }
            int basePrice = 0;
            if (info.getSellPrice() != null) {
                basePrice = Math.max(0, info.getSellPrice());
            } else if (info.getBuyPrice() != null) {
                basePrice = Math.max(0, info.getBuyPrice());
            }
            return new ShopItem.AutoPriceConfig(lower, upper, basePrice, basePrice);
        }

        private void renderAutopriceEditor(ShopData data) {
            Player player = player();
            if (player == null) return;
            if (priceEntry == null) {
                renderPriceManager(data);
                return;
            }
            ShopItem item = priceEntry.item;
            String currency = ensureSelectedCurrency(item);
            ShopItem.ShopPrice info = item.getOrCreatePrice(currency);

            if (autopriceWorking == null) {
                if (info.hasAutoprice() && !autopriceNew) {
                    autopriceWorking = copyAutoprice(info.getAutoprice());
                    autopriceNew = false;
                    autopriceDirty = false;
                } else {
                    autopriceWorking = initialAutopriceConfig(item, info);
                    autopriceNew = true;
                    autopriceDirty = true;
                }
            }

            ShopItem.AutoPriceConfig cfg = autopriceWorking;
            Inventory inv = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 45,
                    ChatColor.GOLD + "Autoprice - " + item.getSaleName());
            actions.clear();
            inv.setItem(0, button(Material.ARROW, ChatColor.YELLOW + "Back"));
            actions.put(0, () -> {
                priceAutopriceView = false;
                clearAutopriceDraft();
                renderPriceEditor(data);
            });

            ItemStack icon = item.getRawItem().clone();
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(fitTitle(ChatColor.YELLOW + item.getSaleName()));
                meta.setLore(wrapLoreLines(List.of(
                        ChatColor.GRAY + "Currency: " + currencyDisplayName(currency),
                        ChatColor.GRAY + "Stock: " + ChatColor.YELLOW + item.getStock())));
                icon.setItemMeta(meta);
            }
            inv.setItem(1, icon);

            boolean active = info.hasAutoprice() && !autopriceNew;
            inv.setItem(4, buildAutopriceEditorInfo(currency, cfg, active, autopriceDirty));

            String saveTitle = autopriceNew ? ChatColor.GREEN + "Enable Autoprice" : ChatColor.GREEN + "Save Changes";
            List<String> saveLore = new ArrayList<>();
            if (autopriceNew) {
                saveLore.add(ChatColor.YELLOW + "Apply this configuration");
            } else if (autopriceDirty) {
                saveLore.add(ChatColor.YELLOW + "Update autoprice settings");
            } else {
                saveLore.add(ChatColor.DARK_GRAY + "No pending changes");
            }
            inv.setItem(6, button(Material.EMERALD_BLOCK, saveTitle, saveLore.toArray(new String[0])));
            actions.put(6, () -> saveAutoprice(data, item, currency, info));

            if (active) {
                inv.setItem(7, button(Material.BARRIER, ChatColor.RED + "Disable Autoprice",
                        ChatColor.GRAY + "Remove configuration"));
                actions.put(7, () -> disableAutoprice(data, item, currency, info));
            } else {
                inv.setItem(7, button(Material.BARRIER, ChatColor.RED + "Cancel Setup",
                        ChatColor.GRAY + "Discard current draft"));
                actions.put(7, () -> {
                    clearAutopriceDraft();
                    priceAutopriceView = false;
                    renderPriceEditor(data);
                });
            }

            populateAutopriceQuantityRow(inv, 9, true, data, item, currency);
            populateAutopricePriceRow(inv, 18, true, data, item, currency);
            populateAutopriceQuantityRow(inv, 27, false, data, item, currency);
            populateAutopricePriceRow(inv, 36, false, data, item, currency);

            this.inventory = inv;
            this.reopenAction = () -> renderAutopriceEditor(data);
            player.openInventory(inv);
        }

        private void populateAutopriceQuantityRow(Inventory inv, int baseSlot, boolean lower, ShopData data,
                                                  ShopItem item, String currency) {
            ShopItem.AutoPriceConfig cfg = autopriceWorking;
            if (cfg == null) {
                return;
            }
            String title = lower ? "Low threshold qty" : "High threshold qty";
            int value = lower ? cfg.getLowerThreshold() : cfg.getUpperThreshold();
            inv.setItem(baseSlot + 4, button(Material.PAPER,
                    ChatColor.AQUA + title,
                    ChatColor.GRAY + "Current: " + ChatColor.YELLOW + value));
            int[] steps = new int[]{1000, 100, 10, 1};
            for (int i = 0; i < steps.length; i++) {
                int step = steps[i];
                int negativeSlot = baseSlot + i;
                inv.setItem(negativeSlot, button(Material.RED_STAINED_GLASS_PANE,
                        ChatColor.RED + "-" + step,
                        ChatColor.GRAY + "Decrease by " + step));
                final int neg = -step;
                actions.put(negativeSlot, () -> changeAutopriceThreshold(data, item, currency, lower, neg));

                int positiveSlot = baseSlot + 8 - i;
                inv.setItem(positiveSlot, button(Material.LIME_STAINED_GLASS_PANE,
                        ChatColor.GREEN + "+" + step,
                        ChatColor.GRAY + "Increase by " + step));
                final int pos = step;
                actions.put(positiveSlot, () -> changeAutopriceThreshold(data, item, currency, lower, pos));
            }
        }

        private void populateAutopricePriceRow(Inventory inv, int baseSlot, boolean highPrice, ShopData data,
                                               ShopItem item, String currency) {
            ShopItem.AutoPriceConfig cfg = autopriceWorking;
            if (cfg == null) {
                return;
            }
            String title = highPrice ? "Low stock price" : "High stock price";
            int value = highPrice ? cfg.getHighPrice() : cfg.getLowPrice();
            inv.setItem(baseSlot + 4, button(Material.PAPER,
                    ChatColor.AQUA + title,
                    ChatColor.GRAY + "Current: " + ChatColor.YELLOW + plugin.formatAmountPlain(value)));
            Integer deltaPointOne = priceDelta("0.1");
            Integer deltaOne = priceDelta("1");
            Integer deltaTen = priceDelta("10");
            Integer deltaHundred = priceDelta("100");
            int[] slots = new int[]{baseSlot, baseSlot + 1, baseSlot + 2, baseSlot + 3};
            int[] deltas = new int[]{deltaHundred != null ? deltaHundred : 0,
                    deltaTen != null ? deltaTen : 0,
                    deltaOne != null ? deltaOne : 0,
                    deltaPointOne != null ? deltaPointOne : 0};
            double[] labels = new double[]{100, 10, 1, 0.1};
            for (int i = 0; i < slots.length; i++) {
                int slot = slots[i];
                double label = labels[i];
                int delta = -deltas[i];
                boolean enabled = deltas[i] != 0 || label >= 1;
                String labelText = formatStep(label);
                if (!enabled && label == 0.1 && deltaPointOne == null) {
                    inv.setItem(slot, button(Material.GRAY_STAINED_GLASS_PANE,
                            ChatColor.DARK_GRAY + "-" + labelText,
                            ChatColor.GRAY + "Decimal disabled"));
                } else {
                    inv.setItem(slot, button(Material.RED_STAINED_GLASS_PANE,
                            ChatColor.RED + "-" + labelText,
                            ChatColor.GRAY + "Decrease by " + labelText));
                    final int change = delta;
                    actions.put(slot, () -> changeAutopricePrice(data, item, currency, highPrice, change));
                }
                int positiveSlot = baseSlot + 8 - (slot - baseSlot);
                if (!enabled && label == 0.1 && deltaPointOne == null) {
                    inv.setItem(positiveSlot, button(Material.GRAY_STAINED_GLASS_PANE,
                            ChatColor.DARK_GRAY + "+" + labelText,
                            ChatColor.GRAY + "Decimal disabled"));
                } else {
                    inv.setItem(positiveSlot, button(Material.LIME_STAINED_GLASS_PANE,
                            ChatColor.GREEN + "+" + labelText,
                            ChatColor.GRAY + "Increase by " + labelText));
                    final int inc = deltas[i];
                    actions.put(positiveSlot, () -> changeAutopricePrice(data, item, currency, highPrice, inc));
                }
            }
        }

        private String formatStep(double value) {
            if (Math.abs(value - Math.rint(value)) < 0.0001D) {
                return String.format("%.0f", value);
            }
            return String.format("%.1f", value);
        }

        private void changeAutopriceThreshold(ShopData data, ShopItem item, String currency, boolean lower, int delta) {
            ShopItem.AutoPriceConfig cfg = autopriceWorking;
            if (cfg == null) {
                renderAutopriceEditor(data);
                return;
            }
            if (lower) {
                int next = Math.max(0, cfg.getLowerThreshold() + delta);
                cfg.setLowerThreshold(next);
                if (cfg.getUpperThreshold() <= next) {
                    cfg.setUpperThreshold(next + 1);
                }
            } else {
                int next = Math.max(0, cfg.getUpperThreshold() + delta);
                if (next <= cfg.getLowerThreshold()) {
                    next = cfg.getLowerThreshold() + 1;
                }
                cfg.setUpperThreshold(next);
            }
            autopriceDirty = true;
            renderAutopriceEditor(data);
        }

        private void changeAutopricePrice(ShopData data, ShopItem item, String currency, boolean highPrice, int delta) {
            ShopItem.AutoPriceConfig cfg = autopriceWorking;
            if (cfg == null) {
                renderAutopriceEditor(data);
                return;
            }
            if (highPrice) {
                int next = Math.max(0, cfg.getHighPrice() + delta);
                cfg.setHighPrice(next);
                if (next < cfg.getLowPrice()) {
                    cfg.setLowPrice(next);
                }
            } else {
                int next = Math.max(0, cfg.getLowPrice() + delta);
                cfg.setLowPrice(next);
                if (next > cfg.getHighPrice()) {
                    cfg.setHighPrice(next);
                }
            }
            autopriceDirty = true;
            renderAutopriceEditor(data);
        }

        private void saveAutoprice(ShopData data, ShopItem item, String currency, ShopItem.ShopPrice info) {
            Player player = player();
            if (player == null) return;
            if (!autopriceNew && !autopriceDirty && info.hasAutoprice()) {
                player.sendMessage(ChatColor.YELLOW + "No changes to save" + ChatColor.RESET);
                return;
            }
            ShopItem.AutoPriceConfig cfg = autopriceWorking;
            if (cfg == null) {
                player.sendMessage(ChatColor.RED + "No draft available" + ChatColor.RESET);
                renderAutopriceEditor(data);
                return;
            }
            if (cfg.getLowerThreshold() < 0 || cfg.getUpperThreshold() < 0) {
                player.sendMessage(ChatColor.RED + "Thresholds must be >= 0 / 0以上を入力してください" + ChatColor.RESET);
                renderAutopriceEditor(data);
                return;
            }
            if (cfg.getUpperThreshold() <= cfg.getLowerThreshold()) {
                player.sendMessage(ChatColor.RED + "Upper must be > lower / 上限は下限より大きくしてください" + ChatColor.RESET);
                renderAutopriceEditor(data);
                return;
            }
            if (cfg.getHighPrice() < 0 || cfg.getLowPrice() < 0) {
                player.sendMessage(ChatColor.RED + "Prices must be >= 0 / 0以上を入力してください" + ChatColor.RESET);
                renderAutopriceEditor(data);
                return;
            }
            if (cfg.getHighPrice() < cfg.getLowPrice()) {
                player.sendMessage(ChatColor.RED + "Low stock price must be >= high stock price / 低在庫時の価格は高在庫時以上にしてください" + ChatColor.RESET);
                renderAutopriceEditor(data);
                return;
            }
            ShopItem.AutoPriceConfig payload = copyAutoprice(cfg);
            submitAutoprice(data, item, currency, info, payload);
        }

        private ShopItem.AutoPriceConfig copyAutoprice(ShopItem.AutoPriceConfig cfg) {
            if (cfg == null) {
                return null;
            }
            return new ShopItem.AutoPriceConfig(
                    cfg.getLowerThreshold(),
                    cfg.getUpperThreshold(),
                    cfg.getHighPrice(),
                    cfg.getLowPrice());
        }

        private void submitAutoprice(ShopData data, ShopItem item, String currency,
                                     ShopItem.ShopPrice info, ShopItem.AutoPriceConfig newConfig) {
            Player player = player();
            if (player == null) return;
            boolean newSetup = !info.hasAutoprice();
            JsonObject payload = new JsonObject();
            payload.addProperty("owner_uuid", player.getUniqueId().toString());
            payload.addProperty("shop_id", data.shopId);
            payload.addProperty("item_key", item.getItemKey());
            if (currency != null && !currency.isBlank()) {
                payload.addProperty("currency", currency);
            }
            payload.addProperty("lower_threshold", Math.max(0, newConfig.getLowerThreshold()));
            payload.addProperty("upper_threshold", Math.max(0, newConfig.getUpperThreshold()));
            payload.addProperty("high_price", Math.max(0, newConfig.getHighPrice()));
            payload.addProperty("low_price", Math.max(0, newConfig.getLowPrice()));
            postJson(player, "/api/shop/autoprice", payload, json -> {
                String status = json.has("status") ? json.get("status").getAsString() : "error";
                if ("success".equalsIgnoreCase(status)) {
                    info.setAutoprice(newConfig);
                    autopriceWorking = copyAutoprice(newConfig);
                    autopriceNew = false;
                    autopriceDirty = false;
                    player.sendMessage(ChatColor.GREEN + (newSetup ? "Autoprice enabled" : "Autoprice updated")
                            + ChatColor.RESET);
                    renderAutopriceEditor(data);
                } else {
                    notifyFailure(player, json.has("reason") ? json.get("reason").getAsString() : "error");
                    renderAutopriceEditor(data);
                }
            }, msg -> {
                player.sendMessage(msg);
                renderAutopriceEditor(data);
            });
        }

        private void disableAutoprice(ShopData data, ShopItem item, String currency, ShopItem.ShopPrice info) {
            Player player = player();
            if (player == null) return;
            JsonObject payload = new JsonObject();
            payload.addProperty("owner_uuid", player.getUniqueId().toString());
            payload.addProperty("shop_id", data.shopId);
            payload.addProperty("item_key", item.getItemKey());
            if (currency != null && !currency.isBlank()) {
                payload.addProperty("currency", currency);
            }
            postJson(player, "/api/shop/autoprice_disable", payload, json -> {
                String status = json.has("status") ? json.get("status").getAsString() : "error";
                if ("success".equalsIgnoreCase(status)) {
                    info.clearAutoprice();
                    clearAutopriceDraft();
                    player.sendMessage(ChatColor.GREEN + "Autoprice disabled" + ChatColor.RESET);
                    priceAutopriceView = false;
                    renderPriceEditor(data);
                } else {
                    notifyFailure(player, json.has("reason") ? json.get("reason").getAsString() : "error");
                    renderAutopriceEditor(data);
                }
            }, msg -> {
                player.sendMessage(msg);
                renderAutopriceEditor(data);
            });
        }
        private ItemStack buildPriceDisplay(ShopEntry entry) {
            ShopItem item = entry.item;
            ItemStack stack = item.getRawItem().clone();
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(fitTitle(ChatColor.YELLOW + item.getSaleName()));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Stock: " + ChatColor.YELLOW + item.getStock());
                Map<String, ShopItem.ShopPrice> prices = item.getPrices();
                if (prices.isEmpty()) {
                    lore.add(ChatColor.RED + "No prices configured");
                } else {
                    List<String> currencies = new ArrayList<>(prices.keySet());
                    currencies.sort(String.CASE_INSENSITIVE_ORDER);
                    for (String currency : currencies) {
                        ShopItem.ShopPrice price = prices.get(currency);
                        if (price == null) {
                            continue;
                        }
                        String displayCurrency = currency == null || currency.isBlank()
                                ? ChatColor.GRAY + "(default)"
                                : ChatColor.WHITE + currency;
                        if (price.getSellPrice() != null) {
                            lore.add(ChatColor.GREEN + "Sell " + displayCurrency + ChatColor.GRAY + ": "
                                    + ChatColor.YELLOW + plugin.formatAmountPlain(price.getSellPrice()));
                        }
                        if (price.getBuyPrice() != null) {
                            lore.add(ChatColor.AQUA + "Buy " + displayCurrency + ChatColor.GRAY + ": "
                                    + ChatColor.YELLOW + plugin.formatAmountPlain(price.getBuyPrice()));
                        }
                        if (price.hasAutoprice()) {
                            lore.add(ChatColor.GOLD + "Autoprice " + displayCurrency + ChatColor.GRAY + ": "
                                    + ChatColor.YELLOW + "ON");
                        }
                    }
                }
                meta.setLore(wrapLoreLines(lore));
                stack.setItemMeta(meta);
            }
            return stack;
        }

        private List<String> currencyList(ShopItem item) {
            Set<String> currencies = new LinkedHashSet<>();
            if (item.getPrices() != null) {
                currencies.addAll(item.getPrices().keySet());
            }
            if (currencies.isEmpty()) {
                currencies.add("");
            }
            return currencies.stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }

        private String ensureSelectedCurrency(ShopItem item) {
            List<String> currencies = currencyList(item);
            if (priceCurrency != null && currencies.contains(priceCurrency)) {
                rememberCurrency(item, priceCurrency);
                return priceCurrency;
            }
            String remembered = currencySelections.get(item.getItemKey());
            if (remembered != null && currencies.contains(remembered)) {
                priceCurrency = remembered;
                return priceCurrency;
            }
            String sell = item.firstSellCurrency();
            if (sell != null && currencies.contains(sell)) {
                priceCurrency = sell;
                rememberCurrency(item, priceCurrency);
                return priceCurrency;
            }
            String buy = item.firstBuyCurrency();
            if (buy != null && currencies.contains(buy)) {
                priceCurrency = buy;
                rememberCurrency(item, priceCurrency);
                return priceCurrency;
            }
            priceCurrency = currencies.isEmpty() ? "" : currencies.get(0);
            rememberCurrency(item, priceCurrency);
            return priceCurrency;
        }

        private String currencyDisplayName(String currency) {
            return currency == null || currency.isBlank()
                    ? ChatColor.GRAY + "(default)"
                    : ChatColor.WHITE + currency;
        }

        private void advanceCurrency(ShopItem item) {
            List<String> currencies = currencyList(item);
            if (currencies.isEmpty()) {
                priceCurrency = "";
                rememberCurrency(item, priceCurrency);
                return;
            }
            int idx = currencies.indexOf(priceCurrency);
            if (idx < 0) {
                idx = 0;
            }
            idx = (idx + 1) % currencies.size();
            priceCurrency = currencies.get(idx);
            rememberCurrency(item, priceCurrency);
        }

        private void promptAddCurrency(ShopData data, ShopItem item) {
            Player player = player();
            if (player == null) return;
            if (!startPrompt()) {
                return;
            }
            beginPrompt(this, player,
                    "Enter currency name / 通貨名を入力してください",
                    input -> {
                        Player p = player();
                        if (p == null) return;
                        String trimmed = input.trim();
                        if (trimmed.isEmpty()) {
                            p.sendMessage(ChatColor.RED + "Currency cannot be empty / 通貨名を入力してください" + ChatColor.RESET);
                            renderPriceEditor(data);
                            return;
                        }
                        item.getOrCreatePrice(trimmed);
                        priceCurrency = trimmed;
                        rememberCurrency(item, priceCurrency);
                        renderPriceEditor(data);
                    },
                    () -> renderPriceEditor(data));
        }

        private void rememberCurrency(ShopItem item, String currency) {
            if (item == null) {
                return;
            }
            String key = item.getItemKey();
            if (currency == null) {
                currencySelections.remove(key);
            } else {
                currencySelections.put(key, currency);
            }
        }

        private void promptSetPrice(ShopData data, ShopItem item, String currency, boolean sell) {
            Player player = player();
            if (player == null) return;
            if (!startPrompt()) {
                return;
            }
            String label = sell ? "sell" : "buy";
            beginPrompt(this, player,
                    "Enter " + label + " price / " + (sell ? "売値" : "買値") + "を入力してください",
                    input -> {
                        Player p = player();
                        if (p == null) return;
                        String trimmed = input.trim();
                        try {
                            int amount = plugin.parseAmount(trimmed);
                            if (amount < 0) {
                                p.sendMessage(ChatColor.RED + "Price must be >= 0 / 0以上を入力してください" + ChatColor.RESET);
                                renderPriceEditor(data);
                                return;
                            }
                            submitPrice(data, item, currency, sell, amount);
                        } catch (NumberFormatException ex) {
                            p.sendMessage(ChatColor.RED + "Invalid number / 無効な数値です" + ChatColor.RESET);
                            renderPriceEditor(data);
                        }
                    },
                    () -> renderPriceEditor(data));
        }

        private Integer priceDelta(String text) {
            try {
                return plugin.parseAmount(text);
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private void adjustPriceValue(ShopData data, ShopItem item, String currency, boolean sell, int delta) {
            ShopItem.ShopPrice info = item.getOrCreatePrice(currency);
            Integer currentValue = sell ? info.getSellPrice() : info.getBuyPrice();
            int current = currentValue != null ? currentValue : 0;
            int next = current + delta;
            if (next < 0) {
                next = 0;
            }
            submitPrice(data, item, currency, sell, next);
        }

        private void submitPrice(ShopData data, ShopItem item, String currency, boolean sell, int amount) {
            Player player = player();
            if (player == null) return;
            JsonObject payload = new JsonObject();
            payload.addProperty("owner_uuid", player.getUniqueId().toString());
            payload.addProperty("shop_id", data.shopId);
            payload.addProperty("item_key", item.getItemKey());
            payload.addProperty("currency", currency == null ? "" : currency);
            payload.addProperty("price", Math.max(0, amount));
            payload.addProperty("price_kind", sell ? "sell" : "buy");
            postJson(player, "/api/shop/set_price", payload, json -> {
                String status = json.has("status") ? json.get("status").getAsString() : "error";
                if ("success".equalsIgnoreCase(status)) {
                    ShopItem.ShopPrice info = item.getOrCreatePrice(currency);
                    if (sell) {
                        info.setSellPrice(amount);
                    } else {
                        info.setBuyPrice(amount);
                    }
                    player.sendMessage(ChatColor.GREEN + "Price updated" + ChatColor.RESET);
                    renderPriceEditor(data);
                } else {
                    notifyFailure(player, json.has("reason") ? json.get("reason").getAsString() : "error");
                    renderPriceEditor(data);
                }
            }, msg -> {
                player.sendMessage(msg);
                renderPriceEditor(data);
            });
        }

        private void openPriceEditor(ShopData data, ShopEntry entry) {
            priceEntry = entry;
            ensureSelectedCurrency(entry.item);
            priceAutopriceView = false;
            renderPriceEditor(data);
        }

        private void renderPriceEditor(ShopData data) {
            Player player = player();
            if (player == null) return;
            if (priceEntry == null) {
                renderPriceManager(data);
                return;
            }
            clearAutopriceDraft();
            ShopItem item = priceEntry.item;
            String currency = ensureSelectedCurrency(item);
            ShopItem.ShopPrice info = item.getOrCreatePrice(currency);
            Inventory inv = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 45,
                    ChatColor.GOLD + "Price - " + item.getSaleName());
            actions.clear();
            inv.setItem(0, button(Material.ARROW, ChatColor.YELLOW + "Back"));
            actions.put(0, () -> {
                priceEntry = null;
                priceCurrency = null;
                renderPriceManager(data);
            });

            ItemStack icon = item.getRawItem().clone();
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(fitTitle(ChatColor.YELLOW + item.getSaleName()));
                meta.setLore(wrapLoreLines(List.of(
                        ChatColor.GRAY + "Stock: " + ChatColor.YELLOW + item.getStock(),
                        ChatColor.GRAY + "Currency: " + currencyDisplayName(currency))));
                icon.setItemMeta(meta);
            }
            inv.setItem(1, icon);

            List<String> currencies = currencyList(item);
            List<String> cycleLore = new ArrayList<>();
            cycleLore.add(ChatColor.GRAY + "Current: " + currencyDisplayName(currency));
            if (currencies.size() > 1) {
                cycleLore.add(ChatColor.YELLOW + "Click to cycle" + ChatColor.RESET);
                actions.put(2, () -> {
                    advanceCurrency(item);
                    renderPriceEditor(data);
                });
            } else {
                cycleLore.add(ChatColor.DARK_GRAY + "No other currencies");
            }
            inv.setItem(2, button(Material.COMPASS, ChatColor.AQUA + "Currency", cycleLore.toArray(new String[0])));

            inv.setItem(3, button(Material.ANVIL, ChatColor.GREEN + "Add Currency",
                    ChatColor.GRAY + "Create a new price entry"));
            actions.put(3, () -> promptAddCurrency(data, item));

            String sellText = info.getSellPrice() != null
                    ? ChatColor.YELLOW + plugin.formatAmountPlain(info.getSellPrice())
                    : ChatColor.RED + "--";
            String buyText = info.getBuyPrice() != null
                    ? ChatColor.YELLOW + plugin.formatAmountPlain(info.getBuyPrice())
                    : ChatColor.RED + "--";
            inv.setItem(4, button(Material.PAPER, ChatColor.YELLOW + "Selected: " + currencyDisplayName(currency),
                    ChatColor.GRAY + "Sell: " + sellText,
                    ChatColor.GRAY + "Buy: " + buyText));

            inv.setItem(5, button(Material.GOLD_NUGGET, ChatColor.GREEN + "Set Sell",
                    ChatColor.GRAY + "Manual entry"));
            actions.put(5, () -> promptSetPrice(data, item, currency, true));

            inv.setItem(6, button(Material.EMERALD, ChatColor.AQUA + "Set Buy",
                    ChatColor.GRAY + "Manual entry"));
            actions.put(6, () -> promptSetPrice(data, item, currency, false));

            if (info.hasAutoprice()) {
                inv.setItem(7, button(Material.CLOCK, ChatColor.GOLD + "Autoprice",
                        ChatColor.GRAY + "Click to edit thresholds"));
                actions.put(7, () -> openAutopriceEditor(data, item, info, currency, false));
            } else {
                inv.setItem(7, button(Material.CLOCK, ChatColor.GREEN + "Enable Autoprice",
                        ChatColor.GRAY + "Create automatic pricing"));
                actions.put(7, () -> openAutopriceEditor(data, item, info, currency, true));
            }

            populatePriceRow(inv, 9, true, data, item, info, currency);
            populatePriceRow(inv, 18, false, data, item, info, currency);

            inv.setItem(36, buildAutopriceInfo(info, currency));

            this.inventory = inv;
            this.reopenAction = () -> renderPriceEditor(data);
            this.priceAutopriceView = false;
            player.openInventory(inv);
        }

        private void openRemoveConfirm(ShopData data) {
            Player player = player();
            if (player == null) return;
            Inventory inv = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 27,
                    ChatColor.DARK_RED + "Remove " + data.shopId);
            actions.clear();
            inv.setItem(11, button(Material.TNT, ChatColor.RED + "Remove (no refund)",
                    ChatColor.GRAY + "Delete without returning stock"));
            actions.put(11, () -> removeShop(data, false));
            inv.setItem(15, button(Material.CHEST, ChatColor.GOLD + "Remove & refund",
                    ChatColor.GRAY + "Return remaining stock"));
            actions.put(15, () -> removeShop(data, true));
            inv.setItem(18, button(Material.ARROW, ChatColor.YELLOW + "Back"));
            actions.put(18, () -> showShopDetails(data));
            this.inventory = inv;
            this.reopenAction = () -> openRemoveConfirm(data);
            player.openInventory(inv);
        }

        private void removeShop(ShopData data, boolean refund) {
            Player player = player();
            if (player == null) return;
            JsonObject payload = new JsonObject();
            payload.addProperty("owner_uuid", player.getUniqueId().toString());
            payload.addProperty("shop_id", data.shopId);
            payload.addProperty("refund", refund);
            postJson(player, "/api/shop/remove", payload, json -> {
                String status = json.has("status") ? json.get("status").getAsString() : "error";
                if ("success".equalsIgnoreCase(status)) {
                    if (refund && json.has("grant") && json.get("grant").isJsonArray()) {
                        json.getAsJsonArray("grant").forEach(element -> {
                            JsonObject grant = element.getAsJsonObject();
                            String token = grant.get("grant_token").getAsString();
                            if (plugin.consumeGrantToken(token)) {
                                ItemStack item = itemFromBase64(grant.get("nbt_blob").getAsString());
                                item.setAmount(grant.get("qty").getAsInt());
                                player.getInventory().addItem(item);
                            }
                        });
                    }
                    player.sendMessage(ChatColor.GREEN + "Shop removed / ショップを撤去しました" + ChatColor.RESET);
                    renderShopList();
                } else {
                    notifyFailure(player, json.has("reason") ? json.get("reason").getAsString() : "error");
                    showShopDetails(data);
                }
            }, msg -> {
                player.sendMessage(msg);
                showShopDetails(data);
            });
        }

        private void requestReopen(ShopData data) {
            Player player = player();
            if (player == null) return;
            JsonObject payload = new JsonObject();
            payload.addProperty("owner_uuid", player.getUniqueId().toString());
            payload.addProperty("shop_id", data.shopId);
            payload.addProperty("timestamp", TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));
            postJson(player, "/api/shop/reopen", payload, json -> {
                String status = json.has("status") ? json.get("status").getAsString() : "error";
                if ("success".equalsIgnoreCase(status)) {
                    player.sendMessage(ChatColor.GREEN + "Reopen requested / 再開申請を送信しました" + ChatColor.RESET);
                    openShop(data.shopId);
                } else {
                    notifyFailure(player, json.has("reason") ? json.get("reason").getAsString() : "error");
                    showInactiveShop(data);
                }
            }, msg -> {
                player.sendMessage(msg);
                showInactiveShop(data);
            });
        }

        private void notifyFailure(Player player, String reason) {
            String message;
            switch (reason) {
                case "not_owner" -> message = "No permission / 権限がありません";
                case "invalid_mode" -> message = "Invalid mode / 種別が不正です";
                case "invalid_sort_mode" -> message = "Invalid sort / 並び順が不正です";
                case "invalid_account" -> message = "Account not found / 口座が存在しません";
                case "no_access" -> message = "Account access missing / 利用権がありません";
                case "shop_not_buying" -> message = "Shop is not buying / 買取を行っていません";
                case "shop_not_selling" -> message = "Shop is not selling / 販売を行っていません";
                default -> message = "Failed / 失敗しました";
            }
            player.sendMessage(ChatColor.RED + message + ChatColor.RESET);
        }

        private void openShopList() {
            Player player = player();
            if (player == null) return;
            Inventory loading = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 27,
                    ChatColor.GOLD + "Loading shops...");
            loading.setItem(13, button(Material.CLOCK, ChatColor.YELLOW + "Loading..."));
            player.openInventory(loading);
            this.inventory = loading;
            actions.clear();
            requestOwnedShops(player, ids -> Bukkit.getScheduler().runTask(plugin, () -> {
                ids.sort(String.CASE_INSENSITIVE_ORDER);
                shopIds = ids;
                page = 0;
                renderShopList();
            }), message -> Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(message);
                showMainMenu();
            }));
        }

        private void renderShopList() {
            Player player = player();
            if (player == null) return;
            Inventory listInv = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 54,
                    ChatColor.GOLD + "Your Shops");
            actions.clear();
            listInv.setItem(45, button(Material.ARROW, ChatColor.YELLOW + "Back"));
            actions.put(45, this::showMainMenu);
            listInv.setItem(53, button(Material.CLOCK, ChatColor.GREEN + "Refresh"));
            actions.put(53, this::openShopList);
            int maxPage = shopIds.isEmpty() ? 0 : (shopIds.size() - 1) / PAGE_SIZE;
            if (page > maxPage) {
                page = maxPage;
            }
            if (page < 0) {
                page = 0;
            }
            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, shopIds.size());
            int index = 0;
            if (shopIds.isEmpty()) {
                listInv.setItem(22, button(Material.BARRIER, ChatColor.RED + "No shops",
                        ChatColor.GRAY + "Create a shop with /le shop create"));
            } else {
                for (int i = start; i < end; i++) {
                    String id = shopIds.get(i);
                    int row = index / 9;
                    int col = index % 9;
                    int displaySlot = row * 9 + col;
                    listInv.setItem(displaySlot, button(Material.CHEST, ChatColor.GOLD + id,
                            ChatColor.GRAY + "Click to open"));
                    final String shopId = id;
                    actions.put(displaySlot, () -> openShop(shopId));
                    index++;
                }
            }
            if (start > 0) {
                listInv.setItem(48, button(Material.ARROW, ChatColor.YELLOW + "Previous"));
                actions.put(48, () -> {
                    page = Math.max(0, page - 1);
                    renderShopList();
                });
            } else {
                listInv.setItem(48, button(Material.GRAY_STAINED_GLASS_PANE,
                        ChatColor.DARK_GRAY + "Previous"));
            }
            if (end < shopIds.size()) {
                listInv.setItem(50, button(Material.ARROW, ChatColor.YELLOW + "Next"));
                actions.put(50, () -> {
                    page = page + 1;
                    renderShopList();
                });
            } else {
                listInv.setItem(50, button(Material.GRAY_STAINED_GLASS_PANE,
                        ChatColor.DARK_GRAY + "Next"));
            }
            listInv.setItem(49, button(Material.NAME_TAG,
                    ChatColor.AQUA + "Page " + (page + 1),
                    ChatColor.GRAY + "Shops " + (shopIds.isEmpty() ? 0 : start + 1) + "-" + end,
                    ChatColor.GRAY + "Total: " + shopIds.size()));
            this.inventory = listInv;
            this.reopenAction = this::renderShopList;
            this.currentShop = null;
            player.openInventory(listInv);
        }

        private void openShop(String shopId) {
            if (closed) {
                return;
            }
            Player player = player();
            if (player == null) return;
            Inventory loading = Bukkit.createInventory(new ShopGuiSessionHolder(playerId), 27,
                    ChatColor.GOLD + "Loading " + shopId + "...");
            loading.setItem(13, button(Material.CLOCK, ChatColor.YELLOW + "Loading..."));
            this.inventory = loading;
            this.reopenAction = () -> openShop(shopId);
            player.openInventory(loading);
            requestShop(player, shopId, this::handleShopLoaded,
                    message -> Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage(message);
                        renderShopList();
                    }));
        }

        private void clear() {
            actions.clear();
            inventory = null;
            reopenAction = null;
            currentShop = null;
            closed = true;
            awaitingPrompt = false;
            priceEntry = null;
            priceCurrency = null;
            priceAutopriceView = false;
            currencySelections.clear();
        }
    }
}
