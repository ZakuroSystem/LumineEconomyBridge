package com.grapelemon.lumineeconomybridge.cash;

import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.google.gson.Gson;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.Location;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Arrays;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PaperCurrencyService {
    private final LumineEconomyBridge plugin;
    private final NamespacedKey noteKey;
    private final NamespacedKey currencyKey;
    private final NamespacedKey amountKey;
    private final OkHttpClient http;
    private final String baseUrl;
    private final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final Queue<CashEventPayload> queue = new ConcurrentLinkedQueue<>();
    private final File queueFile;
    private volatile boolean sending = false;

    public PaperCurrencyService(LumineEconomyBridge plugin, OkHttpClient http, String baseUrl) {
        this.plugin = plugin;
        this.http = http;
        this.baseUrl = baseUrl;
        this.noteKey = new NamespacedKey(plugin, "note_id");
        this.currencyKey = new NamespacedKey(plugin, "note_currency");
        this.amountKey = new NamespacedKey(plugin, "note_amount");
        this.queueFile = new File(plugin.getDataFolder(), "cash_events.json");
        loadQueue();
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::processQueue, 20L, 20L);
        processQueue();
    }

    public ItemStack issue(Player p, String currency, int amount) {
        ItemStack note = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = note.getItemMeta();
        String id = UUID.randomUUID().toString();
        meta.setDisplayName(amount + " " + currency + " note");
        PersistentDataContainer c = meta.getPersistentDataContainer();
        c.set(noteKey, PersistentDataType.STRING, id);
        c.set(currencyKey, PersistentDataType.STRING, currency);
        c.set(amountKey, PersistentDataType.INTEGER, amount);
        note.setItemMeta(meta);
        sendEvent(id, p.getUniqueId().toString(), "issue", currency, amount, locString(p.getLocation()));
        return note;
    }

    public boolean isNote(ItemStack stack) {
        if (stack == null) return false;
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(noteKey, PersistentDataType.STRING);
    }

    public String getId(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        return meta.getPersistentDataContainer().get(noteKey, PersistentDataType.STRING);
    }

    public String getCurrency(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        return meta.getPersistentDataContainer().get(currencyKey, PersistentDataType.STRING);
    }

    public int getAmount(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        Integer v = meta.getPersistentDataContainer().get(amountKey, PersistentDataType.INTEGER);
        return v != null ? v : 0;
    }

    public void trackItem(ItemStack stack, String player, String action, Location loc) {
        if (stack == null) return;
        if (isNote(stack)) {
            sendEvent(getId(stack), player, action, getCurrency(stack), getAmount(stack), locString(loc));
        } else if (stack.getType().name().endsWith("SHULKER_BOX")) {
            ItemMeta meta = stack.getItemMeta();
            if (meta instanceof BlockStateMeta bsm) {
                BlockState state = bsm.getBlockState();
                if (state instanceof ShulkerBox box) {
                    for (ItemStack s : box.getInventory().getContents()) {
                        trackItem(s, player, action, loc);
                    }
                }
            }
        }
    }

    private void scanInventory(Inventory inv, String player, Location loc, String action) {
        for (ItemStack s : inv.getContents()) {
            trackItem(s, player, action, loc);
        }
    }

    public void scanAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            scanInventory(p.getInventory(), p.getUniqueId().toString(), p.getLocation(), "pickup");
        }
        for (World w : Bukkit.getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) {
                for (BlockState st : c.getTileEntities()) {
                    if (st instanceof Chest chest) {
                        scanInventory(chest.getBlockInventory(), "", chest.getLocation(), "store");
                    } else if (st instanceof InventoryHolder holder) {
                        scanInventory(holder.getInventory(), "", st.getLocation(), "store");
                    }
                }
            }
        }
    }

    public void sendEvent(String id, String player, String action, String currency, int amount, String loc) {
        queue.offer(new CashEventPayload(id, player, action, currency, amount, loc));
        saveQueue();
        processQueue();
    }

    private String locString(Location l) {
        return l.getWorld().getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    private void processQueue() {
        if (sending) return;
        CashEventPayload payload = queue.peek();
        if (payload == null) return;
        sending = true;
        Request req = new Request.Builder()
                .url(baseUrl + "/api/cash/event")
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                plugin.getLogger().warning("cash event failed: " + e.getMessage());
                sending = false;
            }
            @Override public void onResponse(Call call, Response response) {
                try (response) {
                    if (response.isSuccessful()) {
                        queue.poll();
                        saveQueue();
                        sending = false;
                        processQueue();
                    } else {
                        plugin.getLogger().warning("cash event HTTP " + response.code());
                        sending = false;
                    }
                }
            }
        });
    }

    private synchronized void saveQueue() {
        try {
            plugin.getDataFolder().mkdirs();
            if (queue.isEmpty()) {
                if (queueFile.exists()) queueFile.delete();
                return;
            }
            try (Writer w = new FileWriter(queueFile)) {
                gson.toJson(queue.toArray(new CashEventPayload[0]), w);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("failed to persist cash queue: " + e.getMessage());
        }
    }

    private void loadQueue() {
        if (!queueFile.exists()) return;
        try (Reader r = new FileReader(queueFile)) {
            CashEventPayload[] arr = gson.fromJson(r, CashEventPayload[].class);
            if (arr != null) queue.addAll(Arrays.asList(arr));
        } catch (IOException e) {
            plugin.getLogger().warning("failed to load cash queue: " + e.getMessage());
        }
    }

    public record CashEventPayload(String note_id, String player_uuid, String action, String currency, int amount, String location) {}
}
