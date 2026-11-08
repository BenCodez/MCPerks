package com.bencodez.mcperks.tests.listeners;
import static com.bencodez.mcperks.tests.TestAccess.*;
import com.bencodez.mcperks.listeners.*;
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
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.userapi.UsageCharges;
import com.bencodez.mcperks.userapi.UsageCharges.Kind;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;
import com.bencodez.simpleapi.folialib.FoliaLib;
class UsageEffectListenerTest {
    @TempDir Path directory;
    private com.bencodez.mcperks.tests.scheduling.OwnerTaskHarness ownerScheduler;
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
        when(plugin.isEnabled()).thenReturn(true);
        ownerScheduler = new com.bencodez.mcperks.tests.scheduling.OwnerTaskHarness(plugin, player); tasks = ownerScheduler.tasks;
        when(plugin.getServer().getPlayer(owner)).thenReturn(player);
         when(plugin.getLogger()).thenReturn(Logger.getLogger("MCPerks-tests"));
        charges = new UsageCharges(directory.resolve("charges.dat")); charges.ready().get(5, TimeUnit.SECONDS); when(plugin.getUsageCharges()).thenReturn(charges);
        perk = mock(Perk.class); when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        when(perk.isUsageChargesEnabled()).thenReturn(true);
        when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.AutoBlockRefill)));
        when(perk.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(owner.toString()))); when(perk.isNotInDisabledWorld(anyString())).thenReturn(true);
        when(perk.getWhitelistedTools()).thenReturn(new ArrayList<>()); when(perk.getWhitelistedBlocks()).thenReturn(new ArrayList<>());
        when(perk.getBlacklistedBlocks()).thenReturn(new ArrayList<>()); when(perk.getRequiredEnchants()).thenReturn(new HashMap<>());
        handler = mock(PerkHandler.class); when(plugin.getPerkHandler()).thenReturn(handler);
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

    @Test void ambiguousSharedChargeOwnerIsRejectedWhileLegacyUnlimitedRemainsAvailable() throws Exception {
        when(perk.getPerkType()).thenReturn(PerkSystemType.ALL); items[0] = new ItemStack(Material.STONE, 8);
        listener.refill(place()); assertTrue(tasks.isEmpty());
        when(perk.isUsageChargesEnabled()).thenReturn(false);
        listener.refill(place()); next().run(); next().run(); barrier(Kind.AutoBlockRefill); assertEquals(7, items[0].getAmount());
        assertEquals(0, charges.get(owner, Kind.AutoBlockRefill));
    }

    @Test void retiredExecutionReleasesProvedUnusedChargesAndAdmissionForReconnect() throws Exception {
        charges.set(owner,Kind.AutoBlockRefill,2).get(); items[0]=new ItemStack(Material.STONE,8);
        listener.refill(place()); next().run(); Runnable execution=next(); ownerScheduler.retirement.get(execution).run(); execution.run(); barrier(Kind.AutoBlockRefill);
        assertEquals(2,charges.get(owner,Kind.AutoBlockRefill)); assertEquals(8,items[0].getAmount());
        listener.refill(place()); next().run(); next().run(); barrier(Kind.AutoBlockRefill); assertEquals(1,charges.get(owner,Kind.AutoBlockRefill)); assertEquals(7,items[0].getAmount());
    }

    private Object capture(Player actor) throws Exception {
        var method = UsageEffectListener.class.getDeclaredMethod("capture", Player.class);
        method.setAccessible(true);
        return method.invoke(listener, actor);
    }
    private Player joinedSuccessor() {
        Player next = mock(Player.class);
        when(next.getUniqueId()).thenReturn(owner);
        when(next.isOnline()).thenReturn(true); when(next.isValid()).thenReturn(true);
        when(plugin.getServer().getPlayer(owner)).thenReturn(next);
        var event = mock(org.bukkit.event.player.PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(next);
        listener.join(event);
        return next;
    }
    private void lateQuit(Player actor) {
        var event = mock(org.bukkit.event.player.PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(actor);
        listener.quit(event);
    }

    @Test void distinctSameUuidJoinRejectsOldDeferredEntryBeforeReservationAndLateQuit() throws Exception {
        charges.set(owner, Kind.AutoBlockRefill, 2).get(); items[0] = new ItemStack(Material.STONE, 8);
        Object oldScope = capture(player);
        listener.refill(place()); Runnable deferred = next();
        Player successor = joinedSuccessor(); Object nextScope = capture(successor);
        assertNotSame(oldScope, nextScope); assertNotSame(player, successor);
        lateQuit(player); deferred.run(); barrier(Kind.AutoBlockRefill);
        assertSame(nextScope, capture(successor));
        assertNull(capture(player), "the obsolete Player cannot claim the current UUID session");
        assertEquals(2, charges.get(owner, Kind.AutoBlockRefill));
        assertEquals(8, items[0].getAmount()); assertNull(items[40]);
        assertNull(tasks.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test void distinctSameUuidJoinAfterReservationRefundsOnceAndFencesOldExecution() throws Exception {
        charges.set(owner, Kind.AutoBlockRefill, 2).get(); items[0] = new ItemStack(Material.STONE, 8);
        listener.refill(place()); next().run(); Runnable oldExecution = next();
        assertEquals(1, charges.get(owner, Kind.AutoBlockRefill));
        Player successor = joinedSuccessor(); Object nextScope = capture(successor);
        lateQuit(player); oldExecution.run(); ownerScheduler.retirement.get(oldExecution).run();
        oldExecution.run(); barrier(Kind.AutoBlockRefill);
        assertSame(nextScope, capture(successor));
        assertEquals(2, charges.get(owner, Kind.AutoBlockRefill));
        assertEquals(0, charges.successful(owner, Kind.AutoBlockRefill));
        assertEquals(8, items[0].getAmount()); assertNull(items[40]);
    }

    @Test void gracefulRetirementDrainsQueuedUnusedReservationBeforeClosingAndRestartingLedger() throws Exception {
        charges.close(); Path path=directory.resolve("shutdown.dat"); CountDownLatch publishing=new CountDownLatch(1), release=new CountDownLatch(1); var block=new java.util.concurrent.atomic.AtomicBoolean();
        charges=com.bencodez.mcperks.tests.userapi.UsageChargesFixture.create(path,(staged,destination)->{
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
    private void setPluginField(String name, Object value) throws Exception {
        var field = MCPerksMain.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    @Test void productionReloadInvalidatesQueuedRefillBeforeWaitingForStorage() throws Exception {
        charges.set(owner, Kind.AutoBlockRefill, 2).get();
        items[0] = new ItemStack(Material.STONE, 8);
        listener.refill(place());
        Runnable oldOwnerCallback = next();
        var storage = mock(com.bencodez.mcperks.data.OrderedStorage.class);
        var pendingStorage = new CompletableFuture<Void>();
        doReturn(pendingStorage).when(storage).runtimeStorage(any());
        setPluginField("storage", storage);
        setPluginField("usageEffects", listener);
        setPluginField("reloadPending", new java.util.concurrent.atomic.AtomicBoolean());
        doCallRealMethod().when(plugin).reloadAsync();
        CompletableFuture<Void> reload = plugin.reloadAsync();
        assertFalse(reload.isDone(), "storage publication remains pending");
        oldOwnerCallback.run();
        barrier(Kind.AutoBlockRefill);
        assertEquals(2, charges.get(owner, Kind.AutoBlockRefill));
        assertEquals(8, items[0].getAmount());
        assertNull(items[40]);
        assertNull(tasks.poll(50, TimeUnit.MILLISECONDS), "stale refill must not reserve or schedule an effect");
        pendingStorage.completeExceptionally(new IllegalStateException("fixture storage failure"));
        assertThrows(ExecutionException.class, () -> reload.get(5, TimeUnit.SECONDS));
    }

    @Test void partialStartupShutdownReleasesChargeFileWithoutListener() throws Exception {
        when(plugin.getPerkHandler()).thenReturn(null);
        setPluginField("usageCharges", charges);
        setPluginField("usageEffects", null);
        var prepare = MCPerksMain.class.getDeclaredMethod("prepareShutdown");
        prepare.setAccessible(true);
        prepare.invoke(plugin);
        try (var successor = new UsageCharges(directory.resolve("charges.dat"))) {
            successor.ready().get(5, TimeUnit.SECONDS);
            assertTrue(successor.available(), "partial startup must release its charge owner");
        }
    }

    @Test void failedReenableClosesNewLedgerInsteadOfRetiredPredecessor() throws Exception {
        listener.retireAndClose().get(5, TimeUnit.SECONDS);
        setPluginField("usageCharges", charges);
        setPluginField("usageEffects", listener);
        when(plugin.getPerkHandler()).thenReturn(null);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        doCallRealMethod().when(plugin).onPostLoad();
        assertThrows(NullPointerException.class, () -> plugin.onPostLoad(), "fixture fails after opening successor ledger");
        var ledgerField = MCPerksMain.class.getDeclaredField("usageCharges");
        ledgerField.setAccessible(true);
        var current = (UsageCharges) ledgerField.get(plugin);
        assertNotNull(current);
        assertNotSame(charges, current);
        var effectsField = MCPerksMain.class.getDeclaredField("usageEffects");
        effectsField.setAccessible(true);
        assertNull(effectsField.get(plugin), "failed startup must not retain the predecessor listener");
        var prepare = MCPerksMain.class.getDeclaredMethod("prepareShutdown");
        prepare.setAccessible(true);
        try {
            prepare.invoke(plugin);
            try (var next = new UsageCharges(directory.resolve("usage-charges.dat"))) {
                next.ready().get(5, TimeUnit.SECONDS);
                assertTrue(next.available(), "failed re-enable must drain the successor ledger owner");
            }
        } finally {
            current.close();
            var storageField = MCPerksMain.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            var storage = (com.bencodez.mcperks.data.OrderedStorage) storageField.get(plugin);
            if (storage != null) storage.close();
            com.bencodez.mcperks.scheduling.OwnerTasks.shutdown(plugin);
        }
    }

    private Block veinBlock(int x, int y, int z, Material material) {
        Block b = mock(Block.class); when(b.getWorld()).thenReturn(world);
        when(b.getX()).thenReturn(x); when(b.getY()).thenReturn(y); when(b.getZ()).thenReturn(z);
        when(b.getLocation()).thenReturn(new Location(world,x,y,z)); when(b.getType()).thenReturn(material); return b;
    }
    private Block[] veinSetup(int count) throws Exception {
        com.bencodez.mcperks.scheduling.OwnerTasks.initialize(plugin);
        charges.set(owner,Kind.VeinMining,count).get(); items[0]=new ItemStack(Material.DIAMOND_PICKAXE);
        when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.VeinMining)));
        when(perk.getVeinOptions()).thenReturn(new VeinOptions(16,8,List.of(),List.of()));
        when(world.getMinHeight()).thenReturn(0); when(world.getMaxHeight()).thenReturn(256);
        Block origin=veinBlock(4,64,4,Material.DIAMOND_ORE), extra=veinBlock(5,64,4,Material.DIAMOND_ORE);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(i -> {
            int x=i.getArgument(0), y=i.getArgument(1), z=i.getArgument(2);
            return x==4 && y==64 && z==4 ? origin : x==5 && y==64 && z==4 ? extra : veinBlock(x,y,z,Material.AIR);
        });
        return new Block[]{origin,extra};
    }
    private Runnable queuedVein(Block origin) throws Exception {
        listener.vein(new BlockBreakEvent(origin,player)); when(origin.getType()).thenReturn(Material.AIR);
        Runnable timer=ownerScheduler.timers.poll(5,TimeUnit.SECONDS); assertNotNull(timer); timer.run(); return next();
    }
    @Test void veinEventUsesPartialBudgetAndGuardsSynchronousRecursion() throws Exception {
        Block[] blocks=veinSetup(1); Block second=veinBlock(6,64,4,Material.DIAMOND_ORE);
        when(world.getBlockAt(eq(6),eq(64),eq(4))).thenReturn(second);
        when(player.breakBlock(blocks[1])).thenAnswer(i -> { listener.vein(new BlockBreakEvent(blocks[1],player)); return true; });
        queuedVein(blocks[0]).run(); barrier(Kind.VeinMining);
        verify(player,times(1)).breakBlock(blocks[1]); verify(player,never()).breakBlock(second); assertTrue(tasks.isEmpty());
        assertEquals(0,charges.get(owner,Kind.VeinMining)); assertEquals(1,charges.successful(owner,Kind.VeinMining));
    }
    @Test void veinEventSkipsUnloadedAdjacentChunk() throws Exception {
        Block[] blocks=veinSetup(1); when(blocks[0].getX()).thenReturn(15);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenAnswer(i -> i.getArgument(0,Integer.class)==0);
        when(world.getBlockAt(eq(15),eq(64),eq(4))).thenReturn(blocks[0]);
        listener.vein(new BlockBreakEvent(blocks[0],player));
        verify(world).getBlockAt(15,64,4); verify(world,never()).getBlockAt(eq(16),anyInt(),anyInt());
    }
    @Test void veinEventChecksOriginChunkBeforeDeferredTypeRead() throws Exception {
        Block[] blocks=veinSetup(1); Runnable effect=queuedVein(blocks[0]); clearInvocations(blocks[0]);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(false); effect.run(); barrier(Kind.VeinMining);
        verify(blocks[0],never()).getType(); verify(player,never()).breakBlock(any()); assertEquals(1,charges.get(owner,Kind.VeinMining));
    }
    @Test void veinEventRejectsChangedHeldSlot() throws Exception {
        Block[] blocks=veinSetup(2); Runnable effect=queuedVein(blocks[0]); when(inventory.getHeldItemSlot()).thenReturn(1);
        effect.run(); barrier(Kind.VeinMining); verify(player,never()).breakBlock(any()); assertEquals(2,charges.get(owner,Kind.VeinMining));
    }
    @Test void veinEventRejectsChangedToolMetadata() throws Exception {
        Block[] blocks=veinSetup(2); Runnable effect=queuedVein(blocks[0]);
        var custom=mock(org.bukkit.inventory.meta.Damageable.class); when(custom.clone()).thenReturn(custom);
        when(custom.getEnchants()).thenReturn(Map.of()); when(custom.getDisplayName()).thenReturn("Different custom tool");
        items[0]=new ItemStack(Material.DIAMOND_PICKAXE); items[0].setItemMeta(custom);
        effect.run(); barrier(Kind.VeinMining); verify(player,never()).breakBlock(any()); assertEquals(2,charges.get(owner,Kind.VeinMining));
    }
    @Test void veinEventStopsAfterToolBreak() throws Exception {
        Block[] blocks=veinSetup(2); Block second=veinBlock(6,64,4,Material.DIAMOND_ORE);
        when(world.getBlockAt(eq(6),eq(64),eq(4))).thenReturn(second);
        when(player.breakBlock(blocks[1])).thenAnswer(i -> { items[0]=new ItemStack(Material.AIR); return true; });
        queuedVein(blocks[0]).run(); barrier(Kind.VeinMining); verify(player,times(1)).breakBlock(blocks[1]);
        verify(player,never()).breakBlock(second); assertEquals(1,charges.get(owner,Kind.VeinMining));
        assertEquals(1,charges.successful(owner,Kind.VeinMining));
    }

    @Test void nativeVeinKeepsFortuneAndSuppressesRecursiveAutomation() throws Exception {
        Block[] blocks=veinSetup(1); Runnable effect=queuedVein(blocks[0]);
        when(plugin.getUsageEffects()).thenReturn(listener);
        var effects=mock(com.bencodez.mcperks.perk.EffectHandler.class); when(plugin.getEffectHandler()).thenReturn(effects);
        when(effects.getBlockBreakEvents()).thenReturn(new ArrayList<>());
        var bonus=mock(Perk.class); when(bonus.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.Fortune)));
        when(bonus.getBlocks()).thenReturn(new ArrayList<>(List.of(Material.DIAMOND_ORE)));
        when(bonus.getIncreasePercent(Effect.Fortune)).thenReturn(200.0);
        when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>(List.of(perk,bonus)));
        when(handler.effectActive(eq(Effect.Fortune),eq(owner.toString()),eq("world"))).thenReturn(true);
        when(player.getItemInHand()).thenAnswer(i -> inventory.getItemInMainHand());
        var legacy=new EffectListeners(plugin);
        when(blocks[1].getDrops(any(ItemStack.class))).thenAnswer(i -> List.of(new ItemStack(Material.DIAMOND,2)));
        when(player.breakBlock(blocks[1])).thenAnswer(i -> {
            assertTrue(listener.isNativeBreak(player)); var nested=new BlockBreakEvent(blocks[1],player);
            legacy.onBlockBreak(nested); listener.vein(nested); return true;
        });
        effect.run(); barrier(Kind.VeinMining);
        verify(world).dropItemNaturally(eq(blocks[1].getLocation()),argThat(item -> item.getType()==Material.DIAMOND && item.getAmount()==4));
        verify(player,times(1)).breakBlock(blocks[1]); assertFalse(listener.isNativeBreak(player));
        verify(handler,never()).effectActive(eq(Effect.AutoPlant),anyString(),anyString());
        verify(handler,never()).effectActive(eq(Effect.IncreaseMiningArea),anyString(),anyString());
        assertEquals(1,charges.successful(owner,Kind.VeinMining)); assertTrue(tasks.isEmpty());
    }

    @Test void veinOldSessionCannotReserveAfterSameUuidReconnect() throws Exception {
        Block[] blocks=veinSetup(1); listener.vein(new BlockBreakEvent(blocks[0],player));
        Runnable timer=ownerScheduler.timers.poll(5,TimeUnit.SECONDS); assertNotNull(timer);
        Player successor=joinedSuccessor(); lateQuit(player); when(blocks[0].getType()).thenReturn(Material.AIR);
        timer.run(); barrier(Kind.VeinMining); verify(player,never()).breakBlock(any());
        assertEquals(1,charges.get(owner,Kind.VeinMining)); assertNotNull(capture(successor));
    }
    @Test void veinOldSessionAfterReservationRefundsOnceOnSameUuidReconnect() throws Exception {
        Block[] blocks=veinSetup(1); Runnable effect=queuedVein(blocks[0]);
        assertEquals(0,charges.get(owner,Kind.VeinMining)); Player successor=joinedSuccessor(); lateQuit(player);
        effect.run(); effect.run(); barrier(Kind.VeinMining); verify(player,never()).breakBlock(any());
        assertEquals(1,charges.get(owner,Kind.VeinMining)); assertNotNull(capture(successor));
    }
    @Test void veinUncertainNativeFailureRemainsSpent() throws Exception {
        Block[] blocks=veinSetup(1); when(player.breakBlock(blocks[1])).thenReturn(false);
        queuedVein(blocks[0]).run(); barrier(Kind.VeinMining);
        verify(player).breakBlock(blocks[1]); assertEquals(0,charges.get(owner,Kind.VeinMining));
        assertEquals(0,charges.successful(owner,Kind.VeinMining));
    }

    @Test void veinIgnoresNonVeinPerkBeforeReadingItsOptions() throws Exception {
        Block[] blocks=veinSetup(1); Perk unrelated=mock(Perk.class);
        when(unrelated.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.Fortune)));
        when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>(List.of(unrelated,perk)));
        when(player.breakBlock(blocks[1])).thenReturn(true);
        queuedVein(blocks[0]).run(); barrier(Kind.VeinMining);
        verify(unrelated,never()).getVeinOptions(); verify(player).breakBlock(blocks[1]);
    }
    @Test void reloadDuringNativeBreakPreservesGuardUntilCallbackReturns() throws Exception {
        Block[] blocks=veinSetup(1); Runnable effect=queuedVein(blocks[0]);
        when(plugin.getUsageEffects()).thenReturn(listener);
        var effects=mock(com.bencodez.mcperks.perk.EffectHandler.class); when(plugin.getEffectHandler()).thenReturn(effects);
        when(effects.getBlockBreakEvents()).thenReturn(new ArrayList<>());
        var legacy=new EffectListeners(plugin);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        var callbackCompleted = new java.util.concurrent.atomic.AtomicBoolean();
        when(player.breakBlock(blocks[1])).thenAnswer(i -> {
            try {
                listener.invalidate(); assertTrue(listener.isNativeBreak(player));
                var replacement=new UsageEffectListener(plugin); when(plugin.getUsageEffects()).thenReturn(replacement);
                assertTrue(replacement.isNativeBreak(player));
                var nested=new BlockBreakEvent(blocks[1],player); legacy.onBlockBreak(nested); replacement.vein(nested);
                callbackCompleted.set(true);
            } catch (Throwable failure) { callbackFailure.set(failure); }
            return true;
        });
        effect.run(); barrier(Kind.VeinMining);
        assertNull(callbackFailure.get(), "callback failed: " + callbackFailure.get());
        assertTrue(callbackCompleted.get(), "nested callback did not complete");
        verify(handler,never()).effectActive(eq(Effect.AutoPlant),anyString(),anyString());
        verify(handler,never()).effectActive(eq(Effect.IncreaseMiningArea),anyString(),anyString());
        verify(player,times(1)).breakBlock(blocks[1]); assertFalse(listener.isNativeBreak(player));
        assertTrue(ownerScheduler.timers.isEmpty());
    }
}
