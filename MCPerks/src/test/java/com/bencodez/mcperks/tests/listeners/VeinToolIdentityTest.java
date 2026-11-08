package com.bencodez.mcperks.tests.listeners;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VeinToolIdentityTest {
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final ItemStack expected = mock(ItemStack.class);
    private final ItemStack actual = mock(ItemStack.class);
    private Method matches;

    @BeforeEach void setup() throws Exception {
        Class<?> type = Class.forName("com.bencodez.mcperks.listeners.VeinToolIdentity");
        matches = type.getDeclaredMethod("matches", Player.class, ItemStack.class, int.class);
        matches.setAccessible(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getHeldItemSlot()).thenReturn(2);
        when(inventory.getItemInMainHand()).thenReturn(actual);
        when(actual.getType()).thenReturn(Material.DIAMOND_PICKAXE);
        when(expected.getType()).thenReturn(Material.DIAMOND_PICKAXE);
    }

    private boolean matches() throws Exception {
        return (boolean) matches.invoke(null, player, expected, 2);
    }

    /** Minimal metadata boundary with equality affected by both name and damage. */
    private static Damageable metadata(String name, int damage) {
        class State implements java.lang.reflect.InvocationHandler {
            final String label = name;
            int currentDamage = damage;
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                return switch (method.getName()) {
                    case "setDamage" -> { currentDamage = (int) args[0]; yield null; }
                    case "getDamage" -> currentDamage;
                    case "equals" -> {
                        if (args[0] == null || !Proxy.isProxyClass(args[0].getClass())) yield false;
                        var other = Proxy.getInvocationHandler(args[0]);
                        yield other instanceof State state && label.equals(state.label) && currentDamage == state.currentDamage;
                    }
                    case "hashCode" -> 1;
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
        }
        return (Damageable) Proxy.newProxyInstance(Damageable.class.getClassLoader(),
                new Class<?>[] {Damageable.class}, new State());
    }

    @Test void selectedSlotChangeRefusesBeforeReadingHeldTool() throws Exception {
        when(inventory.getHeldItemSlot()).thenReturn(3);
        assertFalse(matches());
        verify(inventory, never()).getItemInMainHand();
    }

    @Test void absentOrDifferentMaterialRefuses() throws Exception {
        when(inventory.getItemInMainHand()).thenReturn(null, actual);
        when(actual.getType()).thenReturn(Material.IRON_PICKAXE);
        assertFalse(matches());
        assertFalse(matches());
    }

    @Test void durabilityChangesAloneDoNotChangeIdentity() throws Exception {
        Damageable expectedMeta = metadata("same tool", 2);
        Damageable actualMeta = metadata("same tool", 9);
        when(expected.getItemMeta()).thenReturn(expectedMeta);
        when(actual.getItemMeta()).thenReturn(actualMeta);
        assertTrue(matches());
        assertEquals(0, expectedMeta.getDamage());
        assertEquals(0, actualMeta.getDamage());
    }

    @Test void changedOtherMetadataStillRefuses() throws Exception {
        when(expected.getItemMeta()).thenReturn(metadata("admitted", 2));
        when(actual.getItemMeta()).thenReturn(metadata("replacement", 9));
        assertFalse(matches());
    }

    @Test void bothAbsentMetadataAreEqual() throws Exception {
        when(expected.getItemMeta()).thenReturn(null);
        when(actual.getItemMeta()).thenReturn(null);
        assertTrue(matches());
    }
}
