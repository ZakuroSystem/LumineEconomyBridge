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

    private UUID renter;
    private String renterName;
    private long rentUntilEpochMillis;
    private int rentPriceUnits;
    private String mode = "middle";

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

    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (!worldName.equals(location.getWorld().getName())) {
            return false;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public UUID getRenter() {
        return renter;
    }

    public String getRenterName() {
        return renterName;
    }

    public long getRentUntilEpochMillis() {
        return rentUntilEpochMillis;
    }

    public int getRentPriceUnits() {
        return rentPriceUnits;
    }

    public String getMode() {
        return mode == null || mode.isBlank() ? "middle" : mode;
    }

    public void setMode(String mode) {
        this.mode = (mode == null || mode.isBlank()) ? "middle" : mode.toLowerCase();
    }

    public void setRental(UUID renter, String renterName, long rentUntilEpochMillis, int rentPriceUnits) {
        this.renter = renter;
        this.renterName = renterName;
        this.rentUntilEpochMillis = rentUntilEpochMillis;
        this.rentPriceUnits = rentPriceUnits;
    }

    public void clearRental() {
        this.renter = null;
        this.renterName = null;
        this.rentUntilEpochMillis = 0L;
        this.rentPriceUnits = 0;
    }

    public boolean hasActiveRental() {
        return renter != null && rentUntilEpochMillis > System.currentTimeMillis();
    }

    public boolean isRenter(UUID playerId) {
        return playerId != null && hasActiveRental() && playerId.equals(renter);
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
