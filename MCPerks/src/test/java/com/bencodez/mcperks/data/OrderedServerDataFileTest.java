package com.bencodez.mcperks.data;

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
        runtime = new ServerData(plugin, (staged, file) -> {
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
