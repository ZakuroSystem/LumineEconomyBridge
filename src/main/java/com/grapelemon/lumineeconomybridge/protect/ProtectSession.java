package com.grapelemon.lumineeconomybridge.protect;

import org.bukkit.Location;

import java.util.UUID;

class ProtectSession {
    private final UUID playerId;
    private String initialId;
    private Location first;
    private Location second;
    private boolean awaitingName;
    private boolean finalizing;

    ProtectSession(UUID playerId, String initialId) {
        this.playerId = playerId;
        this.initialId = initialId;
    }

    UUID getPlayerId() {
        return playerId;
    }

    String getInitialId() {
        return initialId;
    }

    void setInitialId(String initialId) {
        this.initialId = initialId;
    }

    Location getFirst() {
        return first;
    }

    void setFirst(Location first) {
        this.first = first;
    }

    Location getSecond() {
        return second;
    }

    void setSecond(Location second) {
        this.second = second;
    }

    boolean hasBoth() {
        return first != null && second != null && first.getWorld() != null && first.getWorld().equals(second.getWorld());
    }

    boolean isAwaitingName() {
        return awaitingName;
    }

    void setAwaitingName(boolean awaitingName) {
        this.awaitingName = awaitingName;
    }

    boolean isFinalizing() {
        return finalizing;
    }

    void setFinalizing(boolean finalizing) {
        this.finalizing = finalizing;
    }
}
