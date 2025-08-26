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

public class PaperCurrencyService {
    private final LumineEconomyBridge plugin;
    private final NamespacedKey currencyKey;
    private final NamespacedKey amountKey;
    private final OkHttpClient http;
    private final String baseUrl;
    private final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public PaperCurrencyService(LumineEconomyBridge plugin, OkHttpClient http, String baseUrl) {
        this.plugin = plugin;
        this.http = http;
        this.baseUrl = baseUrl;
        this.currencyKey = new NamespacedKey(plugin, "note_currency");
        this.amountKey = new NamespacedKey(plugin, "note_amount");
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
                getAmount(stack),
                stack.getAmount(),
                locString(loc)
        );
        Request req = new Request.Builder()
                .url(baseUrl + "/api/cash/event")
                .addHeader("X-LE-Token", plugin.getConfig().getString("api.token", ""))
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                plugin.getLogger().warning("cash event failed: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) { response.close(); }
        });
    }

    private String locString(Location l) {
        return l.getWorld().getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    public void flushAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Map<Integer, Integer>> map = new HashMap<>();
            for (ItemStack stack : p.getInventory().getContents()) {
                if (isNote(stack)) {
                    String cur = getCurrency(stack);
                    int amt = getAmount(stack);
                    map.computeIfAbsent(cur, k -> new HashMap<>()).merge(amt, stack.getAmount(), Integer::sum);
                }
            }
            List<NotePayload> notes = new ArrayList<>();
            for (Map.Entry<String, Map<Integer, Integer>> ce : map.entrySet()) {
                for (Map.Entry<Integer, Integer> ae : ce.getValue().entrySet()) {
                    notes.add(new NotePayload(ce.getKey(), ae.getKey(), ae.getValue()));
                }
            }
            sendRewrite(p.getUniqueId().toString(), notes);
        }
    }

    private void sendRewrite(String player, List<NotePayload> notes) {
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

    public record CashEventPayload(String player_uuid, String action, String currency, int amount, int quantity, String location) {}

    public record NotePayload(String currency, int amount, int quantity) {}
}
