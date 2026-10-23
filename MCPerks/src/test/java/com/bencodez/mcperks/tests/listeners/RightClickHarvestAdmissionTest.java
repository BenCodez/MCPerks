package com.bencodez.mcperks.tests.listeners;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.data.LocalStatistics;
import com.bencodez.mcperks.listeners.UsageEffectListener;
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.InteractionOptions;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.mcperks.perk.PerkHandler;
import com.bencodez.mcperks.perk.PerkSystemType;
import com.bencodez.mcperks.tests.scheduling.OwnerTaskHarness;
import com.bencodez.mcperks.userapi.UsageCharges;
import com.bencodez.mcperks.userapi.UsageCharges.Kind;

/** Admission and successful-use controls run the listener with its durable labeled ledger. */
class RightClickHarvestAdmissionTest {
    @TempDir Path directory;
    private UUID owner;
    private Player player;
    private PlayerInventory inventory;
    private Block block;
    private Ageable age;
    private ItemStack[] items;
    private BlockingQueue<Runnable> tasks;
    private UsageCharges charges;
    private UsageEffectListener listener;
    private Path ledger;

    @BeforeEach void setup() throws Exception {
        RefillPlanTest.server();
        owner = UUID.randomUUID();
        MCPerksMain plugin = mock(MCPerksMain.class);
        player = mock(Player.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("harvest-test");
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getUniqueId()).thenReturn(owner);
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getLocation()).thenReturn(new Location(world, 4, 64, 4));
        inventory = mock(PlayerInventory.class);
        items = new ItemStack[41];
        items[0] = new ItemStack(Material.STONE);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItem(anyInt())).thenAnswer(i -> items[i.getArgument(0, Integer.class)]);
        when(inventory.getItemInMainHand()).thenAnswer(i -> items[0]);
        doAnswer(i -> { items[i.getArgument(0, Integer.class)] = i.getArgument(1); return null; })
                .when(inventory).setItem(anyInt(), nullable(ItemStack.class));
        tasks = new OwnerTaskHarness(plugin, player).tasks;
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MCPerks-harvest-test"));
        when(plugin.getLocalStatistics()).thenReturn(new LocalStatistics());
        ledger = directory.resolve("charges.dat");
        charges = new UsageCharges(ledger);
        charges.ready().get(5, TimeUnit.SECONDS);
        when(plugin.getUsageCharges()).thenReturn(charges);
        charges.set(owner, Kind.RightClickHarvest, 2).get(5, TimeUnit.SECONDS);
        charges.set(owner, Kind.VeinMining, 7).get(5, TimeUnit.SECONDS);
        Perk perk = mock(Perk.class);
        when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        when(perk.getInteractionOptions()).thenReturn(new InteractionOptions(true, false, 16, 8,
                List.of(), List.of(), List.of(), List.of()));
        when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.RightClickHarvest)));
        when(perk.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(owner.toString())));
        when(perk.isNotInDisabledWorld(anyString())).thenReturn(true);
        when(perk.getWhitelistedTools()).thenReturn(new ArrayList<>());
        when(perk.getWhitelistedBlocks()).thenReturn(new ArrayList<>());
        when(perk.getBlacklistedBlocks()).thenReturn(new ArrayList<>());
        when(perk.getRequiredEnchants()).thenReturn(new HashMap<>());
        PerkHandler handler = mock(PerkHandler.class);
        when(plugin.getPerkHandler()).thenReturn(handler);
        when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>(List.of(perk)));
        when(handler.effectDecision(eq(Effect.RightClickHarvest), eq(owner.toString()), anyString()))
                .thenReturn(PerkHandler.Decision.ACTIVE);
        block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(4);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(4);
        when(block.getLocation()).thenReturn(new Location(world, 4, 64, 4));
        age = mock(Ageable.class);
        when(age.clone()).thenReturn(age);
        when(block.getBlockData()).thenReturn(age);
        listener = new UsageEffectListener(plugin);
    }

    @AfterEach void close() throws Exception {
        if (listener != null) listener.retire().get(5, TimeUnit.SECONDS);
        if (charges != null) charges.close();
    }

    private PlayerInteractEvent interact() {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
        return event;
    }

    private void assertNotAdmitted(byte[] originalLedger) throws Exception {
        assertTrue(tasks.isEmpty(), "ineligible crop must not schedule a harvest or reserve a use");
        assertEquals(2, charges.get(owner, Kind.RightClickHarvest));
        assertEquals(7, charges.get(owner, Kind.VeinMining));
        assertEquals(0, charges.successful(owner, Kind.RightClickHarvest));
        assertArrayEquals(originalLedger, Files.readAllBytes(ledger));
        verify(inventory, never()).setItem(anyInt(), any());
        verify(player, never()).breakBlock(any());
        verify(block, never()).setBlockData(any());
    }

    @ParameterizedTest
    @CsvSource({"WHEAT,7", "POTATOES,7", "CARROTS,7", "BEETROOTS,3", "NETHER_WART,3"})
    void matureCropWithoutReplantSeedNeverReservesOrChangesWorld(Material crop, int maximumAge) throws Exception {
        when(block.getType()).thenReturn(crop);
        when(age.getMaximumAge()).thenReturn(maximumAge);
        when(age.getAge()).thenReturn(maximumAge);
        items[1] = new ItemStack(Material.DIRT, 3);
        byte[] before = Files.readAllBytes(ledger);
        listener.harvest(interact());
        listener.harvest(interact());
        assertNotAdmitted(before);
        assertEquals(3, items[1].getAmount());
        assertEquals(LocalStatistics.Skip.SEED, listener.recentSkip(owner).reason());
    }

    @ParameterizedTest
    @CsvSource({"WHEAT,WHEAT_SEEDS,7", "POTATOES,POTATO,7", "CARROTS,CARROT,7", "BEETROOTS,BEETROOT_SEEDS,3", "NETHER_WART,NETHER_WART,3"})
    void immatureCropKeepsItsSeedAndDoesNotReserveAUse(Material crop, Material seed, int maximumAge) throws Exception {
        when(block.getType()).thenReturn(crop);
        when(age.getMaximumAge()).thenReturn(maximumAge);
        when(age.getAge()).thenReturn(maximumAge - 1);
        items[1] = new ItemStack(seed, 3);
        ItemStack beforeSeed = items[1].clone();
        byte[] before = Files.readAllBytes(ledger);
        listener.harvest(interact());
        listener.harvest(interact());
        assertNotAdmitted(before);
        assertEquals(beforeSeed, items[1]);
    }

    private Runnable next() throws Exception {
        Runnable task = tasks.poll(5, TimeUnit.SECONDS);
        assertNotNull(task, "eligible harvest must reach its player owner");
        return task;
    }

    @ParameterizedTest
    @CsvSource({"WHEAT,WHEAT_SEEDS,7", "POTATOES,POTATO,7", "CARROTS,CARROT,7", "BEETROOTS,BEETROOT_SEEDS,3", "NETHER_WART,NETHER_WART,3"})
    void matureCropWithSeedConsumesExactlyOneUse(Material crop, Material seed, int maximumAge) throws Exception {
        when(block.getType()).thenReturn(crop);
        when(age.getMaximumAge()).thenReturn(maximumAge);
        when(age.getAge()).thenReturn(maximumAge);
        items[1] = new ItemStack(seed, 3);
        when(player.breakBlock(block)).thenAnswer(i -> { when(block.getType()).thenReturn(Material.AIR); return true; });
        listener.harvest(interact());
        next().run();
        Runnable execution = next();
        assertEquals(1, charges.get(owner, Kind.RightClickHarvest), "reserve before native break");
        execution.run();
        execution.run();
        charges.add(owner, Kind.RightClickHarvest, 0).get(5, TimeUnit.SECONDS);
        assertEquals(1, charges.get(owner, Kind.RightClickHarvest));
        assertEquals(1, charges.successful(owner, Kind.RightClickHarvest));
        assertEquals(7, charges.get(owner, Kind.VeinMining));
        assertEquals(2, items[1].getAmount());
        verify(player, times(1)).breakBlock(block);
        verify(age).setAge(0);
        verify(block).setBlockData(age);
        assertTrue(tasks.isEmpty());
    }
}
