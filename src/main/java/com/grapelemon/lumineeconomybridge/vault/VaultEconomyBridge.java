package com.grapelemon.lumineeconomybridge.vault;

import com.grapelemon.lumineeconomybridge.LumineEconomyBridge;
import com.grapelemon.lumineeconomybridge.sync.ScoreboardUtil;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class VaultEconomyBridge extends AbstractEconomy {
    private final LumineEconomyBridge plugin;
    private final String currencyObj;

    public VaultEconomyBridge(LumineEconomyBridge plugin) {
        this.plugin = plugin;
        String cur = plugin.getConfig().getString("vault.currency", "thy");
        this.currencyObj = cur.startsWith("currency") ? cur : "currency_" + cur;
    }

    private <T> T callSync(Callable<T> task) {
        try {
            if (Bukkit.isPrimaryThread()) {
                return task.call();
            }
            Future<T> f = Bukkit.getScheduler().callSyncMethod(plugin, task);
            return f.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Scoreboard board(Player p) {
        Scoreboard sb = p.getScoreboard();
        return sb != null ? sb : Bukkit.getScoreboardManager().getMainScoreboard();
    }

    private double balance(Player p) {
        return callSync(() -> (double) ScoreboardUtil.readCurrency(board(p), currencyObj, p.getName()));
    }

    private void addCash(Player p, int amount) {
        callSync(() -> {
            Scoreboard sb = board(p);
            String cash = currencyObj + "_cash";
            int cur = ScoreboardUtil.readCurrency(sb, cash, p.getName());
            ScoreboardUtil.writeCurrency(sb, cash, cash, p.getName(), cur + amount);
            return null;
        });
        plugin.getSyncService().flushAll();
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled() && plugin.isActive();
    }

    @Override
    public String getName() {
        return "LumineEconomyBridge";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 0;
    }

    @Override
    public String currencyNamePlural() {
        return plugin.getConfig().getString("vault.currency", "thy");
    }

    @Override
    public String currencyNameSingular() {
        return currencyNamePlural();
    }

    @Override
    public String format(double amount) {
        return String.valueOf((int) Math.round(amount));
    }

    @Override
    public boolean hasAccount(String playerName) {
        return Bukkit.getPlayerExact(playerName) != null;
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public double getBalance(String playerName) {
        Player p = Bukkit.getPlayerExact(playerName);
        if (p == null) return 0;
        return balance(p);
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        Player p = Bukkit.getPlayerExact(playerName);
        if (p == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not online");
        }
        if (amount < 0) {
            return new EconomyResponse(0, balance(p), EconomyResponse.ResponseType.FAILURE, "Cannot deposit negative amounts");
        }
        addCash(p, (int) Math.round(amount));
        return new EconomyResponse(amount, balance(p), EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        Player p = Bukkit.getPlayerExact(playerName);
        if (p == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not online");
        }
        if (amount < 0) {
            return new EconomyResponse(0, balance(p), EconomyResponse.ResponseType.FAILURE, "Cannot withdraw negative amounts");
        }
        double bal = balance(p);
        if (bal < amount) {
            return new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        }
        addCash(p, -(int) Math.round(amount));
        return new EconomyResponse(amount, balance(p), EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return true;
    }

    // Bank-related operations are not supported
    private static EconomyResponse notImplemented() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support not available");
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return notImplemented();
    }

    @Override
    public List<String> getBanks() {
        return Collections.emptyList();
    }
}
