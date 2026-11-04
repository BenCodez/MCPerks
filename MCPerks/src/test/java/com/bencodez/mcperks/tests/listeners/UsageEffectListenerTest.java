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

}
