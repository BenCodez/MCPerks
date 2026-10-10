package com.bencodez.mcperks.listeners;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.*;
class RefillPlanTest {
    private PlayerInventory inventory;
    private ItemStack[] slots;
    private int selected;
    @BeforeAll static void server() {
        Server server = Bukkit.getServer();
        if (server == null) {
            server = mock(Server.class); when(server.getLogger()).thenReturn(Logger.getLogger("MCPerks-test")); Bukkit.setServer(server);
        }
        if (server.getItemFactory() == null) {
            ItemFactory factory = mock(ItemFactory.class); when(server.getItemFactory()).thenReturn(factory);
            when(factory.equals(nullable(ItemMeta.class), nullable(ItemMeta.class))).thenAnswer(i -> Objects.equals(i.getArgument(0), i.getArgument(1)));
            when(factory.isApplicable(any(ItemMeta.class), any(Material.class))).thenReturn(true);
            when(factory.asMetaFor(any(ItemMeta.class), any(Material.class))).thenAnswer(i -> i.getArgument(0));
        }
    }
    @BeforeEach void setup() {
        slots = new ItemStack[41]; selected = 0; inventory = mock(PlayerInventory.class);
        when(inventory.getHeldItemSlot()).thenAnswer(i -> selected);
        when(inventory.getItem(anyInt())).thenAnswer(i -> slots[i.getArgument(0, Integer.class)]);
        doAnswer(i -> { slots[i.getArgument(0, Integer.class)] = i.getArgument(1); return null; }).when(inventory).setItem(anyInt(), nullable(ItemStack.class));
    }
    private ItemStack stack(int amount) { return new ItemStack(Material.STONE, amount); }
    @Test void offhandCanRefillFromSelectedMainHandAndMoveExactlyOne() {
        slots[0] = stack(8); RefillPlan plan = RefillPlan.capture(inventory, EquipmentSlot.OFF_HAND, stack(1)); assertNotNull(plan);
        assertTrue(plan.apply()); assertEquals(7, slots[0].getAmount()); assertEquals(1, slots[40].getAmount());
        assertFalse(plan.apply()); // repeated callback cannot move another stack
    }
    @Test void lastSourceItemIsRemovedAndMainHandRefillsAfterVanillaConsumption() {
        slots[0] = stack(1); slots[2] = stack(1);
        RefillPlan plan = RefillPlan.capture(inventory, EquipmentSlot.HAND, slots[0]);
        assertFalse(plan.apply()); slots[0] = null;
        assertTrue(plan.apply()); assertNull(slots[2]); assertEquals(1, slots[0].getAmount());
    }
    @Test void sourceCountAndMetadataChangesAreRejectedBeforeAnyMutation() {
        slots[2] = stack(8); RefillPlan plan = RefillPlan.capture(inventory, EquipmentSlot.HAND, stack(1)); assertNotNull(plan);
        slots[2].setAmount(7); assertFalse(plan.apply()); verify(inventory, never()).setItem(anyInt(), any());
        slots[2] = stack(8); plan = RefillPlan.capture(inventory, EquipmentSlot.HAND, stack(1));
        org.bukkit.inventory.meta.Damageable metadata = mock(org.bukkit.inventory.meta.Damageable.class); when(metadata.clone()).thenReturn(metadata); slots[2].setItemMeta(metadata);
        assertFalse(plan.apply()); verify(inventory, never()).setItem(anyInt(), any());
    }
    @Test void changedHotbarSlotAndOccupiedDestinationCannotRefill() {
        slots[2] = stack(8); RefillPlan plan = RefillPlan.capture(inventory, EquipmentSlot.HAND, stack(1));
        selected = 1; assertFalse(plan.apply()); selected = 0; slots[0] = stack(1); assertFalse(plan.apply());
        verify(inventory, never()).setItem(anyInt(), any());
    }
    @Test void differentMaterialsAndArmorAreNeverSources() {
        slots[2] = new ItemStack(Material.DIRT, 4); slots[36] = stack(4);
        assertNull(RefillPlan.capture(inventory, EquipmentSlot.HAND, stack(1)));
    }
}
