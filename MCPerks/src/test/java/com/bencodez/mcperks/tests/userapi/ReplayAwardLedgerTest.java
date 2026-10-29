package com.bencodez.mcperks.tests.userapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.advancedcore.api.user.UserStorage;
import com.bencodez.advancedcore.core.user.storage.SqlUserStorage;
import com.bencodez.advancedcore.core.user.storage.sql.SqlBackendLogger;
import com.bencodez.advancedcore.core.user.storage.sql.SqlUserSchema;
import com.bencodez.advancedcore.core.user.storage.sql.SqliteUserBackend;
import com.bencodez.simpleapi.sql.DataType;
import com.bencodez.simpleapi.sql.data.DataValueInt;
import com.bencodez.simpleapi.sql.data.DataValueString;

class ReplayAwardLedgerTest {
    @TempDir Path directory;
    private SqliteUserBackend backend() {
        return new SqliteUserBackend(directory, "Users", "Users", SqlUserSchema.builder()
                .column("Points", "INTEGER", DataType.INTEGER)
                .column("Activations", "INTEGER", DataType.INTEGER)
                .column("PerkActivations_vote", "INTEGER", DataType.INTEGER)
                .column("MCPerksAwardReceipts", "TEXT", DataType.STRING).build(), SqlBackendLogger.NO_OP);
    }
    private static Object call(String name, Class<?>[] types, Object... args) {
        try {
            var method = Class.forName("com.bencodez.mcperks.userapi.ReplayAwardLedger").getDeclaredMethod(name, types);
            method.setAccessible(true); return method.invoke(null, args);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static boolean award(SqlUserStorage.TransactionScope scope, String id) {
        return (boolean) call("apply", new Class<?>[] { SqlUserStorage.TransactionScope.class, String.class, int.class, String.class }, scope, "Points", 1, id);
    }
    @Test void committedReplayAwardIsNotRepeatedAfterReopeningSqlite() {
        UUID uuid = UUID.randomUUID();
        try (var backend = backend()) {
            var initial = Map.<String, com.bencodez.simpleapi.sql.data.DataValue>of("Points", new DataValueInt(0), "MCPerksAwardReceipts", new DataValueString(""));
            boolean first = backend.user(uuid).transaction(UserStorage.SQLITE, initial, scope -> award(scope, "event:one")); assertTrue(first);
            assertThrows(IllegalStateException.class, () -> backend.user(uuid).transaction(UserStorage.SQLITE, scope -> {
                award(scope, "event:two"); throw new IllegalStateException("rollback");
            }));
        }
        try (var reopened = backend()) {
            boolean repeated = reopened.user(uuid).transaction(UserStorage.SQLITE, scope -> award(scope, "event:one")); assertFalse(repeated);
            var row = reopened.user(uuid).readRow(UserStorage.SQLITE);
            assertEquals(1, row.stream().filter(c -> c.getName().equals("Points")).findFirst().orElseThrow().getValue().getInt());
            assertThrows(IllegalStateException.class, () -> reopened.user(uuid).transaction(UserStorage.SQLITE, scope ->
                    call("apply", new Class<?>[] { SqlUserStorage.TransactionScope.class, String.class, int.class, String.class }, scope, "Points", 2, "event:one")));
            boolean second = reopened.user(uuid).transaction(UserStorage.SQLITE, scope -> award(scope, "event:two")); assertTrue(second);
            for (int i = 0; i < 2; i++) reopened.user(uuid).transaction(UserStorage.SQLITE, scope -> {
                call("retire", new Class<?>[] { SqlUserStorage.TransactionScope.class, String.class }, scope, "event:one"); return null;
            });
            boolean secondAgain = reopened.user(uuid).transaction(UserStorage.SQLITE, scope -> award(scope, "event:two")); assertFalse(secondAgain);
        }
    }
    @Test void separateRewardSourcesKeepSeparateReplayIdentities() {
        UUID uuid = UUID.randomUUID();
        try (var backend = backend()) {
            var initial = Map.<String, com.bencodez.simpleapi.sql.data.DataValue>of("Points", new DataValueInt(0), "MCPerksAwardReceipts", new DataValueString(""));
            boolean first = backend.user(uuid).transaction(UserStorage.SQLITE, initial, scope -> award(scope, "mcperks:same-event")); assertTrue(first);
            boolean second = backend.user(uuid).transaction(UserStorage.SQLITE, scope -> award(scope, "votingplugin:same-event")); assertTrue(second);
            boolean duplicate = backend.user(uuid).transaction(UserStorage.SQLITE, scope -> award(scope, "mcperks:same-event")); assertFalse(duplicate);
            var row = backend.user(uuid).readRow(UserStorage.SQLITE);
            assertEquals(2, row.stream().filter(c -> c.getName().equals("Points")).findFirst().orElseThrow().getValue().getInt());
        }
    }
    @Test void corruptReceiptDataFailsClosedWithoutChangingTheBalance() {
        UUID uuid = UUID.randomUUID();
        try (var backend = backend()) {
            var values = new HashMap<String, com.bencodez.simpleapi.sql.data.DataValue>();
            values.put("Points", new DataValueInt(7)); values.put("MCPerksAwardReceipts", new DataValueString("malformed"));
            backend.user(uuid).writeValues(UserStorage.SQLITE, values);
            assertThrows(IllegalStateException.class, () -> backend.user(uuid).transaction(UserStorage.SQLITE, scope -> award(scope, "event:one")));
            var row = backend.user(uuid).readRow(UserStorage.SQLITE);
            assertEquals(7, row.stream().filter(c -> c.getName().equals("Points")).findFirst().orElseThrow().getValue().getInt());
        }
    }
    @Test void checkpointCannotCreateAMissingUserInAnotherStorageOwner() {
        UUID uuid = UUID.randomUUID();
        try (var backend = backend()) {
            var initial = Map.<String, com.bencodez.simpleapi.sql.data.DataValue>of("MCPerksAwardReceipts", new DataValueString(""));
            assertThrows(SqlUserStorage.ReplayPreconditionException.class, () -> backend.user(uuid).transaction(UserStorage.SQLITE, initial, scope -> {
                call("retire", new Class<?>[] { SqlUserStorage.TransactionScope.class, String.class }, scope, "event:one"); return null;
            }));
            assertFalse(backend.user(uuid).contains(UserStorage.SQLITE));
        }
    }
    @Test void sharedReplayAwardUsesProducerTransactionAndDoesNotRepeat() throws Exception {
        UUID uuid = UUID.randomUUID(); var backend = backend();
        var plugin = mock(com.bencodez.advancedcore.AdvancedCorePlugin.class);
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = new com.bencodez.advancedcore.api.user.usercache.UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var owner = new com.bencodez.advancedcore.bukkit.user.runtime.BukkitUserCacheOwner(manager);
        var runtime = new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend, owner); manager.bindSharedRuntime(runtime);
        var user = mock(com.bencodez.mcperks.userapi.MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(uuid.toString()).when(user).getUUID();
        doReturn(new com.bencodez.advancedcore.api.user.UserData(user)).when(user).getData();
        var notified = new java.util.concurrent.CountDownLatch(1); var logger = mock(java.util.logging.Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        doAnswer(call -> { notified.countDown(); return null; }).when(logger).log(any(java.util.logging.Level.class), anyString(), any(Throwable.class));
        doAnswer(call -> { throw new IllegalStateException("notification failure"); })
                .when(users).onChange(eq(user), any(String[].class));
        try {
            var first = user.addReplayActivationAsync(null, 2, "event:one").toCompletableFuture();
            var duplicate = user.addReplayActivationAsync(null, 2, "event:one").toCompletableFuture();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS); duplicate.get(5, java.util.concurrent.TimeUnit.SECONDS);
            var row = backend.user(uuid).readRow(UserStorage.SQLITE);
            assertEquals(2, row.stream().filter(c -> c.getName().equals("Activations")).findFirst().orElseThrow().getValue().getInt());
            assertTrue(notified.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNotNull(manager.getLastDeferredStorageFailure(), "notification failure is recorded without failing the committed award");
            var conflict = user.addReplayActivationAsync(null, 3, "event:one").toCompletableFuture();
            var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> conflict.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(SqlUserStorage.ReplayPreconditionException.class, error.getCause());
            user.addReplayActivationAsync(null, 1, "event:two").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            var later = backend.user(uuid).readRow(UserStorage.SQLITE);
            assertEquals(3, later.stream().filter(c -> c.getName().equals("Activations")).findFirst().orElseThrow().getValue().getInt());
            assertThrows(IllegalStateException.class, () -> manager.withLegacyIntegerOwnerReplacement(() -> fail("owner changed before checkpoint")));
            var replacement = mock(com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend.class);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> manager.replaceSharedSqlBackendAsync(replacement).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS));
            verify(replacement, never()).close();
            user.checkpointReplayActivationAsync("event:one").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            user.checkpointReplayActivationAsync("event:two").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertDoesNotThrow(() -> manager.withLegacyIntegerOwnerReplacement(() -> {}));
            manager.closeSharedRuntimeAsyncCompletion(() -> {}).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { manager.getTimer().shutdownNow(); if (backend.isOpen()) backend.close(); }
    }
    @Test void unsupportedSharedProviderRejectsAwardWithoutLeakingOwnerLease() throws Exception {
        UUID uuid = UUID.randomUUID(); var backend = mock(com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend.class);
        when(backend.isOpen()).thenReturn(true); when(backend.storageType()).thenReturn(UserStorage.SQLITE);
        var plugin = mock(com.bencodez.advancedcore.AdvancedCorePlugin.class); var users = mock(com.bencodez.advancedcore.api.user.UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = new com.bencodez.advancedcore.api.user.usercache.UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var owner = new com.bencodez.advancedcore.bukkit.user.runtime.BukkitUserCacheOwner(manager);
        manager.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend, owner));
        var user = mock(com.bencodez.mcperks.userapi.MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(uuid.toString()).when(user).getUUID();
        doReturn(new com.bencodez.advancedcore.api.user.UserData(user)).when(user).getData();
        try {
            var receipt = user.addReplayActivationAsync(null, 1, "event:one").toCompletableFuture();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> receipt.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
            assertFalse(manager.hasUnsettledLegacyIntegerMutations()); verify(backend, never()).user(any());
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void checkpointCannotPermitAnAlreadyAdmittedDuplicateAward() throws Exception {
        UUID uuid = UUID.randomUUID(); var backend = backend();
        var plugin = mock(com.bencodez.advancedcore.AdvancedCorePlugin.class); var users = mock(com.bencodez.advancedcore.api.user.UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = spy(new com.bencodez.advancedcore.api.user.usercache.UserDataManager(plugin)); when(users.getDataManager()).thenReturn(manager);
        manager.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend,
                new com.bencodez.advancedcore.bukkit.user.runtime.BukkitUserCacheOwner(manager)));
        var user = mock(com.bencodez.mcperks.userapi.MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(uuid.toString()).when(user).getUUID();
        doReturn(new com.bencodez.advancedcore.api.user.UserData(user)).when(user).getData();
        var pause = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> {
            if (pause.get()) { entered.countDown(); assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            return call.callRealMethod();
        }).when(manager).submitSharedReplayTransaction(any(), any(), anyMap(), any());
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            user.addReplayActivationAsync(null, 2, "event:one").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            pause.set(true);
            var delayed = pool.submit(() -> user.addReplayActivationAsync(null, 2, "event:one").toCompletableFuture());
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            user.checkpointReplayActivationAsync("event:one").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            release.countDown();
            var pending = delayed.get(5, java.util.concurrent.TimeUnit.SECONDS);
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(SqlUserStorage.ReplayPreconditionException.class, failure.getCause());
            var row = backend.user(uuid).readRow(UserStorage.SQLITE);
            assertEquals(2, row.stream().filter(c -> c.getName().equals("Activations")).findFirst().orElseThrow().getValue().getInt());
        } finally { release.countDown(); pool.shutdownNow(); manager.getTimer().shutdownNow(); if (backend.isOpen()) backend.close(); }
    }
    @Test void customUserDataMustOptInBeforeReplayCanBypassOverrides() throws Exception {
        UUID uuid = UUID.randomUUID(); var backend = mock(com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend.class);
        when(backend.isOpen()).thenReturn(true); when(backend.storageType()).thenReturn(UserStorage.SQLITE); when(backend.supportsAtomicUserTransactions()).thenReturn(true);
        var plugin = mock(com.bencodez.advancedcore.AdvancedCorePlugin.class); var users = mock(com.bencodez.advancedcore.api.user.UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = new com.bencodez.advancedcore.api.user.usercache.UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var owner = new com.bencodez.advancedcore.bukkit.user.runtime.BukkitUserCacheOwner(manager);
        manager.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend, owner));
        var user = mock(com.bencodez.mcperks.userapi.MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(uuid.toString()).when(user).getUUID();
        doReturn(new com.bencodez.advancedcore.api.user.UserData(user) { }).when(user).getData();
        try {
            var receipt = user.addReplayActivationAsync(null, 1, "event:one").toCompletableFuture();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> receipt.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
            assertFalse(manager.hasUnsettledLegacyIntegerMutations()); verify(backend, never()).user(any());
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void failedCheckpointKeepsOwnerUntilCleanupCanSucceed() throws Exception {
        UUID uuid = UUID.randomUUID(); var backend = backend();
        var plugin = mock(com.bencodez.advancedcore.AdvancedCorePlugin.class); var users = mock(com.bencodez.advancedcore.api.user.UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = new com.bencodez.advancedcore.api.user.usercache.UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var owner = new com.bencodez.advancedcore.bukkit.user.runtime.BukkitUserCacheOwner(manager);
        manager.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend, owner));
        var user = mock(com.bencodez.mcperks.userapi.MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(uuid.toString()).when(user).getUUID();
        doReturn(new com.bencodez.advancedcore.api.user.UserData(user)).when(user).getData();
        try {
            user.addReplayActivationAsync(null, 2, "event:one").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            var row = backend.user(uuid).readRow(UserStorage.SQLITE);
            String saved = row.stream().filter(c -> c.getName().equals("MCPerksAwardReceipts")).findFirst().orElseThrow().getValue().getString();
            backend.user(uuid).writeValues(UserStorage.SQLITE, new HashMap<>(Map.of("MCPerksAwardReceipts", new DataValueString("invalid"))));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> user.checkpointReplayActivationAsync("event:one").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(manager.hasUnsettledLegacyIntegerMutations());
            assertThrows(IllegalStateException.class, () -> manager.withLegacyIntegerOwnerReplacement(() -> fail("owner changed")));
            backend.user(uuid).writeValues(UserStorage.SQLITE, new HashMap<>(Map.of("MCPerksAwardReceipts", new DataValueString(saved))));
            user.checkpointReplayActivationAsync("event:one").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertDoesNotThrow(() -> manager.withLegacyIntegerOwnerReplacement(() -> {}));
            manager.closeSharedRuntimeAsyncCompletion(() -> {}).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { manager.getTimer().shutdownNow(); if (backend.isOpen()) backend.close(); }
    }
    @Test void replayAfterAbruptRestartDoesNotRepeatCommittedAward() throws Exception {
        UUID uuid = UUID.randomUUID(); var firstBackend = backend();
        var plugin = mock(com.bencodez.advancedcore.AdvancedCorePlugin.class); var users = mock(com.bencodez.advancedcore.api.user.UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var user = mock(com.bencodez.mcperks.userapi.MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(uuid.toString()).when(user).getUUID();
        doReturn(new com.bencodez.advancedcore.api.user.UserData(user)).when(user).getData();
        var first = new com.bencodez.advancedcore.api.user.usercache.UserDataManager(plugin); when(users.getDataManager()).thenReturn(first);
        var firstOwner = new com.bencodez.advancedcore.bukkit.user.runtime.BukkitUserCacheOwner(first);
        first.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(firstBackend, firstOwner));
        try {
            user.addReplayActivationAsync(null, 2, "event:one").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { first.getTimer().shutdownNow(); firstBackend.close(); }
        var secondBackend = backend(); var second = new com.bencodez.advancedcore.api.user.usercache.UserDataManager(plugin);
        when(users.getDataManager()).thenReturn(second);
        var secondOwner = new com.bencodez.advancedcore.bukkit.user.runtime.BukkitUserCacheOwner(second);
        second.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(secondBackend, secondOwner));
        try {
            user.addReplayActivationAsync(null, 2, "event:one").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            var row = secondBackend.user(uuid).readRow(UserStorage.SQLITE);
            assertEquals(2, row.stream().filter(c -> c.getName().equals("Activations")).findFirst().orElseThrow().getValue().getInt());
            user.checkpointReplayActivationAsync("event:one").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            second.closeSharedRuntimeAsyncCompletion(() -> {}).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { second.getTimer().shutdownNow(); if (secondBackend.isOpen()) secondBackend.close(); }
    }
    @Test void shutdownWaitsForReplayCheckpointAndPermitsCleanupDuringDrain() throws Exception {
        UUID uuid = UUID.randomUUID(); var backend = backend();
        var plugin = mock(com.bencodez.advancedcore.AdvancedCorePlugin.class);
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = new com.bencodez.advancedcore.api.user.usercache.UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var owner = new com.bencodez.advancedcore.bukkit.user.runtime.BukkitUserCacheOwner(manager);
        var runtime = new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend, owner); manager.bindSharedRuntime(runtime);
        var user = mock(com.bencodez.mcperks.userapi.MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(uuid.toString()).when(user).getUUID();
        doReturn(new com.bencodez.advancedcore.api.user.UserData(user)).when(user).getData();
        try {
            user.addReplayActivationAsync(null, 2, "event:one").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            var shutdown = manager.closeSharedRuntimeAsyncCompletion(() -> {}).toCompletableFuture(); assertFalse(shutdown.isDone());
            user.checkpointReplayActivationAsync("event:one").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            shutdown.get(5, java.util.concurrent.TimeUnit.SECONDS); assertFalse(backend.isOpen());
        } finally { manager.getTimer().shutdownNow(); if (backend.isOpen()) backend.close(); }
    }
    @Test void registeredRewardInjectorUsesReplayLedgerAndDurableCheckpoint() throws Exception {
        UUID uuid = UUID.randomUUID(); var backend = backend();
        var plugin = mock(com.bencodez.mcperks.MCPerksMain.class, CALLS_REAL_METHODS);
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class);
        var mcUsers = mock(com.bencodez.mcperks.userapi.UserManager.class);
        var handler = mock(com.bencodez.advancedcore.api.rewards.RewardHandler.class);
        var perks = mock(com.bencodez.mcperks.perk.PerkHandler.class);
        var player = mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class);
        var target = mock(com.bencodez.mcperks.userapi.MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(users).when(plugin).getUserManager(); doReturn(UserStorage.SQLITE).when(plugin).getStorageType();
        doReturn(null).when(plugin).getNativeUserStorageOwner();
        doReturn(mock(com.bencodez.advancedcore.AdvancedCoreConfigOptions.class, RETURNS_DEEP_STUBS)).when(plugin).getOptions();
        doReturn(mcUsers).when(plugin).getMcperksUserManager(); doReturn(handler).when(plugin).getRewardHandler();
        doReturn(perks).when(plugin).getPerkHandler();
        var loaded = new HashMap<String, com.bencodez.mcperks.perk.Perk>(); loaded.put("vote", mock(com.bencodez.mcperks.perk.Perk.class));
        when(perks.getLoadedPerks()).thenReturn(loaded);
        when(player.getPlayerName()).thenReturn("example"); when(mcUsers.getMCPerksUser("example")).thenReturn(target);
        doReturn(plugin).when(target).getPlugin(); doReturn(uuid.toString()).when(target).getUUID();
        doReturn(new com.bencodez.advancedcore.api.user.UserData(target)).when(target).getData();
        when(users.getUser(uuid, false)).thenReturn(target);
        doReturn(new com.bencodez.advancedcore.api.user.UserData(target)).when(target).getUserData();
        var manager = new com.bencodez.advancedcore.api.user.usercache.UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var owner = new com.bencodez.advancedcore.bukkit.user.runtime.BukkitUserCacheOwner(manager);
        var runtime = new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend, owner); manager.bindSharedRuntime(runtime);
        var previous = com.bencodez.mcperks.MCPerksMain.plugin; com.bencodez.mcperks.MCPerksMain.plugin = plugin;
        var occurrenceField = com.bencodez.advancedcore.api.rewards.Reward.class.getDeclaredField("ACTIVE_REPLAY_OCCURRENCE_ID"); occurrenceField.setAccessible(true);
        var keyField = com.bencodez.advancedcore.api.rewards.Reward.class.getDeclaredField("ACTIVE_REPLAY_KEY"); keyField.setAccessible(true);
        @SuppressWarnings("unchecked") var occurrence = (ThreadLocal<String>) occurrenceField.get(null);
        @SuppressWarnings("unchecked") var key = (ThreadLocal<String>) keyField.get(null);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            plugin.loadInjectedRewards();
            var captured = org.mockito.ArgumentCaptor.forClass(com.bencodez.advancedcore.api.rewards.injected.RewardInject.class);
            verify(handler, times(2)).addInjectedReward(captured.capture());
            var inject = (com.bencodez.advancedcore.api.rewards.injected.RewardInjectInt) captured.getAllValues().stream()
                    .filter(value -> value.getPath().equals("Activations")).findFirst().orElseThrow();
            var perkInject = (com.bencodez.advancedcore.api.rewards.injected.RewardInjectInt) captured.getAllValues().stream()
                    .filter(value -> value.getPath().equals("PerkActivations.vote")).findFirst().orElseThrow();
            assertTrue(inject.supportsAsyncRequest(), "registered global reward must dispatch its real async receipt");
            assertTrue(perkInject.supportsAsyncRequest(), "registered perk reward must dispatch its real async receipt");
            occurrence.set("event-one"); key.set("root:activation");
            var reward = mock(com.bencodez.advancedcore.api.rewards.Reward.class);
            var config = mock(com.bencodez.advancedcore.api.rewards.RewardFileData.class);
            var configData = new org.bukkit.configuration.file.YamlConfiguration(); configData.set("Activations", 2); configData.set("PerkActivations.vote", 4);
            when(reward.getConfig()).thenReturn(config); when(config.getConfigData()).thenReturn(configData);
            when(player.endAsyncActionCollection(isNull())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
            var state = com.bencodez.advancedcore.api.rewards.Reward.replayStateFor(new com.bencodez.advancedcore.api.rewards.RewardOptions());
            var dispatch = com.bencodez.advancedcore.api.rewards.Reward.class.getDeclaredMethod("requestInjectionAsync",
                    com.bencodez.advancedcore.api.rewards.injected.RewardInject.class,
                    com.bencodez.advancedcore.api.user.AdvancedCoreUser.class, HashMap.class,
                    com.bencodez.advancedcore.api.rewards.Reward.ReplayState.class, String.class, String.class,
                    com.bencodez.advancedcore.api.rewards.RewardOptions.class, org.bukkit.entity.Player.class);
            dispatch.setAccessible(true);
            try (var core = mockStatic(com.bencodez.advancedcore.AdvancedCorePlugin.class)) {
                core.when(com.bencodez.advancedcore.AdvancedCorePlugin::getInstance).thenReturn(plugin);
                for (int i = 0; i < 2; i++) {
                    @SuppressWarnings("unchecked") var award = (java.util.concurrent.CompletionStage<Object>) dispatch.invoke(
                            reward, inject, player, new HashMap<>(), state, "root:activation", "event-one", null, null);
                    award.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (i == 0) configData.set("Activations", null);
                }
                configData.set("PerkActivations.vote", 4);
                for (int i = 0; i < 2; i++) {
                    @SuppressWarnings("unchecked") var award = (java.util.concurrent.CompletionStage<Object>) dispatch.invoke(
                            reward, perkInject, player, new HashMap<>(), state, "root:perk", "event-one", null, null);
                    award.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (i == 0) configData.set("PerkActivations.vote", null);
                }
            }
            key.set("root:perk");
            var row = backend.user(uuid).readRow(UserStorage.SQLITE);
            assertEquals(2, row.stream().filter(c -> c.getName().equals("Activations")).findFirst().orElseThrow().getValue().getInt());
            assertEquals(4, row.stream().filter(c -> c.getName().equals("PerkActivations_vote")).findFirst().orElseThrow().getValue().getInt());
            perkInject.onReplayCheckpointPersisted(reward, player, "event-one", "root:perk", true).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            inject.onReplayCheckpointPersisted(reward, player, "event-one", "root:activation", true).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            occurrence.remove(); key.remove();
            inject.onRewardRequestAsync(reward, player, 1, new HashMap<>()).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(3, manager.getPublishedCache(uuid).getIfPresent("Activations").getInt());
            var custom = new com.bencodez.advancedcore.api.user.UserData(target) {
                @Override public int getInt(String name) { throw new AssertionError("unexpected custom getter"); }
                @Override public void setInt(String name, int value) { throw new AssertionError("unexpected custom setter"); }
            };
            doReturn(custom).when(target).getData();
            var unsupported = inject.onRewardRequestAsync(reward, player, 1, new HashMap<>()).toCompletableFuture();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> unsupported.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
            assertEquals(3, manager.getPublishedCache(uuid).getIfPresent("Activations").getInt());
            manager.closeSharedRuntimeAsyncCompletion(() -> {}).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            occurrence.remove(); key.remove(); com.bencodez.mcperks.MCPerksMain.plugin = previous;
            manager.getTimer().shutdownNow(); if (backend.isOpen()) backend.close();
        }
    }
    @Test void unrelatedReplayCheckpointDoesNotCreateAnAbsentUserRow() throws Exception {
        UUID id = UUID.randomUUID(); var backend = backend();
        var plugin = mock(com.bencodez.mcperks.MCPerksMain.class, CALLS_REAL_METHODS);
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class);
        var mcUsers = mock(com.bencodez.mcperks.userapi.UserManager.class);
        var handler = mock(com.bencodez.advancedcore.api.rewards.RewardHandler.class);
        var perks = mock(com.bencodez.mcperks.perk.PerkHandler.class);
        var player = mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class);
        var target = mock(com.bencodez.mcperks.userapi.MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(users).when(plugin).getUserManager(); doReturn(UserStorage.SQLITE).when(plugin).getStorageType();
        doReturn(null).when(plugin).getNativeUserStorageOwner();
        doReturn(mock(com.bencodez.advancedcore.AdvancedCoreConfigOptions.class, RETURNS_DEEP_STUBS)).when(plugin).getOptions();
        doReturn(mcUsers).when(plugin).getMcperksUserManager(); doReturn(handler).when(plugin).getRewardHandler();
        doReturn(perks).when(plugin).getPerkHandler();
        var loaded = new HashMap<String, com.bencodez.mcperks.perk.Perk>(); loaded.put("vote", mock(com.bencodez.mcperks.perk.Perk.class));
        when(perks.getLoadedPerks()).thenReturn(loaded);
        when(player.getPlayerName()).thenReturn("unrelated"); when(mcUsers.getMCPerksUser("unrelated")).thenReturn(target);
        doReturn(plugin).when(target).getPlugin(); doReturn(id.toString()).when(target).getUUID();
        doReturn(new com.bencodez.advancedcore.api.user.UserData(target)).when(target).getData();
        when(users.getUser(id, false)).thenReturn(target);
        doReturn(new com.bencodez.advancedcore.api.user.UserData(target)).when(target).getUserData();
        var manager = new com.bencodez.advancedcore.api.user.usercache.UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var owner = new com.bencodez.advancedcore.bukkit.user.runtime.BukkitUserCacheOwner(manager);
        manager.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend, owner));
        var previous = com.bencodez.mcperks.MCPerksMain.plugin; com.bencodez.mcperks.MCPerksMain.plugin = plugin;
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            plugin.loadInjectedRewards();
            var captured = org.mockito.ArgumentCaptor.forClass(com.bencodez.advancedcore.api.rewards.injected.RewardInject.class);
            verify(handler, times(2)).addInjectedReward(captured.capture());
            var inject = captured.getAllValues().stream().filter(item -> item.getPath().equals("Activations")).findFirst().orElseThrow();
            var laterCalls = new java.util.concurrent.atomic.AtomicInteger();
            var registrations = new java.util.concurrent.CopyOnWriteArrayList<>(captured.getAllValues());
            registrations.add(new com.bencodez.advancedcore.api.rewards.injected.RewardInject("Later") {
                @Override public Object onRewardRequest(com.bencodez.advancedcore.api.rewards.Reward r,
                        com.bencodez.advancedcore.api.user.AdvancedCoreUser u,
                        org.bukkit.configuration.ConfigurationSection data, HashMap<String, String> placeholders) {
                    laterCalls.incrementAndGet(); return null;
                }
            });
            when(handler.getInjectedRewards()).thenReturn(registrations);
            doReturn(true).when(plugin).isEnabled();
            var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class); doReturn(scheduler).when(plugin).getBukkitScheduler();
            doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; }).when(scheduler).executeOrScheduleSync(eq(plugin), any(Runnable.class));
            doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
            when(player.endAsyncActionCollection(isNull())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
            var data = new org.bukkit.configuration.file.YamlConfiguration();
            try (var core = mockStatic(com.bencodez.advancedcore.AdvancedCorePlugin.class)) {
                core.when(com.bencodez.advancedcore.AdvancedCorePlugin::getInstance).thenReturn(plugin);
                var actualReward = new com.bencodez.advancedcore.api.rewards.Reward("unrelated", data);
                var pluginField = com.bencodez.advancedcore.api.rewards.Reward.class.getDeclaredField("plugin"); pluginField.setAccessible(true); pluginField.set(actualReward, plugin);
                var replay = com.bencodez.advancedcore.api.rewards.Reward.class.getDeclaredMethod("giveInjectedRewardsAsync",
                        com.bencodez.advancedcore.api.user.AdvancedCoreUser.class, HashMap.class, int.class);
                replay.setAccessible(true);
                @SuppressWarnings("unchecked") var delivery = (java.util.concurrent.CompletionStage<Void>) replay.invoke(actualReward, player, new HashMap<>(), 0);
                delivery.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
                @SuppressWarnings("unchecked") var resumed = (java.util.concurrent.CompletionStage<Void>) replay.invoke(actualReward, player, new HashMap<>(), 2);
                resumed.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertEquals(2, laterCalls.get(), "later injector also runs after resumed unselected credit stages");
            var reward = mock(com.bencodez.advancedcore.api.rewards.Reward.class);
            inject.onReplayCheckpointPersisted(reward, player, "unrelated-event", "root:unrelated", false)
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            verify(mcUsers, never()).getMCPerksUser("unrelated");
            assertFalse(backend.user(id).contains(UserStorage.SQLITE), "unexecuted injection must not create a row");
            assertThrows(java.util.concurrent.ExecutionException.class, () -> inject.onReplayCheckpointPersisted(
                    reward, player, "unrelated-event", "root:unrelated", true).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> inject.onReplayCheckpointPersisted(
                    reward, player, "unrelated-event", "root:unrelated").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(backend.user(id).contains(UserStorage.SQLITE), "unknown or missing selected row must not be created");
        } finally { com.bencodez.mcperks.MCPerksMain.plugin = previous; manager.getTimer().shutdownNow(); if (backend.isOpen()) backend.close(); }
    }
    @Test void nonSharedRewardInjectorWaitsForTheActualAwardReceipt() throws Exception {
        var plugin = mock(com.bencodez.mcperks.MCPerksMain.class, CALLS_REAL_METHODS);
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class);
        var manager = mock(com.bencodez.advancedcore.api.user.usercache.UserDataManager.class);
        var mcUsers = mock(com.bencodez.mcperks.userapi.UserManager.class);
        var handler = mock(com.bencodez.advancedcore.api.rewards.RewardHandler.class);
        var perks = mock(com.bencodez.mcperks.perk.PerkHandler.class);
        var player = mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class);
        var target = mock(com.bencodez.mcperks.userapi.MCPerksUser.class);
        doReturn(users).when(plugin).getUserManager(); doReturn(UserStorage.SQLITE).when(plugin).getStorageType();
        doReturn(mock(com.bencodez.advancedcore.AdvancedCoreConfigOptions.class, RETURNS_DEEP_STUBS)).when(plugin).getOptions();
        doReturn(mcUsers).when(plugin).getMcperksUserManager(); doReturn(handler).when(plugin).getRewardHandler(); doReturn(perks).when(plugin).getPerkHandler();
        when(users.getDataManager()).thenReturn(manager);
        var loaded = new HashMap<String, com.bencodez.mcperks.perk.Perk>(); loaded.put("vote", mock(com.bencodez.mcperks.perk.Perk.class));
        when(perks.getLoadedPerks()).thenReturn(loaded);
        when(player.getPlayerName()).thenReturn("example"); when(mcUsers.getMCPerksUser("example")).thenReturn(target);
        var receipt = new java.util.concurrent.CompletableFuture<Void>(); when(target.addActivationAsync(2)).thenReturn(receipt);
        var previous = com.bencodez.mcperks.MCPerksMain.plugin; com.bencodez.mcperks.MCPerksMain.plugin = plugin;
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            plugin.loadInjectedRewards();
            var captured = org.mockito.ArgumentCaptor.forClass(com.bencodez.advancedcore.api.rewards.injected.RewardInject.class);
            verify(handler, times(2)).addInjectedReward(captured.capture());
            var inject = (com.bencodez.advancedcore.api.rewards.injected.RewardInjectInt) captured.getAllValues().stream()
                    .filter(item -> item.getPath().equals("Activations")).findFirst().orElseThrow();
            var perkInject = (com.bencodez.advancedcore.api.rewards.injected.RewardInjectInt) captured.getAllValues().stream()
                    .filter(item -> item.getPath().equals("PerkActivations.vote")).findFirst().orElseThrow();
            var reward = mock(com.bencodez.advancedcore.api.rewards.Reward.class);
            var config = mock(com.bencodez.advancedcore.api.rewards.RewardFileData.class);
            var data = new org.bukkit.configuration.file.YamlConfiguration(); data.set("Activations", 2);
            when(reward.getConfig()).thenReturn(config); when(config.getConfigData()).thenReturn(data);
            when(player.endAsyncActionCollection(isNull())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
            var state = com.bencodez.advancedcore.api.rewards.Reward.replayStateFor(new com.bencodez.advancedcore.api.rewards.RewardOptions());
            var dispatch = com.bencodez.advancedcore.api.rewards.Reward.class.getDeclaredMethod("requestInjectionAsync",
                    com.bencodez.advancedcore.api.rewards.injected.RewardInject.class,
                    com.bencodez.advancedcore.api.user.AdvancedCoreUser.class, HashMap.class,
                    com.bencodez.advancedcore.api.rewards.Reward.ReplayState.class, String.class, String.class,
                    com.bencodez.advancedcore.api.rewards.RewardOptions.class, org.bukkit.entity.Player.class);
            dispatch.setAccessible(true);
            try (var core = mockStatic(com.bencodez.advancedcore.AdvancedCorePlugin.class)) {
                core.when(com.bencodez.advancedcore.AdvancedCorePlugin::getInstance).thenReturn(plugin);
                @SuppressWarnings("unchecked") var result = ((java.util.concurrent.CompletionStage<Object>) dispatch.invoke(
                        reward, inject, player, new HashMap<>(), state, "root:activation", null, null, null)).toCompletableFuture();
                assertFalse(result.isDone()); verify(target, never()).addActivation(2);
                receipt.complete(null); result.get(5, java.util.concurrent.TimeUnit.SECONDS); verify(target).addActivationAsync(2);
                data.set("Activations", null);
                @SuppressWarnings("unchecked") var absent = (java.util.concurrent.CompletionStage<Object>) dispatch.invoke(
                        reward, inject, player, new HashMap<>(), state, "root:absent", null, null, null);
                assertNull(absent.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS));
                verify(target, times(1)).addActivationAsync(2);
                data.set("Activations", 2);
                var failureReceipt = new java.util.concurrent.CompletableFuture<Void>(); when(target.addActivationAsync(2)).thenReturn(failureReceipt);
                @SuppressWarnings("unchecked") var failed = (java.util.concurrent.CompletionStage<Object>) dispatch.invoke(
                        reward, inject, player, new HashMap<>(), state, "root:failure", null, null, null);
                assertFalse(failed.toCompletableFuture().isDone());
                failureReceipt.completeExceptionally(new UnsupportedOperationException("award rejected before admission"));
                assertThrows(java.util.concurrent.ExecutionException.class, () -> failed.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS));
                data.set("Activations", null);
                var replayReceipt = new java.util.concurrent.CompletableFuture<Void>(); when(target.addActivationAsync(2)).thenReturn(replayReceipt);
                @SuppressWarnings("unchecked") var resumed = (java.util.concurrent.CompletionStage<Object>) dispatch.invoke(
                        reward, inject, player, new HashMap<>(), state, "root:failure", null, null, null);
                assertFalse(resumed.toCompletableFuture().isDone(), "saved award intent must survive configuration removal");
                replayReceipt.complete(null); resumed.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
                data.set("PerkActivations.vote", 2);
                var perkReceipt = new java.util.concurrent.CompletableFuture<Void>(); when(target.addActivationAsync("vote", 2)).thenReturn(perkReceipt);
                @SuppressWarnings("unchecked") var perkResult = (java.util.concurrent.CompletionStage<Object>) dispatch.invoke(
                        reward, perkInject, player, new HashMap<>(), state, "root:perk", null, null, null);
                assertFalse(perkResult.toCompletableFuture().isDone()); verify(target, never()).addActivation("vote", 2);
                perkReceipt.complete(null); perkResult.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
                verify(target).addActivationAsync("vote", 2);
            }
        } finally { com.bencodez.mcperks.MCPerksMain.plugin = previous; }
    }
    @Test void votingPluginRewardInjectorUsesTheSameReplayLedger() throws Exception {
        UUID uuid = UUID.randomUUID(); var backend = backend();
        var plugin = mock(com.bencodez.mcperks.MCPerksMain.class);
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class);
        var mcUsers = mock(com.bencodez.mcperks.userapi.UserManager.class);
        var perks = mock(com.bencodez.mcperks.perk.PerkHandler.class);
        var player = mock(com.bencodez.votingplugin.advancedcore.api.user.AdvancedCoreUser.class);
        var target = mock(com.bencodez.mcperks.userapi.MCPerksUser.class, CALLS_REAL_METHODS);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        when(plugin.getMcperksUserManager()).thenReturn(mcUsers); when(plugin.getPerkHandler()).thenReturn(perks);
        when(plugin.getOptions()).thenReturn(mock(com.bencodez.advancedcore.AdvancedCoreConfigOptions.class, RETURNS_DEEP_STUBS));
        var loaded = new HashMap<String, com.bencodez.mcperks.perk.Perk>(); loaded.put("vote", mock(com.bencodez.mcperks.perk.Perk.class));
        when(perks.getLoadedPerks()).thenReturn(loaded); when(plugin.getJavascriptEngineRequests()).thenReturn(new java.util.ArrayList<>());
        when(player.getPlayerName()).thenReturn("example"); when(mcUsers.getMCPerksUser("example")).thenReturn(target);
        doReturn(plugin).when(target).getPlugin(); doReturn(uuid.toString()).when(target).getUUID();
        doReturn(new com.bencodez.advancedcore.api.user.UserData(target)).when(target).getData();
        when(users.getUser(uuid, false)).thenReturn(target);
        doReturn(new com.bencodez.advancedcore.api.user.UserData(target)).when(target).getUserData();
        var manager = new com.bencodez.advancedcore.api.user.usercache.UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var owner = new com.bencodez.advancedcore.bukkit.user.runtime.BukkitUserCacheOwner(manager);
        var runtime = new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend, owner); manager.bindSharedRuntime(runtime);
        var previous = com.bencodez.mcperks.MCPerksMain.plugin; com.bencodez.mcperks.MCPerksMain.plugin = plugin;
        var occurrenceField = com.bencodez.votingplugin.advancedcore.api.rewards.Reward.class.getDeclaredField("ACTIVE_REPLAY_OCCURRENCE_ID"); occurrenceField.setAccessible(true);
        var keyField = com.bencodez.votingplugin.advancedcore.api.rewards.Reward.class.getDeclaredField("ACTIVE_REPLAY_KEY"); keyField.setAccessible(true);
        @SuppressWarnings("unchecked") var occurrence = (ThreadLocal<String>) occurrenceField.get(null);
        @SuppressWarnings("unchecked") var key = (ThreadLocal<String>) keyField.get(null);
        var hooks = mock(com.bencodez.votingplugin.VotingPluginHooks.class);
        try (var staticHooks = mockStatic(com.bencodez.votingplugin.VotingPluginHooks.class)) {
            staticHooks.when(com.bencodez.votingplugin.VotingPluginHooks::getInstance).thenReturn(hooks);
            com.bencodez.mcperks.VotingPluginHook.getInstance().loadRewards();
            var captured = org.mockito.ArgumentCaptor.forClass(com.bencodez.votingplugin.advancedcore.api.rewards.injected.RewardInject.class);
            verify(hooks, times(2)).addCustomReward(captured.capture());
            var inject = (com.bencodez.votingplugin.advancedcore.api.rewards.injected.RewardInjectInt) captured.getAllValues().stream()
                    .filter(item -> item.getPath().equals("Activations")).findFirst().orElseThrow();
            var perkInject = (com.bencodez.votingplugin.advancedcore.api.rewards.injected.RewardInjectInt) captured.getAllValues().stream()
                    .filter(item -> item.getPath().equals("PerkActivations.vote")).findFirst().orElseThrow();
            assertTrue(inject.supportsAsyncRequest(), "VotingPlugin global reward must dispatch its real async receipt");
            assertTrue(perkInject.supportsAsyncRequest(), "VotingPlugin perk reward must dispatch its real async receipt");
            assertTrue(inject.requiresConfiguredDataForAsync()); assertTrue(perkInject.requiresConfiguredDataForAsync());
            var unrelated = mock(com.bencodez.votingplugin.advancedcore.api.user.AdvancedCoreUser.class);
            when(unrelated.getPlayerName()).thenReturn("unrelated");
            for (var candidate : java.util.List.of(inject, perkInject)) {
                var contextual = candidate.getClass().getMethod("onReplayCheckpointPersisted",
                        com.bencodez.votingplugin.advancedcore.api.rewards.Reward.class,
                        com.bencodez.votingplugin.advancedcore.api.user.AdvancedCoreUser.class, String.class, String.class, boolean.class);
                contextual.setAccessible(true);
                @SuppressWarnings("unchecked") var skipped = (java.util.concurrent.CompletionStage<Void>) contextual.invoke(
                        candidate, mock(com.bencodez.votingplugin.advancedcore.api.rewards.Reward.class), unrelated, "other", "root:other", false);
                skipped.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            verify(mcUsers, never()).getMCPerksUser("unrelated");
            occurrence.set("event-one"); key.set("root:activation");
            var reward = mock(com.bencodez.votingplugin.advancedcore.api.rewards.Reward.class);
            inject.onRewardRequestAsync(reward, player, 2, new HashMap<>()).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            inject.onRewardRequestAsync(reward, player, 2, new HashMap<>()).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            var row = backend.user(uuid).readRow(UserStorage.SQLITE);
            assertEquals(2, row.stream().filter(c -> c.getName().equals("Activations")).findFirst().orElseThrow().getValue().getInt());
            inject.onReplayCheckpointPersisted(reward, player, "event-one", "root:activation").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            occurrence.remove(); key.remove();
            inject.onRewardRequestAsync(reward, player, 1, new HashMap<>()).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(3, manager.getPublishedCache(uuid).getIfPresent("Activations").getInt());
            var fallback = mock(com.bencodez.mcperks.userapi.MCPerksUser.class);
            when(mcUsers.getMCPerksUser("example")).thenReturn(fallback);
            var fallbackReceipt = new java.util.concurrent.CompletableFuture<Void>();
            when(fallback.addActivationAsync(3)).thenReturn(fallbackReceipt);
            try (var oldReward = mockStatic(com.bencodez.votingplugin.advancedcore.api.rewards.Reward.class, CALLS_REAL_METHODS)) {
                oldReward.when(com.bencodez.votingplugin.advancedcore.api.rewards.Reward::currentReplaySideEffectOccurrenceId).thenThrow(new NoSuchMethodError());
                var pendingFallback = inject.onRewardRequestAsync(reward, player, 3, new HashMap<>()).toCompletableFuture();
                assertFalse(pendingFallback.isDone()); fallbackReceipt.complete(null); pendingFallback.get(5, java.util.concurrent.TimeUnit.SECONDS);
                verify(fallback).addActivationAsync(3); verify(fallback, never()).addActivation(3);
                oldReward.when(() -> com.bencodez.votingplugin.advancedcore.api.rewards.Reward.replaySideEffectOccurrenceId("event-one", "root:activation"))
                        .thenThrow(new NoSuchMethodError());
                inject.onReplayCheckpointPersisted(reward, player, "event-one", "root:activation").toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            // Exercise the shaded VotingPlugin Reward dispatcher for both registrations.
            var dispatchReward = mock(com.bencodez.votingplugin.advancedcore.api.rewards.Reward.class);
            var config = mock(com.bencodez.votingplugin.advancedcore.api.rewards.RewardFileData.class);
            var configData = new org.bukkit.configuration.file.YamlConfiguration(); configData.set("Activations", 3);
            when(dispatchReward.getConfig()).thenReturn(config); when(config.getConfigData()).thenReturn(configData);
            when(player.endAsyncActionCollection(isNull())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
            var state = com.bencodez.votingplugin.advancedcore.api.rewards.Reward.replayStateFor(
                    new com.bencodez.votingplugin.advancedcore.api.rewards.RewardOptions());
            var dispatch = com.bencodez.votingplugin.advancedcore.api.rewards.Reward.class.getDeclaredMethod("requestInjectionAsync",
                    com.bencodez.votingplugin.advancedcore.api.rewards.injected.RewardInject.class,
                    com.bencodez.votingplugin.advancedcore.api.user.AdvancedCoreUser.class, HashMap.class,
                    com.bencodez.votingplugin.advancedcore.api.rewards.Reward.ReplayState.class, String.class, String.class);
            dispatch.setAccessible(true);
            var vpCore = mock(com.bencodez.votingplugin.advancedcore.AdvancedCorePlugin.class);
            try (var core = mockStatic(com.bencodez.votingplugin.advancedcore.AdvancedCorePlugin.class)) {
                core.when(com.bencodez.votingplugin.advancedcore.AdvancedCorePlugin::getInstance).thenReturn(vpCore);
                var globalReceipt = new java.util.concurrent.CompletableFuture<Void>(); when(fallback.addActivationAsync(3)).thenReturn(globalReceipt);
                @SuppressWarnings("unchecked") var global = (java.util.concurrent.CompletionStage<Object>) dispatch.invoke(
                        dispatchReward, inject, player, new HashMap<>(), state, "root:global", null);
                assertFalse(global.toCompletableFuture().isDone()); globalReceipt.complete(null);
                global.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
                var rejected = new java.util.concurrent.CompletableFuture<Void>(); when(fallback.addActivationAsync(3)).thenReturn(rejected);
                @SuppressWarnings("unchecked") var failed = (java.util.concurrent.CompletionStage<Object>) dispatch.invoke(
                        dispatchReward, inject, player, new HashMap<>(), state, "root:rejected", null);
                rejected.completeExceptionally(new UnsupportedOperationException("rejected before admission"));
                assertThrows(java.util.concurrent.ExecutionException.class, () -> failed.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS));
                configData.set("Activations", null);
                var resumedReceipt = new java.util.concurrent.CompletableFuture<Void>(); when(fallback.addActivationAsync(3)).thenReturn(resumedReceipt);
                @SuppressWarnings("unchecked") var resumed = (java.util.concurrent.CompletionStage<Object>) dispatch.invoke(
                        dispatchReward, inject, player, new HashMap<>(), state, "root:rejected", null);
                assertFalse(resumed.toCompletableFuture().isDone()); resumedReceipt.complete(null);
                resumed.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
                configData.set("PerkActivations.vote", 3);
                var perkReceipt = new java.util.concurrent.CompletableFuture<Void>(); when(fallback.addActivationAsync("vote", 3)).thenReturn(perkReceipt);
                @SuppressWarnings("unchecked") var perkResult = (java.util.concurrent.CompletionStage<Object>) dispatch.invoke(
                        dispatchReward, perkInject, player, new HashMap<>(), state, "root:perk", null);
                assertFalse(perkResult.toCompletableFuture().isDone()); perkReceipt.complete(null);
                perkResult.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
                verify(fallback).addActivationAsync("vote", 3); verify(fallback, never()).addActivation("vote", 3);
            }
            manager.closeSharedRuntimeAsyncCompletion(() -> {}).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            occurrence.remove(); key.remove(); com.bencodez.mcperks.MCPerksMain.plugin = previous;
            manager.getTimer().shutdownNow(); if (backend.isOpen()) backend.close();
        }
    }
}
