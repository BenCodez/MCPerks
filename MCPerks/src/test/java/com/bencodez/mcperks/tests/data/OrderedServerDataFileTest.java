package com.bencodez.mcperks.tests.data;
import static com.bencodez.mcperks.tests.TestAccess.*;
import com.bencodez.mcperks.data.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.advancedcore.api.time.TimeType;
import com.bencodez.advancedcore.data.ServerData.TimeChangeTransitionState;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.perk.Perk;

class OrderedServerDataFileTest {
    @TempDir Path directory;
    private MCPerksMain plugin;
    private com.bencodez.advancedcore.data.ServerData original;
    private ServerData runtime;
    private OrderedServerDataFile ordered;
    private FeatureState state;
    private Perk perk;
    private final AtomicReference<DurableFeatureFiles.Publisher> publication = new AtomicReference<>(DurableFeatureFiles::publish);
    private final ExecutorService calendar = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "test-calendar"); thread.setDaemon(true); return thread;
    });
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    @BeforeEach void setup() throws Exception {
        if (Bukkit.getServer() == null) {
            var server = mock(org.bukkit.Server.class); when(server.getLogger()).thenReturn(Logger.getLogger("test")); Bukkit.setServer(server);
        }
        plugin = mock(MCPerksMain.class); when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        original = mock(com.bencodez.advancedcore.data.ServerData.class);
        var live = spy(new YamlConfiguration()); live.set("PrevDay", 8);
        when(original.getData()).thenReturn(live); when(original.getdFile()).thenReturn(directory.resolve("ServerData.yml").toFile());
        state = new FeatureState(directory.resolve("features.dat")); await(state.ready()); when(plugin.getFeatureState()).thenReturn(state);
        runtime = newServerData(plugin, (staged, file) -> {
            assertFalse(Thread.holdsLock(runtime), "file I/O must not own the runtime monitor");
            publication.get().publish(staged, file);
        });
        when(plugin.getMcperksServerData()).thenReturn(runtime);
        try (var constructors = mockConstruction(com.bencodez.simpleapi.scheduler.BukkitScheduler.class)) {
            ordered = new OrderedServerDataFile(plugin, original, runtime);
        }
        when(plugin.getServerDataFile()).thenReturn(ordered);
        perk = mock(Perk.class); when(perk.getPerk()).thenReturn("legacy");
        // All live serialization and mutations must keep their existing owner. Worker rebasing
        // uses freshly parsed private YAML; it cannot evade this assertion with a shared cache.
        doAnswer(call -> { assertTrue(Thread.holdsLock(runtime)); return call.callRealMethod(); }).when(live).saveToString();
        doAnswer(call -> { assertTrue(Thread.holdsLock(runtime)); return call.callRealMethod(); }).when(live).set(anyString(), any());
        await(runtime.initialize(state));
    }
    @AfterEach void close() { state.close(); calendar.shutdownNow(); }
    private CompletableFuture<TimeChangeTransitionState> begin() {
        return CompletableFuture.supplyAsync(() -> ordered.beginTimeChangeTransition(TimeType.DAY, "2026-10-09", "9"), calendar);
    }
    private CompletableFuture<Void> complete(TimeChangeTransitionState transition) {
        return CompletableFuture.runAsync(() -> ordered.completeTimeChangeTransition(transition), calendar);
    }
    private YamlConfiguration disk() { return YamlConfiguration.loadConfiguration(directory.resolve("ServerData.yml").toFile()); }

    @Test void ordinarySnapshotDoesNotRewriteUnchangedLiveLongSubtrees() throws Exception {
        var live = original.getData();
        synchronized (runtime) {
            live.set("OfflinePerk.test-owner.legacy.Experiaton", -1L);
            live.set("PerkHistory.OCTOBER", List.of("unchanged"));
        }
        var section = live.getConfigurationSection("OfflinePerk");
        clearInvocations(live);
        await(runtime.confirmedFinalMutation(data -> {}));
        assertSame(section, live.getConfigurationSection("OfflinePerk"),
                "a YAML numeric representation change is not an owner mutation");
        verify(live, never()).set(eq("OfflinePerk"), isNull());
        assertEquals(-1L, live.getLong("OfflinePerk.test-owner.legacy.Experiaton"));
        assertEquals(-1L, disk().getLong("OfflinePerk.test-owner.legacy.Experiaton"));
    }

    @Test void activationAdmissionPublishesBeforeGameplayOnOwnerScheduler() throws Exception {
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        doAnswer(call -> { call.getArgument(1, Runnable.class).run(); call.getArgument(1, Runnable.class).run(); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        var gameplay = new AtomicInteger();
        await(runtime.confirmedActivationBeforeGameplay(
                Map.of("admission.CoolDown", 1234L),
                () -> CompletableFuture.completedFuture(null),
                gameplay::incrementAndGet, null));
        assertEquals(1, gameplay.get());
        assertEquals(1234, disk().getLong("admission.CoolDown"));
    }

    @Test void failedActivationAdmissionDoesNotRunGameplay() throws Exception {
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        publication.set((staged, file) -> { throw new IOException("activation publication rejected"); });
        var gameplay = new AtomicInteger();
        var result = runtime.confirmedActivationBeforeGameplay(
                Map.of("rejected.CoolDown", 5678L),
                () -> CompletableFuture.completedFuture(null),
                gameplay::incrementAndGet, null);
        assertThrows(ExecutionException.class, () -> await(result));
        assertEquals(0, gameplay.get());
        assertEquals(0, disk().getLong("rejected.CoolDown"));
        assertEquals(0, runtime.getServerCooldown("rejected"));
    }
    @Test void oversizedAdmissionFailsBeforeAnyGameplayOrUserFlush() throws Exception {
        synchronized (runtime) { runtime.getData().set("RequiredLargePayload", "x".repeat(4_000_001)); }
        var gameplay = new AtomicInteger(); var caller = new AtomicInteger();
        var result = runtime.confirmedActivationBeforeGameplay(Map.of("tooLarge.CoolDown", 55L),
                () -> { caller.incrementAndGet(); return CompletableFuture.completedFuture(null); }, gameplay::incrementAndGet, null);
        assertThrows(ExecutionException.class, () -> await(result));
        assertEquals(0, gameplay.get()); assertEquals(0, caller.get());
        assertEquals(0, disk().getLong("tooLarge.CoolDown"));
        assertEquals(0, runtime.getServerCooldown("tooLarge"));
    }
    @Test void userStorageFailureRetainsPublishedAdmissionButNeverSchedulesGameplay() throws Exception {
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        var gameplay = new AtomicInteger();
        var result = runtime.confirmedActivationBeforeGameplay(Map.of("userFailure.CoolDown", 66L),
                () -> CompletableFuture.failedFuture(new IOException("user flush failed")), gameplay::incrementAndGet, null);
        assertThrows(ExecutionException.class, () -> await(result));
        assertEquals(0, gameplay.get()); assertEquals(66, disk().getLong("userFailure.CoolDown"));
        verify(scheduler, times(1)).runTask(eq(plugin), any(Runnable.class));
    }
    private void immediateOwnerScheduler() {
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
    }
    @Test void failedStagedAdmissionCannotLeakIntoQueuedOrdinarySnapshot() throws Exception {
        immediateOwnerScheduler(); CompletableFuture<Void> admission;
        var caller = new AtomicInteger();
        try (Gate gate = new Gate(true)) {
            admission = runtime.confirmedActivationBeforeGameplay(Map.of("staged.CoolDown", 50L, "Experation.staged", 60L),
                    () -> { caller.incrementAndGet(); return CompletableFuture.completedFuture(null); }, () -> fail("gameplay"), null);
            gate.entered(); assertEquals(0, runtime.getServerCooldown("staged"));
            synchronized (runtime) { assertNull(runtime.getData().get("Experation.staged")); }
            runtime.saveData();
        }
        assertThrows(ExecutionException.class, () -> await(admission)); await(state.runtimeStorage(() -> null));
        assertEquals(0, caller.get()); assertNull(disk().get("staged.CoolDown")); assertNull(disk().get("Experation.staged"));
        assertEquals(0, runtime.getServerCooldown("staged"));
    }
    @Test void confirmedAdmissionRebasesCalendarAndOrdinarySnapshotsWithoutLosingNewerSameValueEdit() throws Exception {
        immediateOwnerScheduler(); CompletableFuture<Void> admission; CompletableFuture<TimeChangeTransitionState> calendarWrite;
        try (Gate gate = new Gate(false)) {
            admission = runtime.confirmedActivationBeforeGameplay(Map.of("staged.CoolDown", 50L, "Experation.staged", 60L),
                    () -> CompletableFuture.completedFuture(null), () -> {}, null);
            gate.entered();
            synchronized (runtime) { assertNull(runtime.getData().get("Experation.staged")); }
            // Explicitly setting the old zero is still a newer update, not an absent edit.
            ordered.setData("staged.CoolDown", 0L);
            ordered.setData("TimedPermissions.new-owner", List.of("permission.test%line%12345"));
            calendarWrite = begin(); runtime.saveData();
        }
        await(admission); await(calendarWrite); await(state.runtimeStorage(() -> null));
        assertEquals(0, disk().getLong("staged.CoolDown")); assertEquals(60, disk().getLong("Experation.staged"));
        assertEquals(0, runtime.getServerCooldown("staged"));
        synchronized (runtime) { assertEquals(60, runtime.getData().getLong("Experation.staged")); }
        assertTrue(disk().getBoolean("TimeTransitions.DAY.Pending"));
        assertEquals(List.of("permission.test%line%12345"), disk().getStringList("TimedPermissions.new-owner"));
    }
    private CompletableFuture<Void> ownerSnapshot(long expiration) throws Exception {
        // Waiting for this outer future checks monitor responsiveness, without waiting for storage.
        return await(CompletableFuture.supplyAsync(() -> runtime.confirmedActivation(() -> {
            assertNotEquals("MCPerks-feature-store", Thread.currentThread().getName());
            runtime.setPerkExperation(perk, expiration); runtime.setServerCooldown("legacy", expiration + 1);
        })));
    }
    private final class Gate implements AutoCloseable {
        private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);
        private Gate(boolean fail) {
            publication.set((staged, file) -> {
                if (first.compareAndSet(true, false)) {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test publication timeout"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                    if (fail) throw new IOException("calendar publication rejected");
                }
                DurableFeatureFiles.publish(staged, file);
            });
        }
        private void entered() throws Exception { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
        @Override public void close() { release.countDown(); }
    }
    @Test void confirmedBeginAndCompletionRebaseLaterPerkSnapshotsWithoutBlockingOwner() throws Exception {
        CompletableFuture<TimeChangeTransitionState> beginning; CompletableFuture<Void> activation;
        try (Gate gate = new Gate(false)) {
            beginning = begin(); gate.entered(); assertFalse(beginning.isDone());
            activation = ownerSnapshot(1234); assertFalse(activation.isDone()); assertEquals(1235, runtime.getServerCooldown("legacy"));
            synchronized (runtime) { assertFalse(runtime.getData().getBoolean("TimeTransitions.DAY.Pending")); }
            ordered.reloadData(); verify(original, never()).reloadData();
        }
        var transition = await(beginning); await(activation);
        assertTrue(disk().getBoolean("TimeTransitions.DAY.Pending")); assertEquals(1234, disk().getLong("Experation.legacy"));
        CompletableFuture<Void> completion;
        try (Gate gate = new Gate(false)) {
            completion = complete(transition); gate.entered(); activation = ownerSnapshot(5678);
            assertEquals(8, ordered.getPrevDay());
            synchronized (runtime) { assertTrue(runtime.getData().getBoolean("TimeTransitions.DAY.Pending")); }
        }
        await(completion); await(activation);
        assertEquals(9, ordered.getPrevDay()); assertEquals(9, disk().getInt("PrevDay"));
        assertFalse(disk().getBoolean("TimeTransitions.DAY.Pending")); assertEquals(5678, disk().getLong("Experation.legacy"));
        ordered.reloadData(); verify(original).reloadData();
        verify(original, never()).saveData();
        verify((YamlConfiguration) original.getData(), never()).save(any(java.io.File.class));
    }
    @Test void failedBeginNeverLeaksIntoLiveOrLaterPerkSnapshotAndRetryIsDurable() throws Exception {
        CompletableFuture<TimeChangeTransitionState> beginning; CompletableFuture<Void> activation;
        try (Gate gate = new Gate(true)) { beginning = begin(); gate.entered(); activation = ownerSnapshot(1234); }
        assertThrows(ExecutionException.class, () -> await(beginning)); await(activation);
        synchronized (runtime) { assertNull(runtime.getData().get("TimeTransitions.DAY.Id")); }
        assertNull(disk().get("TimeTransitions.DAY.Id")); assertEquals(8, disk().getInt("PrevDay"));
        assertEquals(1234, disk().getLong("Experation.legacy"));
        var transition = await(begin()); ordered.failTimeChangeTransition(transition); ordered.completeTimeChangeTransition(transition);
        assertEquals(9, disk().getInt("PrevDay")); assertFalse(disk().getBoolean("TimeTransitions.DAY.Pending"));
    }
    @Test void failedCompletionLeavesPendingMarkerInLiveAndLaterSnapshotForRetry() throws Exception {
        var transition = await(begin()); CompletableFuture<Void> completion, activation;
        try (Gate gate = new Gate(true)) { completion = complete(transition); gate.entered(); activation = ownerSnapshot(9876); }
        assertThrows(ExecutionException.class, () -> await(completion)); await(activation);
        assertEquals(8, ordered.getPrevDay()); assertTrue(ordered.isPendingTimeChangeTransition(transition));
        assertEquals(8, disk().getInt("PrevDay")); assertTrue(disk().getBoolean("TimeTransitions.DAY.Pending"));
        assertEquals(9876, disk().getLong("Experation.legacy"));
        await(complete(transition)); assertNull(ordered.getPendingTimeChangeTransition(TimeType.DAY));
        assertFalse(ordered.isPendingTimeChangeTransition(transition)); assertEquals(9, disk().getInt("PrevDay"));
    }
    @Test void laterExplicitMarkerEditWinsWhileConfirmedPendingFlagSurvivesItsSnapshot() throws Exception {
        var transition = await(begin()); CompletableFuture<Void> completion, activation;
        try (Gate gate = new Gate(false)) {
            completion = complete(transition); gate.entered(); ordered.setPrevDay(8); activation = ownerSnapshot(5678);
        }
        await(completion); await(activation);
        assertEquals(8, ordered.getPrevDay()); assertEquals(8, disk().getInt("PrevDay"));
        assertFalse(disk().getBoolean("TimeTransitions.DAY.Pending")); assertEquals(5678, disk().getLong("Experation.legacy"));
    }
    @Test void shutdownAndSuccessorDrainAcceptedCalendarAndFinalPerkSnapshotBeforeReconciliation() throws Exception {
        var transition = await(begin()); CompletableFuture<Void> completion, activation;
        try (Gate gate = new Gate(false)) {
            completion = complete(transition); gate.entered(); activation = ownerSnapshot(7654);
            runtime.saveData(); state.close();
            try (FeatureState successor = new FeatureState(directory.resolve("features.dat"))) {
                assertFalse(successor.ready().isDone()); gate.close(); await(completion); await(activation); await(successor.ready());
                assertThrows(CompletionException.class, () -> ordered.beginTimeChangeTransition(TimeType.WEEK, "2026-41", "41"));
                synchronized (runtime) { assertNull(runtime.getData().get("TimeTransitions.WEEK.Id")); }
                when(plugin.getFeatureState()).thenReturn(successor);
                var stale = new YamlConfiguration(); stale.set("PrevDay", 7); when(original.getData()).thenReturn(stale);
                var restored = new ServerData(plugin); await(restored.initialize(successor));
                assertEquals(7654, restored.getPerkExperation(perk)); assertEquals(9, stale.getInt("PrevDay"));
                assertFalse(stale.getBoolean("TimeTransitions.DAY.Pending")); assertEquals(7654, disk().getLong("Experation.legacy"));
            }
        }
    }
    @Test void fullWorkerRejectsCalendarAdmissionWithoutChangingRuntimeAndRemainsBounded() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var blocked = state.runtimeStorage(() -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch (InterruptedException error) { throw new CompletionException(error); } return null;
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var accepted = new ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < 256; i++) accepted.add(state.runtimeStorage(() -> null));
            assertThrows(ExecutionException.class, () -> await(begin()));
            synchronized (runtime) { assertNull(runtime.getData().get("TimeTransitions.DAY.Id")); }
            release.countDown(); await(blocked); for (var future : accepted) await(future);
            await(begin()); assertTrue(disk().getBoolean("TimeTransitions.DAY.Pending"));
        } finally { release.countDown(); }
    }
    @Test void weekMonthAndInvalidPendingCleanupShareTheSameDurablePrivateView() throws Exception {
        var week = ordered.beginTimeChangeTransition(TimeType.WEEK, "2026-41", "41");
        ordered.completeTimeChangeTransition(week);
        var month = ordered.beginTimeChangeTransition(TimeType.MONTH, "2026-10", "OCTOBER");
        CompletableFuture<Void> completion, activation;
        try (Gate gate = new Gate(false)) {
            completion = complete(month); gate.entered(); activation = ownerSnapshot(4567);
        }
        await(completion); await(activation);
        assertEquals(41, disk().getInt("PrevWeek")); assertEquals("OCTOBER", disk().getString("Month"));
        assertFalse(disk().getBoolean("TimeTransitions.WEEK.Pending")); assertFalse(disk().getBoolean("TimeTransitions.MONTH.Pending"));
        ordered.setData("TimeTransitions.DAY.Id", "legacy-invalid"); ordered.setData("TimeTransitions.DAY.Period", "2026-10-09");
        ordered.setData("TimeTransitions.DAY.Marker", "invalid"); ordered.setData("TimeTransitions.DAY.Pending", true);
        await(state.runtimeStorage(() -> null));
        CompletableFuture<TimeChangeTransitionState> cleanup;
        try (Gate gate = new Gate(true)) {
            cleanup = CompletableFuture.supplyAsync(() -> ordered.getPendingTimeChangeTransition(TimeType.DAY), calendar);
            gate.entered(); activation = ownerSnapshot(8910);
        }
        assertThrows(ExecutionException.class, () -> await(cleanup)); await(activation);
        synchronized (runtime) { assertTrue(runtime.getData().getBoolean("TimeTransitions.DAY.Pending")); }
        assertTrue(disk().getBoolean("TimeTransitions.DAY.Pending"));
        assertNull(ordered.getPendingTimeChangeTransition(TimeType.DAY));
        assertFalse(disk().getBoolean("TimeTransitions.DAY.Pending")); assertEquals(8910, disk().getLong("Experation.legacy"));
    }
    @Test void corruptOptionalFeatureStateStillPermitsInitializedCalendarAndLegacySnapshots() throws Exception {
        state.close(); Path broken = directory.resolve("broken-features.dat"); Files.writeString(broken, "invalid features");
        try (FeatureState unavailable = new FeatureState(broken)) {
            assertThrows(ExecutionException.class, () -> await(unavailable.ready())); when(plugin.getFeatureState()).thenReturn(unavailable);
            await(runtime.initialize(unavailable)); assertTrue(runtime.isReady()); assertFalse(unavailable.available());
            var transition = await(begin()); ordered.completeTimeChangeTransition(transition); await(ownerSnapshot(3456));
            assertEquals(9, disk().getInt("PrevDay")); assertEquals(3456, disk().getLong("Experation.legacy"));
            assertEquals("invalid features", Files.readString(broken));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void finalMutationReportsActualOrderedPublicationAndShutdownWaitsForBothStages(boolean fail) throws Exception {
        var ownerField = MCPerksMain.class.getDeclaredField("featureState"); ownerField.setAccessible(true); ownerField.set(plugin, state);
        var preparationField = MCPerksMain.class.getDeclaredField("shutdownPreparation"); preparationField.setAccessible(true);
        var preparation = new CompletableFuture<Void>(); preparationField.set(plugin, preparation);
        doCallRealMethod().when(plugin).onShutdownComplete(any(java.util.concurrent.CompletionStage.class));
        doCallRealMethod().when(plugin).onShutdownComplete();
        byte[] previous = Files.readAllBytes(directory.resolve("ServerData.yml"));
        CompletableFuture<Void> finalFiles;
        try (Gate gate = new Gate(fail)) {
            finalFiles = ordered.mutateFinalData(data -> {
                assertTrue(Thread.holdsLock(runtime), "final YAML mutation belongs to runtime owner");
                data.set("LastUpdated", 2468L);
            }).toCompletableFuture();
            gate.entered();
            plugin.onShutdownComplete(finalFiles);
            assertFalse(finalFiles.isDone());
            assertArrayEquals(previous, Files.readAllBytes(directory.resolve("ServerData.yml")));
            // Work is still admissible after the lifecycle hook; close cannot discard
            // either preparation continuations or the actual adapter publication.
            var admitted = state.runtimeStorage(() -> null);
            gate.close();
            if (fail) assertThrows(ExecutionException.class, () -> await(finalFiles));
            else await(finalFiles);
            await(admitted);
            await(state.runtimeStorage(() -> null)); // Final files alone cannot close preparation's owner.
            if (fail) preparation.completeExceptionally(new IllegalStateException("preparation failed"));
            else preparation.complete(null);
            assertThrows(ExecutionException.class, () -> await(state.runtimeStorage(() -> null)));
        }
        if (fail) assertArrayEquals(previous, Files.readAllBytes(directory.resolve("ServerData.yml")));
        else {
            assertEquals(2468L, disk().getLong("LastUpdated"));
            assertTrue(Files.readString(directory.resolve("ServerData.yml")).startsWith("# MCPerks ordered ServerData v1"));
        }
        verify(original, never()).saveData();
        verify(original, never()).saveDataChecked();
    }

    @Test void completedPreparationCannotCloseOwnerBeforeDelayedFinalMutationIsAdmitted() throws Exception {
        var ownerField = MCPerksMain.class.getDeclaredField("featureState"); ownerField.setAccessible(true); ownerField.set(plugin, state);
        var preparationField = MCPerksMain.class.getDeclaredField("shutdownPreparation"); preparationField.setAccessible(true);
        preparationField.set(plugin, CompletableFuture.completedFuture(null));
        doCallRealMethod().when(plugin).onShutdownComplete(any(java.util.concurrent.CompletionStage.class));
        doCallRealMethod().when(plugin).onShutdownComplete();
        var permissionFiles = new CompletableFuture<Void>();
        plugin.onShutdownComplete(permissionFiles);
        await(state.runtimeStorage(() -> null));
        // Native permission snapshot may be queued on AdvancedCore's shutdown worker
        // after lifecycle cleanup has returned. It must still reach this exact owner.
        var actual = ordered.mutateFinalData(data -> data.set("LastUpdated", 1357L));
        actual.whenComplete((ignored, failure) -> {
            if (failure == null) permissionFiles.complete(null);
            else permissionFiles.completeExceptionally(failure);
        });
        await(permissionFiles);
        assertEquals(1357L, disk().getLong("LastUpdated"));
        try (FeatureState successor = new FeatureState(directory.resolve("features.dat"))) {
            await(successor.ready());
            assertThrows(ExecutionException.class, () -> await(state.runtimeStorage(() -> null)));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void replayOverflowPublicationCannotLeakOrDisappearThroughQueuedWholeFileSaves(boolean fail) throws Exception {
        org.bukkit.configuration.serialization.ConfigurationSerialization.registerClass(
                PersistedOverflowItem.class, "MCFinalOverflowTestItem");
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler); when(plugin.isEnabled()).thenReturn(true);
        var options = mock(com.bencodez.advancedcore.AdvancedCoreConfigOptions.class);
        when(plugin.getOptions()).thenReturn(options);
        var uuid = UUID.randomUUID(); var player = mock(org.bukkit.entity.Player.class);
        var playerInventory = mock(org.bukkit.inventory.PlayerInventory.class);
        when(player.getUniqueId()).thenReturn(uuid); when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(playerInventory);
        var item = new PersistedOverflowItem();
        when(playerInventory.addItem(item)).thenReturn(new HashMap<>(Map.of(0, item)));
        var ownerTask = new AtomicReference<Runnable>();
        doAnswer(call -> { ownerTask.set(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class), eq(player));
        var inventory = new com.bencodez.advancedcore.api.item.FullInventoryHandler(plugin);
        inventory.getLastMessageTime().put(uuid, System.currentTimeMillis());
        var timerEntered = new CountDownLatch(1); var releaseTimer = new CountDownLatch(1);
        inventory.getTimer().execute(() -> {
            timerEntered.countDown();
            try { releaseTimer.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(timerEntered.await(2, TimeUnit.SECONDS));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            var receipt = inventory.giveItemAsync(player, item).toCompletableFuture(); ownerTask.get().run();
            assertFalse(receipt.isDone());
            CompletableFuture<Void> finalInventory, timestamp, permissions;
            try (Gate gate = new Gate(fail)) {
                finalInventory = inventory.shutdownAsync(Runnable::run).toCompletableFuture();
                gate.entered();
                synchronized (runtime) {
                    assertFalse(runtime.getData().contains("FullInventory." + uuid),
                            "pending overflow must remain outside live snapshots");
                }
                timestamp = ordered.mutateFinalData(data -> data.set("LastUpdated", 2468L)).toCompletableFuture();
                permissions = ordered.mutateFinalData(data -> data.set("TimedPermissions.example",
                        List.of("example.use%line%12345"))).toCompletableFuture();
                runtime.saveData(); // Capture another ordinary snapshot before the first publication settles.
                assertFalse(receipt.isDone());
            }
            if (fail) assertThrows(ExecutionException.class, () -> await(finalInventory));
            else await(finalInventory);
            await(timestamp); await(permissions); await(state.runtimeStorage(() -> null));
            assertEquals(!fail, receipt.isDone());
            assertEquals(!fail, inventory.getItems().containsKey(uuid));
            var recovered = disk();
            assertEquals(!fail, recovered.contains("FullInventory." + uuid));
            assertEquals(2468L, recovered.getLong("LastUpdated"));
            assertEquals(List.of("example.use%line%12345"), recovered.getStringList("TimedPermissions.example"));
            synchronized (runtime) { assertEquals(!fail, runtime.getData().contains("FullInventory." + uuid)); }
            var successorFile = mock(com.bencodez.advancedcore.data.ServerData.class);
            when(successorFile.getData()).thenReturn(recovered);
            when(plugin.getServerDataFile()).thenReturn(successorFile);
            var successor = new com.bencodez.advancedcore.api.item.FullInventoryHandler(plugin);
            try {
                assertEquals(!fail, successor.getItems().containsKey(uuid),
                        "restart cannot recover overflow whose replay receipt is still pending");
                if (!fail) assertEquals(1, successor.getItems().get(uuid).size());
            } finally { successor.getTimer().shutdownNow(); }
            verify(playerInventory, times(1)).addItem(item);
        } finally {
            releaseTimer.countDown(); inventory.getTimer().shutdownNow();
            org.bukkit.configuration.serialization.ConfigurationSerialization.unregisterClass(PersistedOverflowItem.class);
        }
    }

    @Test void publishedFinalMutationCannotOverwriteALaterExplicitSectionEdit() throws Exception {
        CompletableFuture<Void> finalInventory;
        try (Gate gate = new Gate(false)) {
            finalInventory = ordered.mutateFinalData(data -> data.set("FullInventory.old.Time", 123L)).toCompletableFuture();
            gate.entered();
            // Clearing an absent section is still an explicit later write.
            ordered.setData("FullInventory", null);
            ordered.setData("UnrelatedValue", "retained");
        }
        await(finalInventory); await(state.runtimeStorage(() -> null));
        synchronized (runtime) { assertFalse(runtime.getData().contains("FullInventory")); }
        assertFalse(disk().contains("FullInventory")); assertEquals("retained", disk().getString("UnrelatedValue"));
    }

    @Test void laterConfirmedFinalMutationOfSameSectionRemainsTheLiveAndDiskOwner() throws Exception {
        CompletableFuture<Void> first, second;
        try (Gate gate = new Gate(false)) {
            first = ordered.mutateFinalData(data -> data.set("TimedPermissions.example", List.of("first%line%123")))
                    .toCompletableFuture();
            gate.entered();
            second = ordered.mutateFinalData(data -> data.set("TimedPermissions.example", List.of("second%line%456")))
                    .toCompletableFuture();
            runtime.saveData();
        }
        await(first); await(second); await(state.runtimeStorage(() -> null));
        synchronized (runtime) { assertEquals(List.of("second%line%456"), runtime.getData().getStringList("TimedPermissions.example")); }
        assertEquals(List.of("second%line%456"), disk().getStringList("TimedPermissions.example"));
    }

    @Test void laterFinalClearOfUnpublishedSectionRemainsAbsentInLiveAndDisk() throws Exception {
        CompletableFuture<Void> first, cleared;
        var calls = new AtomicInteger();
        try (Gate gate = new Gate(false)) {
            first = ordered.mutateFinalData(data -> {
                calls.incrementAndGet(); data.set("FullInventory.old.Time", 111_111_111_111L);
            }).toCompletableFuture();
            gate.entered();
            synchronized (runtime) { assertFalse(runtime.getData().contains("FullInventory")); }
            cleared = ordered.mutateFinalData(data -> {
                calls.incrementAndGet(); data.set("FullInventory", null);
            }).toCompletableFuture();
            runtime.saveData(); // An ordinary later snapshot must retain the acknowledged clear.
        }
        await(first); await(cleared); await(state.runtimeStorage(() -> null));
        assertEquals(2, calls.get(), "consumer mutations must never be replayed");
        synchronized (runtime) { assertFalse(runtime.getData().contains("FullInventory")); }
        assertFalse(disk().contains("FullInventory"));
    }

    @Test void laterSameBaselineNestedFinalWriteFencesAnUnpublishedMutation() throws Exception {
        long originalTime = 111_111_111_111L;
        synchronized (runtime) { runtime.getData().set("FullInventory.owner.Time", originalTime); }
        CompletableFuture<Void> first, restored;
        try (Gate gate = new Gate(false)) {
            first = ordered.mutateFinalData(data -> data.getConfigurationSection("FullInventory.owner")
                    .set("Time", 222_222_222_222L)).toCompletableFuture();
            gate.entered();
            restored = ordered.mutateFinalData(data -> data.getConfigurationSection("FullInventory.owner")
                    .set("Time", originalTime)).toCompletableFuture();
            runtime.saveData();
        }
        await(first); await(restored); await(state.runtimeStorage(() -> null));
        synchronized (runtime) { assertEquals(originalTime, runtime.getData().getLong("FullInventory.owner.Time")); }
        assertEquals(originalTime, disk().getLong("FullInventory.owner.Time"));
    }

    @Test void laterNestedFinalWriteRetainsAnUnpublishedDifferentSibling() throws Exception {
        synchronized (runtime) { runtime.getData().set("FullInventory.seed.Time", 111_111_111_111L); }
        CompletableFuture<Void> first, second;
        try (Gate gate = new Gate(false)) {
            first = ordered.mutateFinalData(data -> data.set("FullInventory.first.Time", 222_222_222_222L))
                    .toCompletableFuture();
            gate.entered();
            second = ordered.mutateFinalData(data -> data.getConfigurationSection("FullInventory")
                    .createSection("second", Map.of("Time", 333_333_333_333L))).toCompletableFuture();
            runtime.saveData();
        }
        await(first); await(second); await(state.runtimeStorage(() -> null));
        synchronized (runtime) {
            assertEquals(111_111_111_111L, runtime.getData().getLong("FullInventory.seed.Time"));
            assertEquals(222_222_222_222L, runtime.getData().getLong("FullInventory.first.Time"));
            assertEquals(333_333_333_333L, runtime.getData().getLong("FullInventory.second.Time"));
        }
        var written = disk();
        assertEquals(111_111_111_111L, written.getLong("FullInventory.seed.Time"));
        assertEquals(222_222_222_222L, written.getLong("FullInventory.first.Time"));
        assertEquals(333_333_333_333L, written.getLong("FullInventory.second.Time"));
    }

    @Test void laterNestedWritePreservesDifferentSiblingFromUnpublishedParentReplacement() throws Exception {
        CompletableFuture<Void> replacement, nested;
        try (Gate gate = new Gate(false)) {
            replacement = ordered.mutateFinalData(data -> data.createSection("FullInventory", Map.of(
                    "first", Map.of("Time", 222_222_222_222L)))).toCompletableFuture();
            gate.entered();
            nested = ordered.mutateFinalData(data -> data.set("FullInventory.second.Time", 333_333_333_333L))
                    .toCompletableFuture();
            runtime.saveData();
        }
        await(replacement); await(nested); await(state.runtimeStorage(() -> null));
        synchronized (runtime) {
            assertEquals(222_222_222_222L, runtime.getData().getLong("FullInventory.first.Time"));
            assertEquals(333_333_333_333L, runtime.getData().getLong("FullInventory.second.Time"));
        }
        assertEquals(222_222_222_222L, disk().getLong("FullInventory.first.Time"));
        assertEquals(333_333_333_333L, disk().getLong("FullInventory.second.Time"));
    }

    @Test void laterSameBaselineCreateSectionClearsAnUnpublishedChild() throws Exception {
        synchronized (runtime) { runtime.getData().createSection("FullInventory"); }
        CompletableFuture<Void> child, replacement;
        try (Gate gate = new Gate(false)) {
            child = ordered.mutateFinalData(data -> data.set("FullInventory.old.Time", 222_222_222_222L))
                    .toCompletableFuture();
            gate.entered();
            replacement = ordered.mutateFinalData(data -> data.createSection("FullInventory"))
                    .toCompletableFuture();
            runtime.saveData();
        }
        await(child); await(replacement); await(state.runtimeStorage(() -> null));
        synchronized (runtime) {
            assertNotNull(runtime.getData().getConfigurationSection("FullInventory"));
            assertTrue(runtime.getData().getConfigurationSection("FullInventory").getKeys(false).isEmpty());
        }
        assertNotNull(disk().getConfigurationSection("FullInventory"));
        assertTrue(disk().getConfigurationSection("FullInventory").getKeys(false).isEmpty());
    }

    @Test void finalMutationSupportsMoreThan256DistinctPermissionOwners() throws Exception {
        await(ordered.mutateFinalData(data -> {
            for (int i = 0; i < 300; i++) data.set("TimedPermissions." + new UUID(0, i),
                    List.of("permission.example%line%111111111111"));
        }).toCompletableFuture());
        synchronized (runtime) {
            assertEquals(300, runtime.getData().getConfigurationSection("TimedPermissions").getKeys(false).size());
        }
        assertEquals(300, disk().getConfigurationSection("TimedPermissions").getKeys(false).size());
    }

    @Test void excessiveFinalMutationPathsRejectBeforePublicationAndLeaveOwnerUsable() throws Exception {
        byte[] previous = Files.readAllBytes(directory.resolve("ServerData.yml"));
        var rejected = ordered.mutateFinalData(data -> {
            data.set("FinalField" + "x".repeat(4_000_001), null);
        }).toCompletableFuture();
        assertThrows(ExecutionException.class, () -> await(rejected));
        assertArrayEquals(previous, Files.readAllBytes(directory.resolve("ServerData.yml")));
        synchronized (runtime) { assertTrue(runtime.getData().getKeys(false).stream().noneMatch(path -> path.startsWith("FinalField"))); }
        await(ordered.mutateFinalData(data -> data.set("LastUpdated", 111_111_111_111L)).toCompletableFuture());
        assertEquals(111_111_111_111L, disk().getLong("LastUpdated"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {-1L, 9_007_199_254_740_993L})
    void ordinarySnapshotRetainsPublishedIntegralValuesAcrossYamlNumericTypes(long previousTime) throws Exception {
        long preciseLargeValue = 9_007_199_254_740_993L;
        var original = Map.of("Time", previousTime, "Numbers", List.of(-1L, preciseLargeValue),
                "Nested", Map.of("Whole", 7L, "Fraction", 0.125d));
        synchronized (runtime) { runtime.getData().createSection("NumericState", original); }
        CompletableFuture<Void> mutation;
        try (Gate gate = new Gate(false)) {
            mutation = ordered.mutateFinalData(data -> data.createSection("NumericState", Map.of(
                    "Time", 5L, "Numbers", List.of(-1L, preciseLargeValue),
                    "Nested", Map.of("Whole", 8L, "Fraction", 0.125d)))).toCompletableFuture();
            gate.entered();
            runtime.saveData();
        }
        await(mutation); await(state.runtimeStorage(() -> null));
        synchronized (runtime) {
            assertEquals(5L, runtime.getData().getLong("NumericState.Time"));
            assertEquals(8L, runtime.getData().getLong("NumericState.Nested.Whole"));
            assertEquals(preciseLargeValue, ((Number) runtime.getData().getList("NumericState.Numbers").get(1)).longValue());
            assertEquals(0.125d, runtime.getData().getDouble("NumericState.Nested.Fraction"));
        }
        var written = disk();
        assertEquals(5L, written.getLong("NumericState.Time"));
        assertEquals(8L, written.getLong("NumericState.Nested.Whole"));
        assertEquals(-1L, ((Number) written.getList("NumericState.Numbers").get(0)).longValue());
        assertEquals(preciseLargeValue, ((Number) written.getList("NumericState.Numbers").get(1)).longValue());
        assertEquals(0.125d, written.getDouble("NumericState.Nested.Fraction"));
    }

    @Test void ordinaryChildEditRetainsOtherOwnerFromUnpublishedParentReplacement() throws Exception {
        CompletableFuture<Void> replacement;
        try (Gate gate = new Gate(false)) {
            replacement = ordered.mutateFinalData(data -> data.createSection("FullInventory", Map.of(
                    "first", Map.of("Time", 111_111_111_111L)))).toCompletableFuture();
            gate.entered();
            ordered.setData("FullInventory.second.Time", 222_222_222_222L);
            runtime.saveData();
        }
        await(replacement); await(state.runtimeStorage(() -> null));
        synchronized (runtime) {
            assertEquals(111_111_111_111L, runtime.getData().getLong("FullInventory.first.Time"));
            assertEquals(222_222_222_222L, runtime.getData().getLong("FullInventory.second.Time"));
        }
        assertEquals(111_111_111_111L, disk().getLong("FullInventory.first.Time"));
        assertEquals(222_222_222_222L, disk().getLong("FullInventory.second.Time"));
    }

    @Test void ordinaryEditCapacityRejectionCannotDiscardPendingParentPublication() throws Exception {
        CompletableFuture<Void> replacement;
        try (Gate gate = new Gate(false)) {
            replacement = ordered.mutateFinalData(data -> data.createSection("FullInventory", Map.of(
                    "first", Map.of("Time", 111_111_111_111L)))).toCompletableFuture();
            gate.entered();
            assertThrows(IllegalStateException.class, () -> ordered.setData(
                    "FullInventory." + "x".repeat(4_000_001), null));
        }
        await(replacement); await(state.runtimeStorage(() -> null));
        synchronized (runtime) { assertEquals(111_111_111_111L, runtime.getData().getLong("FullInventory.first.Time")); }
        assertEquals(111_111_111_111L, disk().getLong("FullInventory.first.Time"));
        await(ordered.mutateFinalData(data -> data.set("LastUpdated", 222_222_222_222L)).toCompletableFuture());
        assertEquals(222_222_222_222L, disk().getLong("LastUpdated"));
    }

    @org.bukkit.configuration.serialization.SerializableAs("MCFinalOverflowTestItem")
    public static final class PersistedOverflowItem extends org.bukkit.inventory.ItemStack {
        public PersistedOverflowItem() { super(org.bukkit.Material.STONE, 1); }
        @Override public Map<String, Object> serialize() { return Map.of("amount", 1); }
        public static PersistedOverflowItem deserialize(Map<String, Object> values) { return new PersistedOverflowItem(); }
        @Override public boolean equals(Object value) { return value instanceof PersistedOverflowItem; }
        @Override public int hashCode() { return 1; }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void actualInventoryFinalSaveWaitsForOrderedAdapterPublicationAndReportsFailure(boolean fail) throws Exception {
        var inventory = new com.bencodez.advancedcore.api.item.FullInventoryHandler(plugin);
        CompletableFuture<Void> finalFiles;
        byte[] previous = Files.readAllBytes(directory.resolve("ServerData.yml"));
        try (Gate gate = new Gate(fail)) {
            finalFiles = inventory.shutdownAsync(Runnable::run).toCompletableFuture();
            gate.entered();
            assertFalse(finalFiles.isDone()); assertTrue(inventory.getTimer().isShutdown());
            assertArrayEquals(previous, Files.readAllBytes(directory.resolve("ServerData.yml")));
            gate.close();
            if (fail) assertThrows(ExecutionException.class, () -> await(finalFiles));
            else await(finalFiles);
        } finally { inventory.getTimer().shutdownNow(); }
        if (fail) assertArrayEquals(previous, Files.readAllBytes(directory.resolve("ServerData.yml")));
        else assertTrue(Files.readString(directory.resolve("ServerData.yml")).startsWith("# MCPerks ordered ServerData v1"));
        verify(original, never()).saveDataChecked();
    }

    @Test void actualTimestampFinalCallerUsesOrderedAdapterAndExposesPendingPublicationToHook() throws Exception {
        var capturedFiles = new AtomicReference<java.util.concurrent.CompletionStage<Void>>();
        Thread lifecycleThread = Thread.currentThread();
        doAnswer(call -> {
            assertSame(lifecycleThread, Thread.currentThread()); capturedFiles.set(call.getArgument(0)); return null;
        }).when(plugin).onShutdownComplete(any(java.util.concurrent.CompletionStage.class));
        var platform = new com.bencodez.advancedcore.bukkit.runtime.BukkitRuntimePlatform(plugin);
        byte[] previous = Files.readAllBytes(directory.resolve("ServerData.yml"));
        try (Gate gate = new Gate(false)) {
            platform.beforeExecutorShutdown().stream().filter(cleanup -> cleanup.name().equals("server data timestamp"))
                    .findFirst().orElseThrow().action().run();
            gate.entered();
            platform.afterExecutorShutdown().forEach(cleanup -> cleanup.action().run());
            assertNotNull(capturedFiles.get()); assertFalse(capturedFiles.get().toCompletableFuture().isDone());
            assertArrayEquals(previous, Files.readAllBytes(directory.resolve("ServerData.yml")));
            gate.close(); await(capturedFiles.get().toCompletableFuture());
        }
        assertTrue(disk().getLong("LastUpdated") > 0);
        assertTrue(Files.readString(directory.resolve("ServerData.yml")).startsWith("# MCPerks ordered ServerData v1"));
        verify(original, never()).saveDataChecked();
    }

    private static class LegacyHookObserver extends MCPerksMain {
        private int hookCalls;
        private boolean reenter;
        private Thread hookThread;
        @Override public void onShutdownComplete() {
            hookCalls++; hookThread = Thread.currentThread();
            if (reenter) onShutdownComplete(CompletableFuture.completedFuture(null));
            super.onShutdownComplete();
        }
    }

    @Test void completionStageHookPreservesVirtualLegacyDispatchAndReentrantCallsCannotEraseFence() throws Exception {
        var observer = mock(LegacyHookObserver.class, CALLS_REAL_METHODS); observer.reenter = true;
        var ownerField = MCPerksMain.class.getDeclaredField("featureState"); ownerField.setAccessible(true); ownerField.set(observer, state);
        var preparationField = MCPerksMain.class.getDeclaredField("shutdownPreparation"); preparationField.setAccessible(true);
        preparationField.set(observer, CompletableFuture.completedFuture(null));
        CompletableFuture<Void> actual;
        try (Gate gate = new Gate(false)) {
            actual = ordered.mutateFinalData(data -> data.set("LastUpdated", 9753L)).toCompletableFuture();
            gate.entered();
            observer.onShutdownComplete(actual);
            observer.onShutdownComplete(CompletableFuture.completedFuture(null));
            assertEquals(1, observer.hookCalls); assertSame(Thread.currentThread(), observer.hookThread);
            assertFalse(actual.isDone());
            var accepted = state.runtimeStorage(() -> null);
            observer.onShutdownComplete(); // Direct old hook still retains the already admitted fence.
            assertEquals(2, observer.hookCalls);
            gate.close(); await(actual); await(accepted);
        }
        try (FeatureState successor = new FeatureState(directory.resolve("features.dat"))) { await(successor.ready()); }
        assertEquals(9753L, disk().getLong("LastUpdated"));
        assertThrows(ExecutionException.class, () -> await(state.runtimeStorage(() -> null)));
    }

    private void historyMonth() {
        var checker = mock(com.bencodez.advancedcore.api.time.TimeChecker.class);
        when(checker.getTime()).thenReturn(java.time.LocalDateTime.of(2026, 10, 8, 12, 0));
        when(plugin.getTimeChecker()).thenReturn(checker);
    }
    @Test void globalAudienceHistoryCannotPoisonSubsequentTimerAndCalendarSnapshots() throws Exception {
        historyMonth();
        String audience = String.join(",", java.util.stream.IntStream.range(0, 500)
                .mapToObj(i -> new UUID(0, i).toString()).toList());
        await(runtime.confirmedActivation(() -> {
            for (int i = 0; i < 224; i++) runtime.addPerkHistory("activation-" + i + ":" + audience);
            runtime.setPerkExperation(perk, 123456); runtime.setServerCooldown("legacy", 789012);
        }));
        assertTrue(Files.size(directory.resolve("ServerData.yml")) <= 4_000_000);
        var entries = disk().getStringList("PerkHistory.OCTOBER");
        assertFalse(entries.isEmpty()); assertTrue(entries.size() < 224);
        assertEquals("activation-223:" + audience, entries.getLast());
        assertEquals(123456, disk().getLong("Experation.legacy")); assertEquals(789012, disk().getLong("legacy.CoolDown"));
        await(ownerSnapshot(456789)); var transition = await(begin()); await(complete(transition));
        await(runtime.initialize(state));
        assertEquals(456789, runtime.getPerkExperation(perk)); assertEquals(456790, runtime.getServerCooldown("legacy"));
        assertEquals(9, disk().getInt("PrevDay"));
    }
    @Test void completeEncodedHistoryEntriesShareOneBudgetAcrossAllMonths() throws Exception {
        String text = "雪\n'quoted': ".repeat(300);
        synchronized (runtime) {
            for (java.time.Month month : java.time.Month.values()) {
                var entries = new ArrayList<String>();
                for (int i = 0; i < 80; i++) entries.add(i + text);
                runtime.getData().set("PerkHistory." + month, entries);
            }
        }
        await(ownerSnapshot(42));
        var written = disk(); var history = new YamlConfiguration();
        history.set("PerkHistory", written.getConfigurationSection("PerkHistory").getValues(false));
        assertTrue(history.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1_000_000);
        assertTrue(written.getStringList("PerkHistory.DECEMBER").contains("79" + text));
        int count = 0;
        for (java.time.Month month : java.time.Month.values()) {
            var entries = written.getStringList("PerkHistory." + month); count += entries.size();
            for (int i = 0; i < entries.size(); i++) assertEquals((80 - entries.size() + i) + text, entries.get(i));
        }
        assertTrue(count < 960); assertEquals(42, written.getLong("Experation.legacy"));
    }
    @Test void trimmedLiveHistoryRemainsASectionAcrossSuccessiveActivations() throws Exception {
        historyMonth();
        await(runtime.confirmedActivation(() -> runtime.addPerkHistory("first")));
        synchronized (runtime) {
            assertTrue(runtime.getData().isConfigurationSection("PerkHistory"));
            assertEquals(List.of("first"), runtime.getData().getStringList("PerkHistory.OCTOBER"));
        }
        await(runtime.confirmedActivation(() -> runtime.addPerkHistory("second")));
        assertEquals(List.of("first", "second"), disk().getStringList("PerkHistory.OCTOBER"));
    }
    @Test void historyYieldsRemainingSnapshotCapacityToAuthoritativeRuntimeState() throws Exception {
        historyMonth();
        synchronized (runtime) { runtime.getData().set("LegacyPayload", "x".repeat(3_950_000)); }
        await(runtime.confirmedActivation(() -> {
            runtime.addPerkHistory("audience:" + "y".repeat(100_000)); runtime.setPerkExperation(perk, 777);
        }));
        assertTrue(Files.size(directory.resolve("ServerData.yml")) <= 4_000_000);
        assertTrue(disk().getStringList("PerkHistory.OCTOBER").isEmpty());
        assertEquals(3_950_000, disk().getString("LegacyPayload").length());
        await(ownerSnapshot(888)); assertEquals(888, disk().getLong("Experation.legacy"));
    }
    @Test void monthlyCountCompatibilityAndOversizedSingleHistoryEntryCannotPoisonSaves() throws Exception {
        historyMonth();
        await(runtime.confirmedActivation(() -> {
            for (int i = 0; i < 257; i++) runtime.addPerkHistory("small-" + i);
        }));
        var entries = disk().getStringList("PerkHistory.OCTOBER");
        assertEquals(256, entries.size()); assertEquals("small-1", entries.getFirst()); assertEquals("small-256", entries.getLast());
        await(runtime.confirmedActivation(() -> runtime.addPerkHistory("huge:" + "雪".repeat(400_000))));
        assertTrue(disk().getStringList("PerkHistory.OCTOBER").isEmpty());
        await(ownerSnapshot(999)); assertEquals(999, disk().getLong("Experation.legacy"));
    }
}
