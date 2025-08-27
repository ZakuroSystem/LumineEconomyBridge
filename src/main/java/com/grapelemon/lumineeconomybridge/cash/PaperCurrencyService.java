package com.grapelemon.lumineeconomybridge.cash;

import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.google.gson.Gson;
import okhttp3.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.Location;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class PaperCurrencyService {
    private final LumineEconomyBridge plugin;
    private final NamespacedKey currencyKey;
    private final NamespacedKey amountKey;
    private final OkHttpClient http;
    private final String baseUrl;
    private final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final BlockingQueue<RetryableCashEvent> eventQueue;
    private static final int MAX_RETRIES = 5;
    private static final long BASE_RETRY_DELAY_TICKS = 20L; // 1 second

    public PaperCurrencyService(LumineEconomyBridge plugin, OkHttpClient http, String baseUrl) {
        this.plugin = plugin;
        this.http = http;
        this.baseUrl = baseUrl;
        this.currencyKey = new NamespacedKey(plugin, "note_currency");
        this.amountKey = new NamespacedKey(plugin, "note_amount");
        int capacity = plugin.getConfig().getInt("cash.queue_capacity", 1000);
        this.eventQueue = new LinkedBlockingQueue<>(capacity);
        long period = 20L * 5L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushEvents, period, period);
    }

    public ItemStack issue(Player p, String currency, int amount) {
        ItemStack note = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = note.getItemMeta();
        meta.setDisplayName(amount + " " + currency + " note");
        PersistentDataContainer c = meta.getPersistentDataContainer();
        c.set(currencyKey, PersistentDataType.STRING, currency);
        c.set(amountKey, PersistentDataType.INTEGER, amount);
        note.setItemMeta(meta);
        sendEvent(p.getUniqueId().toString(), "issue", note, p.getLocation());
        return note;
    }

    public boolean isNote(ItemStack stack) {
        if (stack == null) return false;
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(currencyKey, PersistentDataType.STRING);
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

    public void sendEvent(String player, String action, ItemStack stack, Location loc) {
        CashEventPayload payload = new CashEventPayload(
                player,
                action,
                getCurrency(stack),
                getAmount(stack) * stack.getAmount(),
                locString(loc)
        );
        if (!eventQueue.offer(new RetryableCashEvent(payload, 0))) {
            plugin.getLogger().warning("cash event dropped: queue full");
        }
    }

    public void flushEvents() {
        RetryableCashEvent evt;
        while ((evt = eventQueue.poll()) != null) {
            sendPayload(evt);
        }
    }

    private void sendPayload(RetryableCashEvent event) {
        CashEventPayload payload = event.payload();
        Request req = new Request.Builder()
                .url(baseUrl + "/api/cash/event")
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        try (Response response = http.newCall(req).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("unexpected code " + response.code());
            }
        } catch (IOException e) {
            int nextAttempt = event.attempt() + 1;
            if (nextAttempt > MAX_RETRIES) {
                plugin.getLogger().warning("cash event permanently failed after " + event.attempt() + " retries: " + e.getMessage());
                return;
            }
            long delay = (1L << (nextAttempt - 1)) * BASE_RETRY_DELAY_TICKS;
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                if (!eventQueue.offer(new RetryableCashEvent(payload, nextAttempt))) {
                    plugin.getLogger().warning("cash event dropped after retry: queue full");
                }
            }, delay);
            plugin.getLogger().warning("cash event failed: " + e.getMessage() + ", retrying in " + (delay / 20) + "s (attempt " + nextAttempt + ")");
        }
    }

    private String locString(Location l) {
        return l.getWorld().getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    public void flushAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Integer> totals = new HashMap<>();
            for (ItemStack stack : p.getInventory().getContents()) {
                if (isNote(stack)) {
                    String cur = getCurrency(stack);
                    int value = getAmount(stack) * stack.getAmount();
                    totals.merge(cur, value, Integer::sum);
                }
            }
            List<CashBalancePayload> notes = new ArrayList<>();
            for (Map.Entry<String, Integer> e : totals.entrySet()) {
                notes.add(new CashBalancePayload(e.getKey(), e.getValue()));
            }
            sendRewrite(p.getUniqueId().toString(), notes);
        }
    }

    private void sendRewrite(String player, List<CashBalancePayload> notes) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("player_uuid", player);
        payload.put("notes", notes);
        Request req = new Request.Builder()
                .url(baseUrl + "/api/cash/rewrite")
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { plugin.getLogger().warning("cash rewrite failed: " + e.getMessage()); }
            @Override public void onResponse(Call call, Response response) { response.close(); }
        });
    }

    private record RetryableCashEvent(CashEventPayload payload, int attempt) {}

    public record CashEventPayload(String player_uuid, String action, String currency, int amount, String location) {}

    public record CashBalancePayload(String currency, int amount) {}
}
