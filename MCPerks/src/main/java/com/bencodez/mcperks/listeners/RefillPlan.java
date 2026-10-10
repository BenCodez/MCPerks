package com.bencodez.mcperks.listeners;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
/** Captures and revalidates the exact hand, selected slot and matching source stack. */
public final class RefillPlan implements EffectBatch.Unit {
    private final PlayerInventory inventory;
    private final int destination, source, selected;
    private final EquipmentSlot hand;
    private final ItemStack sourceBefore;
    private RefillPlan(PlayerInventory inventory, EquipmentSlot hand, int source) {
        this.inventory = inventory; this.hand = hand; this.source = source;
        selected = inventory.getHeldItemSlot(); destination = hand == EquipmentSlot.OFF_HAND ? 40 : selected;
        sourceBefore = inventory.getItem(source).clone();
    }
    public static RefillPlan capture(PlayerInventory inventory, EquipmentSlot hand, ItemStack placed) {
        if (placed == null || placed.getAmount() != 1) return null;
        int destination = hand == EquipmentSlot.OFF_HAND ? 40 : inventory.getHeldItemSlot();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (slot != destination && item != null && item.getAmount() > 0 && item.isSimilar(placed)) return new RefillPlan(inventory, hand, slot);
        }
        return null;
    }
    @Override public boolean apply() {
        if (hand == EquipmentSlot.HAND && inventory.getHeldItemSlot() != selected) return false;
        ItemStack held = inventory.getItem(destination), item = inventory.getItem(source);
        if (held != null && held.getType() != Material.AIR && held.getAmount() > 0) return false;
        if (!sourceBefore.equals(item)) return false;
        ItemStack replacement = item.clone(); replacement.setAmount(1);
        ItemStack remainder = item.clone(); remainder.setAmount(remainder.getAmount() - 1);
        inventory.setItem(source, remainder.getAmount() == 0 ? null : remainder);
        inventory.setItem(destination, replacement);
        return true;
    }
}
