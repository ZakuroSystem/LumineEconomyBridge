package com.grapelemon.lumineeconomybridge.guide;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class GuideBookMenuHolder implements InventoryHolder {

    public enum GuideAction {
        CHECK_BALANCE,
        CREATE_SHOP,
        RECOMMENDED_QUESTS
    }

    private final Map<Integer, GuideAction> actions = new HashMap<>();
    private Inventory inventory;

    public void bind(int slot, GuideAction action) {
        actions.put(slot, action);
    }

    public GuideAction getAction(int slot) {
        return actions.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
