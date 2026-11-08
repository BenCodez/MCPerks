package com.bencodez.mcperks.listeners;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

/** Checks the admitted tool before each deferred native vein break. */
final class VeinToolIdentity {
    private VeinToolIdentity() {}

    static boolean matches(Player player, ItemStack expected, int selectedSlot) {
        if (player.getInventory().getHeldItemSlot() != selectedSlot) return false;
        ItemStack actual = player.getInventory().getItemInMainHand();
        if (actual == null || actual.getType() != expected.getType()) return false;
        var actualMeta = actual.getItemMeta();
        var expectedMeta = expected.getItemMeta();
        // Native breaks change only durability. All other metadata identifies the admitted tool.
        if (actualMeta instanceof Damageable damage) damage.setDamage(0);
        if (expectedMeta instanceof Damageable damage) damage.setDamage(0);
        return Objects.equals(actualMeta, expectedMeta);
    }
}
