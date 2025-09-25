package com.grapelemon.lumineeconomybridge.guide;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class GuideBookMenuHolder implements InventoryHolder {

    public enum MenuType {
        MAIN,
        BALANCE,
        QUESTS,
        SHOPS
    }

    public enum GuideAction {
        CHECK_BALANCE,
        CREATE_SHOP,
        MANAGE_SHOPS,
        RECOMMENDED_QUESTS,
        RECOMMENDED_SHOPS,
        BACK_TO_MAIN
    }

    private final Map<Integer, GuideAction> actions = new HashMap<>();
    private final MenuType menuType;
    private Inventory inventory;

    public GuideBookMenuHolder(MenuType menuType) {
        this.menuType = menuType;
    }

    public MenuType getMenuType() {
        return menuType;
    }

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
