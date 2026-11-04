package com.bencodez.mcperks.tests.perk;
import static com.bencodez.mcperks.tests.TestAccess.*;
import com.bencodez.mcperks.perk.*;
import com.bencodez.mcperks.perk.Effect;
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
import com.bencodez.mcperks.tests.scheduling.OwnerTaskHarness;
class NativePersistenceLifecycleTest {
    @Test void uncachedLegacyShutdownCompletesAfterDirectExpirationWrite() throws Exception {
        runtimeFile();
        when(perk.getActivater()).thenReturn(user);
        when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        when(perk.getExperation(user)).thenReturn(987654L);
        when(dataManager.getCache(owner)).thenReturn(null);
        doCallRealMethod().when(perk).persistForShutdown();
        doCallRealMethod().when(perk).setExperation(user, 0);
        perk.persistForShutdown().get(5, TimeUnit.SECONDS);
        verify(user).setPerkExperation(perk, 0);
        assertEquals(987654L, plugin.getMcperksServerData().getData().getLong(
                "OfflinePerk." + owner + ".vein.Experiaton"));
    }
    @Test void missingSharedShutdownCacheCannotBeAcknowledgedAsDurable() throws Exception {
        runtimeFile();
        when(perk.getActivater()).thenReturn(user);
        when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        when(perk.getExperation(user)).thenReturn(987654L);
        when(dataManager.hasSharedSqlBackend()).thenReturn(true);
        when(dataManager.getCache(owner)).thenReturn(null);
        doCallRealMethod().when(perk).persistForShutdown();
        doCallRealMethod().when(perk).setExperation(user, 0);
        var failure = assertThrows(ExecutionException.class, () -> perk.persistForShutdown().get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }
    @TempDir Path directory;
    private UUID owner; private Player player; private MCPerksMain plugin; private MCPerksUser user;
    private Perk perk; private Config config; private PerkHandler handler; private UserDataManager dataManager;
    private OrderedStorage state; private OwnerTaskHarness scheduler;
    private AtomicInteger personal, global, activated; private Runnable afterDebit = () -> {};
    @BeforeEach void setup() throws Exception {
        if (Bukkit.getServer() == null) { Server server = mock(Server.class); when(server.getLogger()).thenReturn(Logger.getLogger("test")); Bukkit.setServer(server); }
        owner = UUID.randomUUID(); player = mock(Player.class); when(Bukkit.getServer().getPlayer(owner)).thenReturn(player); when(player.getUniqueId()).thenReturn(owner); when(player.isOnline()).thenReturn(true); when(player.hasPermission(anyString())).thenReturn(true);
        World world = mock(World.class); when(world.getName()).thenReturn("world"); when(player.getWorld()).thenReturn(world);
        plugin = mock(MCPerksMain.class); when(plugin.isEnabled()).thenReturn(true); when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        initializeAdvancedCoreLifecycleLock(plugin);
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
        perk = mock(Perk.class); when(perk.getPerk()).thenReturn("vein"); when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER); when(perk.getTime()).thenReturn(10); when(perk.isNotInDisabledWorld("world")).thenReturn(true);
        when(perk.executeConfirmed(user,10)).thenAnswer(i -> { activated.incrementAndGet(); return CompletableFuture.completedFuture(null); });
        handler = mock(PerkHandler.class); when(handler.isRestorationReady()).thenReturn(true); when(handler.retireNativeActivations()).thenReturn(CompletableFuture.completedFuture(null)); handler.que = new HashMap<>(); when(plugin.getPerkHandler()).thenReturn(handler); when(handler.getPerk("vein")).thenReturn(perk); when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>());
        scheduler = new OwnerTaskHarness(plugin, player);
        state = new OrderedStorage(directory.resolve("features.dat")); state.ready().get(5,TimeUnit.SECONDS); when(plugin.getStorage()).thenReturn(state);
    }

    private static void initializeAdvancedCoreLifecycleLock(MCPerksMain plugin) throws Exception {
        try {
            var lock = com.bencodez.advancedcore.AdvancedCorePlugin.class.getDeclaredField("lifecycleLock");
            lock.setAccessible(true);
            lock.set(plugin, new Object());
        } catch (NoSuchFieldException ignored) {
            // Older AdvancedCore artifacts do not have the split lifecycle lock.
        }
    }
    @AfterEach void close() throws Exception {
        state.close();
        // Closing admits worker retirement; await actual termination before JUnit removes its owned files.
        var worker = OrderedStorage.class.getDeclaredField("worker"); worker.setAccessible(true);
        assertTrue(((ExecutorService) worker.get(state)).awaitTermination(5, TimeUnit.SECONDS));
    }
    private Runnable next() throws Exception { return Objects.requireNonNull(scheduler.tasks.poll(5, TimeUnit.SECONDS)); }
    private void drainUntil(java.util.function.BooleanSupplier done) throws Exception { drainUntil(done, 5); }
    private void drainUntil(java.util.function.BooleanSupplier done, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) { Runnable task = scheduler.tasks.poll(20, TimeUnit.MILLISECONDS); if (task != null) { task.run(); task.run(); } }
        assertTrue(done.getAsBoolean(), "condition not reached"); state.runtimeStorage(() -> null).get(5, TimeUnit.SECONDS);
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




    @Test void activeLegacyShutdownReadsExpirationAndDeactivatesBeforeNativeStorageRetirement() throws Exception {
        runtimeFile();
        MCPerksMain previous = MCPerksMain.plugin; MCPerksMain.plugin = plugin;
        var stateField = MCPerksMain.class.getDeclaredField("storage");
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
                }).when(cleanup).shutdown(anyLong()))) {
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
        var stateField = MCPerksMain.class.getDeclaredField("storage");
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
                }).when(cleanup).shutdown(anyLong()))) {
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
        var stateField=MCPerksMain.class.getDeclaredField("storage"); stateField.setAccessible(true); stateField.set(plugin,state);
        var runtime=plugin.getMcperksServerData(); runtime.setServerCooldown("vein",1234);
        try (var constructors = mockConstruction(com.bencodez.simpleapi.scheduler.BukkitScheduler.class)) {
            var ordered = new com.bencodez.mcperks.data.OrderedServerDataFile(plugin, nativeFile, runtime);
            when(plugin.getServerDataFile()).thenReturn(ordered);
        }
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        state.runtimeStorage(()->{ entered.countDown(); try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch(InterruptedException e) { throw new RuntimeException(e); } return null; });
        try (var lifecycle = mockConstruction(com.bencodez.advancedcore.lifecycle.AdvancedCoreLifecycle.class,
                (cleanup, context) -> doAnswer(call -> {
                    plugin.onUnLoad();
                    // The native PermissionHandler has this additional save after onUnLoad.
                    runtime.setServerCooldown("vein", 5678);
                    plugin.getServerDataFile().saveData();
                    plugin.onShutdownComplete();
                    return null;
                }).when(cleanup).shutdown(anyLong()))) {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            doCallRealMethod().when(plugin).onUnLoad(); doCallRealMethod().when(plugin).onDisable();
            doCallRealMethod().when(plugin).onShutdownComplete(); plugin.onDisable();
            assertEquals(1, lifecycle.constructed().size());
            assertFalse(Files.exists(directory.resolve("ServerData.yml")));
            try(OrderedStorage successor=new OrderedStorage(directory.resolve("features.dat"))) {
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
        try(OrderedStorage successor=new OrderedStorage(directory.resolve("features.dat"))) {
            when(plugin.getStorage()).thenReturn(successor);
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


    private PerkHandler restorer(BlockingQueue<Runnable> restorationTasks, Perk legacy) throws Exception {
        var actual = spy(newPerkHandler(plugin, List.of()));
        var timer = PerkHandler.class.getDeclaredField("timer"); timer.setAccessible(true); ((Timer) timer.get(actual)).cancel();
        when(plugin.getPerkHandler()).thenReturn(actual);
        actual.setPerk("vein", perk); actual.setPerk("permanent", legacy);
        doNothing().when(actual).restoreActivePerk(any(), any(), any());
        when(plugin.getUserManager().getAllUUIDs()).thenReturn(new ArrayList<>(List.of(owner.toString())));
        doAnswer(i -> { restorationTasks.add(i.getArgument(1)); return null; })
                .when(scheduler.scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(i -> { restorationTasks.add(i.getArgument(1)); return null; })
                .when(scheduler.scheduler).runTask(eq(plugin), any(Runnable.class));
        return actual;
    }
    private static void completeRestoration(BlockingQueue<Runnable> tasks, CompletableFuture<Void> restored) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!restored.isDone() && System.nanoTime() < deadline) {
            var task = tasks.poll(100, TimeUnit.MILLISECONDS); if (task != null) task.run();
        }
        restored.get(5, TimeUnit.SECONDS);
    }
    private Perk permanent() {
        var legacy = mock(Perk.class); when(legacy.getPerk()).thenReturn("permanent"); when(legacy.isEnabled()).thenReturn(true);
        when(legacy.getExperation(user)).thenReturn(-1L); when(legacy.isTimed()).thenReturn(true); return legacy;
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {-1L, -7L, 60_000L, 0L, -60_000L})
    void nativeExpirationWithoutShutdownRecordRestoresWithoutActivationReplay(long remaining) throws Exception {
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent();
        long expiration = remaining > 0 ? System.currentTimeMillis() + remaining
                : remaining == -60_000L ? System.currentTimeMillis() + remaining : remaining;
        when(legacy.getExperation(user)).thenReturn(expiration);
        var actual = restorer(tasks, legacy);
        try {
            var restoration = actual.loadActivePerksConfirmed(() -> {});
            completeRestoration(tasks, restoration);
            if (remaining < 0 && remaining != -60_000L) verify(actual).restoreActivePerk(legacy, user, null);
            else if (remaining > 0) verify(actual).restoreActivePerk(eq(legacy), eq(user),
                    argThat(saved -> saved != null && saved.getTime() == expiration));
            else verify(actual, never()).restoreActivePerk(eq(legacy), any(), any());
            verify(legacy, never()).activatePerk(any(), anyInt());
            verify(legacy, never()).scheduleDeactivation(any());
        } finally { actual.retireTimers(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void offlineRestorationDoesNotAccessUnpublishedCanonicalCacheOnOwner(boolean shutdownRecord) throws Exception {
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent();
        when(legacy.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        var actual = restorer(tasks, legacy);
        var published = new java.util.concurrent.atomic.AtomicBoolean();
        var cache = mock(com.bencodez.advancedcore.api.user.usercache.UserDataCache.class);
        when(dataManager.usesSharedSqlStorage(any())).thenReturn(true);
        when(plugin.getStorageType()).thenReturn(com.bencodez.advancedcore.api.user.UserStorage.SQLITE);
        when(dataManager.getPublishedCache(owner)).thenAnswer(i -> published.get() ? cache : null);
        doAnswer(i -> { published.set(true); return null; }).when(dataManager).cacheUser(owner);
        if (shutdownRecord) plugin.getMcperksServerData().getData().set("OfflinePerk." + owner + ".permanent.Experiaton", -1L);
        doAnswer(i -> {
            if (!published.get()) throw new IllegalStateException("owner read on unpublished canonical cache");
            return null;
        }).when(actual).restoreActivePerk(eq(legacy), eq(user), nullable(Timestamp.class));
        try {
            var restoration = actual.loadActivePerksConfirmed(() -> {
                if (shutdownRecord) actual.restoreActivePerk(legacy, user, null);
            });
            completeRestoration(tasks, restoration);
            verify(actual).restoreActivePerk(eq(legacy), eq(user), nullable(Timestamp.class));
        } finally { actual.retireTimers(); }
    }

    @Test void restorationDoesNotRefreshAnAlreadyPublishedCanonicalCache() throws Exception {
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent();
        when(legacy.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        var actual = restorer(tasks, legacy);
        var cache = mock(com.bencodez.advancedcore.api.user.usercache.UserDataCache.class);
        when(dataManager.usesSharedSqlStorage(any())).thenReturn(true);
        when(plugin.getStorageType()).thenReturn(com.bencodez.advancedcore.api.user.UserStorage.SQLITE);
        when(dataManager.getPublishedCache(owner)).thenReturn(cache);
        try {
            completeRestoration(tasks, actual.loadActivePerksConfirmed(() -> {}));
            verify(dataManager, never()).cacheUser(owner);
            verify(actual).restoreActivePerk(legacy, user, null);
        } finally { actual.retireTimers(); }
    }

    @Test void restorationDoesNotDispatchOwnerWorkWhenCachePublicationFails() throws Exception {
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent();
        when(legacy.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        var actual = restorer(tasks, legacy);
        when(dataManager.usesSharedSqlStorage(any())).thenReturn(true);
        when(plugin.getStorageType()).thenReturn(com.bencodez.advancedcore.api.user.UserStorage.SQLITE);
        when(dataManager.getPublishedCache(owner)).thenReturn(null);
        var shutdown = new AtomicInteger();
        try {
            var restored = actual.loadActivePerksConfirmed(shutdown::incrementAndGet);
            var task = tasks.poll(5, TimeUnit.SECONDS); assertNotNull(task); task.run();
            assertTrue(restored.isCompletedExceptionally());
            assertEquals(0, shutdown.get());
            verify(actual, never()).restoreActivePerk(any(), any(), any());
        } finally { actual.retireTimers(); }
    }

    @Test void expiredOrUnavailableShutdownRecordsDoNotRequireAnOwnerCache() throws Exception {
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent();
        var actual = restorer(tasks, legacy);
        var data = plugin.getMcperksServerData().getData();
        data.set("OfflinePerk." + owner + ".permanent.Experiaton", System.currentTimeMillis() - 10_000);
        data.set("OfflinePerk.invalid-owner.missing.Experiaton", -1L);
        when(plugin.getUserManager().getAllUUIDs()).thenReturn(new ArrayList<>());
        when(dataManager.usesSharedSqlStorage(any())).thenReturn(true);
        when(plugin.getStorageType()).thenReturn(com.bencodez.advancedcore.api.user.UserStorage.SQLITE);
        try {
            completeRestoration(tasks, actual.loadActivePerksConfirmed(() -> {}));
            verify(dataManager, never()).cacheUser(any(UUID.class));
        } finally { actual.retireTimers(); }
    }

    @Test void globalNativeExpirationRestoresOnceAcrossStoredUsers() throws Exception {
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent();
        when(legacy.getPerkType()).thenReturn(PerkSystemType.ALL);
        var other = UUID.randomUUID(); var otherUser = mock(MCPerksUser.class);
        when(otherUser.getUUID()).thenReturn(other.toString());
        when(plugin.getMcperksUserManager().getMCPerksUser(other)).thenReturn(otherUser);
        when(legacy.getExperation(otherUser)).thenReturn(-1L);
        var actual = restorer(tasks, legacy);
        when(plugin.getUserManager().getAllUUIDs()).thenReturn(new ArrayList<>(List.of(owner.toString(), other.toString())));
        try {
            var restored = actual.loadActivePerksConfirmed(() -> {}); completeRestoration(tasks, restored);
            verify(actual, times(1)).restoreActivePerk(eq(legacy), any(), isNull());
            verify(legacy, never()).activatePerk(any(), anyInt());
        } finally { actual.retireTimers(); }
    }
    @Test void shutdownRecordOwnsRestorationWhenTheNativeExpirationWasNotYetCleared() throws Exception {
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent(); var actual = restorer(tasks, legacy);
        plugin.getMcperksServerData().getData().set("OfflinePerk." + owner + ".permanent.Experiaton", -1L);
        var shutdownCalls = new AtomicInteger();
        try {
            var restored = actual.loadActivePerksConfirmed(() -> {
                shutdownCalls.incrementAndGet(); actual.restoreActivePerk(legacy, user, null);
            });
            completeRestoration(tasks, restored);
            assertEquals(1, shutdownCalls.get());
            verify(actual, times(1)).restoreActivePerk(legacy, user, null);
        } finally { actual.retireTimers(); }
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
        completeRestoration(tasks, restored);
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
        state.runtimeStorage(() -> null).get(5, TimeUnit.SECONDS);
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

    @Test void retiredLegacyRestorationCannotMutateTheSuccessorHandler() throws Exception {
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent(); var actual = restorer(tasks, legacy);
        var restored = actual.loadActivePerksConfirmed(() -> fail("retired shutdown callback"));
        when(plugin.getPerkHandler()).thenReturn(handler);
        Objects.requireNonNull(tasks.poll(5, TimeUnit.SECONDS)).run();
        assertThrows(ExecutionException.class, () -> restored.get(5, TimeUnit.SECONDS)); assertTrue(actual.getActivePerks().isEmpty());
        verify(legacy, never()).activatePerk(any(), anyInt());
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"cancel", "success", "failure"})
    void callerObservationCannotChangeActualRestorationReadiness(String mutation) throws Exception {
        var tasks = new LinkedBlockingQueue<Runnable>(); var legacy = permanent(); var actual = restorer(tasks, legacy);
        var restored = actual.loadActivePerksConfirmed(() -> {});
        switch (mutation) {
            case "cancel" -> restored.cancel(false);
            case "success" -> restored.complete(null);
            case "failure" -> restored.completeExceptionally(new IllegalStateException("caller observation"));
            default -> fail("unknown mutation");
        }
        assertFalse(actual.isRestorationReady());
        Objects.requireNonNull(tasks.poll(5, TimeUnit.SECONDS)).run();
        assertFalse(actual.isRestorationReady(), "the owner callback must still run after the storage scan");
        Objects.requireNonNull(tasks.poll(5, TimeUnit.SECONDS)).run();
        assertTrue(actual.isRestorationReady());
        verify(actual).restoreActivePerk(legacy, user, null);
        verify(legacy, never()).activatePerk(any(), anyInt());
    }
}
