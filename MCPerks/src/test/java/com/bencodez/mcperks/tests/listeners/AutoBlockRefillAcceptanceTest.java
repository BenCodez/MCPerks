package com.bencodez.mcperks.tests.listeners;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.data.LocalStatistics;
import com.bencodez.mcperks.listeners.UsageEffectListener;
import com.bencodez.mcperks.perk.*;
import com.bencodez.mcperks.tests.scheduling.OwnerTaskHarness;
import com.bencodez.mcperks.userapi.UsageCharges;
import com.bencodez.mcperks.userapi.UsageCharges.Kind;

/** Runs actual placement callbacks and the durable ledger across inventory/eligibility transitions. */
class AutoBlockRefillAcceptanceTest {
    @TempDir Path directory;
    private UUID owner;
    private MCPerksMain plugin;
    private Player player;
    private PlayerInventory inventory;
    private ItemStack[] items;
    private int selected;
    private BlockingQueue<Runnable> tasks;
    private UsageCharges charges;
    private UsageEffectListener listener;

    @BeforeEach void setup() throws Exception {
        RefillPlanTest.server();
        owner = UUID.randomUUID();
        plugin = mock(MCPerksMain.class);
        player = mock(Player.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("refill-test");
        when(player.getWorld()).thenReturn(world);
        when(player.getUniqueId()).thenReturn(owner);
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        inventory = mock(PlayerInventory.class);
        items = new ItemStack[41];
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getHeldItemSlot()).thenAnswer(i -> selected);
        when(inventory.getItem(anyInt())).thenAnswer(i -> items[i.getArgument(0, Integer.class)]);
        doAnswer(i -> { items[i.getArgument(0, Integer.class)] = i.getArgument(1); return null; })
                .when(inventory).setItem(anyInt(), nullable(ItemStack.class));
        tasks = new OwnerTaskHarness(plugin, player).tasks;
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("refill-test"));
        when(plugin.getLocalStatistics()).thenReturn(new LocalStatistics());
        charges = new UsageCharges(directory.resolve("charges.dat"));
        charges.ready().get(5, TimeUnit.SECONDS);
        when(plugin.getUsageCharges()).thenReturn(charges);
        Perk perk = mock(Perk.class);
        when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        when(perk.getInteractionOptions()).thenReturn(new InteractionOptions(true, false, 16, 8,
                List.of(), List.of(), List.of(), List.of()));
        when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.AutoBlockRefill)));
        when(perk.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(owner.toString())));
        when(perk.isNotInDisabledWorld(anyString())).thenReturn(true);
        PerkHandler handler = mock(PerkHandler.class);
        when(plugin.getPerkHandler()).thenReturn(handler);
        when(handler.effectDecision(eq(Effect.AutoBlockRefill), eq(owner.toString()), anyString()))
                .thenReturn(PerkHandler.Decision.ACTIVE);
        when(handler.getActivePerks()).thenReturn(new java.util.concurrent.CopyOnWriteArrayList<>(List.of(perk)));
        listener = new UsageEffectListener(plugin);
        charges.set(owner, Kind.AutoBlockRefill, 2).get(5, TimeUnit.SECONDS);
        charges.set(owner, Kind.VeinMining, 7).get(5, TimeUnit.SECONDS);
        items[2] = new ItemStack(Material.STONE, 8);
    }

    @AfterEach void close() throws Exception {
        if (listener != null) listener.retire().get(5, TimeUnit.SECONDS);
        if (charges != null) charges.close();
    }

    private BlockPlaceEvent place(EquipmentSlot hand) {
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(hand);
        when(event.getItemInHand()).thenReturn(new ItemStack(Material.STONE, 1));
        return event;
    }
    private Runnable next() throws Exception {
        Runnable task = tasks.poll(5, TimeUnit.SECONDS);
        assertNotNull(task, "expected player-owner callback");
        return task;
    }
    private Runnable reserve(BlockPlaceEvent event) throws Exception {
        listener.refill(event);
        next().run();
        Runnable effect = next();
        assertEquals(1, charges.get(owner, Kind.AutoBlockRefill), "durable reservation before inventory mutation");
        assertEquals(8, items[2].getAmount());
        return effect;
    }
    private void assertUnused(Runnable effect) throws Exception {
        effect.run();
        effect.run(); // duplicate scheduling cannot debit or mutate twice
        charges.add(owner, Kind.AutoBlockRefill, 0).get(5, TimeUnit.SECONDS);
        assertEquals(2, charges.get(owner, Kind.AutoBlockRefill));
        assertEquals(0, charges.successful(owner, Kind.AutoBlockRefill));
        assertEquals(7, charges.get(owner, Kind.VeinMining), "unrelated labeled balance");
        assertEquals(8, items[2].getAmount());
        verify(inventory, never()).setItem(anyInt(), any());
        assertTrue(tasks.isEmpty());
    }

    @ParameterizedTest @EnumSource(value = EquipmentSlot.class, names = {"HAND", "OFF_HAND"})
    void cancelledAfterReservationPreservesBothInventoriesAndBudget(EquipmentSlot hand) throws Exception {
        BlockPlaceEvent event = place(hand);
        Runnable effect = reserve(event);
        when(event.isCancelled()).thenReturn(true);
        assertUnused(effect);
    }

    @ParameterizedTest @EnumSource(value = EquipmentSlot.class, names = {"HAND", "OFF_HAND"})
    void switchingToCreativeAfterReservationRefundsUnusedBudget(EquipmentSlot hand) throws Exception {
        Runnable effect = reserve(place(hand));
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        assertUnused(effect);
    }

    @Test void creativePlacementNeverSchedulesOrReserves() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        listener.refill(place(EquipmentSlot.HAND));
        assertTrue(tasks.isEmpty());
        assertEquals(2, charges.get(owner, Kind.AutoBlockRefill));
        assertEquals(8, items[2].getAmount());
        verify(inventory, never()).setItem(anyInt(), any());
    }

    @Test void changedSelectedSlotAfterReservationCannotRefillEitherSlot() throws Exception {
        Runnable effect = reserve(place(EquipmentSlot.HAND));
        selected = 1;
        assertUnused(effect);
        assertNull(items[0]);
        assertNull(items[1]);
    }

    @ParameterizedTest @EnumSource(value = EquipmentSlot.class, names = {"HAND", "OFF_HAND"})
    void replacementInDestinationAfterReservationIsNeverOverwritten(EquipmentSlot hand) throws Exception {
        Runnable effect = reserve(place(hand));
        int destination = hand == EquipmentSlot.HAND ? 0 : 40;
        ItemStack replacement = new ItemStack(Material.DIRT, 3);
        items[destination] = replacement;
        assertUnused(effect);
        assertSame(replacement, items[destination]);
    }

    @ParameterizedTest @EnumSource(value = EquipmentSlot.class, names = {"HAND", "OFF_HAND"})
    void successfulRefillMovesOneItemAndDebitsOnlyItsLabeledBudget(EquipmentSlot hand) throws Exception {
        Runnable effect = reserve(place(hand));
        effect.run();
        effect.run();
        charges.add(owner, Kind.AutoBlockRefill, 0).get(5, TimeUnit.SECONDS);
        assertEquals(7, items[2].getAmount());
        assertEquals(1, items[hand == EquipmentSlot.HAND ? 0 : 40].getAmount());
        assertEquals(1, charges.get(owner, Kind.AutoBlockRefill));
        assertEquals(1, charges.successful(owner, Kind.AutoBlockRefill));
        assertEquals(7, charges.get(owner, Kind.VeinMining));
        assertTrue(tasks.isEmpty());
    }
}
