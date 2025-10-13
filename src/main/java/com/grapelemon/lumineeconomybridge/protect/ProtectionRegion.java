package com.grapelemon.lumineeconomybridge.protect;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;
import java.util.UUID;

public class ProtectionRegion {
    private final String id;
    private final UUID owner;
    private final String worldName;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final long createdAt;

    public ProtectionRegion(String id, UUID owner, String worldName,
                            int minX, int minY, int minZ,
                            int maxX, int maxY, int maxZ,
                            long createdAt) {
        this.id = id;
        this.owner = owner;
        this.worldName = worldName;
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getMinX() {
        return minX;
    }

    public int getMinY() {
        return minY;
    }

    public int getMinZ() {
        return minZ;
    }

    public int getMaxX() {
        return maxX;
    }

    public int getMaxY() {
        return maxY;
    }

    public int getMaxZ() {
        return maxZ;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getVolume() {
        long dx = (long) maxX - minX + 1L;
        long dy = (long) maxY - minY + 1L;
        long dz = (long) maxZ - minZ + 1L;
        return dx * dy * dz;
    }

    public Location getCenter() {
        World world = Bukkit.getWorld(worldName);
        double x = (minX + maxX) / 2.0D;
        double y = (minY + maxY) / 2.0D;
        double z = (minZ + maxZ) / 2.0D;
        return world != null ? new Location(world, x, y, z) : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProtectionRegion region)) return false;
        return Objects.equals(id, region.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
