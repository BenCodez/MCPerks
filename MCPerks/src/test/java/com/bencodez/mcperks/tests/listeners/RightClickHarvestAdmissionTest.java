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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.listeners.UsageEffectListener;
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.mcperks.perk.PerkHandler;
import com.bencodez.mcperks.perk.PerkSystemType;
import com.bencodez.mcperks.tests.scheduling.OwnerTaskHarness;
import com.bencodez.mcperks.userapi.UsageCharges;
import com.bencodez.mcperks.userapi.UsageCharges.Kind;

/** Admission and successful-use controls run the listener with its durable labeled ledger. */
class RightClickHarvestAdmissionTest {
    @TempDir Path directory;
    private MCPerksMain plugin;
    private Perk perk;
    private PerkHandler handler;
    private World world;
    private OwnerTaskHarness ownerScheduler;
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
        plugin = mock(MCPerksMain.class);
        player = mock(Player.class);
        world = mock(World.class);
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
        when(plugin.isEnabled()).thenReturn(true);
        ownerScheduler = new OwnerTaskHarness(plugin, player);
        tasks = ownerScheduler.tasks;
        when(plugin.getServer().getPlayer(owner)).thenReturn(player);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MCPerks-harvest-test"));
        ledger = directory.resolve("charges.dat");
        charges = new UsageCharges(ledger);
        charges.ready().get(5, TimeUnit.SECONDS);
        when(plugin.getUsageCharges()).thenReturn(charges);
        charges.set(owner, Kind.RightClickHarvest, 2).get(5, TimeUnit.SECONDS);
        charges.set(owner, Kind.VeinMining, 7).get(5, TimeUnit.SECONDS);
        perk = mock(Perk.class);
        when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        when(perk.isUsageChargesEnabled()).thenReturn(true);
        when(perk.getHarvestCrops()).thenReturn(List.of());
        when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.RightClickHarvest)));
        when(perk.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(owner.toString())));
        when(perk.isNotInDisabledWorld(anyString())).thenReturn(true);
        when(perk.getWhitelistedTools()).thenReturn(new ArrayList<>());
        when(perk.getWhitelistedBlocks()).thenReturn(new ArrayList<>());
        when(perk.getBlacklistedBlocks()).thenReturn(new ArrayList<>());
        when(perk.getRequiredEnchants()).thenReturn(new HashMap<>());
        handler = mock(PerkHandler.class);
        when(plugin.getPerkHandler()).thenReturn(handler);
        when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>(List.of(perk)));
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
        when(plugin.getUsageEffects()).thenReturn(listener);
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
        when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
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
        // Missing-seed admission is proved by the real ledger, inventory and native checks above.
        // Diagnostic skip counters remain in the separate statistics slice.
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
    private void matureWheat() {
        when(block.getType()).thenReturn(Material.WHEAT);
        when(age.getMaximumAge()).thenReturn(7);
        when(age.getAge()).thenReturn(7);
        items[1] = new ItemStack(Material.WHEAT_SEEDS, 3);
    }

    private void barrier() throws Exception {
        charges.add(owner, Kind.RightClickHarvest, 0).get(5, TimeUnit.SECONDS);
    }

    private void deny(PlayerInteractEvent event, String denial) {
        switch (denial) {
            case "cancelled" -> when(event.isCancelled()).thenReturn(true);
            case "block" -> when(event.useInteractedBlock()).thenReturn(Event.Result.DENY);
            case "item" -> when(event.useItemInHand()).thenReturn(Event.Result.DENY);
            default -> throw new AssertionError(denial);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"offhand", "cancelled", "block", "item"})
    void offhandAndInitialProtectionNeverAdmitOrMutateTheEvent(String denial) throws Exception {
        matureWheat();
        var event = interact();
        if (denial.equals("offhand")) when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        else deny(event, denial);
        byte[] before = Files.readAllBytes(ledger);
        listener.harvest(event);
        assertNotAdmitted(before);
        verify(event, never()).setCancelled(anyBoolean());
        verify(event, never()).setUseInteractedBlock(any());
        verify(event, never()).setUseItemInHand(any());
    }

    @ParameterizedTest
    @CsvSource({"cancelled,false", "block,false", "item,false", "cancelled,true", "block,true", "item,true"})
    void finalInteractionDenialKeepsSeedAndReleasesOnlyProvedUnusedUnits(String denial, boolean reserved) throws Exception {
        matureWheat();
        var event = interact();
        listener.harvest(event);
        Runnable callback = next();
        if (reserved) { callback.run(); callback = next(); assertEquals(1, charges.get(owner, Kind.RightClickHarvest)); }
        deny(event, denial);
        callback.run();
        barrier();
        assertEquals(2, charges.get(owner, Kind.RightClickHarvest));
        assertEquals(0, charges.successful(owner, Kind.RightClickHarvest));
        assertEquals(3, items[1].getAmount());
        verify(inventory, never()).setItem(anyInt(), any());
        verify(player, never()).breakBlock(any());
        verify(block, never()).setBlockData(any());
        assertTrue(tasks.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"slot", "tool", "metadata", "seed", "block", "range", "world", "chunk", "creative", "offline", "invalid", "audience", "disabled-world", "perk", "crop-policy", "tool-policy"})
    void staleExecutionContextIsUnusedAndNeverChangesGameplay(String changed) throws Exception {
        matureWheat();
        listener.harvest(interact()); next().run(); Runnable execute = next();
        assertEquals(1, charges.get(owner, Kind.RightClickHarvest));
        switch (changed) {
            case "slot" -> when(inventory.getHeldItemSlot()).thenReturn(2);
            case "tool" -> items[0] = new ItemStack(Material.DIRT);
            case "metadata" -> {
                var meta = mock(org.bukkit.inventory.meta.Damageable.class);
                when(meta.clone()).thenReturn(meta); when(meta.getEnchants()).thenReturn(java.util.Map.of());
                when(meta.getDisplayName()).thenReturn("Replacement metadata");
                items[0] = new ItemStack(Material.STONE); items[0].setItemMeta(meta);
            }
            case "seed" -> items[1].setAmount(4);
            case "block" -> when(block.getBlockData()).thenReturn(mock(Ageable.class));
            case "range" -> when(player.getLocation()).thenReturn(new Location(world, 40, 64, 4));
            case "world" -> when(block.getWorld()).thenReturn(mock(World.class));
            case "chunk" -> when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
            case "creative" -> when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
            case "offline" -> when(player.isOnline()).thenReturn(false);
            case "invalid" -> when(player.isValid()).thenReturn(false);
            case "audience" -> when(perk.getEffectedPlayers()).thenReturn(new ArrayList<>());
            case "disabled-world" -> when(perk.isNotInDisabledWorld(anyString())).thenReturn(false);
            case "perk" -> when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>());
            case "crop-policy" -> when(perk.getHarvestCrops()).thenReturn(List.of("POTATOES"));
            case "tool-policy" -> when(perk.getWhitelistedTools()).thenReturn(new ArrayList<>(List.of(Material.DIAMOND_HOE)));
            default -> throw new AssertionError(changed);
        }
        ItemStack seed = items[1].clone();
        execute.run(); execute.run(); barrier();
        assertEquals(seed, items[1]);
        assertEquals(2, charges.get(owner, Kind.RightClickHarvest));
        assertEquals(0, charges.successful(owner, Kind.RightClickHarvest));
        verify(inventory, never()).setItem(anyInt(), any());
        verify(player, never()).breakBlock(any());
        verify(block, never()).setBlockData(any());
        assertTrue(tasks.isEmpty());
    }

    @Test void reconnectAndLateQuitCannotExecuteThePredecessorReservation() throws Exception {
        matureWheat(); listener.harvest(interact()); next().run(); Runnable execute = next();
        Player successor = mock(Player.class); when(successor.getUniqueId()).thenReturn(owner);
        when(successor.isOnline()).thenReturn(true); when(successor.isValid()).thenReturn(true);
        when(plugin.getServer().getPlayer(owner)).thenReturn(successor);
        var join = mock(org.bukkit.event.player.PlayerJoinEvent.class); when(join.getPlayer()).thenReturn(successor); listener.join(join);
        var quit = mock(org.bukkit.event.player.PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(player); listener.quit(quit);
        execute.run(); execute.run(); barrier();
        assertEquals(2, charges.get(owner, Kind.RightClickHarvest));
        assertEquals(3, items[1].getAmount()); verify(player, never()).breakBlock(any());
        verify(inventory, never()).setItem(anyInt(), any());
    }

    @Test void invalidationBeforeDeferredEntryNeverReservesAndDuplicateCallbacksNeverReplay() throws Exception {
        matureWheat(); byte[] before = Files.readAllBytes(ledger);
        listener.harvest(interact()); Runnable deferred = next(); listener.invalidate(); deferred.run(); deferred.run();
        assertNotAdmitted(before);
    }

    @Test void duplicatedEntryAndExecutionStillConsumeExactlyOneSeedAndUse() throws Exception {
        matureWheat();
        when(player.breakBlock(block)).thenAnswer(i -> { when(block.getType()).thenReturn(Material.AIR); return true; });
        listener.harvest(interact()); Runnable deferred = next(); deferred.run(); deferred.run();
        Runnable execute = next(); execute.run(); execute.run(); deferred.run(); barrier();
        assertEquals(1, charges.get(owner, Kind.RightClickHarvest));
        assertEquals(1, charges.successful(owner, Kind.RightClickHarvest));
        assertEquals(2, items[1].getAmount()); verify(player, times(1)).breakBlock(block);
        verify(block, times(1)).setBlockData(age); assertTrue(tasks.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "false-after-effect", "throw", "replacement", "reload", "replant-throw"})
    void uncertainNativeOutcomeKeepsSeedAndChargeSpentWithoutReplay(String outcome) throws Exception {
        matureWheat();
        when(player.breakBlock(block)).thenAnswer(i -> {
            if (outcome.equals("throw")) throw new IllegalStateException("Synthetic unknown native outcome");
            if (outcome.equals("replacement")) when(block.getType()).thenReturn(Material.STONE);
            else if (!outcome.equals("false")) when(block.getType()).thenReturn(Material.AIR);
            if (outcome.equals("reload")) listener.invalidate();
            return !outcome.startsWith("false");
        });
        if (outcome.equals("replant-throw")) doThrow(new IllegalStateException("Synthetic replant failure")).when(block).setBlockData(any());
        listener.harvest(interact()); next().run(); Runnable execute = next(); execute.run(); execute.run(); barrier();
        assertEquals(1, charges.get(owner, Kind.RightClickHarvest));
        assertEquals(0, charges.successful(owner, Kind.RightClickHarvest));
        assertEquals(7, charges.get(owner, Kind.VeinMining));
        assertEquals(2, items[1].getAmount()); verify(player, times(1)).breakBlock(block);
        if (!outcome.equals("replant-throw")) verify(block, never()).setBlockData(any());
        assertFalse(listener.isNativeBreak(player)); assertTrue(tasks.isEmpty());
    }

    @Test void aSingleSeedIsReservedBeforeNativeCallbacksAndMainHandSeedsRemainSupported() throws Exception {
        matureWheat(); items[0] = new ItemStack(Material.WHEAT_SEEDS); items[1] = null;
        var seedReserved = new java.util.concurrent.atomic.AtomicBoolean();
        when(player.breakBlock(block)).thenAnswer(i -> {
            seedReserved.set(items[0] == null); when(block.getType()).thenReturn(Material.AIR); return true;
        });
        listener.harvest(interact()); next().run(); next().run(); barrier();
        assertTrue(seedReserved.get()); assertNull(items[0]);
        assertEquals(1, charges.successful(owner, Kind.RightClickHarvest));
        assertEquals(1, charges.get(owner, Kind.RightClickHarvest)); verify(block).setBlockData(age);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tool", "whitelist", "blacklist", "crops"})
    void sharedAutoPlantAndCropPolicyRejectBeforeReservation(String rule) throws Exception {
        matureWheat();
        switch (rule) {
            case "tool" -> when(perk.getWhitelistedTools()).thenReturn(new ArrayList<>(List.of(Material.DIAMOND_HOE)));
            case "whitelist" -> when(perk.getWhitelistedBlocks()).thenReturn(new ArrayList<>(List.of(Material.POTATOES)));
            case "blacklist" -> when(perk.getBlacklistedBlocks()).thenReturn(new ArrayList<>(List.of(Material.WHEAT)));
            case "crops" -> when(perk.getHarvestCrops()).thenReturn(List.of("POTATOES"));
        }
        byte[] before = Files.readAllBytes(ledger); listener.harvest(interact()); assertNotAdmitted(before);
    }

    @Test void sharedAutoPlantEnchantmentRequirementsAllowExactLevelAndFailClosedUnknownKeys() {
        var tool = mock(ItemStack.class); when(tool.getType()).thenReturn(Material.DIAMOND_HOE);
        when(perk.getRequiredEnchants()).thenReturn(new HashMap<>(java.util.Map.of("unbreaking", 2)));
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            // Initialize the API registries through a controlled server boundary,
            // before Enchantment's static constants request their registry entries.
            bukkit.when(() -> org.bukkit.Bukkit.getRegistry(any())).thenAnswer(i -> mock(org.bukkit.Registry.class));
            var registry = org.bukkit.Registry.ENCHANTMENT;
            doAnswer(i -> mock(org.bukkit.enchantments.Enchantment.class)).when(registry).getOrThrow(any());
            var enchantment = org.bukkit.enchantments.Enchantment.UNBREAKING;
            doReturn(enchantment).when(registry).get(org.bukkit.NamespacedKey.minecraft("unbreaking"));
            doReturn(null).when(registry).get(org.bukkit.NamespacedKey.minecraft("unknown"));
            when(tool.getEnchantmentLevel(enchantment)).thenReturn(2);
            assertTrue(com.bencodez.mcperks.effects.AutoPlant.allows(perk, tool, Material.WHEAT));
            when(tool.getEnchantmentLevel(enchantment)).thenReturn(1);
            assertFalse(com.bencodez.mcperks.effects.AutoPlant.allows(perk, tool, Material.WHEAT));
            when(perk.getRequiredEnchants()).thenReturn(new HashMap<>(java.util.Map.of("unknown", 1)));
            assertFalse(com.bencodez.mcperks.effects.AutoPlant.allows(perk, tool, Material.WHEAT));
        }
    }

    @SuppressWarnings("deprecation")
    @Test void nativeHarvestRetainsFarmerDropsWhileSuppressingAutoPlantAndRecursiveAutomation() throws Exception {
        matureWheat();
        var effects = mock(com.bencodez.mcperks.perk.EffectHandler.class); when(plugin.getEffectHandler()).thenReturn(effects);
        when(effects.getBlockBreakEvents()).thenReturn(new ArrayList<>());
        Perk farmer = mock(Perk.class); when(farmer.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.Farmer)));
        when(farmer.getBlocks()).thenReturn(new ArrayList<>(List.of(Material.WHEAT)));
        when(farmer.getIncreasePercent(Effect.Farmer)).thenReturn(200.0);
        when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>(List.of(perk, farmer)));
        when(handler.effectActive(Effect.Farmer, owner.toString(), "harvest-test")).thenReturn(true);
        when(player.getItemInHand()).thenAnswer(i -> inventory.getItemInMainHand());
        when(block.getDrops(any(ItemStack.class))).thenAnswer(i -> List.of(new ItemStack(Material.WHEAT, 2)));
        var legacy = new com.bencodez.mcperks.listeners.EffectListeners(plugin);
        var guarded = new java.util.concurrent.atomic.AtomicBoolean();
        when(player.breakBlock(block)).thenAnswer(i -> {
            guarded.set(listener.isNativeBreak(player));
            var event = new org.bukkit.event.block.BlockBreakEvent(block, player);
            legacy.onBlockBreak(event); listener.vein(event); listener.harvest(interact());
            when(block.getType()).thenReturn(Material.AIR); return true;
        });
        listener.harvest(interact()); next().run(); next().run(); barrier();
        assertTrue(guarded.get()); assertFalse(listener.isNativeBreak(player));
        verify(world).dropItemNaturally(eq(block.getLocation()), argThat(item -> item.getType() == Material.WHEAT && item.getAmount() == 4));
        verify(handler, never()).effectActive(eq(Effect.AutoPlant), anyString(), anyString());
        verify(handler, never()).effectActive(eq(Effect.IncreaseMiningArea), anyString(), anyString());
        verify(player, times(1)).breakBlock(block); verify(block, times(1)).setBlockData(age);
        assertEquals(1, charges.successful(owner, Kind.RightClickHarvest)); assertEquals(2, items[1].getAmount());
        assertTrue(tasks.isEmpty());
    }

    @Test void reloadDuringNativeBreakKeepsSuccessorAutomationGuardedAndDoesNotReplant() throws Exception {
        matureWheat();
        var successor = new java.util.concurrent.atomic.AtomicReference<UsageEffectListener>();
        var guarded = new java.util.concurrent.atomic.AtomicBoolean();
        when(player.breakBlock(block)).thenAnswer(i -> {
            listener.invalidate();
            successor.set(new UsageEffectListener(plugin));
            when(plugin.getUsageEffects()).thenReturn(successor.get());
            guarded.set(successor.get().isNativeBreak(player));
            successor.get().harvest(interact());
            when(block.getType()).thenReturn(Material.AIR);
            return true;
        });
        listener.harvest(interact()); next().run(); next().run(); barrier();
        assertTrue(guarded.get()); assertFalse(successor.get().isNativeBreak(player));
        assertTrue(tasks.isEmpty()); verify(player, times(1)).breakBlock(block);
        verify(block, never()).setBlockData(any());
        assertEquals(1, charges.get(owner, Kind.RightClickHarvest));
        assertEquals(0, charges.successful(owner, Kind.RightClickHarvest));
        assertEquals(2, items[1].getAmount());
        successor.get().retire().get(5, TimeUnit.SECONDS);
    }

}
