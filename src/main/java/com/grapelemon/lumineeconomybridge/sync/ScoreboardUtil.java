package com.grapelemon.lumineeconomybridge.sync;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import net.kyori.adventure.text.Component;

public final class ScoreboardUtil {
    private ScoreboardUtil() {}

    private static Objective getOrCreateObjective(Scoreboard board, String name, String display) {
        Objective obj = board.getObjective(name);
        if (obj == null) {
            obj = board.registerNewObjective(name, "dummy", Component.text(display));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        return obj;
    }

    public static int readCurrency(Scoreboard board, String objective, String entry) {
        Objective obj = board.getObjective(objective);
        if (obj == null) return 0;
        return obj.getScore(entry).getScore();
    }

    public static void writeCurrency(Scoreboard board, String objective, String display, String entry, int value) {
        Objective obj = getOrCreateObjective(board, objective, display);
        obj.getScore(entry).setScore(value);
    }

    public static int[] readBothSync(Player p) {
        Scoreboard sb = p.getScoreboard() != null ? p.getScoreboard() : Bukkit.getScoreboardManager().getMainScoreboard();
        String entry = p.getName();
        int c1 = readCurrency(sb, "currency1", entry);
        int c2 = readCurrency(sb, "currency2", entry);
        return new int[]{c1, c2};
    }

    public static void applyAbsoluteSync(Player p, Integer c1, Integer c2) {
        Scoreboard sb = p.getScoreboard() != null ? p.getScoreboard() : Bukkit.getScoreboardManager().getMainScoreboard();
        String entry = p.getName();
        if (c1 != null) writeCurrency(sb, "currency1", "Currency 1", entry, c1);
        if (c2 != null) writeCurrency(sb, "currency2", "Currency 2", entry, c2);
    }
}
