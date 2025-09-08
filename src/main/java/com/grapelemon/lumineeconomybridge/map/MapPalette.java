package com.grapelemon.lumineeconomybridge.map;

import org.bukkit.Material;

public final class MapPalette {
    // 0 is default/other. Indices 1-6 correspond to palette entries in MapColorService.
    public static int indexOf(Material m) {
        if (m == null) {
            return 0;
        }
        String id = m.name().toLowerCase();
        if ("grass_block".equals(id) || id.contains("tall_grass") || id.contains("grass") || id.contains("green")) {
            return 1; // grass/green
        } else if (id.contains("leaves")) {
            return 2; // leaves
        } else if (id.contains("water")) {
            return 3; // water
        } else if (id.contains("quartz") || id.contains("white") || id.contains("snow")) {
            return 4; // quartz/white/snow
        } else if (id.contains("stone") || id.contains("gray")) {
            return 5; // stone/gray
        } else if (id.contains("lava")) {
            return 6; // lava
        }
        return 0; // other
    }

    private MapPalette() {}
}

