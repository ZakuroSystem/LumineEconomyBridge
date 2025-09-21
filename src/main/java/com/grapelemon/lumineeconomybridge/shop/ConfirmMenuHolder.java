package com.grapelemon.lumineeconomybridge.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

public class ConfirmMenuHolder implements InventoryHolder {
    public enum ActionType {
        BUY,
        SELL
    }

    public static class ConfirmAction {
        private final ActionType type;
        private final int quantity;

        public ConfirmAction(ActionType type, int quantity) {
            this.type = type;
            this.quantity = quantity;
        }

        public ActionType getType() {
            return type;
        }

        public int getQuantity() {
            return quantity;
        }
    }

    private final String shopId;
    private final ShopItem item;
    private final ShopMenuHolder origin;
    private final int slot;
    private final Map<Integer, ConfirmAction> actions = new HashMap<>();
    private int backSlot = -1;

    public ConfirmMenuHolder(String shopId, ShopMenuHolder origin, int slot, ShopItem item) {
        this.shopId = shopId;
        this.origin = origin;
        this.slot = slot;
        this.item = item;
    }

    public String getShopId() {
        return shopId;
    }

    public ShopItem getItem() {
        return item;
    }

    public ShopMenuHolder getOrigin() {
        return origin;
    }

    public int getSlot() {
        return slot;
    }

    public void registerAction(int slot, ActionType type, int quantity) {
        actions.put(slot, new ConfirmAction(type, quantity));
    }

    public ConfirmAction getAction(int slot) {
        return actions.get(slot);
    }

    public void setBackSlot(int slot) {
        this.backSlot = slot;
    }

    public boolean isBackSlot(int slot) {
        return backSlot == slot;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
