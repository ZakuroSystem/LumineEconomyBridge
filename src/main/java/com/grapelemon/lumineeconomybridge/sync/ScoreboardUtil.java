package com.grapelemon.lumineeconomybridge.sync;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import net.kyori.adventure.text.Component;

import java.util.HashMap;
import java.util.Map;

public final class ScoreboardUtil {
    private ScoreboardUtil() {}

    private static Objective getOrCreateObjective(Scoreboard board, String name, String display) {
        Objective obj = board.getObjective(name);
        if (obj == null) {
            obj = board.registerNewObjective(name, "dummy", Component.text(display));
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

    /**
     * Read all currency objectives for the given player. Objectives whose
     * names start with "currency" are treated as currency objectives.
     */
    public static Map<String, Integer> readAllSync(Player p) {
        Scoreboard sb = p.getScoreboard() != null ? p.getScoreboard() : Bukkit.getScoreboardManager().getMainScoreboard();
        String entry = p.getName();
        Map<String, Integer> result = new HashMap<>();
        for (Objective obj : sb.getObjectives()) {
            String name = obj.getName();
            if (name.startsWith("currency") && !name.endsWith("_cash")) {
                String cur = name.startsWith("currency_") ? name.substring(9) : name;
                result.put(cur, obj.getScore(entry).getScore());
            }
        }
        return result;
    }

    /**
     * Apply absolute scoreboard values for all currencies.
     */
    public static void applyAbsoluteSync(Player p, Map<String, Integer> values) {
        Scoreboard sb = p.getScoreboard() != null ? p.getScoreboard() : Bukkit.getScoreboardManager().getMainScoreboard();
        String entry = p.getName();
        for (Map.Entry<String, Integer> e : values.entrySet()) {
            String objName = e.getKey().startsWith("currency") ? e.getKey() : "currency_" + e.getKey();
            writeCurrency(sb, objName, objName, entry, e.getValue());
        }
    }
}
