package com.grapelemon.lumineeconomybridge.map;

import org.bukkit.Material;

public final class MapPalette {
    // 0 is transparent/unmapped. Extend as needed up to 63.
    public static int indexOf(Material m) {
        if (m == null) return 0;
        switch (m) {
            case GRASS_BLOCK: return 1;
            case DIRT: return 2;
            case STONE: return 11;
            case SAND: return 12;
            case WATER: return 20;
            case OAK_LEAVES:
            case SPRUCE_LEAVES:
            case BIRCH_LEAVES: return 25;
            case OAK_LOG:
            case SPRUCE_LOG:
            case BIRCH_LOG: return 26;
            case SNOW:
            case POWDER_SNOW: return 30;
            case LAVA: return 31;
            case CLAY: return 32;
            default: return 0;
        }
    }

    private MapPalette() {}
}

