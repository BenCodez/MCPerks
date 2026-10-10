package com.bencodez.mcperks.listeners;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.perk.*;
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.userapi.UsageCharges;
import com.bencodez.mcperks.userapi.UsageCharges.Kind;
import com.bencodez.mcperks.data.LocalStatistics;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;
import com.bencodez.simpleapi.folialib.FoliaLib;
class UsageEffectListenerTest {
    @TempDir Path directory;
    private com.bencodez.mcperks.scheduling.OwnerTaskHarness ownerScheduler;
    private MCPerksMain plugin; private Player player; private Perk perk; private PerkHandler handler;
    private UsageCharges charges; private UsageEffectListener listener; private ItemStack[] items;
    private PlayerInventory inventory; private BlockingQueue<Runnable> tasks; private UUID owner; private World world;
    @BeforeEach void setup() throws Exception {
        RefillPlanTest.server(); owner = UUID.randomUUID(); plugin = mock(MCPerksMain.class); player = mock(Player.class);
        world = mock(World.class); when(world.getName()).thenReturn("world"); when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(player.getWorld()).thenReturn(world); when(player.getUniqueId()).thenReturn(owner); when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL); when(player.getLocation()).thenReturn(new Location(world, 4, 64, 4));
        items = new ItemStack[41]; inventory = mock(PlayerInventory.class); when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItem(anyInt())).thenAnswer(i -> items[i.getArgument(0, Integer.class)]);
        when(inventory.getItemInMainHand()).thenAnswer(i -> items[0] == null ? new ItemStack(Material.AIR) : items[0]);
        doAnswer(i -> { items[i.getArgument(0, Integer.class)] = i.getArgument(1); return null; }).when(inventory).setItem(anyInt(), nullable(ItemStack.class));
        ownerScheduler = new com.bencodez.mcperks.scheduling.OwnerTaskHarness(plugin, player); tasks = ownerScheduler.tasks;
        when(plugin.isEnabled()).thenReturn(true); when(plugin.getLogger()).thenReturn(Logger.getLogger("MCPerks-tests"));
        when(plugin.getLocalStatistics()).thenReturn(new LocalStatistics());
        charges = new UsageCharges(directory.resolve("charges.dat")); charges.ready().get(5, TimeUnit.SECONDS); when(plugin.getUsageCharges()).thenReturn(charges);
        perk = mock(Perk.class); when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        when(perk.getInteractionOptions()).thenReturn(new InteractionOptions(true, false, 16, 8, List.of(), List.of(), List.of(), List.of()));
        when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.AutoBlockRefill, Effect.RightClickHarvest, Effect.VeinMining)));
        when(perk.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(owner.toString()))); when(perk.isNotInDisabledWorld(anyString())).thenReturn(true);
        when(perk.getWhitelistedTools()).thenReturn(new ArrayList<>()); when(perk.getWhitelistedBlocks()).thenReturn(new ArrayList<>());
        when(perk.getBlacklistedBlocks()).thenReturn(new ArrayList<>()); when(perk.getRequiredEnchants()).thenReturn(new HashMap<>());
        handler = mock(PerkHandler.class); when(plugin.getPerkHandler()).thenReturn(handler);
        when(handler.effectDecision(any(Effect.class), eq(owner.toString()), anyString())).thenReturn(PerkHandler.Decision.ACTIVE);
        when(handler.getActivePerks()).thenReturn(new java.util.concurrent.CopyOnWriteArrayList<>(List.of(perk)));
        listener = new UsageEffectListener(plugin);
    }
    @AfterEach void close() { charges.close(); }
    private Runnable next() throws Exception { Runnable task = tasks.poll(5, TimeUnit.SECONDS); assertNotNull(task, "owner callback missing"); return task; }
    private void barrier(Kind kind) throws Exception { charges.add(owner, kind, 0).get(5, TimeUnit.SECONDS); }
    private BlockPlaceEvent place() {
        BlockPlaceEvent event = mock(BlockPlaceEvent.class); when(event.getPlayer()).thenReturn(player); when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        when(event.getItemInHand()).thenReturn(new ItemStack(Material.STONE, 1)); return event;
    }
    @Test void actualRefillEntryReservesDurablyDefersUntilConsumptionAndDeduplicatesCallback() throws Exception {
        charges.set(owner, Kind.AutoBlockRefill, 2).get(); items[0] = new ItemStack(Material.STONE, 8); items[40] = new ItemStack(Material.STONE, 1);
        listener.refill(place()); assertEquals(8, items[0].getAmount()); items[40] = null;
        next().run(); Runnable effect = next(); assertEquals(1, charges.get(owner, Kind.AutoBlockRefill));
        effect.run(); effect.run(); barrier(Kind.AutoBlockRefill);
        assertEquals(7, items[0].getAmount()); assertEquals(1, items[40].getAmount()); assertEquals(1, charges.successful(owner, Kind.AutoBlockRefill));
    }
    @Test void unlimitedRefillRemainsUsableWithDepletedOrUnavailableChargedGrantAheadOfIt() throws Exception {
        var unlimited = mock(Perk.class);
        when(unlimited.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.AutoBlockRefill)));
        when(unlimited.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(owner.toString())));
        when(unlimited.isNotInDisabledWorld(anyString())).thenReturn(true);
        when(unlimited.getInteractionOptions()).thenReturn(new InteractionOptions(false, false, 16, 8, List.of(), List.of(), List.of(), List.of()));
        when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>(List.of(perk, unlimited)));
        items[0] = new ItemStack(Material.STONE, 8); listener.refill(place()); next().run(); next().run(); barrier(Kind.AutoBlockRefill);
        assertEquals(7, items[0].getAmount()); assertEquals(1, items[40].getAmount()); assertEquals(0, charges.get(owner, Kind.AutoBlockRefill));
        assertEquals(1, charges.successful(owner, Kind.AutoBlockRefill));
        charges.close(); items[40] = null;
        listener.refill(place()); next().run(); next().run();
        assertEquals(6, items[0].getAmount()); assertEquals(1, items[40].getAmount());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void alternativeUnlimitedHarvestMustMatchItsOwnCropRulesAndNeverConsumeChargedGrant(boolean matchingCrop) throws Exception {
        var unlimited = mock(Perk.class);
        when(unlimited.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.RightClickHarvest)));
        when(unlimited.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(owner.toString())));
        when(unlimited.isNotInDisabledWorld(anyString())).thenReturn(true);
        when(unlimited.getWhitelistedTools()).thenReturn(new ArrayList<>());
        when(unlimited.getWhitelistedBlocks()).thenReturn(new ArrayList<>());
        when(unlimited.getBlacklistedBlocks()).thenReturn(new ArrayList<>());
        when(unlimited.getRequiredEnchants()).thenReturn(new HashMap<>());
        when(unlimited.getInteractionOptions()).thenReturn(new InteractionOptions(false, false, 16, 8, List.of(), List.of(),
                List.of(matchingCrop ? "WHEAT" : "CARROTS"), List.of()));
        when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>(List.of(perk, unlimited)));
        charges.set(owner, Kind.RightClickHarvest, 2).get(5, TimeUnit.SECONDS);
        items[0] = new ItemStack(Material.STONE); items[1] = new ItemStack(Material.WHEAT_SEEDS, 3);
        Block block = crop(); when(player.breakBlock(block)).thenAnswer(i -> { when(block.getType()).thenReturn(Material.AIR); return true; });
        listener.harvest(harvest(block)); next().run(); next().run(); barrier(Kind.RightClickHarvest);
        assertEquals(2, items[1].getAmount()); assertEquals(matchingCrop ? 2 : 1, charges.get(owner, Kind.RightClickHarvest));
        assertEquals(1, charges.successful(owner, Kind.RightClickHarvest)); verify(player, times(1)).breakBlock(block);
        verify(block).setBlockData(any(Ageable.class));
    }

    @Test void depletedAndPrecisionSuppressedRefillsNeverMoveItemsOrConsumeCharges() throws Exception {
        items[0] = new ItemStack(Material.STONE, 8); listener.refill(place()); next().run(); barrier(Kind.AutoBlockRefill);
        assertEquals(8, items[0].getAmount()); assertNull(tasks.poll(50, TimeUnit.MILLISECONDS));
        charges.set(owner, Kind.AutoBlockRefill, 2).get(); when(handler.effectDecision(eq(Effect.AutoBlockRefill), anyString(), anyString())).thenReturn(PerkHandler.Decision.PRECISION_SUPPRESSED);
        listener.refill(place()); assertNull(tasks.poll(50, TimeUnit.MILLISECONDS)); assertEquals(2, charges.get(owner, Kind.AutoBlockRefill));
    }
    @Test void reloadBeforeInitialDeferredCallbackCannotAdoptSuccessorGeneration() throws Exception {
        charges.set(owner,Kind.AutoBlockRefill,2).get(); items[0]=new ItemStack(Material.STONE,8);
        listener.refill(place()); Runnable old=next(); listener.invalidate();
        old.run(); barrier(Kind.AutoBlockRefill);
        assertEquals(2,charges.get(owner,Kind.AutoBlockRefill)); assertEquals(8,items[0].getAmount());
        assertNull(items[40]); assertNull(tasks.poll(50,TimeUnit.MILLISECONDS));
        listener.refill(place()); next().run(); next().run(); barrier(Kind.AutoBlockRefill);
        assertEquals(1,charges.get(owner,Kind.AutoBlockRefill)); assertEquals(7,items[0].getAmount());
        assertEquals(1,items[40].getAmount());
    }
    @Test void reloadAndInventoryChangesBeforeOwnerCallbackReleaseProvedUnusedReservation() throws Exception {
        charges.set(owner, Kind.AutoBlockRefill, 2).get(); items[0] = new ItemStack(Material.STONE, 8);
        listener.refill(place()); next().run(); Runnable effect = next(); listener.invalidate(); effect.run(); barrier(Kind.AutoBlockRefill);
        assertEquals(2, charges.get(owner, Kind.AutoBlockRefill)); assertEquals(8, items[0].getAmount());
        listener.refill(place()); next().run(); effect = next(); items[0].setAmount(7); effect.run(); barrier(Kind.AutoBlockRefill);
        assertEquals(2, charges.get(owner, Kind.AutoBlockRefill)); assertEquals(0, charges.successful(owner, Kind.AutoBlockRefill));
    }
    private PlayerInteractEvent harvest(Block block) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class); when(event.getPlayer()).thenReturn(player); when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK); when(event.getClickedBlock()).thenReturn(block); when(event.useInteractedBlock()).thenReturn(org.bukkit.event.Event.Result.DEFAULT); return event;
    }
    private Block crop() {
        Block block = mock(Block.class); when(block.getWorld()).thenReturn(world); when(block.getX()).thenReturn(4); when(block.getY()).thenReturn(64); when(block.getZ()).thenReturn(4);
        when(block.getLocation()).thenReturn(new Location(world, 4, 64, 4)); when(block.getType()).thenReturn(Material.WHEAT);
        Ageable age = mock(Ageable.class); when(age.getAge()).thenReturn(7); when(age.getMaximumAge()).thenReturn(7); when(age.clone()).thenReturn(age); when(block.getBlockData()).thenReturn(age); return block;
    }
    @Test void protectedHarvestRestoresExactSeedAndRefundsCharge() throws Exception {
        charges.set(owner, Kind.RightClickHarvest, 2).get(); items[0] = new ItemStack(Material.STONE); items[1] = new ItemStack(Material.WHEAT_SEEDS, 3);
        Block block = crop(); when(player.breakBlock(block)).thenReturn(false); listener.harvest(harvest(block)); next().run(); next().run(); barrier(Kind.RightClickHarvest);
        assertEquals(3, items[1].getAmount()); assertEquals(2, charges.get(owner, Kind.RightClickHarvest)); assertEquals(0, charges.successful(owner, Kind.RightClickHarvest));
        verify(block, never()).setBlockData(any());
    }
    @Test void successfulNativeHarvestConsumesOneSeedReplantsExactCropAndIgnoresOffhand() throws Exception {
        charges.set(owner, Kind.RightClickHarvest, 2).get(); items[0] = new ItemStack(Material.STONE); items[1] = new ItemStack(Material.WHEAT_SEEDS, 3);
        Block block = crop(); when(player.breakBlock(block)).thenAnswer(i -> { when(block.getType()).thenReturn(Material.AIR); listener.vein(new BlockBreakEvent(block, player)); return true; });
        var offhand = harvest(block); when(offhand.getHand()).thenReturn(EquipmentSlot.OFF_HAND); listener.harvest(offhand); assertTrue(tasks.isEmpty());
        listener.harvest(harvest(block)); next().run(); next().run(); barrier(Kind.RightClickHarvest);
        assertEquals(2, items[1].getAmount()); assertEquals(1, charges.get(owner, Kind.RightClickHarvest)); assertEquals(1, charges.successful(owner, Kind.RightClickHarvest));
        verify(block).setBlockData(any(Ageable.class)); verify((Ageable) block.getBlockData()).setAge(0); verify(player, times(1)).breakBlock(block);
    }
    @Test void ambiguousSharedChargeOwnerIsRejectedWhileLegacyUnlimitedRemainsAvailable() throws Exception {
        when(perk.getPerkType()).thenReturn(PerkSystemType.ALL); items[0] = new ItemStack(Material.STONE, 8);
        listener.refill(place()); assertTrue(tasks.isEmpty());
        when(perk.getInteractionOptions()).thenReturn(new InteractionOptions(false, false, 16, 8, List.of(), List.of(), List.of(), List.of()));
        listener.refill(place()); next().run(); next().run(); barrier(Kind.AutoBlockRefill); assertEquals(7, items[0].getAmount());
        assertEquals(0, charges.get(owner, Kind.AutoBlockRefill));
    }
    private Block oreBlock(int x, int y, int z, Material material) {
        Block block = mock(Block.class); when(block.getWorld()).thenReturn(world); when(block.getX()).thenReturn(x); when(block.getY()).thenReturn(y); when(block.getZ()).thenReturn(z);
        when(block.getLocation()).thenReturn(new Location(world, x, y, z)); when(block.getType()).thenReturn(material); return block;
    }
    @Test void actualVeinUsesPartialBudgetNativeProtectionAndNoRecursiveAdditionalBatch() throws Exception {
        charges.set(owner, Kind.VeinMining, 1).get(); items[0] = new ItemStack(Material.DIAMOND_PICKAXE);
        when(world.getMinHeight()).thenReturn(0); when(world.getMaxHeight()).thenReturn(256);
        Map<String, Block> blocks = new HashMap<>(); Block origin = oreBlock(4,64,4,Material.DIAMOND_ORE), denied = oreBlock(5,64,4,Material.DIAMOND_ORE), allowed = oreBlock(6,64,4,Material.DIAMOND_ORE);
        blocks.put("4,64,4", origin); blocks.put("5,64,4", denied); blocks.put("6,64,4", allowed);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(i -> {
            int x=i.getArgument(0), y=i.getArgument(1), z=i.getArgument(2);
            return blocks.computeIfAbsent(x+","+y+","+z, k -> oreBlock(x,y,z,Material.AIR));
        });
        when(player.breakBlock(denied)).thenReturn(false);
        when(player.breakBlock(allowed)).thenAnswer(i -> { listener.vein(new BlockBreakEvent(allowed,player)); when(allowed.getType()).thenReturn(Material.AIR); return true; });
        listener.vein(new BlockBreakEvent(origin, player)); when(origin.getType()).thenReturn(Material.AIR); next().run(); next().run(); barrier(Kind.VeinMining);
        verify(player).breakBlock(denied); verify(player).breakBlock(allowed); assertTrue(tasks.isEmpty());
        assertEquals(0, charges.get(owner, Kind.VeinMining)); assertEquals(1, charges.successful(owner, Kind.VeinMining));
    }
    @Test void unloadedAdjacentChunkIsNeverObtainedAndToolBreakStopsAdditionalNativeBreaks() throws Exception {
        charges.set(owner, Kind.VeinMining, 2).get(); items[0] = new ItemStack(Material.DIAMOND_PICKAXE);
        when(world.getMinHeight()).thenReturn(0); when(world.getMaxHeight()).thenReturn(256);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(i -> i.getArgument(0,Integer.class) == 0 && i.getArgument(1,Integer.class) == 0);
        Map<String, Block> blocks = new HashMap<>(); Block origin = oreBlock(4,64,4,Material.DIAMOND_ORE), first = oreBlock(5,64,4,Material.DIAMOND_ORE), second = oreBlock(6,64,4,Material.DIAMOND_ORE);
        blocks.put("4,64,4",origin); blocks.put("5,64,4",first); blocks.put("6,64,4",second);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(i -> { int x=i.getArgument(0),y=i.getArgument(1),z=i.getArgument(2); return blocks.computeIfAbsent(x+","+y+","+z,k -> oreBlock(x,y,z,Material.AIR)); });
        when(player.breakBlock(first)).thenAnswer(i -> { items[0] = new ItemStack(Material.AIR); return true; });
        listener.vein(new BlockBreakEvent(origin,player)); when(origin.getType()).thenReturn(Material.AIR); next().run(); next().run(); barrier(Kind.VeinMining);
        verify(player,never()).breakBlock(second); assertEquals(1,charges.get(owner,Kind.VeinMining));
        // An ore at the loaded-chunk edge cannot cause access to the unloaded neighbour.
        Block edge=oreBlock(15,64,4,Material.DIAMOND_ORE); blocks.put("15,64,4",edge); items[0]=new ItemStack(Material.DIAMOND_PICKAXE);
        listener.vein(new BlockBreakEvent(edge,player)); verify(world,never()).getBlockAt(eq(16),anyInt(),anyInt());
    }

    @Test void retiredExecutionReleasesProvedUnusedChargesAndAdmissionForReconnect() throws Exception {
        charges.set(owner,Kind.AutoBlockRefill,2).get(); items[0]=new ItemStack(Material.STONE,8);
        listener.refill(place()); next().run(); Runnable execution=next(); ownerScheduler.retirement.get(execution).run(); execution.run(); barrier(Kind.AutoBlockRefill);
        assertEquals(2,charges.get(owner,Kind.AutoBlockRefill)); assertEquals(8,items[0].getAmount());
        listener.refill(place()); next().run(); next().run(); barrier(Kind.AutoBlockRefill); assertEquals(1,charges.get(owner,Kind.AutoBlockRefill)); assertEquals(7,items[0].getAmount());
    }
    @Test void deniedHarvestReturnsExactSeedMetadataWhenItsOriginalSlotChanges() throws Exception {
        charges.set(owner,Kind.RightClickHarvest,1).get(); items[0]=new ItemStack(Material.STONE); items[1]=new ItemStack(Material.WHEAT_SEEDS,3);
        var metadata=mock(org.bukkit.inventory.meta.Damageable.class); when(metadata.clone()).thenReturn(metadata); items[1].setItemMeta(metadata); ItemStack expected=items[1].clone(); assertEquals(items[1], expected); expected.setAmount(1);
        when(inventory.addItem(any(ItemStack.class))).thenAnswer(i -> new HashMap<>(Map.of(0,i.getArgument(0,ItemStack.class))));
        Block block=crop(); when(player.breakBlock(block)).thenAnswer(i -> { items[1]=new ItemStack(Material.DIRT); return false; });
        listener.harvest(harvest(block)); next().run(); next().run(); barrier(Kind.RightClickHarvest);
        verify(player).breakBlock(block); verify(inventory).addItem(any(ItemStack.class)); var restored=org.mockito.ArgumentCaptor.forClass(ItemStack.class); verify(world).dropItemNaturally(any(Location.class),restored.capture()); assertEquals(expected,restored.getValue()); assertEquals(1,charges.get(owner,Kind.RightClickHarvest));
    }
    @Test void originChunkIsCheckedBeforeDeferredBlockTypeReadAndNoChargeIsSpent() throws Exception {
        charges.set(owner,Kind.VeinMining,1).get(); items[0]=new ItemStack(Material.DIAMOND_PICKAXE); when(world.getMaxHeight()).thenReturn(256);
        Block origin=oreBlock(4,64,4,Material.DIAMOND_ORE), extra=oreBlock(5,64,4,Material.DIAMOND_ORE);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(i -> { int x=i.getArgument(0),y=i.getArgument(1),z=i.getArgument(2); return x==4&&y==64&&z==4 ? origin : x==5&&y==64&&z==4 ? extra : oreBlock(x,y,z,Material.AIR); });
        listener.vein(new BlockBreakEvent(origin,player)); next().run(); Runnable effect=next(); clearInvocations(origin); when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(false); effect.run(); barrier(Kind.VeinMining);
        verify(origin,never()).getType(); verify(player,never()).breakBlock(any()); assertEquals(1,charges.get(owner,Kind.VeinMining));
    }
    @Test void precisionTracksCompletedSneakingStateAndIgnoresCancelledTransitions() throws Exception {
        var event=new org.bukkit.event.player.PlayerToggleSneakEvent(player,true); event.setCancelled(true); listener.sneak(event); assertTrue(tasks.isEmpty());
        event.setCancelled(false); listener.sneak(event); when(player.isSneaking()).thenReturn(false); next().run(); verify(handler).setPrecisionSneaking(owner,false);
        listener.sneak(event); when(player.isSneaking()).thenReturn(true); next().run(); verify(handler).setPrecisionSneaking(owner,true);
    }

    @Test void finalInteractionProtectionDenialPreventsReservationSeedsAndNativeBreakEvenWhenBreakingIsAllowed() throws Exception {
        charges.set(owner,Kind.RightClickHarvest,1).get(); items[0]=new ItemStack(Material.STONE); items[1]=new ItemStack(Material.WHEAT_SEEDS,3); Block block=crop(); when(player.breakBlock(block)).thenReturn(true);
        var event=harvest(block); listener.harvest(event); verify(event,never()).setUseInteractedBlock(any()); verify(event,never()).setUseItemInHand(any());
        // A later protection listener denies right-click interaction without denying ordinary breaking.
        when(event.useInteractedBlock()).thenReturn(org.bukkit.event.Event.Result.DENY); next().run(); barrier(Kind.RightClickHarvest);
        assertEquals(3,items[1].getAmount()); assertEquals(1,charges.get(owner,Kind.RightClickHarvest)); assertEquals(0,charges.successful(owner,Kind.RightClickHarvest)); assertTrue(tasks.isEmpty()); assertEquals(LocalStatistics.Skip.INTERACTION_DENIED,listener.recentSkip(owner).reason());
        var cancelled=harvest(block); listener.harvest(cancelled); when(cancelled.isCancelled()).thenReturn(true); next().run(); barrier(Kind.RightClickHarvest);
        var itemDenied=harvest(block); listener.harvest(itemDenied); when(itemDenied.useItemInHand()).thenReturn(org.bukkit.event.Event.Result.DENY); next().run(); barrier(Kind.RightClickHarvest);
        assertEquals(3,items[1].getAmount()); assertEquals(1,charges.get(owner,Kind.RightClickHarvest)); verify(player,never()).breakBlock(any()); verify(block,never()).setBlockData(any());
    }

    @Test void deferredHarvestAndVeinModeChangesNeverBreakSeedOrSpendCharges() throws Exception {
        charges.set(owner,Kind.RightClickHarvest,1).get(); items[0]=new ItemStack(Material.STONE); items[1]=new ItemStack(Material.WHEAT_SEEDS,3); Block crop=crop();
        listener.harvest(harvest(crop)); next().run(); Runnable harvest=next(); when(player.getGameMode()).thenReturn(GameMode.CREATIVE); harvest.run(); barrier(Kind.RightClickHarvest);
        assertEquals(3,items[1].getAmount()); assertEquals(1,charges.get(owner,Kind.RightClickHarvest)); verify(crop,never()).setBlockData(any());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL); charges.set(owner,Kind.VeinMining,1).get(); items[0]=new ItemStack(Material.DIAMOND_PICKAXE); when(world.getMaxHeight()).thenReturn(256);
        Block origin=oreBlock(4,64,4,Material.DIAMOND_ORE), extra=oreBlock(5,64,4,Material.DIAMOND_ORE);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(i -> { int x=i.getArgument(0),y=i.getArgument(1),z=i.getArgument(2); return x==4&&y==64&&z==4?origin:x==5&&y==64&&z==4?extra:oreBlock(x,y,z,Material.AIR); });
        listener.vein(new BlockBreakEvent(origin,player)); when(origin.getType()).thenReturn(Material.AIR); next().run(); Runnable vein=next(); when(player.getGameMode()).thenReturn(GameMode.CREATIVE); vein.run(); barrier(Kind.VeinMining);
        assertEquals(1,charges.get(owner,Kind.VeinMining)); verify(player,never()).breakBlock(any()); assertEquals(0,charges.successful(owner,Kind.VeinMining));
    }
    @Test void unavailableBankCannotSupplyChargePolicyAheadOfAvailableUnlimitedPerk() throws Exception {
        var banks=mock(BankedPerks.class); when(plugin.getBankedPerks()).thenReturn(banks); when(banks.available(perk)).thenReturn(false);
        Perk unlimited=mock(Perk.class); when(unlimited.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.AutoBlockRefill))); when(unlimited.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(owner.toString()))); when(unlimited.isNotInDisabledWorld("world")).thenReturn(true);
        when(unlimited.getInteractionOptions()).thenReturn(new InteractionOptions(false,false,16,8,List.of(),List.of(),List.of(),List.of())); when(banks.available(unlimited)).thenReturn(true); handler.getActivePerks().add(unlimited);
        items[0]=new ItemStack(Material.STONE,8); listener.refill(place()); next().run(); next().run(); barrier(Kind.AutoBlockRefill);
        assertEquals(7,items[0].getAmount()); assertEquals(0,charges.get(owner,Kind.AutoBlockRefill)); assertEquals(1,charges.successful(owner,Kind.AutoBlockRefill));
    }
    @Test void gracefulRetirementDrainsQueuedUnusedReservationBeforeClosingAndRestartingLedger() throws Exception {
        charges.close(); Path path=directory.resolve("shutdown.dat"); CountDownLatch publishing=new CountDownLatch(1), release=new CountDownLatch(1); var block=new java.util.concurrent.atomic.AtomicBoolean();
        charges=com.bencodez.mcperks.userapi.UsageChargesFixture.create(path,(staged,destination)->{
            if(block.get()) { publishing.countDown(); try { if(!release.await(5,TimeUnit.SECONDS)) throw new java.io.IOException("test timeout"); } catch(InterruptedException e) { throw new java.io.IOException(e); } }
            com.bencodez.mcperks.data.DurableFeatureFiles.publish(staged,destination);
        }); when(plugin.getUsageCharges()).thenReturn(charges); listener=new UsageEffectListener(plugin); charges.set(owner,Kind.AutoBlockRefill,2).get(); items[0]=new ItemStack(Material.STONE,8);
        block.set(true); listener.refill(place()); next().run(); assertTrue(publishing.await(5,TimeUnit.SECONDS)); CompletableFuture<Void> drained=listener.retireAndClose(); assertFalse(drained.isDone());
        block.set(false); release.countDown(); drained.get(5,TimeUnit.SECONDS); assertEquals(2,charges.get(owner,Kind.AutoBlockRefill)); assertEquals(8,items[0].getAmount()); assertTrue(tasks.isEmpty());
        try(UsageCharges restarted=new UsageCharges(path)) { restarted.ready().get(5,TimeUnit.SECONDS); assertEquals(2,restarted.get(owner,Kind.AutoBlockRefill)); assertEquals(0,restarted.successful(owner,Kind.AutoBlockRefill)); }
    }
    @Test void retirementCancelsAlreadyScheduledUnusedEffectWithoutWaitingForRetiredOwner() throws Exception {
        charges.set(owner,Kind.AutoBlockRefill,1).get(); items[0]=new ItemStack(Material.STONE,8); listener.refill(place()); next().run(); Runnable queued=next();
        listener.retireAndClose().get(5,TimeUnit.SECONDS); queued.run(); ownerScheduler.retirement.get(queued).run(); assertEquals(1,charges.get(owner,Kind.AutoBlockRefill)); assertEquals(8,items[0].getAmount());
    }

    @Test void predecessorListenerNeverAdmitsOrCompletesAgainstSuccessorLedgerDuringReload() throws Exception {
        charges.set(owner,Kind.AutoBlockRefill,1).get(); items[0]=new ItemStack(Material.STONE,8); listener.refill(place());
        try(UsageCharges successor=new UsageCharges(directory.resolve("charges.dat"))) {
            when(plugin.getUsageCharges()).thenReturn(successor); assertFalse(successor.ready().isDone());
            next().run(); next().run(); listener.retireAndClose().get(5,TimeUnit.SECONDS); successor.ready().get(5,TimeUnit.SECONDS);
            assertEquals(7,items[0].getAmount()); assertEquals(0,successor.get(owner,Kind.AutoBlockRefill)); assertEquals(1,successor.successful(owner,Kind.AutoBlockRefill));
        }
    }

    @Test void unlimitedRefillsContinueWhileStatisticsDiskPublicationIsBlocked() throws Exception {
        charges.close(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        charges=com.bencodez.mcperks.userapi.UsageChargesFixture.create(directory.resolve("stats.dat"),(staged,destination)-> {
            entered.countDown(); try { if(!release.await(5,TimeUnit.SECONDS)) throw new java.io.IOException("timeout"); }
            catch(InterruptedException error) { throw new java.io.IOException(error); }
            com.bencodez.mcperks.data.DurableFeatureFiles.publish(staged,destination);
        });
        charges.ready().get(5,TimeUnit.SECONDS); when(plugin.getUsageCharges()).thenReturn(charges);
        when(perk.getInteractionOptions()).thenReturn(new InteractionOptions(false,false,16,8,List.of(),List.of(),List.of(),List.of()));
        listener=new UsageEffectListener(plugin); items[0]=new ItemStack(Material.STONE,8);
        try {
            listener.refill(place()); next().run(); next().run(); assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals(7,items[0].getAmount());
            listener.refill(place()); items[40]=null; next().run(); next().run();
            assertEquals(6,items[0].getAmount()); assertEquals(1,items[40].getAmount());
        } finally { release.countDown(); }
        barrier(Kind.AutoBlockRefill); assertEquals(2,charges.successful(owner,Kind.AutoBlockRefill));
    }
    @Test void veinRejectsChangedHeldSlotBeforeAnyAdditionalBlockOrCharge() throws Exception {
        Runnable execute=queuedVein(); when(inventory.getHeldItemSlot()).thenReturn(1);
        execute.run(); barrier(Kind.VeinMining); verify(player,never()).breakBlock(any());
        assertEquals(2,charges.get(owner,Kind.VeinMining)); assertEquals(0,charges.successful(owner,Kind.VeinMining));
    }
    private Runnable queuedVein() throws Exception {
        charges.set(owner,Kind.VeinMining,2).get(); items[0]=new ItemStack(Material.DIAMOND_PICKAXE);
        when(world.getMinHeight()).thenReturn(0); when(world.getMaxHeight()).thenReturn(256);
        Block origin=oreBlock(4,64,4,Material.DIAMOND_ORE), extra=oreBlock(5,64,4,Material.DIAMOND_ORE);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(i-> {
            int x=i.getArgument(0), y=i.getArgument(1), z=i.getArgument(2);
            return x==4&&y==64&&z==4?origin:x==5&&y==64&&z==4?extra:oreBlock(x,y,z,Material.AIR);
        });
        when(player.breakBlock(extra)).thenReturn(true);
        listener.vein(new BlockBreakEvent(origin,player)); when(origin.getType()).thenReturn(Material.AIR);
        next().run(); return next();
    }
    private EffectListeners legacyBonus(Effect effect,Material material) {
        when(plugin.getUsageEffects()).thenReturn(listener);
        var effects=mock(com.bencodez.mcperks.perk.EffectHandler.class); when(plugin.getEffectHandler()).thenReturn(effects);
        when(effects.getBlockBreakEvents()).thenReturn(new ArrayList<>());
        var bonus=mock(Perk.class); when(bonus.getEffects()).thenReturn(new ArrayList<>(List.of(effect)));
        when(bonus.getBlocks()).thenReturn(new ArrayList<>(List.of(material))); when(bonus.getIncreasePercent(effect)).thenReturn(200.0);
        when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>(List.of(perk,bonus)));
        when(handler.effectActive(eq(effect),eq(owner.toString()),eq("world"))).thenReturn(true);
        when(bonus.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(owner.toString())));
        when(bonus.isNotInDisabledWorld("world")).thenReturn(true);
        when(handler.effectAllowed(bonus,effect,owner.toString(),"world")).thenReturn(true);
        when(player.getItemInHand()).thenAnswer(i -> inventory.getItemInMainHand());
        return new EffectListeners(plugin);
    }
    @Test void actualNativeVeinBreakRetainsFortuneDropsWithoutRecursingIntoAutomation() throws Exception {
        Runnable execute=queuedVein(); Block extra=world.getBlockAt(5,64,4);
        var legacy=legacyBonus(Effect.Fortune,Material.DIAMOND_ORE);
        when(extra.getDrops(any(ItemStack.class))).thenAnswer(i -> List.of(new ItemStack(Material.DIAMOND,2)));
        when(player.breakBlock(extra)).thenAnswer(i -> {
            assertTrue(listener.isNativeBreak(owner)); var event=new BlockBreakEvent(extra,player);
            legacy.onBlockBreak(event); listener.vein(event); return true;
        });
        execute.run(); barrier(Kind.VeinMining);
        verify(world).dropItemNaturally(eq(extra.getLocation()),argThat(item -> item.getType()==Material.DIAMOND && item.getAmount()==4));
        verify(player,times(1)).breakBlock(extra); assertFalse(listener.isNativeBreak(owner));
        verify(handler,never()).effectActive(eq(Effect.AutoPlant),anyString(),anyString());
        verify(handler,never()).effectActive(eq(Effect.IncreaseMiningArea),anyString(),anyString());
        assertEquals(1,charges.successful(owner,Kind.VeinMining)); assertTrue(tasks.isEmpty());
    }
    @Test void actualNativeRightClickHarvestRetainsFarmerDropsWithoutAutoPlantOrRecursiveVein() throws Exception {
        charges.set(owner,Kind.RightClickHarvest,2).get(); items[0]=new ItemStack(Material.STONE); items[1]=new ItemStack(Material.WHEAT_SEEDS,3);
        Block block=crop(); var legacy=legacyBonus(Effect.Farmer,Material.WHEAT);
        when(block.getDrops(any(ItemStack.class))).thenAnswer(i -> List.of(new ItemStack(Material.WHEAT,2)));
        when(player.breakBlock(block)).thenAnswer(i -> {
            assertTrue(listener.isNativeBreak(owner)); var event=new BlockBreakEvent(block,player);
            legacy.onBlockBreak(event); listener.vein(event); when(block.getType()).thenReturn(Material.AIR); return true;
        });
        listener.harvest(harvest(block)); next().run(); next().run(); barrier(Kind.RightClickHarvest);
        verify(world).dropItemNaturally(eq(block.getLocation()),argThat(item -> item.getType()==Material.WHEAT && item.getAmount()==4));
        verify(player,times(1)).breakBlock(block); verify(block).setBlockData(any()); assertFalse(listener.isNativeBreak(owner));
        verify(handler,never()).effectActive(eq(Effect.AutoPlant),anyString(),anyString());
        verify(handler,never()).effectActive(eq(Effect.IncreaseMiningArea),anyString(),anyString());
        assertEquals(1,charges.successful(owner,Kind.RightClickHarvest)); assertEquals(2,items[1].getAmount()); assertTrue(tasks.isEmpty());
    }
    @Test void veinRejectsReplacementToolWithSameMaterialAndEnchantmentsButDifferentMetadata() throws Exception {
        Runnable execute=queuedVein(); var custom=mock(org.bukkit.inventory.meta.Damageable.class);
        when(custom.clone()).thenReturn(custom); when(custom.getEnchants()).thenReturn(Map.of());
        when(custom.getDisplayName()).thenReturn("Different custom tool");
        items[0]=new ItemStack(Material.DIAMOND_PICKAXE); items[0].setItemMeta(custom);
        execute.run(); barrier(Kind.VeinMining); verify(player,never()).breakBlock(any());
        assertEquals(2,charges.get(owner,Kind.VeinMining)); assertEquals(0,charges.successful(owner,Kind.VeinMining));
    }

    @Test void harvestRejectsEqualToolInAnotherSelectedSlotBeforeSeedNativeBreakOrCharge() throws Exception {
        charges.set(owner,Kind.RightClickHarvest,1).get(); items[0]=new ItemStack(Material.STONE);
        items[1]=items[0].clone(); items[2]=new ItemStack(Material.WHEAT_SEEDS,3); Block block=crop();
        when(player.breakBlock(block)).thenReturn(true);
        listener.harvest(harvest(block)); next().run(); Runnable execution=next();
        when(inventory.getHeldItemSlot()).thenReturn(1); when(inventory.getItemInMainHand()).thenAnswer(i->items[1]);
        execution.run(); barrier(Kind.RightClickHarvest);
        assertEquals(3,items[2].getAmount()); assertEquals(new ItemStack(Material.STONE),items[0]);
        assertEquals(1,charges.get(owner,Kind.RightClickHarvest)); assertEquals(0,charges.successful(owner,Kind.RightClickHarvest));
        verify(player,never()).breakBlock(any()); verify(block,never()).setBlockData(any());
    }

}
