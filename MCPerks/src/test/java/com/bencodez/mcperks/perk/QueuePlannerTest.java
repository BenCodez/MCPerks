package com.bencodez.mcperks.perk;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.configs.Config;
import com.bencodez.mcperks.data.*;
import com.bencodez.mcperks.userapi.*;
import com.bencodez.advancedcore.api.user.UserData;
import com.bencodez.advancedcore.api.user.usercache.UserDataManager;
import com.bencodez.advancedcore.core.user.storage.SqlUserStorage;
import com.bencodez.mcperks.scheduling.OwnerTaskHarness;
class QueuePlannerTest {
    @TempDir Path directory;
    private UUID owner; private Player player; private MCPerksMain plugin; private MCPerksUser user;
    private Perk perk; private Config config; private PerkHandler handler; private UserDataManager dataManager;
    private FeatureState state; private QueuePlanner planner; private OwnerTaskHarness scheduler;
    private AtomicInteger personal, global, activated; private Runnable afterDebit = () -> {};
    @BeforeEach void setup() throws Exception {
        if (Bukkit.getServer() == null) { Server server = mock(Server.class); when(server.getLogger()).thenReturn(Logger.getLogger("test")); Bukkit.setServer(server); }
        owner = UUID.randomUUID(); player = mock(Player.class); when(Bukkit.getServer().getPlayer(owner)).thenReturn(player); when(player.getUniqueId()).thenReturn(owner); when(player.isOnline()).thenReturn(true); when(player.hasPermission(anyString())).thenReturn(true);
        World world = mock(World.class); when(world.getName()).thenReturn("world"); when(player.getWorld()).thenReturn(world);
        plugin = mock(MCPerksMain.class); when(plugin.isEnabled()).thenReturn(true); when(plugin.getLocalStatistics()).thenReturn(new LocalStatistics()); when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        ServerData serverData = mock(ServerData.class); when(serverData.isReady()).thenReturn(true); when(serverData.getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration()); when(plugin.getMcperksServerData()).thenReturn(serverData);
        config = mock(Config.class); when(config.getLimitActivations()).thenReturn(true); when(plugin.getConfigFile()).thenReturn(config);
        user = mock(MCPerksUser.class); when(user.getUUID()).thenReturn(owner.toString()); when(user.getActivations("vein")).thenReturn(1); when(user.getActivations()).thenReturn(5); when(user.getPerkLimit()).thenReturn(-1);
        com.bencodez.mcperks.userapi.UserManager manager = mock(com.bencodez.mcperks.userapi.UserManager.class); when(plugin.getMcperksUserManager()).thenReturn(manager); when(manager.getMCPerksUser(owner)).thenReturn(user); when(manager.getMCPerksUser(player)).thenReturn(user);
        com.bencodez.advancedcore.api.user.UserManager baseManager = mock(com.bencodez.advancedcore.api.user.UserManager.class); when(plugin.getUserManager()).thenReturn(baseManager);
        dataManager = mock(UserDataManager.class); when(baseManager.getDataManager()).thenReturn(dataManager); when(dataManager.hasSharedRuntime()).thenReturn(true); var cache = mock(com.bencodez.advancedcore.api.user.usercache.UserDataCache.class);
        var storageWorker = mock(java.util.concurrent.ScheduledExecutorService.class);
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                .when(storageWorker).execute(any(Runnable.class));
        when(dataManager.getTimer()).thenReturn(storageWorker);
        when(dataManager.getPublishedCache(owner)).thenReturn(cache); when(dataManager.getCache(owner)).thenReturn(cache);
        doAnswer(i -> { i.getArgument(0, Runnable.class).run(); return null; }).when(cache).flushChangesAndRun(any(Runnable.class));
        personal = new AtomicInteger(1); global = new AtomicInteger(5); activated = new AtomicInteger(); when(user.getData()).thenReturn(mock(UserData.class));
        when(dataManager.withAtomicUserTransaction(eq(owner), any(), anyMap(), any())).thenAnswer(invocation -> {
            SqlUserStorage.TransactionScope transaction = mock(SqlUserStorage.TransactionScope.class); com.bencodez.simpleapi.sql.Column column = mock(com.bencodez.simpleapi.sql.Column.class);
            when(column.getName()).thenReturn("PerkActivations_vein"); when(column.getValue()).thenAnswer(i -> new com.bencodez.simpleapi.sql.data.DataValueInt(personal.get())); when(transaction.readRow()).thenReturn(List.of(column));
            doAnswer(i -> { Map<String, com.bencodez.simpleapi.sql.data.DataValue> values = i.getArgument(0); personal.set(values.get("PerkActivations_vein").getInt()); afterDebit.run(); return null; }).when(transaction).writeValues(anyMap());
            SqlUserStorage.TransactionWork<?> work = invocation.getArgument(3); return work.run(transaction);
        });
        perk = mock(Perk.class); when(perk.getInteractionOptions()).thenReturn(new InteractionOptions(false,false,16,8,List.of(),List.of(),List.of(),List.of())); when(perk.getPerk()).thenReturn("vein"); when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER); when(perk.getTime()).thenReturn(10); when(perk.isNotInDisabledWorld("world")).thenReturn(true);
        when(perk.executeConfirmed(user,10)).thenAnswer(i -> { activated.incrementAndGet(); return CompletableFuture.completedFuture(null); });
        handler = mock(PerkHandler.class); when(handler.isRestorationReady()).thenReturn(true); handler.que = new HashMap<>(); when(plugin.getPerkHandler()).thenReturn(handler); when(handler.getPerk("vein")).thenReturn(perk); when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>());
        scheduler = new OwnerTaskHarness(plugin, player);
        state = new FeatureState(directory.resolve("features.dat")); state.ready().get(5,TimeUnit.SECONDS); when(plugin.getFeatureState()).thenReturn(state);
        planner = new QueuePlanner(plugin,state); when(plugin.getQueuePlanner()).thenReturn(planner); when(plugin.getBankedPerks()).thenReturn(new BankedPerks(plugin,state));
    }
    @AfterEach void close() throws Exception {
        planner.retire(); state.close();
        // Closing admits worker retirement; await actual termination before JUnit removes its owned files.
        var worker = FeatureState.class.getDeclaredField("worker"); worker.setAccessible(true);
        assertTrue(((ExecutorService) worker.get(state)).awaitTermination(5, TimeUnit.SECONDS));
    }
    private Runnable next() throws Exception { return Objects.requireNonNull(scheduler.tasks.poll(5, TimeUnit.SECONDS)); }
    private void drainUntil(java.util.function.BooleanSupplier done) throws Exception { drainUntil(done, 5); }
    private void drainUntil(java.util.function.BooleanSupplier done, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) { Runnable task = scheduler.tasks.poll(20, TimeUnit.MILLISECONDS); if (task != null) { task.run(); task.run(); } }
        assertTrue(done.getAsBoolean(), "condition not reached"); state.runtimeStorage(() -> null).get(5, TimeUnit.SECONDS);
    }
    @Test void queueHeadUsesOneSnapshotWhenConcurrentCancellationPublishesEmptyQueue() throws Exception {
        var entry = state.enqueue(owner, "vein", "", 1).get(5, TimeUnit.SECONDS);
        FeatureState racing = spy(state);
        doReturn(List.of(entry), List.of()).when(racing).queue();
        planner = new QueuePlanner(plugin, racing); when(plugin.getQueuePlanner()).thenReturn(planner);
        assertDoesNotThrow(planner::check);
        verify(racing, times(1)).queue();
        assertTrue(planner.isStarting());
        Runnable preparation = next(); scheduler.retirement.get(preparation).run();
        assertFalse(planner.isStarting());
        assertDoesNotThrow(planner::check); assertFalse(planner.isStarting());
    }
    @Test void synchronousOwnerSchedulingFailureReleasesStartLatch() throws Exception {
        state.enqueue(owner, "vein", "", 1).get(5, TimeUnit.SECONDS);
        doThrow(new IllegalStateException("scheduler rejected")).when(scheduler.implementation).runAtEntityWithFallback(eq(player), any(), any());
        // OwnerTasks may either propagate rejection or settle it through the retirement callback.
        try { planner.check(); } catch (IllegalStateException expected) { assertEquals("scheduler rejected", expected.getMessage()); }
        assertFalse(planner.isStarting());
    }
    @Test void actualStartDebitsOneReservedCreditAndDuplicateOwnerCallbackDoesNotActivateTwice() throws Exception {
        planner.enqueue(user,perk); drainUntil(() -> state.queue().isEmpty() && activated.get() == 1);
        assertEquals(0,personal.get()); assertEquals(5,global.get()); verify(dataManager,times(1)).withAtomicUserTransaction(eq(owner),any(),anyMap(),any()); verify(user.getData(),never()).setInt(anyString(),anyInt(),anyBoolean(),anyBoolean());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void queuedBankKeepsStartingFenceThroughPublishedButUnattachedOwnerCallbacks(boolean retireInitialOwner) throws Exception {
        when(config.getLimitActivations()).thenReturn(false); when(config.getPerkQue()).thenReturn(true);
        when(perk.getInteractionOptions()).thenReturn(new InteractionOptions(false, true, 16, 8, List.of(), List.of(), List.of(), List.of()));
        when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER); when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.NoFallDamage)));
        when(perk.clone()).thenAnswer(invocation -> {
            var clone = mock(Perk.class); when(clone.getPerk()).thenReturn("vein"); when(clone.getActivater()).thenReturn(user);
            when(clone.getEffectedPlayers()).thenReturn(new ArrayList<String>()); return clone;
        });
        var pluginField = Perk.class.getDeclaredField("plugin"); pluginField.setAccessible(true); pluginField.set(perk, plugin);
        when(plugin.getMcperksServerData().confirmedActivation(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run(); return CompletableFuture.completedFuture(null);
        });
        doCallRealMethod().when(perk).executePublishedBank(user);
        var entry = state.enqueue(owner, "vein", "", 1).get(5, TimeUnit.SECONDS);
        planner.check(); next().run(); // durable claim and bank publication
        Runnable execution = next(); execution.run(); execution.run();
        assertTrue(planner.isStarting()); assertTrue(handler.getActivePerks().isEmpty());
        assertEquals(10000, state.banks().get(new FeatureState.BankKey(owner, "vein")).milliseconds());
        assertEquals(FeatureState.Stage.STARTING, state.queue().getFirst().stage());
        planner.check(); Runnable initialStart = next();
        if (retireInitialOwner) {
            scheduler.retirement.get(initialStart).run(); initialStart.run();
            drainUntil(() -> !planner.isStarting() && state.queue().isEmpty());
            assertFalse(planner.isStarting()); assertTrue(handler.getActivePerks().isEmpty()); assertTrue(state.queue().isEmpty());
            assertEquals(10000, state.banks().get(new FeatureState.BankKey(owner, "vein")).milliseconds());
            verify(perk, times(1)).executePublishedBank(user); verify(dataManager, never()).withAtomicUserTransaction(any(), any(), anyMap(), any());
            return;
        }
        initialStart.run(); // only schedules the storage->owner attachment
        state.storage(() -> null).get(5, TimeUnit.SECONDS);
        assertTrue(planner.isStarting()); assertTrue(handler.getActivePerks().isEmpty()); planner.check();
        next().run(); drainUntil(() -> !planner.isStarting() && state.queue().isEmpty());
        assertEquals(1, handler.getActivePerks().size()); assertFalse(planner.isStarting());
        assertTrue(state.queue().isEmpty()); assertEquals(10000, state.banks().get(new FeatureState.BankKey(owner, "vein")).milliseconds());
        verify(perk, times(1)).executePublishedBank(user); verify(dataManager, never()).withAtomicUserTransaction(any(), any(), anyMap(), any());
        assertEquals(1, personal.get()); assertNotNull(entry);
    }
    @Test void unsupportedTransactionLeavesWaitingAndUnlimitedLegacyStillStarts() throws Exception {
        when(dataManager.hasSharedRuntime()).thenReturn(false); planner.enqueue(user,perk); next().run(); next().run(); state.storage(() -> null).get(5,TimeUnit.SECONDS);
        assertEquals(FeatureState.Stage.WAITING,state.queue().getFirst().stage()); assertEquals(1,personal.get()); assertEquals(0,activated.get()); assertEquals("transactional activation storage unavailable",planner.startUnavailable(state.queue().getFirst()));
        state.clearWaiting().get(); when(config.getLimitActivations()).thenReturn(false); planner.enqueue(user,perk); drainUntil(() -> activated.get()==1 && state.queue().isEmpty()); assertEquals(1,personal.get());
        verify(dataManager,never()).withAtomicUserTransaction(any(),any(),anyMap(),any());
    }
    @Test void droppedPreparationReleasesStartingAndRetryCanStart() throws Exception {
        planner.enqueue(user,perk); next().run(); Runnable prepare = next(); scheduler.retirement.get(prepare).run(); assertFalse(planner.isStarting()); assertEquals(FeatureState.Stage.WAITING,state.queue().getFirst().stage());
        prepare.run(); assertEquals(1,personal.get()); planner.check(); drainUntil(() -> activated.get()==1 && state.queue().isEmpty());
    }
    @Test void droppedExecutionKeepsVisibleUncertainFenceAndNeverReplaysCredit() throws Exception {
        planner.enqueue(user,perk); next().run(); next().run(); Runnable effect = next(); scheduler.retirement.get(effect).run(); state.storage(() -> null).get(); effect.run(); planner.check();
        assertFalse(planner.isStarting()); assertEquals(FeatureState.Stage.UNCERTAIN,state.queue().getFirst().stage()); assertEquals(0,personal.get()); assertEquals(0,activated.get()); verify(dataManager,times(1)).withAtomicUserTransaction(any(),any(),anyMap(),any());
    }
    @Test void changedUnsupportedBankDefinitionRejectsBeforeClaimOrDebit() throws Exception {
        state.pauseQueue(true).get(); planner.enqueue(user,perk); next().run(); state.storage(() -> null).get();
        when(perk.getInteractionOptions()).thenReturn(new InteractionOptions(false,true,16,8,List.of(),List.of(),List.of(),List.of())); when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER); when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.Commands)));
        state.pauseQueue(false).get(); planner.check(); next().run(); assertEquals(FeatureState.Stage.WAITING,state.queue().getFirst().stage()); assertEquals(1,personal.get()); verify(dataManager,never()).withAtomicUserTransaction(any(),any(),anyMap(),any());
    }
    @Test void postDebitBankPublicationFailureIsUncertainAndDoesNotRefund() throws Exception {
        when(perk.getInteractionOptions()).thenReturn(new InteractionOptions(false,true,16,8,List.of(),List.of(),List.of(),List.of())); when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER); when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.NoFallDamage)));
        doAnswer(i -> plugin.getBankedPerks().grant(user,perk,10)).when(perk).executeConfirmed(user,10);
        planner.retire(); state.close(); var fail = new java.util.concurrent.atomic.AtomicBoolean();
        state = FeatureStateFixture.create(directory.resolve("features.dat"), (staged,file) -> { if (fail.get()) throw new java.io.IOException("bank publication rejected"); DurableFeatureFiles.publish(staged,file); });
        state.ready().get(); when(plugin.getFeatureState()).thenReturn(state); planner = new QueuePlanner(plugin,state); when(plugin.getQueuePlanner()).thenReturn(planner); when(plugin.getBankedPerks()).thenReturn(new BankedPerks(plugin,state)); afterDebit = () -> fail.set(true);
        planner.enqueue(user,perk); next().run(); next().run();
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5); while(state.available() && System.nanoTime()<deadline) Thread.onSpinWait();
        assertFalse(state.available()); assertEquals(0,personal.get()); assertEquals(FeatureState.Stage.STARTING,state.queue().getFirst().stage()); assertTrue(state.banks().isEmpty());
        state.close(); try (FeatureState restarted = new FeatureState(directory.resolve("features.dat"))) {
            restarted.ready().get(5,TimeUnit.SECONDS); assertEquals(FeatureState.Stage.UNCERTAIN,restarted.queue().getFirst().stage()); assertTrue(restarted.banks().isEmpty()); assertNull(restarted.claim(restarted.queue().getFirst().id()).get());
        }
    }
    @Test void finiteAbsolutePredecessorsHaveEstimatesButPausedOrPermanentQueuesDoNot() throws Exception {
        var entry = state.enqueue(owner,"vein","",1).get(); Perk active = mock(Perk.class); when(active.getInteractionOptions()).thenReturn(new InteractionOptions(false,false,16,8,List.of(),List.of(),List.of(),List.of())); when(active.getKnownExpiration()).thenReturn(System.currentTimeMillis()+30_000);
        handler.getActivePerks().add(active); when(config.getLimitPermission()).thenReturn(true); when(user.getPerkLimit()).thenReturn(1); when(player.hasPermission(anyString())).thenReturn(false); CompletableFuture<Map<UUID,Long>> estimates = planner.estimatedWaits(); next().run(); long wait = estimates.get(5,TimeUnit.SECONDS).get(entry.id()); assertTrue(wait>=28 && wait<=30); verify(plugin.getMcperksUserManager(),never()).getMCPerksUser(player);
        state.pauseQueue(true).get(); assertTrue(planner.estimatedWaits().get().isEmpty()); state.pauseQueue(false).get(); when(active.getKnownExpiration()).thenReturn(0L); assertTrue(planner.estimatedWaits().get().isEmpty());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void advisoryCreditReadRepopulatesRetiredCacheBeforeCompleting(boolean hasCredit) throws Exception {
        var entry = state.enqueue(owner,"vein","PerkActivations_vein",1).get();
        var ready = new java.util.concurrent.atomic.AtomicBoolean(true);
        var published = mock(com.bencodez.advancedcore.api.user.usercache.UserDataCache.class);
        when(dataManager.getPublishedCache(owner)).thenAnswer(i -> ready.get() ? published : null);
        when(dataManager.withAtomicUserTransaction(eq(owner),any(),anyMap(),any())).thenAnswer(i -> {
            var transaction = mock(SqlUserStorage.TransactionScope.class);
            var column = mock(com.bencodez.simpleapi.sql.Column.class);
            when(column.getName()).thenReturn("PerkActivations_vein");
            when(column.getValue()).thenReturn(new com.bencodez.simpleapi.sql.data.DataValueInt(hasCredit ? 1 : 0));
            when(transaction.readRow()).thenReturn(List.of(column));
            SqlUserStorage.TransactionWork<?> work = i.getArgument(3);
            Object result = work.run(transaction);
            verify(transaction,never()).writeValues(anyMap());
            ready.set(false); return result;
        });
        CountDownLatch populating = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(i -> {
            assertEquals("MCPerks-feature-store",Thread.currentThread().getName());
            populating.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); ready.set(true); return null;
        }).when(dataManager).cacheUser(owner);
        try {
            var estimates = planner.estimatedWaits(); next().run();
            assertTrue(populating.await(5,TimeUnit.SECONDS)); assertFalse(estimates.isDone());
            release.countDown();
            assertEquals(hasCredit,estimates.get(5,TimeUnit.SECONDS).containsKey(entry.id()));
            assertSame(published,dataManager.getPublishedCache(owner));
            assertEquals(1,personal.get()); assertEquals(0,activated.get());
        } finally { release.countDown(); }
    }
    @Test void periodicQueueChecksUseTheSchedulersSecondsContract() {
        planner.startChecks(); verify(scheduler.scheduler).runTaskLater(eq(plugin),any(Runnable.class),eq(1L));
    }

    @Test void committedDebitWaitsForCanonicalCachePublicationBeforeGrantingOnce() throws Exception {
        CountDownLatch publishing=new CountDownLatch(1), release=new CountDownLatch(1); var ready=new java.util.concurrent.atomic.AtomicBoolean(true);
        var published=mock(com.bencodez.advancedcore.api.user.usercache.UserDataCache.class); when(dataManager.getPublishedCache(owner)).thenAnswer(i->ready.get()?published:null);
        afterDebit=()->ready.set(false);
        doAnswer(i->{ assertEquals("MCPerks-feature-store",Thread.currentThread().getName()); publishing.countDown(); if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("cache release timeout"); ready.set(true); return null; }).when(dataManager).cacheUser(owner);
        when(user.isIgnorePerkEffects()).thenAnswer(i->{ if(!ready.get()) throw new IllegalStateException("Shared user data is still loading"); return false; });
        doAnswer(i->{ user.isIgnorePerkEffects(); activated.incrementAndGet(); return CompletableFuture.completedFuture(null); }).when(perk).executeConfirmed(user,10);
        try {
            planner.enqueue(user,perk); next().run(); next().run(); assertTrue(publishing.await(5,TimeUnit.SECONDS));
            assertEquals(0,personal.get()); assertEquals(0,activated.get()); assertEquals(FeatureState.Stage.STARTING,state.queue().getFirst().stage()); assertNull(scheduler.tasks.poll(50,TimeUnit.MILLISECONDS));
            release.countDown(); drainUntil(()->activated.get()==1 && state.queue().isEmpty()); assertEquals(0,personal.get());
            verify(dataManager,times(1)).withAtomicUserTransaction(any(),any(),anyMap(),any()); verify(dataManager,times(1)).cacheUser(owner); verify(user,times(1)).isIgnorePerkEffects();
        } finally { release.countDown(); }
    }

    @Test void removedReservedCreditMakesThisEntryAndSuccessorsUnknownWithoutSpendingIt() throws Exception {
        var first=state.enqueue(owner,"vein","PerkActivations_vein",1).get();
        UUID nextOwner=UUID.randomUUID(); Player nextPlayer=mock(Player.class); World world=player.getWorld();
        when(nextPlayer.isOnline()).thenReturn(true); when(nextPlayer.getUniqueId()).thenReturn(nextOwner);
        when(nextPlayer.getWorld()).thenReturn(world); when(nextPlayer.hasPermission(anyString())).thenReturn(true);
        when(Bukkit.getServer().getPlayer(nextOwner)).thenReturn(nextPlayer);
        MCPerksUser nextUser=mock(MCPerksUser.class); when(plugin.getMcperksUserManager().getMCPerksUser(nextOwner)).thenReturn(nextUser);
        when(scheduler.implementation.runAtEntityWithFallback(eq(nextPlayer),any(),any())).thenAnswer(invocation -> {
            java.util.function.Consumer<com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask> action=invocation.getArgument(1);
            scheduler.tasks.add(()->action.accept(null));
            return CompletableFuture.completedFuture(com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS);
        });
        var second=state.enqueue(nextOwner,"vein","",2).get(); personal.set(0);
        var blocked=planner.estimatedWaits(); next().run(); next().run(); assertTrue(blocked.get(5,TimeUnit.SECONDS).isEmpty());
        assertEquals(0,personal.get()); assertEquals(2,state.queue().size());
        personal.set(1); var restored=planner.estimatedWaits(); next().run(); next().run();
        var estimates=restored.get(5,TimeUnit.SECONDS); assertEquals(0L,estimates.get(first.id()));
        assertEquals(10L,estimates.get(second.id())); assertEquals(1,personal.get()); assertEquals(0,activated.get());
    }

    private void bankDefinition() {
        when(perk.getInteractionOptions()).thenReturn(new InteractionOptions(false,true,16,8,List.of(),List.of(),List.of(),List.of()));
        when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER); when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.NoFallDamage)));
        when(perk.executePublishedBank(user)).thenAnswer(i->{ activated.incrementAndGet(); return CompletableFuture.completedFuture(null); });
    }
    @Test void bankFilledAfterOwnerAdmissionIsRejectedOnWorkerBeforeAnyDebit() throws Exception {
        state.pauseQueue(true).get(); planner.enqueue(user,perk); next().run(); state.storage(()->null).get();
        bankDefinition(); state.pauseQueue(false).get();
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        state.storage(()->{ entered.countDown(); try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch(InterruptedException e) { throw new RuntimeException(e); } return null; });
        assertTrue(entered.await(5,TimeUnit.SECONDS)); planner.check(); next().run();
        var key=new FeatureState.BankKey(owner,"vein"); var fill=state.setBank(key,31_536_000_000L);
        release.countDown(); fill.get(5,TimeUnit.SECONDS); drainUntil(()->!planner.isStarting());
        assertEquals(FeatureState.Stage.WAITING,state.queue().getFirst().stage()); assertEquals(1,personal.get());
        assertEquals("bank capacity reached",planner.startUnavailable(state.queue().getFirst()));
        assertEquals(0,activated.get()); verify(dataManager,never()).withAtomicUserTransaction(any(),any(),anyMap(),any());
    }
    @Test void exactRemainingBankCapacityDebitsOnceAndOwnerCompletionNeverAddsTimeAgain() throws Exception {
        bankDefinition(); var key=new FeatureState.BankKey(owner,"vein");
        state.setBank(key,31_536_000_000L-10000).get(); state.pauseBank(key,true).get();
        planner.enqueue(user,perk); drainUntil(()->activated.get()==1 && state.queue().isEmpty());
        assertEquals(new FeatureState.Bank(31_536_000_000L,true),state.banks().get(key)); assertEquals(0,personal.get());
        verify(perk,times(1)).executePublishedBank(user); verify(perk,never()).executeConfirmed(any(),anyInt());
        verify(dataManager,times(1)).withAtomicUserTransaction(any(),any(),anyMap(),any());
    }
    private com.bencodez.advancedcore.data.ServerData runtimeFile() throws Exception {
        var nativeFile=mock(com.bencodez.advancedcore.data.ServerData.class);
        var yaml=spy(new org.bukkit.configuration.file.YamlConfiguration());
        when(nativeFile.getData()).thenReturn(yaml); when(nativeFile.getdFile()).thenReturn(directory.resolve("ServerData.yml").toFile());
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getServerDataFile()).thenReturn(nativeFile);
        var checker=mock(com.bencodez.advancedcore.api.time.TimeChecker.class);
        when(checker.getTime()).thenReturn(java.time.LocalDateTime.of(2026,10,8,12,0)); when(plugin.getTimeChecker()).thenReturn(checker);
        when(plugin.getMcperksServerData()).thenReturn(new ServerData(plugin));
        var field=Perk.class.getDeclaredField("plugin"); field.setAccessible(true); field.set(perk,plugin);
        var admissions=Perk.class.getDeclaredField("activationAdmissions"); admissions.setAccessible(true); admissions.set(perk,new LinkedHashMap<String,Long>());
        doCallRealMethod().when(perk).executeConfirmed(user,10);
        doAnswer(call -> { scheduler.tasks.add(call.getArgument(1, Runnable.class)); return null; }).when(scheduler.scheduler).runTask(eq(plugin), any(Runnable.class));
        return nativeFile;
    }
    @Test void queuedPersonalStartRetainsFenceUntilActualUserFlushCallback() throws Exception {
        runtimeFile(); when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        var cache = dataManager.getCache(owner); var callback = new CompletableFuture<Runnable>();
        doAnswer(i -> { assertEquals("MCPerks-feature-store", Thread.currentThread().getName()); callback.complete(i.getArgument(0)); return null; })
                .when(cache).flushChangesAndRun(any(Runnable.class));
        doAnswer(i -> { activated.incrementAndGet(); user.setPerkExperation(perk, 123456); return null; }).when(perk).activatePerk(eq(user),eq(10),nullable(Timestamp.class));
        planner.enqueue(user,perk); drainUntil(callback::isDone);
        Runnable completed = callback.get(5,TimeUnit.SECONDS);
        assertEquals(0, personal.get()); assertEquals(0, activated.get());
        assertEquals(FeatureState.Stage.STARTING, state.queue().getFirst().stage());
        completed.run(); drainUntil(() -> state.queue().isEmpty()); assertEquals(1, activated.get());
        verify(user).setPerkExperation(perk,123456); verify(user).setPerkCoolDown(eq(perk),anyLong());
        completed.run(); planner.check(); assertEquals(1, activated.get());
    }
    @Test void queuedPersonalFlushFailureRetainsUncertainFenceAfterYamlSucceeds() throws Exception {
        runtimeFile(); when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        var failedCache = dataManager.getCache(owner);
        doThrow(new IllegalStateException("SQL flush failed")).when(failedCache).flushChangesAndRun(any(Runnable.class));
        doAnswer(i -> { activated.incrementAndGet(); user.setPerkExperation(perk,123456); return null; }).when(perk).activatePerk(eq(user),eq(10),nullable(Timestamp.class));
        planner.enqueue(user,perk); drainUntil(() -> !state.queue().isEmpty() && state.queue().getFirst().stage()==FeatureState.Stage.UNCERTAIN);
        assertTrue(Files.isRegularFile(directory.resolve("ServerData.yml"))); assertEquals(0,personal.get());
        planner.check(); assertEquals(0,activated.get());
        state.close(); try (var recovered = new FeatureState(directory.resolve("features.dat"))) {
            recovered.ready().get(5,TimeUnit.SECONDS); assertEquals(FeatureState.Stage.UNCERTAIN,recovered.queue().getFirst().stage());
        }
    }
    @Test void directLimitedActivationRejectsPendingAndFailedRuntimeBeforeEitherCreditDebit() throws Exception {
        var nativeFile=runtimeFile(); field(perk,"perk","vein"); doCallRealMethod().when(perk).runPerk(user);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        state.runtimeStorage(() -> { entered.countDown(); try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch (InterruptedException error) { throw new RuntimeException(error); } return null; });
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        Files.createDirectory(nativeFile.getdFile().toPath());
        var runtime=plugin.getMcperksServerData(); var initialized=runtime.initialize(state);
        try { perk.runPerk(user); perk.runPerk(user); assertFalse(runtime.isReady()); }
        finally { release.countDown(); }
        assertThrows(ExecutionException.class, () -> initialized.get(5,TimeUnit.SECONDS)); perk.runPerk(user);
        verify(user,never()).addActivation(anyInt()); verify(user,never()).addActivation(anyString(),anyInt());
        verify(perk,never()).executeConfirmed(any(),anyInt()); verify(user,times(3)).sendMessage(contains("runtime state is unavailable"));
    }
    @Test void readyLegacyRuntimeStillConsumesOnlySelectedPersonalCreditOnDirectActivation() throws Exception {
        runtimeFile(); field(perk,"perk","vein"); doCallRealMethod().when(perk).runPerk(user);
        when(perk.checkTimesUsed(user)).thenReturn(true); when(perk.checkCoolDown(user)).thenReturn(true); when(perk.checkServerWideCoolDown(user)).thenReturn(true);
        doAnswer(call -> { activated.incrementAndGet(); return null; }).when(perk).activatePerk(eq(user), eq(10), nullable(Timestamp.class));
        perk.runPerk(user);
        drainUntil(() -> activated.get() == 1 && state.queue().isEmpty());
        assertEquals(0, personal.get()); assertEquals(5, global.get());
        verify(user, never()).addActivation(anyString(), anyInt()); verify(user,never()).addActivation(anyInt());
        verify(dataManager, times(1)).withAtomicUserTransaction(eq(owner), any(), anyMap(), any());
        verify(perk, times(1)).activatePerk(eq(user), eq(10), nullable(Timestamp.class)); verify(user,never()).sendMessage(contains("unavailable"));
    }
    private void directRuntime() throws Exception {
        runtimeFile(); field(perk, "perk", "vein"); doCallRealMethod().when(perk).runPerk(user);
        when(perk.checkTimesUsed(user)).thenReturn(true); when(perk.checkCoolDown(user)).thenReturn(true);
        when(perk.checkServerWideCoolDown(user)).thenReturn(true);
        doAnswer(call -> { activated.incrementAndGet(); return null; }).when(perk).activatePerk(eq(user), eq(10), nullable(Timestamp.class));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void directPredebitServerPublicationOrCapacityFailureConsumesNothing(boolean oversized) throws Exception {
        directRuntime();
        if (oversized) plugin.getMcperksServerData().getData().set("Indispensable", "x".repeat(4_100_000));
        else Files.createDirectory(directory.resolve("ServerData.yml"));
        perk.runPerk(user);
        // Serializing the intentionally oversized four-MB YAML fixture can exceed five
        // seconds in the full suite; retain every accounting assertion and a hard bound.
        drainUntil(() -> !planner.hasAdmissions(), oversized ? 20 : 5);
        assertEquals(1, personal.get()); assertEquals(0, activated.get()); assertTrue(state.queue().isEmpty());
        verify(dataManager, never()).withAtomicUserTransaction(any(), any(), anyMap(), any());
    }
    @Test void directCallerStorageFailureReleasesDurableStartWithoutDebitOrGameplay() throws Exception {
        directRuntime();
        var cache = dataManager.getCache(owner);
        doThrow(new IllegalStateException("caller flush unavailable")).when(cache).flushChangesAndRun(any());
        perk.runPerk(user); drainUntil(() -> !planner.hasAdmissions());
        assertTrue(Files.isRegularFile(directory.resolve("ServerData.yml")));
        assertTrue(state.queue().isEmpty()); assertEquals(1, personal.get()); assertEquals(0, activated.get());
        verify(dataManager, never()).withAtomicUserTransaction(any(), any(), anyMap(), any());
    }
    @Test void directAtomicFalseReleasesFenceWithoutGameplay() throws Exception {
        directRuntime(); personal.set(0); // advisory cache still offers one, authoritative transaction offers none
        perk.runPerk(user); drainUntil(() -> !planner.hasAdmissions());
        assertTrue(state.queue().isEmpty()); assertEquals(0, personal.get()); assertEquals(0, activated.get());
        verify(dataManager, times(1)).withAtomicUserTransaction(eq(owner), any(), anyMap(), any());
        verify(dataManager, never()).cacheUser(any());
    }
    @Test void directDebitThenCachePublicationFailureRetainsUncertainIdentityAcrossRestart() throws Exception {
        directRuntime(); when(dataManager.getPublishedCache(owner)).thenReturn(null);
        perk.runPerk(user); drainUntil(() -> !planner.hasAdmissions());
        assertEquals(0, personal.get()); assertEquals(0, activated.get());
        var entry = state.queue().getFirst(); assertEquals(FeatureState.Stage.UNCERTAIN, entry.stage());
        planner.check(); perk.runPerk(user); drainUntil(() -> !planner.hasAdmissions());
        assertEquals(List.of(entry), state.queue());
        verify(dataManager, times(1)).withAtomicUserTransaction(eq(owner), any(), anyMap(), any());
        state.close();
        try (var recovered = new FeatureState(directory.resolve("features.dat"))) {
            recovered.ready().get(5, TimeUnit.SECONDS); assertEquals(List.of(entry), recovered.queue());
        }
    }
    @Test void directStartIsPersistedBeforeCallerFlushAndRetainsOwnerUntilFinalCompletion() throws Exception {
        directRuntime(); personal.set(2); when(user.getActivations("vein")).thenReturn(2);
        var flush = new CompletableFuture<Runnable>();
        var cache = dataManager.getCache(owner);
        var firstFlush = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> { Runnable callback = call.getArgument(0); if (firstFlush.getAndSet(false)) flush.complete(callback); else callback.run(); return null; }).when(cache).flushChangesAndRun(any());
        perk.runPerk(user); drainUntil(flush::isDone);
        var entry = state.queue().getFirst(); assertEquals(FeatureState.Stage.STARTING, entry.stage());
        assertEquals(2, personal.get()); assertEquals(0, activated.get());
        perk.runPerk(user); assertEquals(1, state.queue().size()); assertTrue(planner.hasAdmissions());
        verify(user).sendMessage(contains("still being confirmed"));
        flush.get(5, TimeUnit.SECONDS).run(); drainUntil(() -> !planner.hasAdmissions());
        assertTrue(state.queue().isEmpty()); assertEquals(1, personal.get()); assertEquals(1, activated.get());
        verify(dataManager, times(1)).withAtomicUserTransaction(eq(owner), any(), anyMap(), any());
    }
    @Test void directReloadRetiresPendingCallerFlushBeforeDebit() throws Exception {
        directRuntime(); var epoch = new AtomicInteger(); when(plugin.getFeatureLifecycle()).thenAnswer(call -> epoch.get());
        var flush = new CompletableFuture<Runnable>();
        var cache = dataManager.getCache(owner);
        doAnswer(call -> { flush.complete(call.getArgument(0)); return null; }).when(cache).flushChangesAndRun(any());
        perk.runPerk(user); drainUntil(flush::isDone); epoch.incrementAndGet(); planner.invalidate();
        flush.get(5, TimeUnit.SECONDS).run(); drainUntil(() -> !planner.hasAdmissions());
        assertEquals(1, personal.get()); assertEquals(0, activated.get()); assertTrue(state.queue().isEmpty());
        verify(dataManager, never()).withAtomicUserTransaction(any(), any(), anyMap(), any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"PLAYER,false", "PLAYER,true", "ALL,false", "ALL,true"})
    void rejectedPermanentDirectPurchasePreservesExistingMetadataAndCannotRestoreAsFreeGrant(String audience, boolean retireBeforeDebit) throws Exception {
        var nativeFile = runtimeFile(); field(perk, "perk", "vein"); doCallRealMethod().when(perk).runPerk(user);
        when(perk.checkTimesUsed(user)).thenReturn(true); when(perk.checkCoolDown(user)).thenReturn(true); when(perk.checkServerWideCoolDown(user)).thenReturn(true);
        PerkSystemType scope = PerkSystemType.valueOf(audience); when(perk.getPerkType()).thenReturn(scope);
        when(perk.isTimed()).thenReturn(true); when(perk.isLastForever()).thenReturn(true);
        when(perk.getCoolDown()).thenReturn(30); when(perk.getServerWideCoolDown()).thenReturn(60);
        long priorExpiration = System.currentTimeMillis() + 120_000, priorPersonalCooldown = System.currentTimeMillis() - 100_000;
        long priorServerCooldown = System.currentTimeMillis() - 50_000;
        var personalExpiration = new java.util.concurrent.atomic.AtomicLong(priorExpiration);
        var personalCooldown = new java.util.concurrent.atomic.AtomicLong(priorPersonalCooldown);
        when(user.getPerkExperation(perk)).thenAnswer(call -> personalExpiration.get());
        when(user.getPerkCoolDown(perk)).thenAnswer(call -> personalCooldown.get());
        doAnswer(call -> { personalExpiration.set(call.getArgument(1)); return null; }).when(user).setPerkExperation(eq(perk), anyLong());
        doAnswer(call -> { personalCooldown.set(call.getArgument(1)); return null; }).when(user).setPerkCoolDown(eq(perk), anyLong());
        plugin.getMcperksServerData().getData().set("Experation.vein", priorExpiration);
        plugin.getMcperksServerData().getData().set("vein.CoolDown", priorServerCooldown);
        var epoch = new AtomicInteger(); when(plugin.getFeatureLifecycle()).thenAnswer(call -> epoch.get());
        var flush = new CompletableFuture<Runnable>(); var cache = dataManager.getCache(owner);
        if (retireBeforeDebit) doAnswer(call -> { flush.complete(call.getArgument(0)); return null; }).when(cache).flushChangesAndRun(any());
        else personal.set(0); // stale admission cache still offers one; the authoritative debit returns false
        perk.runPerk(user);
        if (retireBeforeDebit) {
            drainUntil(flush::isDone); assertEquals(FeatureState.Stage.STARTING, state.queue().getFirst().stage());
            assertEquals(priorExpiration, personalExpiration.get()); assertEquals(priorPersonalCooldown, personalCooldown.get());
            epoch.incrementAndGet(); planner.invalidate(); flush.get(5, TimeUnit.SECONDS).run();
        }
        drainUntil(() -> !planner.hasAdmissions()); assertTrue(state.queue().isEmpty());
        assertEquals(retireBeforeDebit ? 1 : 0, personal.get()); assertEquals(priorExpiration, personalExpiration.get());
        assertEquals(priorPersonalCooldown, personalCooldown.get());
        assertEquals(priorExpiration, plugin.getMcperksServerData().getPerkExperation(perk));
        assertEquals(priorServerCooldown, plugin.getMcperksServerData().getServerCooldown("vein"));
        verify(user, never()).setPerkExperation(any(), anyLong()); verify(user, never()).setPerkCoolDown(any(), anyLong());
        verify(perk, never()).activatePerk(any(), anyInt(), any());
        var recovered = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("ServerData.yml").toFile());
        assertEquals(priorExpiration, recovered.getLong("Experation.vein")); assertEquals(priorServerCooldown, recovered.getLong("vein.CoolDown"));
        when(nativeFile.getData()).thenReturn(recovered);
        var runtime = new ServerData(plugin); when(plugin.getMcperksServerData()).thenReturn(runtime);
        runtime.initialize(state).get(5, TimeUnit.SECONDS);
        when(perk.getExperation(user)).thenAnswer(call -> scope == PerkSystemType.ALL ? runtime.getPerkExperation(perk) : user.getPerkExperation(perk));
        var restorationTasks = new LinkedBlockingQueue<Runnable>(); var actual = restorer(restorationTasks, perk);
        var restored = actual.loadActivePerksConfirmed(() -> {});
        Objects.requireNonNull(restorationTasks.poll(5, TimeUnit.SECONDS)).run(); restored.get(5, TimeUnit.SECONDS);
        verify(perk, never()).activatePerk(user, 10); verify(perk, atLeastOnce()).scheduleDeactivation(user);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PLAYER", "ALL"})
    void successfulPermanentDirectPurchasePublishesMetadataOnlyAfterOneDebitAndRunsHooksOnce(String audience) throws Exception {
        directRuntime(); when(perk.getPerkType()).thenReturn(PerkSystemType.valueOf(audience));
        when(perk.isTimed()).thenReturn(true); when(perk.isLastForever()).thenReturn(true);
        when(perk.getCoolDown()).thenReturn(30); when(perk.getServerWideCoolDown()).thenReturn(60);
        doAnswer(call -> { assertEquals(0, personal.get(), "new personal metadata requires a committed debit"); return null; }).when(user).setPerkExperation(eq(perk), anyLong());
        doAnswer(call -> { assertEquals(0, personal.get(), "new cooldown requires a committed debit"); return null; }).when(user).setPerkCoolDown(eq(perk), anyLong());
        perk.runPerk(user); drainUntil(() -> !planner.hasAdmissions());
        assertEquals(0, personal.get()); assertEquals(1, activated.get()); assertTrue(state.queue().isEmpty());
        if (audience.equals("PLAYER")) verify(user).setPerkExperation(perk, -1);
        else assertEquals(-1, plugin.getMcperksServerData().getPerkExperation(perk));
        verify(dataManager, times(1)).withAtomicUserTransaction(eq(owner), any(), anyMap(), any());
        verify(perk, times(1)).activatePerk(eq(user), eq(10), isNull());
    }
    @Test void directTransactionExceptionRetainsUncertaintyEvenWhenBackendMayHaveCommitted() throws Exception {
        directRuntime(); afterDebit = () -> { throw new IllegalStateException("transaction acknowledgement lost"); };
        perk.runPerk(user); drainUntil(() -> !planner.hasAdmissions());
        assertEquals(0, personal.get()); assertEquals(0, activated.get());
        assertEquals(FeatureState.Stage.UNCERTAIN, state.queue().getFirst().stage());
        planner.check(); verify(dataManager, times(1)).withAtomicUserTransaction(eq(owner), any(), anyMap(), any());
    }
    @Test void repeatedDirectConsumeCallbackUsesTheSameAtomicCompletion() throws Exception {
        var completed = planner.startDirect(user, perk, () -> {}, consume -> {
            var first = consume.get(); assertSame(first, consume.get()); return first;
        });
        drainUntil(completed::isDone); completed.get(5, TimeUnit.SECONDS);
        assertEquals(0, personal.get()); assertTrue(state.queue().isEmpty());
        verify(dataManager, times(1)).withAtomicUserTransaction(eq(owner), any(), anyMap(), any());
    }
    @Test void acceptedQueuedConsumeThenSynchronousThrowKeepsStartFenceUntilDebitSettles() throws Exception {
        var completed = planner.startDirect(user, perk, () -> {}, consume -> {
            consume.get(); throw new IllegalStateException("caller failed after accepting consumption");
        });
        Runnable callback = next(); // admission is durable, but the owner has not requested consumption yet
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocker = state.storage(() -> {
            entered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch (InterruptedException error) { throw new RuntimeException(error); } return null;
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); callback.run(); callback.run();
            assertFalse(completed.isDone()); assertTrue(planner.hasAdmissions());
            assertEquals(FeatureState.Stage.STARTING, state.queue().getFirst().stage());
            assertEquals(1, personal.get()); assertEquals(0, activated.get());
        } finally { release.countDown(); }
        blocker.get(5, TimeUnit.SECONDS); drainUntil(completed::isDone);
        assertThrows(ExecutionException.class, () -> completed.get(5, TimeUnit.SECONDS));
        assertFalse(planner.hasAdmissions()); assertEquals(0, personal.get()); assertEquals(0, activated.get());
        assertEquals(FeatureState.Stage.UNCERTAIN, state.queue().getFirst().stage());
        verify(dataManager, times(1)).withAtomicUserTransaction(eq(owner), any(), anyMap(), any());
    }
    @Test void consumeAfterExecutionSettlesIsRejectedBeforeAnyDebit() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<java.util.function.Supplier<CompletableFuture<Void>>>();
        var completed = planner.startDirect(user, perk, () -> {}, consume -> {
            captured.set(consume); return CompletableFuture.completedFuture(null);
        });
        drainUntil(completed::isDone); completed.get(5, TimeUnit.SECONDS);
        assertTrue(state.queue().isEmpty()); assertEquals(1, personal.get()); assertFalse(planner.hasAdmissions());
        assertThrows(ExecutionException.class, () -> captured.get().get().get(5, TimeUnit.SECONDS));
        assertEquals(1, personal.get()); assertTrue(state.queue().isEmpty());
        verify(dataManager, never()).withAtomicUserTransaction(any(), any(), anyMap(), any());
    }
    @Test void directCompletionFailureRetainsStartFenceAndRecoversUncertainWithoutReplay() throws Exception {
        directRuntime(); planner.retire(); state.close(); var publications = new AtomicInteger();
        state = FeatureStateFixture.create(directory.resolve("features.dat"), (staged, target) -> {
            if (publications.incrementAndGet() == 2) throw new java.io.IOException("completion publication failed");
            DurableFeatureFiles.publish(staged, target);
        });
        state.ready().get(5, TimeUnit.SECONDS); when(plugin.getFeatureState()).thenReturn(state);
        planner = new QueuePlanner(plugin, state); when(plugin.getQueuePlanner()).thenReturn(planner);
        perk.runPerk(user); drainUntil(() -> !planner.hasAdmissions());
        assertEquals(1, activated.get()); assertEquals(0, personal.get()); assertFalse(state.available());
        var id = state.queue().getFirst().id(); assertEquals(FeatureState.Stage.STARTING, state.queue().getFirst().stage());
        planner.check(); assertEquals(1, activated.get()); state.close();
        try (var recovered = new FeatureState(directory.resolve("features.dat"))) {
            recovered.ready().get(5, TimeUnit.SECONDS); assertEquals(id, recovered.queue().getFirst().id());
            assertEquals(FeatureState.Stage.UNCERTAIN, recovered.queue().getFirst().stage());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void queueDisabledLimitedBanksRemainParallelExceptSharedPerkCooldown(boolean sharedCooldown) throws Exception {
        directRuntime(); when(config.getPerkQue()).thenReturn(false);
        when(perk.getInteractionOptions()).thenReturn(new InteractionOptions(false, true, 16, 8, List.of(), List.of(), List.of(), List.of()));
        when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.NoFallDamage)));
        when(perk.getServerWideCoolDown()).thenReturn(sharedCooldown ? 60 : 0);
        when(perk.clone()).thenAnswer(call -> {
            var clone = mock(Perk.class); when(clone.getPerk()).thenReturn("vein");
            when(clone.getEffectedPlayers()).thenReturn(new ArrayList<String>()); return clone;
        });
        UUID secondOwner = UUID.randomUUID(); var secondPlayer = mock(Player.class); var secondUser = mock(MCPerksUser.class);
        when(secondPlayer.getUniqueId()).thenReturn(secondOwner); when(secondPlayer.isOnline()).thenReturn(true);
        var secondWorld = player.getWorld();
        when(secondPlayer.isValid()).thenReturn(true); when(secondPlayer.getWorld()).thenReturn(secondWorld);
        when(Bukkit.getServer().getPlayer(secondOwner)).thenReturn(secondPlayer);
        when(secondUser.getUUID()).thenReturn(secondOwner.toString()); when(secondUser.getActivations("vein")).thenReturn(1);
        when(plugin.getMcperksUserManager().getMCPerksUser(secondOwner)).thenReturn(secondUser);
        when(perk.checkTimesUsed(secondUser)).thenReturn(true); when(perk.checkCoolDown(secondUser)).thenReturn(true);
        when(perk.checkServerWideCoolDown(secondUser)).thenReturn(true);
        doCallRealMethod().when(perk).runPerk(secondUser);
        when(scheduler.implementation.runAtEntityWithFallback(eq(secondPlayer), any(), any())).thenAnswer(call -> {
            java.util.function.Consumer<com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask> action = call.getArgument(1);
            scheduler.tasks.add(() -> action.accept(null));
            return CompletableFuture.completedFuture(com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS);
        });
        var secondCredits = new AtomicInteger(1); var secondCache = mock(com.bencodez.advancedcore.api.user.usercache.UserDataCache.class);
        when(dataManager.getCache(secondOwner)).thenReturn(secondCache); when(dataManager.getPublishedCache(secondOwner)).thenReturn(secondCache);
        when(dataManager.withAtomicUserTransaction(eq(secondOwner), any(), anyMap(), any())).thenAnswer(call -> {
            var transaction = mock(SqlUserStorage.TransactionScope.class); var column = mock(com.bencodez.simpleapi.sql.Column.class);
            when(column.getName()).thenReturn("PerkActivations_vein"); when(column.getValue()).thenAnswer(i -> new com.bencodez.simpleapi.sql.data.DataValueInt(secondCredits.get()));
            when(transaction.readRow()).thenReturn(List.of(column));
            doAnswer(i -> { Map<String, com.bencodez.simpleapi.sql.data.DataValue> values = i.getArgument(0); secondCredits.set(values.get("PerkActivations_vein").getInt()); return null; }).when(transaction).writeValues(anyMap());
            SqlUserStorage.TransactionWork<?> work = call.getArgument(3); return work.run(transaction);
        });
        var flushes = new ConcurrentHashMap<UUID, Runnable>();
        for (var entry : Map.of(owner, dataManager.getCache(owner), secondOwner, secondCache).entrySet()) {
            var first = new java.util.concurrent.atomic.AtomicBoolean(true);
            doAnswer(call -> { Runnable callback = call.getArgument(0); if (first.getAndSet(false)) flushes.put(entry.getKey(), callback); else callback.run(); return null; }).when(entry.getValue()).flushChangesAndRun(any());
        }
        var unrelated = mock(Perk.class); when(unrelated.getPerk()).thenReturn("permanent-global"); when(unrelated.isLastForever()).thenReturn(true);
        handler.getActivePerks().add(unrelated);
        perk.runPerk(user); drainUntil(() -> flushes.containsKey(owner));
        perk.runPerk(secondUser);
        if (sharedCooldown) verify(secondUser).sendMessage(contains("still being confirmed"));
        else { drainUntil(() -> flushes.containsKey(secondOwner)); verify(perk).checkTimesUsed(secondUser); }
        assertEquals(sharedCooldown ? 1 : 2, state.queue().size()); assertEquals(1, personal.get()); assertEquals(1, secondCredits.get());
        flushes.values().forEach(Runnable::run); drainUntil(() -> !planner.hasAdmissions());
        assertTrue(state.queue().isEmpty()); assertEquals(0, personal.get()); assertEquals(sharedCooldown ? 1 : 0, secondCredits.get());
        assertEquals(10_000, state.banks().get(new FeatureState.BankKey(owner, "vein")).milliseconds());
        assertEquals(sharedCooldown ? 2 : 3, handler.getActivePerks().size());
        if (!sharedCooldown) assertEquals(10_000, state.banks().get(new FeatureState.BankKey(secondOwner, "vein")).milliseconds());
        verify(handler, never()).addQue(any(), any()); verify(perk, never()).activatePerk(any(), anyInt(), any());
    }
    private static void field(Perk perk,String name,Object value) throws Exception {
        var field=Perk.class.getDeclaredField(name); field.setAccessible(true); field.set(perk,value);
    }
    @Test void earlierProductionEligibilityBlockedByCalendarSnapshotDoesNotDeadlockWorkerOrOwnerActivation() throws Exception {
        var nativeFile=runtimeFile(); var runtime=plugin.getMcperksServerData(); OrderedServerDataFile ordered;
        try(var constructors=mockConstruction(com.bencodez.simpleapi.scheduler.BukkitScheduler.class)) {
            ordered=new OrderedServerDataFile(plugin,nativeFile,runtime);
        }
        when(plugin.getServerDataFile()).thenReturn(ordered); ordered.setPrevDay(8);
        state.runtimeStorage(()->null).get(5,TimeUnit.SECONDS);
        var eligibilityEntered=new CountDownLatch(1); var releaseEligibility=new CountDownLatch(1);
        var eligibilityThread=new java.util.concurrent.atomic.AtomicReference<Thread>();
        when(user.getPerkCoolDown(perk)).thenAnswer(call->{
            eligibilityThread.set(Thread.currentThread()); eligibilityEntered.countDown();
            assertTrue(releaseEligibility.await(5,TimeUnit.SECONDS)); return 0L;
        });
        doAnswer(call->{ activated.incrementAndGet(); runtime.setPerkExperation(perk,123456); return null; }).when(perk).activatePerk(eq(user),eq(10),nullable(Timestamp.class));
        planner.enqueue(user,perk);
        long eligibilityDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (eligibilityEntered.getCount() != 0 && System.nanoTime() < eligibilityDeadline) {
            Runnable ownerTask = scheduler.tasks.poll(20, TimeUnit.MILLISECONDS);
            if (ownerTask != null) ownerTask.run();
        }
        assertEquals(0, eligibilityEntered.getCount(), "production eligibility did not start");
        var blockedByCalendar=new java.util.concurrent.atomic.AtomicBoolean();
        var live=(org.bukkit.configuration.file.YamlConfiguration)nativeFile.getData();
        doAnswer(call->{
            if(blockedByCalendar.compareAndSet(false,true)) {
                assertTrue(Thread.holdsLock(runtime)); releaseEligibility.countDown();
                var threads=java.lang.management.ManagementFactory.getThreadMXBean();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean observed=false;
                while(System.nanoTime()<deadline) {
                    var info=threads.getThreadInfo(eligibilityThread.get().threadId());
                    if(info!=null && info.getThreadState()==Thread.State.BLOCKED && info.getLockOwnerId()==Thread.currentThread().threadId()
                            && info.getLockInfo().getIdentityHashCode()==System.identityHashCode(runtime)) { observed=true; break; }
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                assertTrue(observed,"production eligibility must block on the calendar-owned runtime monitor");
            }
            return call.callRealMethod();
        }).when(live).saveToString();
        var calendar=CompletableFuture.supplyAsync(()->ordered.beginTimeChangeTransition(com.bencodez.advancedcore.api.time.TimeType.DAY,"2026-10-09","9"));
        try {
            var transition=calendar.get(5,TimeUnit.SECONDS); assertTrue(blockedByCalendar.get());
            drainUntil(()->activated.get()==1 && state.queue().isEmpty());
            ordered.completeTimeChangeTransition(transition); state.runtimeStorage(()->null).get(5,TimeUnit.SECONDS);
            var written=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("ServerData.yml").toFile());
            assertEquals(123456,written.getLong("Experation.vein")); assertEquals(9,written.getInt("PrevDay"));
            assertFalse(written.getBoolean("TimeTransitions.DAY.Pending")); assertEquals(0,personal.get());
        } finally { releaseEligibility.countDown(); }
    }
    @Test void inheritedCalendarTransitionWaitsBehindOlderRuntimeSnapshotAndStaysCompleteAfterRestart() throws Exception {
        var nativeFile=runtimeFile(); var runtime=plugin.getMcperksServerData(); OrderedServerDataFile ordered;
        try (var constructors=mockConstruction(com.bencodez.simpleapi.scheduler.BukkitScheduler.class)) {
            ordered=new OrderedServerDataFile(plugin,nativeFile,runtime);
        }
        when(plugin.getServerDataFile()).thenReturn(ordered); ordered.setPrevDay(8);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var calendarEntered=new CountDownLatch(1);
        state.runtimeStorage(() -> { entered.countDown(); try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch (InterruptedException error) { throw new RuntimeException(error); } return null; });
        assertTrue(entered.await(5,TimeUnit.SECONDS)); runtime.setPerkExperation(perk,123456);
        var calendar=CompletableFuture.supplyAsync(() -> {
            calendarEntered.countDown(); return ordered.beginTimeChangeTransition(com.bencodez.advancedcore.api.time.TimeType.DAY,"2026-10-09","9");
        });
        try {
            assertTrue(calendarEntered.await(5,TimeUnit.SECONDS)); assertThrows(TimeoutException.class, () -> calendar.get(100,TimeUnit.MILLISECONDS));
        } finally { release.countDown(); }
        var transition=calendar.get(5,TimeUnit.SECONDS);
        ordered.failTimeChangeTransition(transition); ordered.completeTimeChangeTransition(transition);
        state.runtimeStorage(() -> null).get(5,TimeUnit.SECONDS);
        var written=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("ServerData.yml").toFile());
        assertEquals(123456,written.getLong("Experation.vein")); assertEquals(9,written.getInt("PrevDay"));
        when(nativeFile.getData()).thenReturn(written);
        assertNull(ordered.getPendingTimeChangeTransition(com.bencodez.advancedcore.api.time.TimeType.DAY));
        assertFalse(ordered.isPendingTimeChangeTransition(transition)); verify(nativeFile,never()).saveData();
    }
    @Test void inheritedCalendarCompletionRollsBackLiveMarkerWhenOrderedPublicationFails() throws Exception {
        var nativeFile=runtimeFile(); var runtime=plugin.getMcperksServerData(); OrderedServerDataFile ordered;
        try (var constructors=mockConstruction(com.bencodez.simpleapi.scheduler.BukkitScheduler.class)) {
            ordered=new OrderedServerDataFile(plugin,nativeFile,runtime);
        }
        when(plugin.getServerDataFile()).thenReturn(ordered); ordered.setPrevDay(8);
        var transition=ordered.beginTimeChangeTransition(com.bencodez.advancedcore.api.time.TimeType.DAY,"2026-10-09","9");
        Files.delete(directory.resolve("ServerData.yml")); Files.createDirectory(directory.resolve("ServerData.yml"));
        assertThrows(CompletionException.class, () -> ordered.completeTimeChangeTransition(transition));
        assertEquals(8,ordered.getPrevDay()); assertTrue(ordered.isPendingTimeChangeTransition(transition));
        assertEquals(transition.id(),ordered.getPendingTimeChangeTransition(com.bencodez.advancedcore.api.time.TimeType.DAY).id());
    }
    @Test void actualQueuedLegacyActivationKeepsStartingUntilImmutableYamlAndCapturedLogPublish() throws Exception {
        var nativeFile=runtimeFile(); when(perk.getPerkType()).thenReturn(PerkSystemType.ALL);
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicInteger publications=new AtomicInteger();
        var constructor=ServerData.class.getDeclaredConstructor(MCPerksMain.class,DurableFeatureFiles.Publisher.class);
        constructor.setAccessible(true);
        DurableFeatureFiles.Publisher publisher=(staged,file)->{
            if(publications.getAndIncrement()==0) {
                entered.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }
            DurableFeatureFiles.publish(staged,file);
        };
        when(plugin.getMcperksServerData()).thenReturn(constructor.newInstance(plugin,publisher));
        var nativeEffects=new AtomicInteger(); var logThread=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(i->{
            assertNotEquals("MCPerks-feature-store",Thread.currentThread().getName());
            nativeEffects.incrementAndGet(); plugin.getMcperksServerData().setPerkExperation(perk,123456);
            plugin.getMcperksServerData().addPerkHistory("actual queued start");
            plugin.getMcperksServerData().activationLog(()->logThread.set(Thread.currentThread().getName()));
            return null;
        }).when(perk).activatePerk(eq(user),eq(10),nullable(Timestamp.class));
        planner.enqueue(user,perk);
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(entered.getCount()!=0 && System.nanoTime()<deadline) {
                Runnable task=scheduler.tasks.poll(20,TimeUnit.MILLISECONDS);
                if(task!=null) { task.run(); task.run(); }
            }
            assertTrue(entered.await(100,TimeUnit.MILLISECONDS));
            assertEquals(0,nativeEffects.get()); assertEquals(0,personal.get());
            assertEquals(FeatureState.Stage.STARTING,state.queue().getFirst().stage());
            verify(nativeFile,never()).saveData(); verify((org.bukkit.configuration.file.YamlConfiguration)nativeFile.getData(),never()).save(any(java.io.File.class));
            // A later calendar snapshot must retain the perk fields and run after the captured activation snapshot.
            OrderedServerDataFile ordered;
            try(var constructors=mockConstruction(com.bencodez.simpleapi.scheduler.BukkitScheduler.class)) {
                ordered=new OrderedServerDataFile(plugin,nativeFile,plugin.getMcperksServerData());
            }
            ordered.setPrevDay(9); ordered.reloadData(); verify(nativeFile,never()).reloadData();
            assertFalse(Files.exists(directory.resolve("ServerData.yml"))); release.countDown();
            drainUntil(()->state.queue().isEmpty()); state.storage(()->null).get(5,TimeUnit.SECONDS);
            var written=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("ServerData.yml").toFile());
            assertEquals(123456,written.getLong("Experation.vein")); assertEquals(List.of("actual queued start"),written.getStringList("PerkHistory.OCTOBER")); assertEquals(9,written.getInt("PrevDay"));
            assertEquals("MCPerks-feature-store",logThread.get());
            ordered.reloadData(); verify(nativeFile,times(1)).reloadData();
        } finally { release.countDown(); }
    }
    @Test void failedQueuedLegacyYamlPublicationKeepsUncertainFenceWithoutReplayingEffectOrDebit() throws Exception {
        runtimeFile(); Files.createDirectory(directory.resolve("ServerData.yml"));
        doAnswer(i->{ activated.incrementAndGet(); plugin.getMcperksServerData().setPerkExperation(perk,123456); plugin.getMcperksServerData().addPerkHistory("failed publish"); return null; }).when(perk).activatePerk(eq(user),eq(10),nullable(Timestamp.class));
        planner.enqueue(user,perk); drainUntil(()->!state.queue().isEmpty() && state.queue().getFirst().stage()==FeatureState.Stage.UNCERTAIN);
        planner.check(); state.storage(()->null).get(); assertEquals(0,activated.get()); assertEquals(0,personal.get());
        verify(dataManager,times(1)).withAtomicUserTransaction(any(),any(),anyMap(),any());
    }

    @Test void realPerkHandlerAllExpirationHistoryAndOptionalActivationLogUseConfirmedWorkerPublication() throws Exception {
        var nativeFile=runtimeFile(); MCPerksMain previous=MCPerksMain.plugin; MCPerksMain.plugin=plugin;
        var actual=spy(new PerkHandler(plugin,List.of())); doReturn(perk).when(actual).getPerk("vein"); when(plugin.getPerkHandler()).thenReturn(actual);
        var clone=mock(Perk.class); when(perk.clone()).thenReturn(clone); when(perk.getPerkType()).thenReturn(PerkSystemType.ALL);
        field(clone, "plugin", plugin); // A real Perk constructor captures its owning plugin.
        when(perk.getEffects()).thenReturn(new ArrayList<>()); when(perk.getName()).thenReturn("Vein");
        when(clone.getPerkType()).thenReturn(PerkSystemType.ALL); when(clone.getPerk()).thenReturn("vein");
        when(clone.getEffectedPlayers()).thenReturn(new ArrayList<>()); when(clone.getEffects()).thenReturn(new ArrayList<>());
        when(clone.getActivater()).thenReturn(user); when(clone.isTimed()).thenReturn(true); when(user.getPlayerName()).thenReturn("requester");
        doCallRealMethod().when(clone).setExperation(eq(user),anyLong()); doCallRealMethod().when(perk).activatePerk(eq(user),eq(10),nullable(Timestamp.class));
        var effects=mock(EffectHandler.class); when(plugin.getEffectHandler()).thenReturn(effects);
        doAnswer(i->{ assertNotEquals("MCPerks-feature-store",Thread.currentThread().getName()); activated.incrementAndGet(); return null; }).when(effects).activate(clone,user,true);
        when(plugin.getRewardHandler()).thenReturn(mock(com.bencodez.advancedcore.api.rewards.RewardHandler.class));
        var writer=mock(com.bencodez.advancedcore.logger.Logger.class); var logThread=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(i->{ logThread.set(Thread.currentThread().getName()); return null; }).when(writer).logToFile(anyString());
        var logField=PerkHandler.class.getDeclaredField("activationLog"); logField.setAccessible(true); logField.set(actual,writer); when(config.getLogActivation()).thenReturn(true);
        var perkConfig=mock(com.bencodez.mcperks.configs.ConfigPerks.class); when(perkConfig.getCountDownTimes("vein")).thenReturn(new ArrayList<>());
        var language=mock(com.bencodez.mcperks.configs.Lang.class); when(language.getCountDownTimer()).thenReturn("");
        try(var configStatic=mockStatic(com.bencodez.mcperks.configs.ConfigPerks.class); var langStatic=mockStatic(com.bencodez.mcperks.configs.Lang.class)) {
            configStatic.when(com.bencodez.mcperks.configs.ConfigPerks::getInstance).thenReturn(perkConfig);
            langStatic.when(com.bencodez.mcperks.configs.Lang::getInstance).thenReturn(language);
            planner.enqueue(user,perk); drainUntil(()->activated.get()==1 && state.queue().isEmpty());
            var written=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("ServerData.yml").toFile());
            assertTrue(written.getLong("Experation.vein")>System.currentTimeMillis()); assertEquals(1,written.getStringList("PerkHistory.OCTOBER").size());
            verify(nativeFile,never()).saveData(); verify(writer,times(1)).logToFile(anyString()); assertEquals("MCPerks-feature-store",logThread.get());
            verify(effects,times(1)).activate(clone,user,true); verify(dataManager,times(1)).withAtomicUserTransaction(any(),any(),anyMap(),any());
        } finally {
            var timer=PerkHandler.class.getDeclaredField("timer"); timer.setAccessible(true); ((Timer)timer.get(actual)).cancel(); MCPerksMain.plugin=previous;
        }
    }
    @Test void activeLegacyShutdownReadsExpirationAndDeactivatesBeforeNativeStorageRetirement() throws Exception {
        runtimeFile();
        MCPerksMain previous = MCPerksMain.plugin; MCPerksMain.plugin = plugin;
        var stateField = MCPerksMain.class.getDeclaredField("featureState");
        stateField.setAccessible(true); stateField.set(plugin, state);
        var retired = new java.util.concurrent.atomic.AtomicBoolean();
        var deactivated = new AtomicInteger();
        // Bukkit marks a plugin disabled before calling onDisable; do not rely on isEnabled.
        when(plugin.isEnabled()).thenReturn(false);
        when(perk.getActivater()).thenReturn(user);
        when(perk.getKnownExpiration()).thenReturn(1000L);
        when(perk.getExperation(user)).thenAnswer(call -> {
            assertFalse(retired.get(), "expiration read after native storage retirement");
            return 987654L; // Authoritative extension, deliberately different from an old cached value.
        });
        when(perk.detachForShutdown()).thenAnswer(call -> {
            assertFalse(retired.get(), "effect cleanup admitted after native storage retirement");
            deactivated.incrementAndGet(); return true;
        });
        doCallRealMethod().when(perk).persistForShutdown();
        doCallRealMethod().when(perk).setExperation(user, 0);
        when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>(List.of(perk)));
        try (var lifecycle = mockConstruction(com.bencodez.advancedcore.lifecycle.AdvancedCoreLifecycle.class,
                (cleanup, context) -> doAnswer(call -> {
                    plugin.onBeforeStorageShutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    retired.set(true); plugin.onUnLoad(); plugin.onShutdownComplete(); return null;
                }).when(cleanup).shutdown())) {
            doCallRealMethod().when(plugin).onUnLoad(); doCallRealMethod().when(plugin).onDisable();
            doCallRealMethod().when(plugin).onBeforeStorageShutdown(); doCallRealMethod().when(plugin).onShutdownComplete();
            assertDoesNotThrow(plugin::onDisable);
            assertEquals(1, deactivated.get());
            assertEquals(987654L, plugin.getMcperksServerData().getData().getLong(
                    "OfflinePerk." + owner + ".vein.Experiaton"));
            verify(perk, times(1)).detachForShutdown(); verify(perk, never()).deactivatePerk(any());
            verify(perk, times(1)).getExperation(user); verify(user).setPerkExperation(perk, 0);
        } finally { MCPerksMain.plugin = previous; }
    }

    @Test void deferredNativeCleanupKeepsFileOwnerOpenUntilLegacyShutdownPersistenceSettles() throws Exception {
        verifyDeferredShutdownPersistence(false);
    }

    @Test void deferredSharedPerkShutdownUsesCapturedOwnerAfterStaticPluginIsCleared() throws Exception {
        verifyDeferredShutdownPersistence(true);
    }

    private void verifyDeferredShutdownPersistence(boolean shared) throws Exception {
        runtimeFile();
        MCPerksMain previous = MCPerksMain.plugin; MCPerksMain.plugin = plugin;
        var stateField = MCPerksMain.class.getDeclaredField("featureState");
        stateField.setAccessible(true); stateField.set(plugin, state);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        state.runtimeStorage(() -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch (InterruptedException error) { throw new RuntimeException(error); }
            return null;
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        when(plugin.isEnabled()).thenReturn(false);
        when(perk.getActivater()).thenReturn(user);
        if (shared) {
            when(perk.getPerkType()).thenReturn(PerkSystemType.ALL);
            plugin.getMcperksServerData().setPerkExperation(perk, 987654L);
            doCallRealMethod().when(perk).getExperation(user);
        } else when(perk.getExperation(user)).thenReturn(987654L);
        when(perk.detachForShutdown()).thenReturn(true);
        doCallRealMethod().when(perk).persistForShutdown();
        doCallRealMethod().when(perk).setExperation(user, 0);
        when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>(List.of(perk)));
        try (var lifecycle = mockConstruction(com.bencodez.advancedcore.lifecycle.AdvancedCoreLifecycle.class,
                (cleanup, context) -> doAnswer(call -> {
                    assertFalse(plugin.onBeforeStorageShutdown().toCompletableFuture().isDone());
                    // Native primary-thread cleanup does not synchronously wait for storage.
                    plugin.onUnLoad(); plugin.onShutdownComplete(); return null;
                }).when(cleanup).shutdown())) {
            doCallRealMethod().when(plugin).onUnLoad(); doCallRealMethod().when(plugin).onDisable();
            doCallRealMethod().when(plugin).onBeforeStorageShutdown(); doCallRealMethod().when(plugin).onShutdownComplete();
            plugin.onDisable();
            release.countDown();
            plugin.onBeforeStorageShutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(987654L, plugin.getMcperksServerData().getData().getLong(
                    "OfflinePerk." + owner + ".vein.Experiaton"));
            if (shared) {
                assertEquals(0, plugin.getMcperksServerData().getPerkExperation(perk));
                verify(user, never()).setPerkExperation(any(), anyLong());
            } else verify(user).setPerkExperation(perk, 0);
        } finally { release.countDown(); MCPerksMain.plugin = previous; }
    }

    @Test void gracefulPluginDisableRetainsTheWorkerThroughInheritedFinalRuntimeSave() throws Exception {
        var nativeFile = runtimeFile(); MCPerksMain previous=MCPerksMain.plugin; MCPerksMain.plugin=plugin;
        var stateField=MCPerksMain.class.getDeclaredField("featureState"); stateField.setAccessible(true); stateField.set(plugin,state);
        var runtime=plugin.getMcperksServerData(); runtime.setServerCooldown("vein",1234);
        try (var constructors = mockConstruction(com.bencodez.simpleapi.scheduler.BukkitScheduler.class)) {
            var ordered = new com.bencodez.mcperks.data.OrderedServerDataFile(plugin, nativeFile, runtime);
            when(plugin.getServerDataFile()).thenReturn(ordered);
        }
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        state.storage(()->{ entered.countDown(); try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch(InterruptedException e) { throw new RuntimeException(e); } return null; });
        try (var lifecycle = mockConstruction(com.bencodez.advancedcore.lifecycle.AdvancedCoreLifecycle.class,
                (cleanup, context) -> doAnswer(call -> {
                    plugin.onUnLoad();
                    // The native PermissionHandler has this additional save after onUnLoad.
                    runtime.setServerCooldown("vein", 5678);
                    plugin.getServerDataFile().saveData();
                    plugin.onShutdownComplete();
                    return null;
                }).when(cleanup).shutdown())) {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            doCallRealMethod().when(plugin).onUnLoad(); doCallRealMethod().when(plugin).onDisable();
            doCallRealMethod().when(plugin).onShutdownComplete(); plugin.onDisable();
            assertEquals(1, lifecycle.constructed().size());
            assertFalse(Files.exists(directory.resolve("ServerData.yml")));
            try(FeatureState successor=new FeatureState(directory.resolve("features.dat"))) {
                assertFalse(successor.ready().isDone()); release.countDown(); successor.ready().get(5,TimeUnit.SECONDS);
                var written=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("ServerData.yml").toFile());
                assertEquals(5678,written.getLong("vein.CoolDown"));
            }
        } finally { release.countDown(); MCPerksMain.plugin=previous; }
    }

    @Test void successorRuntimeReconcilesConfirmedPredecessorDataAndCalendarChangesMadeDuringDrain() throws Exception {
        var nativeFile=runtimeFile(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        Path runtimePath=directory.resolve("ServerData.yml");
        state.runtimeStorage(()->{ entered.countDown(); try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch(InterruptedException e) { throw new RuntimeException(e); } return null; });
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        plugin.getMcperksServerData().setPerkExperation(perk,987654); state.close();
        try(FeatureState successor=new FeatureState(directory.resolve("features.dat"))) {
            when(plugin.getFeatureState()).thenReturn(successor);
            // This is the stale cache AdvancedCore can read before the predecessor's final snapshot publishes.
            var stale=new org.bukkit.configuration.file.YamlConfiguration(); stale.set("PrevDay",7); when(nativeFile.getData()).thenReturn(stale);
            var restored=new ServerData(plugin); when(plugin.getMcperksServerData()).thenReturn(restored);
            var initialized=restored.initialize(successor); assertFalse(initialized.isDone());
            OrderedServerDataFile ordered;
            try(var constructors=mockConstruction(com.bencodez.simpleapi.scheduler.BukkitScheduler.class)) { ordered=new OrderedServerDataFile(plugin,nativeFile,restored); }
            ordered.setPrevDay(9); assertFalse(restored.isReady());
            var premature=new AtomicInteger(); assertThrows(ExecutionException.class,()->restored.confirmedActivation(premature::incrementAndGet).get()); assertEquals(0,premature.get());
            release.countDown(); initialized.get(5,TimeUnit.SECONDS); assertTrue(restored.isReady());
            assertEquals(987654,restored.getPerkExperation(perk)); assertEquals(9,ordered.getPrevDay());
            var written=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(runtimePath.toFile());
            assertEquals(987654,written.getLong("Experation.vein")); assertEquals(9,written.getInt("PrevDay")); verify(nativeFile,never()).saveData();
        } finally { release.countDown(); }
    }
    @Test void unreadableOptionalFeatureFileDoesNotDisableIndependentLegacyRuntimePersistence() throws Exception {
        runtimeFile(); state.close(); Path broken=directory.resolve("broken-features.dat"); Files.writeString(broken,"invalid feature state");
        try(FeatureState unavailable=new FeatureState(broken)) {
            assertThrows(ExecutionException.class,()->unavailable.ready().get()); when(plugin.getFeatureState()).thenReturn(unavailable);
            var runtime=new ServerData(plugin); when(plugin.getMcperksServerData()).thenReturn(runtime); runtime.initialize(unavailable).get(5,TimeUnit.SECONDS);
            assertFalse(unavailable.available()); assertTrue(runtime.isReady());
            runtime.confirmedActivation(()->runtime.setPerkExperation(perk,1234)).get(5,TimeUnit.SECONDS);
            var written=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("ServerData.yml").toFile());
            assertEquals(1234,written.getLong("Experation.vein")); assertEquals("invalid feature state",Files.readString(broken));
        }
    }

    private PerkHandler restorer(BlockingQueue<Runnable> restorationTasks, Perk legacy) throws Exception {
        var actual = spy(new PerkHandler(plugin, List.of()));
        var timer = PerkHandler.class.getDeclaredField("timer"); timer.setAccessible(true); ((Timer) timer.get(actual)).cancel();
        when(plugin.getPerkHandler()).thenReturn(actual);
        actual.setPerk("vein", perk); actual.setPerk("permanent", legacy);
        when(plugin.getUserManager().getAllUUIDs()).thenReturn(new ArrayList<>(List.of(owner.toString())));
        doAnswer(i -> { restorationTasks.add(i.getArgument(1)); return null; })
                .when(scheduler.scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        return actual;
    }
    private Perk permanent() {
        var legacy = mock(Perk.class); when(legacy.getPerk()).thenReturn("permanent"); when(legacy.isEnabled()).thenReturn(true);
        when(legacy.getExperation(user)).thenReturn(-1L); return legacy;
    }
    @Test void persistedQueueWaitsForDelayedLegacyRestorationBeforeAnyClaimOrDebit() throws Exception {
        var entry = state.enqueue(owner, "vein", "PerkActivations_vein", 1).get(5, TimeUnit.SECONDS);
        planner.retire(); state.close(); state = new FeatureState(directory.resolve("features.dat")); state.ready().get(5, TimeUnit.SECONDS);
        when(plugin.getFeatureState()).thenReturn(state); planner = new QueuePlanner(plugin, state); when(plugin.getQueuePlanner()).thenReturn(planner);
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent(); var actual = restorer(tasks, legacy);
        doAnswer(i -> { actual.getActivePerks().add(legacy); return null; }).when(legacy).activatePerk(user, 0);
        var restored = actual.loadActivePerksConfirmed(() -> {});
        planner.check(); planner.check();
        assertFalse(actual.isRestorationReady()); assertFalse(restored.isDone()); assertFalse(planner.isStarting());
        assertEquals(entry, state.queue().getFirst()); assertEquals(1, personal.get()); assertTrue(scheduler.tasks.isEmpty());
        assertTrue(planner.estimatedWaits().get(5, TimeUnit.SECONDS).isEmpty());
        Objects.requireNonNull(tasks.poll(5, TimeUnit.SECONDS)).run(); restored.get(5, TimeUnit.SECONDS);
        assertTrue(actual.isRestorationReady()); assertEquals(List.of(legacy), actual.getActivePerks());
        planner.check(); assertEquals(FeatureState.Stage.WAITING, state.queue().getFirst().stage()); assertEquals(1, personal.get());
        actual.getActivePerks().clear(); planner.check(); drainUntil(() -> activated.get() == 1 && state.queue().isEmpty());
        assertEquals(0, personal.get()); verify(dataManager, times(1)).withAtomicUserTransaction(any(), any(), anyMap(), any());
    }
    @Test void shutdownRecordRestorationAlsoKeepsQueueWaitingUntilItsActualCallbackReturns() throws Exception {
        state.enqueue(owner, "vein", "PerkActivations_vein", 1).get(5, TimeUnit.SECONDS);
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent(); var actual = restorer(tasks, legacy);
        plugin.getMcperksServerData().getData().set("OfflinePerk." + owner + ".permanent.Experiaton", -1);
        doAnswer(i -> { actual.getActivePerks().add(legacy); return null; }).when(actual).restoreActivePerk(eq(legacy), eq(user), isNull());
        var shutdown = MCPerksMain.class.getDeclaredMethod("restoreOfflinePerks"); shutdown.setAccessible(true);
        var finished = new java.util.concurrent.atomic.AtomicBoolean();
        var restored = actual.loadActivePerksConfirmed(() -> {
            assertFalse(actual.isRestorationReady()); planner.check();
            assertFalse(planner.isStarting()); assertEquals(1, personal.get());
            try { shutdown.invoke(plugin); } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
            finished.set(true);
        });
        planner.check(); Objects.requireNonNull(tasks.poll(5, TimeUnit.SECONDS)).run(); restored.get(5, TimeUnit.SECONDS);
        assertTrue(finished.get()); assertTrue(actual.isRestorationReady()); assertEquals(List.of(legacy), actual.getActivePerks());
        verify(actual).restoreActivePerk(legacy, user, null);
        verify(plugin.getMcperksServerData()).removeOfflinePerkIfMatches(owner.toString(), "permanent", -1L);
        verify(actual, never()).activePerk(any(), any(), anyInt(), any());
        planner.check(); assertEquals(FeatureState.Stage.WAITING, state.queue().getFirst().stage()); assertEquals(1, personal.get());
        verify(dataManager, never()).withAtomicUserTransaction(any(), any(), anyMap(), any());
    }
    @Test void unavailableShutdownDefinitionsDoNotBlockHealthyRestorationOrQueueAdmission() throws Exception {
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent(); var actual = restorer(tasks, legacy);
        when(legacy.getExperation(user)).thenReturn(0L);
        var disabled = mock(Perk.class); when(actual.getPerk("disabled")).thenReturn(disabled);
        for (String key : List.of("missing", "disabled", "permanent")) plugin.getMcperksServerData().getData().set("OfflinePerk." + owner + "." + key + ".Experiaton", -1);
        doAnswer(i -> { actual.getActivePerks().add(legacy); return null; }).when(actual).restoreActivePerk(eq(legacy), eq(user), isNull());
        var shutdown = MCPerksMain.class.getDeclaredMethod("restoreOfflinePerks"); shutdown.setAccessible(true);
        var restored = actual.loadActivePerksConfirmed(() -> {
            try { shutdown.invoke(plugin); } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
        });
        Objects.requireNonNull(tasks.poll(5, TimeUnit.SECONDS)).run(); restored.get(5, TimeUnit.SECONDS);
        assertTrue(actual.isRestorationReady()); assertEquals(List.of(legacy),actual.getActivePerks());
        verify(actual).restoreActivePerk(legacy,user,null); verify(actual,never()).restoreActivePerk(eq(disabled),any(),any());
        verify(plugin.getMcperksServerData(), never()).removeOfflinePerkIfMatches(owner.toString(), "missing", -1L);
        verify(plugin.getMcperksServerData(), never()).removeOfflinePerkIfMatches(owner.toString(), "disabled", -1L);
        verify(plugin.getMcperksServerData()).removeOfflinePerkIfMatches(owner.toString(), "permanent", -1L);
    }
    @Test void skippedShutdownRecordsSurviveSerializedRestartAndRestoreAfterDefinitionsReturn() throws Exception {
        var nativeFile = runtimeFile();
        var runtime = plugin.getMcperksServerData();
        var yaml = runtime.getData();
        for (String key : List.of("missing", "disabled", "permanent"))
            yaml.set("OfflinePerk." + owner + "." + key + ".Experiaton", -1L);
        var legacy = permanent(); var actual = restorer(new LinkedBlockingQueue<>(), legacy);
        var disabled = mock(Perk.class); doReturn(disabled).when(actual).getPerk("disabled");
        doNothing().when(actual).restoreActivePerk(any(), any(), any());
        var shutdown = MCPerksMain.class.getDeclaredMethod("restoreOfflinePerks"); shutdown.setAccessible(true);
        shutdown.invoke(plugin);
        assertFalse(yaml.contains("OfflinePerk." + owner + ".permanent"));
        assertEquals(-1L, yaml.getLong("OfflinePerk." + owner + ".missing.Experiaton"));
        assertEquals(-1L, yaml.getLong("OfflinePerk." + owner + ".disabled.Experiaton"));
        state.storage(() -> null).get(5, TimeUnit.SECONDS);
        var recovered = new org.bukkit.configuration.file.YamlConfiguration();
        recovered.loadFromString(Files.readString(directory.resolve("ServerData.yml")));
        when(nativeFile.getData()).thenReturn(recovered);
        when(disabled.isEnabled()).thenReturn(true); doReturn(disabled).when(actual).getPerk("missing");
        shutdown.invoke(plugin);
        assertFalse(recovered.contains("OfflinePerk"));
        verify(actual, times(1)).restoreActivePerk(legacy, user, null);
        verify(actual, times(2)).restoreActivePerk(disabled, user, null);
        shutdown.invoke(plugin);
        verify(actual, times(2)).restoreActivePerk(disabled, user, null);
    }
    @Test void shutdownRecordRetirementCannotEraseAReplacementOrUnrelatedMetadata() throws Exception {
        runtimeFile(); var runtime = plugin.getMcperksServerData(); var yaml = runtime.getData();
        String record = "OfflinePerk." + owner + ".permanent";
        yaml.set(record + ".Experiaton", 123L);
        assertFalse(runtime.removeOfflinePerkIfMatches(owner.toString(), "permanent", -1L));
        assertEquals(123L, yaml.getLong(record + ".Experiaton"));
        yaml.set(record + ".FutureMetadata", "preserved");
        assertTrue(runtime.removeOfflinePerkIfMatches(owner.toString(), "permanent", 123L));
        assertEquals("preserved", yaml.getString(record + ".FutureMetadata"));
        assertFalse(runtime.removeOfflinePerkIfMatches(owner.toString(), "permanent", 123L));
    }
    @Test void futureShutdownExpirationRestoresWithOriginalExpirationAndExpiredRecordIsRetired() throws Exception {
        long future = System.currentTimeMillis() + 60_000L;
        plugin.getMcperksServerData().getData().set("OfflinePerk." + owner + ".permanent.Experiaton", future);
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent(); var actual = restorer(tasks, legacy);
        when(actual.getPerk("permanent")).thenReturn(legacy);
        doAnswer(i -> { actual.getActivePerks().add(legacy); return null; }).when(actual)
                .restoreActivePerk(eq(legacy), eq(user), nullable(Timestamp.class));
        var shutdown = MCPerksMain.class.getDeclaredMethod("restoreOfflinePerks"); shutdown.setAccessible(true);
        shutdown.invoke(plugin);
        verify(actual).restoreActivePerk(eq(legacy), eq(user), argThat(value -> value != null && value.getTime() == future));
        verify(plugin.getMcperksServerData()).removeOfflinePerkIfMatches(owner.toString(), "permanent", future);

        reset(actual, plugin.getMcperksServerData());
        when(plugin.getMcperksServerData().getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        long expired = System.currentTimeMillis() - 60_000L;
        plugin.getMcperksServerData().getData().set("OfflinePerk." + owner + ".permanent.Experiaton", expired);
        when(actual.getPerk("permanent")).thenReturn(legacy);
        shutdown.invoke(plugin);
        verify(actual, never()).activePerk(any(), any(), anyInt(), any());
        verify(plugin.getMcperksServerData()).removeOfflinePerkIfMatches(owner.toString(), "permanent", expired);
    }
    @Test void failedShutdownActivationRetainsRecordForRetry() throws Exception {
        plugin.getMcperksServerData().getData().set("OfflinePerk." + owner + ".permanent.Experiaton", -1L);
        var legacy = permanent(); var actual = restorer(new LinkedBlockingQueue<>(), legacy);
        when(actual.getPerk("permanent")).thenReturn(legacy);
        doThrow(new IllegalStateException("activation unavailable")).when(actual)
                .restoreActivePerk(eq(legacy), eq(user), isNull());
        var shutdown = MCPerksMain.class.getDeclaredMethod("restoreOfflinePerks"); shutdown.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> shutdown.invoke(plugin));
        verify(plugin.getMcperksServerData(), never()).removeOfflinePerkIfMatches(owner.toString(), "permanent", -1L);
    }
    @Test void failedLegacyRestorationLeavesPersistedQueueAndCreditUntouched() throws Exception {
        var entry = state.enqueue(owner, "vein", "PerkActivations_vein", 1).get(5, TimeUnit.SECONDS);
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent(); var actual = restorer(tasks, legacy);
        when(legacy.getExperation(user)).thenThrow(new IllegalStateException("user storage unavailable"));
        var restored = actual.loadActivePerksConfirmed(() -> fail("shutdown records must not run after scan failure"));
        Objects.requireNonNull(tasks.poll(5, TimeUnit.SECONDS)).run();
        assertThrows(ExecutionException.class, () -> restored.get(5, TimeUnit.SECONDS)); planner.check();
        assertFalse(actual.isRestorationReady()); assertFalse(planner.isStarting()); assertEquals(entry, state.queue().getFirst());
        assertEquals("legacy perk restoration unavailable", planner.startUnavailable(entry)); assertEquals(1, personal.get());
        verify(dataManager, never()).withAtomicUserTransaction(any(), any(), anyMap(), any()); verify(perk, never()).executeConfirmed(any(), anyInt());
    }
    @Test void retiredLegacyRestorationCannotMutateTheSuccessorHandler() throws Exception {
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent(); var actual = restorer(tasks, legacy);
        var restored = actual.loadActivePerksConfirmed(() -> fail("retired shutdown callback"));
        when(plugin.getPerkHandler()).thenReturn(handler);
        Objects.requireNonNull(tasks.poll(5, TimeUnit.SECONDS)).run();
        assertThrows(ExecutionException.class, () -> restored.get(5, TimeUnit.SECONDS)); assertTrue(actual.getActivePerks().isEmpty());
        verify(legacy, never()).activatePerk(any(), anyInt());
    }
    @Test void rejectedRestorationSchedulingFailsClosedBeforeAnyQueuedDebit() throws Exception {
        var entry = state.enqueue(owner, "vein", "PerkActivations_vein", 1).get(5, TimeUnit.SECONDS);
        var actual = restorer(new LinkedBlockingQueue<>(), permanent());
        doThrow(new IllegalStateException("scheduler rejected")).when(scheduler.scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        var restored = actual.loadActivePerksConfirmed(() -> fail("unscheduled shutdown callback"));
        assertThrows(ExecutionException.class, () -> restored.get(5, TimeUnit.SECONDS)); planner.check();
        assertFalse(actual.isRestorationReady()); assertEquals(entry, state.queue().getFirst()); assertEquals(1, personal.get());
        verify(dataManager, never()).withAtomicUserTransaction(any(), any(), anyMap(), any());
    }
    @Test void directPurchaseDuringLegacyRestorationRejectsBeforeCreditOrGameplay() throws Exception {
        runtimeFile(); field(perk, "perk", "vein"); doCallRealMethod().when(perk).runPerk(user);
        when(handler.isRestorationReady()).thenReturn(false); perk.runPerk(user);
        verify(user).sendMessage(contains("runtime state is unavailable"));
        verify(user, never()).addActivation(anyInt()); verify(user, never()).addActivation(anyString(), anyInt());
        verify(perk, never()).executeConfirmed(any(), anyInt()); assertEquals(1, personal.get()); assertTrue(state.queue().isEmpty());
    }
}
