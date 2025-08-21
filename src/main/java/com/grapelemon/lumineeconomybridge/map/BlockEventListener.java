package com.grapelemon.lumineeconomybridge.map;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Listen to block events and mark affected tiles dirty.
 */
public class BlockEventListener implements Listener {
    private final TileDebounceManager manager;

    public BlockEventListener(TileDebounceManager manager) {
        this.manager = manager;
    }

    private void mark(Block b) {
        World w = b.getWorld();
        int cx = b.getX() >> 4;
        int cz = b.getZ() >> 4;
        int tx = Math.floorDiv(cx, 4);
        int tz = Math.floorDiv(cz, 4);
        manager.markDirty(w, tx, tz);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        mark(e.getBlockPlaced());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        mark(e.getBlock());
    }
}
