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

import java.io.IOException;
import java.util.UUID;

public class PaperCurrencyService {
    private final LumineEconomyBridge plugin;
    private final NamespacedKey noteKey;
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
        this.noteKey = new NamespacedKey(plugin, "note_id");
        this.currencyKey = new NamespacedKey(plugin, "note_currency");
        this.amountKey = new NamespacedKey(plugin, "note_amount");
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

    public void sendEvent(String id, String player, String action, String currency, int amount, String loc) {
        CashEventPayload payload = new CashEventPayload(id, player, action, currency, amount, loc);
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

    public record CashEventPayload(String note_id, String player_uuid, String action, String currency, int amount, String location) {}
}
