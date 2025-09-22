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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
        private final Consumer<String> handler;
        private final Runnable cancelAction;
        private final BukkitTask timeout;

        private ChatPrompt(Consumer<String> handler, Runnable cancelAction, BukkitTask timeout) {
            this.handler = handler;
            this.cancelAction = cancelAction;
            this.timeout = timeout;
        }
    }

    private static final class ShopData {
        private final String shopId;
        private String status = "active";
        private String tradeMode = "both";
        private boolean listed = true;
        private String accountUuid;
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

    public void close(Player player) {
        ShopGuiSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.clear();
        }
        cancelPrompt(player.getUniqueId(), true);
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
                if (prompt.cancelAction != null) {
                    prompt.cancelAction.run();
                }
                player.sendMessage(ChatColor.RED + "Cancelled / キャンセルしました" + ChatColor.RESET);
            });
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> prompt.handler.accept(trimmed));
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

    private void cancelPrompt(UUID playerId, boolean runCancel) {
        ChatPrompt prompt = prompts.remove(playerId);
        if (prompt != null) {
            if (prompt.timeout != null) {
                prompt.timeout.cancel();
            }
            if (runCancel && prompt.cancelAction != null) {
                Bukkit.getScheduler().runTask(plugin, prompt.cancelAction);
            }
        }
    }

    private void beginPrompt(Player player, String instruction, Consumer<String> handler, Runnable cancelAction) {
        UUID playerId = player.getUniqueId();
        cancelPrompt(playerId, false);
        player.closeInventory();
        player.sendMessage(ChatColor.YELLOW + instruction + ChatColor.GRAY + " (type cancel to abort / キャンセルはcancel)" + ChatColor.RESET);
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ChatPrompt prompt = prompts.remove(playerId);
            if (prompt != null) {
                if (prompt.cancelAction != null) {
                    prompt.cancelAction.run();
                }
                Player target = Bukkit.getPlayer(playerId);
                if (target != null) {
                    target.sendMessage(ChatColor.RED + "Timed out / タイムアウトしました" + ChatColor.RESET);
                }
            }
        }, PROMPT_TIMEOUT_TICKS);
        prompts.put(playerId, new ChatPrompt(handler, cancelAction, timeout));
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
        String activeColors = "";
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ChatColor.COLOR_CHAR && i + 1 < line.length()) {
                current.append(c).append(line.charAt(i + 1));
                i++;
                activeColors = ChatColor.getLastColors(current.toString());
                continue;
            }
            double glyph = glyphWidth(c);
            if (width + glyph > LORE_WIDTH_LIMIT && current.length() > 0) {
                result.add(current.toString());
                current = new StringBuilder(activeColors);
                width = 0;
            }
            current.append(c);
            width += glyph;
        }
        if (current.length() > 0) {
            result.add(current.toString());
        } else if (result.isEmpty()) {
            result.add("");
        }
        return result;
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

    private void requestOwnedShops(Player player, Consumer<List<String>> success, Consumer<String> error) {
        OkHttpClient client = plugin.getHttpClient();
        if (client == null || !plugin.isActive()) {
            error.accept(ChatColor.RED + "Backend unavailable" + ChatColor.RESET);
            return;
        }
        HttpUrl url = HttpUrl.parse(plugin.getBaseUrl() + "/api/shop/ids").newBuilder()
                .addQueryParameter("owner_uuid", player.getUniqueId().toString())
                .build();
        Request request = new Request.Builder().url(url).build();
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
        HttpUrl url = HttpUrl.parse(plugin.getBaseUrl() + "/api/shop/items").newBuilder()
                .addQueryParameter("shop_id", shopId)
                .build();
        Request request = new Request.Builder().url(url).build();
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
                                    if (sellPrice != null) {
                                        lore.add(ChatColor.GREEN + "Sell: " + ChatColor.YELLOW + plugin.formatAmountPlain(sellPrice)
                                                + ChatColor.WHITE + " " + currencyText);
                                    }
                                    if (buyPrice != null) {
                                        lore.add(ChatColor.AQUA + "Buy: " + ChatColor.YELLOW + plugin.formatAmountPlain(buyPrice)
                                                + ChatColor.WHITE + " " + currencyText);
                                    }
                                    priceMap.put(currency, new ShopItem.ShopPrice(sellPrice, buyPrice));
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
        Request request = new Request.Builder()
                .url(plugin.getBaseUrl() + path)
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
        HttpUrl url = HttpUrl.parse(plugin.getBaseUrl() + "/api/shop/items").newBuilder()
                .addQueryParameter("shop_id", shopId)
                .build();
        Request request = new Request.Builder().url(url).build();
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
        private List<String> shopIds = new ArrayList<>();
        private int page = 0;
        private ShopData currentShop;
        private Runnable reopenAction;

        private ShopGuiSession(Player player) {
            this.playerId = player.getUniqueId();
        }

        private Player player() {
            return Bukkit.getPlayer(playerId);
        }

        private void showMainMenu() {
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
            menu.setItem(15, button(Material.BOOK, ChatColor.GREEN + "Help",
                    ChatColor.GRAY + "Click to view shop commands"));
            actions.put(15, () -> {
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
            Runnable action = actions.get(slot);
            if (action != null) {
                action.run();
            }
        }

        private void handleShopLoaded(ShopData data) {
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
            inv.setItem(12, button(data.listed ? Material.OAK_SIGN : Material.INK_SAC,
                    data.listed ? ChatColor.GREEN + "Listed" : ChatColor.RED + "Hidden",
                    ChatColor.GRAY + (data.listed ? "Click to hide from catalog" : "Click to publish")));
            actions.put(12, () -> toggleListing(data));
            inv.setItem(14, button(Material.LEVER, ChatColor.AQUA + "Mode: " + data.tradeMode.toUpperCase(),
                    ChatColor.GRAY + "Toggle buy/sell permissions"));
            actions.put(14, () -> cycleTradeMode(data));
            inv.setItem(16, button(Material.PLAYER_HEAD, ChatColor.AQUA + "Partners",
                    ChatColor.GRAY + "Manage co-owners"));
            actions.put(16, () -> openPartnerManager(data));
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
            lore.add(ChatColor.GRAY + "Listed: " + (data.listed ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
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
            openShopInventory(player, data);
        }

        private void promptCreateShop() {
            Player player = player();
            if (player == null) return;
            beginPrompt(player,
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
                    this::showMainMenu);
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
            beginPrompt(player,
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
            beginPrompt(player,
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
                    () -> openPartnerManager(data));
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
        }
    }
}
