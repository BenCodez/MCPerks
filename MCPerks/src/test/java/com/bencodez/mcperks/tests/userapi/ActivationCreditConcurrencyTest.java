package com.bencodez.mcperks.tests.userapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.user.UserData;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.usercache.UserDataCache;
import com.bencodez.advancedcore.api.user.usercache.UserDataManager;
import com.bencodez.advancedcore.api.user.UserManager;
import com.bencodez.advancedcore.api.user.UserStorage;
import com.bencodez.simpleapi.sql.data.DataValue;
import com.bencodez.simpleapi.sql.data.DataValueInt;
import java.util.HashMap;

class ActivationCreditConcurrencyTest {
    private static class CustomUserManager extends UserManager {
        CustomUserManager(AdvancedCorePlugin plugin) { super(plugin); }
        @Override public com.bencodez.advancedcore.api.user.AdvancedCoreUser getUser(UUID uuid, boolean loadName) {
            return super.getUser(uuid, loadName);
        }
    }
    private static class InheritedUserManager extends UserManager {
        InheritedUserManager(AdvancedCorePlugin plugin) { super(plugin); }
    }
    private static class CustomDataUser extends MCPerksUser {
        CustomDataUser(com.bencodez.mcperks.MCPerksMain plugin, UUID id) { super(plugin, id); }
        @Override public UserData getData() { return super.getData(); }
    }
    @Test void asynchronousAwardRetainsOriginalOperationAcrossDirectWrite() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        var table = mock(com.bencodez.advancedcore.api.user.userstorage.sql.UserTable.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        when(plugin.getSQLiteUserTable()).thenReturn(table);
        when(plugin.getNativeUserStorageOwner()).thenReturn(new AdvancedCorePlugin.UserStorageOwner(UserStorage.SQLITE, null, table));
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var stored = mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class); var data = mock(UserData.class);
        when(users.getUser(id, false)).thenReturn(stored); when(stored.getUserData()).thenReturn(data);
        when(data.supportsStrictCompleteValues()).thenReturn(true);
        when(data.getValuesIfStrictComplete()).thenReturn(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        var cache = new UserDataCache(manager, id);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(data).when(user).getData();
        var writer = manager.beginLegacyDirectWrite(id);
        try {
            var award = user.addActivationAsync(1).toCompletableFuture();
            assertFalse(award.cancel(false), "an accepted award must retain its observable outcome");
            assertFalse(award.complete(null), "callers must not forge successful completion");
            assertFalse(award.isDone()); assertEquals(1, cache.getIfPresent("Activations").getInt());
            manager.endLegacyDirectWrite(writer); writer = null;
            award.get(5, TimeUnit.SECONDS);
            assertEquals(2, cache.getIfPresent("Activations").getInt());
            verify(data, never()).getInt(anyString()); verify(data, never()).setInt(anyString(), anyInt());
        } finally { if (writer != null) manager.endLegacyDirectWrite(writer); manager.getTimer().shutdownNow(); }
    }
    @Test void sharedAwardUsesObservableProducerReceipt() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        var backend = mock(com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend.class);
        var owner = mock(com.bencodez.advancedcore.core.user.runtime.UserCacheOwner.class);
        when(backend.isOpen()).thenReturn(true); when(backend.storageType()).thenReturn(UserStorage.SQLITE);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        manager.bindSharedSqlBackend(backend, (uuid, operation) -> operation.run());
        manager.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend, owner));
        var cache = new UserDataCache(manager, id); cache.configureSharedStorage(values -> {}, Runnable::run);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID();
        doReturn(new UserData(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class))).when(user).getData();
        try {
            var receipt = user.addActivationAsync(1).toCompletableFuture();
            assertFalse(receipt.cancel(false)); receipt.get(5, TimeUnit.SECONDS);
            assertEquals(2, cache.getIfPresent("Activations").getInt());
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void asyncSetAndClampedRemoveUseObservedAtomicOperations() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        var backend = mock(com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend.class);
        when(backend.isOpen()).thenReturn(true); when(backend.storageType()).thenReturn(UserStorage.SQLITE);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        manager.bindSharedSqlBackend(backend, (uuid, operation) -> operation.run());
        manager.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend,
                mock(com.bencodez.advancedcore.core.user.runtime.UserCacheOwner.class)));
        var cache = new UserDataCache(manager, id); cache.configureSharedStorage(values -> {}, Runnable::run);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(3), "PerkActivations_vote", new DataValueInt(2))));
        manager.getUserDataCache().put(id, cache);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID();
        doReturn(new UserData(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class))).when(user).getData();
        try {
            user.setActivationsAsync(10).toCompletableFuture().get(5, TimeUnit.SECONDS);
            user.removeActivationClampedAsync(4).toCompletableFuture().get(5, TimeUnit.SECONDS);
            user.setActivationsAsync("vote", 3).toCompletableFuture().get(5, TimeUnit.SECONDS);
            user.removeActivationClampedAsync("vote", 9).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(6, cache.getIfPresent("Activations").getInt()); assertEquals(0, cache.getIfPresent("PerkActivations_vote").getInt());
            var overflow = assertThrows(ExecutionException.class, () -> user.removeActivationClampedAsync(Integer.MIN_VALUE).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertInstanceOf(ArithmeticException.class, overflow.getCause()); assertEquals(6, cache.getIfPresent("Activations").getInt());
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void coldSharedAsyncAwardWaitsForTheExistingBalance() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class, RETURNS_DEEP_STUBS);
        var users = mock(UserManager.class); var stored = mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class);
        var data = mock(UserData.class); var backend = mock(com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend.class);
        var owner = mock(com.bencodez.advancedcore.core.user.runtime.UserCacheOwner.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        when(plugin.getNativeUserStorageOwner()).thenReturn(null);
        when(users.getUser(id, false)).thenReturn(stored); when(stored.getUserData()).thenReturn(data);
        when(backend.isOpen()).thenReturn(true); when(backend.storageType()).thenReturn(UserStorage.SQLITE);
        when(backend.hasAuthoritativeRowReads()).thenReturn(true);
        when(data.getKeys()).thenReturn(new java.util.ArrayList<>());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(data.getValuesIfComplete()).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            return new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1)));
        });
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        manager.bindSharedCacheInitializer(cache -> cache.configureSharedStorage(values -> {}, Runnable::run));
        manager.bindSharedSqlBackend(backend, (uuid, operation) -> operation.run());
        manager.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend, owner));
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID();
        doReturn(new UserData(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class))).when(user).getData();
        try {
            var receipt = user.addActivationAsync(1).toCompletableFuture();
            if (!entered.await(5, TimeUnit.SECONDS)) { receipt.get(1, TimeUnit.SECONDS); fail("storage read did not begin"); }
            assertFalse(receipt.isDone()); assertFalse(receipt.cancel(false));
            release.countDown(); receipt.get(5, TimeUnit.SECONDS);
            assertEquals(2, manager.getPublishedCache(id).getIfPresent("Activations").getInt());
            verify(data, times(1)).getValuesIfComplete();
        } finally { release.countDown(); manager.getTimer().shutdownNow(); }
    }
    @Test void coldSharedAsyncAwardRejectsAnUnconfirmedBalance() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class, RETURNS_DEEP_STUBS);
        var users = mock(UserManager.class); var stored = mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class);
        var data = mock(UserData.class); var backend = mock(com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend.class);
        var owner = mock(com.bencodez.advancedcore.core.user.runtime.UserCacheOwner.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        when(plugin.getNativeUserStorageOwner()).thenReturn(null);
        when(users.getUser(id, false)).thenReturn(stored); when(stored.getUserData()).thenReturn(data);
        when(backend.isOpen()).thenReturn(true); when(backend.storageType()).thenReturn(UserStorage.SQLITE);
        when(backend.hasAuthoritativeRowReads()).thenReturn(true); when(data.getKeys()).thenReturn(new java.util.ArrayList<>());
        when(data.getValuesIfComplete()).thenReturn(null);
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        manager.bindSharedCacheInitializer(cache -> cache.configureSharedStorage(values -> {}, Runnable::run));
        manager.bindSharedSqlBackend(backend, (uuid, operation) -> operation.run());
        manager.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend, owner));
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID();
        doReturn(new UserData(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class))).when(user).getData();
        try {
            var receipt = user.addActivationAsync(1).toCompletableFuture();
            assertThrows(ExecutionException.class, () -> receipt.get(5, TimeUnit.SECONDS));
            var cache = manager.getPublishedCache(id);
            if (cache != null) assertNull(cache.getIfPresent("Activations"));
            verify(data, never()).setInt(anyString(), anyInt());
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void supportedLegacyAwardDoesNotFallBackWhileDirectWriteIsAdmitted() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var table = mock(com.bencodez.advancedcore.api.user.userstorage.sql.UserTable.class);
        when(plugin.getSQLiteUserTable()).thenReturn(table);
        when(plugin.getNativeUserStorageOwner()).thenReturn(new AdvancedCorePlugin.UserStorageOwner(UserStorage.SQLITE, null, table));
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var cache = new UserDataCache(manager, id);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS); var data = mock(UserData.class);
        when(data.supportsStrictCompleteValues()).thenReturn(true);
        when(data.getValuesIfStrictComplete()).thenReturn(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        var stored = mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class);
        when(users.getUser(id, false)).thenReturn(stored); when(stored.getUserData()).thenReturn(data);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(data).when(user).getData();
        var admission = manager.beginLegacyDirectWrite(id);
        try {
            user.addActivation(1);
            var pending = manager.pendingLegacyIntegerMutations(id).toCompletableFuture(); assertFalse(pending.isDone());
            assertEquals(1, cache.getIfPresent("Activations").getInt());
            manager.endLegacyDirectWrite(admission); admission = null; pending.get(5, TimeUnit.SECONDS);
            assertEquals(2, cache.getIfPresent("Activations").getInt());
            verify(data, never()).getInt(anyString()); verify(data, never()).setInt(anyString(), anyInt());
        } finally { if (admission != null) manager.endLegacyDirectWrite(admission); manager.getTimer().shutdownNow(); }
    }
    @Test void legacyVoidAdmissionReportsFailureWithoutRetryOrFallback() {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        var manager = mock(UserDataManager.class); when(plugin.getUserManager()).thenReturn(users);
        when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE); when(users.getDataManager()).thenReturn(manager);
        var data = mock(UserData.class); when(data.supportsStrictCompleteValues()).thenReturn(true);
        var receipt = new CompletableFuture<UserDataCache.IntegerMutationResult>();
        when(manager.submitLegacyIntegerMutation(eq(id), anyMap(), eq(UserDataCache.IntegerMutation.ADD), any())).thenReturn(receipt);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(data).when(user).getData();
        user.addActivation(1); assertFalse(receipt.isDone());
        var failure = new IllegalStateException("unconfirmed"); receipt.completeExceptionally(failure);
        verify(manager).recordSharedStorageFailure(failure);
        verify(manager, times(1)).submitLegacyIntegerMutation(eq(id), anyMap(), eq(UserDataCache.IntegerMutation.ADD), any());
        verify(data, never()).getInt(anyString()); verify(data, never()).setInt(anyString(), anyInt());
    }
    @Test void coldSharedSynchronousAwardAdmitsOneOperationAndReportsFailure() {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        var manager = mock(UserDataManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        when(users.getDataManager()).thenReturn(manager); when(manager.usesSharedSqlStorage(UserStorage.SQLITE)).thenReturn(true);
        when(manager.mustDeferSharedStorageAccess()).thenReturn(true);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID();
        var receipt = new CompletableFuture<UserDataCache.IntegerMutationResult>();
        when(manager.submitSharedIntegerMutation(eq(id), anyMap(), eq(UserDataCache.IntegerMutation.ADD), any())).thenReturn(receipt);
        user.addActivation(1);
        assertFalse(receipt.isDone()); verify(manager, times(1)).submitSharedIntegerMutation(eq(id), anyMap(), eq(UserDataCache.IntegerMutation.ADD), any());
        verify(manager, never()).cacheUser(id);
        var failure = new IllegalStateException("unconfirmed"); receipt.completeExceptionally(failure);
        verify(manager).recordSharedStorageFailure(failure);
    }
    @Test void opaqueAsyncAwardFailsBeforeCallingAnUncertainSetter() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS); var data = mock(UserData.class);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(data).when(user).getData();
        try {
            var receipt = user.addActivationAsync(1).toCompletableFuture();
            var error = assertThrows(ExecutionException.class, () -> receipt.get(2, TimeUnit.SECONDS));
            assertInstanceOf(UnsupportedOperationException.class, error.getCause());
            verify(data, never()).getInt(anyString()); verify(data, never()).setInt(anyString(), anyInt());
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void customReaderAwardKeepsLegacyCallbacksEvenWithPublishedCache() {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var cache = new UserDataCache(manager, id);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS); var data = mock(UserData.class);
        when(data.supportsStrictCompleteValues()).thenReturn(false); when(data.getInt("Activations")).thenReturn(1);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(data).when(user).getData();
        try {
            assertNull(user.reserveSharedActivations("vote"));
            user.addActivation(1);
            verify(data).setInt("Activations", 2);
            assertEquals(1, cache.getIfPresent("Activations").getInt());
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void strictReaderCapabilityDoesNotBypassCustomIntegerCallbacks() {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var cache = new UserDataCache(manager, id);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache);
        var balance = new AtomicInteger(1); var writes = new AtomicInteger();
        var custom = new UserData(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class)) {
            @Override public boolean supportsStrictCompleteValues() { return true; }
            @Override public int getInt(String key) { return balance.get(); }
            @Override public void setInt(String key, int value) { writes.incrementAndGet(); balance.set(value); }
        };
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(custom).when(user).getData();
        try {
            user.addActivation(1);
            assertEquals(2, balance.get()); assertEquals(1, writes.get());
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void asyncReceiptRejectsCustomIntegerCallbacksBeforeAdmission() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var cache = new UserDataCache(manager, id);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache);
        var writes = new AtomicInteger();
        var custom = new UserData(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class)) {
            @Override public boolean supportsStrictCompleteValues() { return true; }
            @Override public int getInt(String key) { return 1; }
            @Override public void setInt(String key, int value) { writes.incrementAndGet(); }
        };
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(custom).when(user).getData();
        try {
            var failure = assertThrows(ExecutionException.class, () -> user.addActivationAsync(1).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
            assertEquals(0, writes.get()); assertEquals(1, cache.getIfPresent("Activations").getInt());
            assertFalse(manager.hasUnsettledLegacyIntegerMutations());
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void sharedAsyncReceiptRejectsCustomCallbacksWithoutEnqueuing() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        var manager = mock(UserDataManager.class); when(plugin.getUserManager()).thenReturn(users);
        when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE); when(users.getDataManager()).thenReturn(manager);
        when(manager.usesSharedSqlStorage(UserStorage.SQLITE)).thenReturn(true);
        var custom = new UserData(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class)) {
            @Override public int getInt(String key) { throw new AssertionError("unexpected getter"); }
            @Override public void setInt(String key, int value) { throw new AssertionError("unexpected setter"); }
        };
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(custom).when(user).getData();
        var failure = assertThrows(ExecutionException.class, () -> user.addActivationAsync(1).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
        verify(manager, never()).submitSharedIntegerMutation(any(), anyMap(), any(), any());
    }
    @Test void asyncReceiptRejectsCustomCanonicalUserManagerBeforeAdmission() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class);
        var users = mock(CustomUserManager.class); when(plugin.getUserManager()).thenReturn(users);
        when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var backend = mock(com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend.class);
        when(backend.isOpen()).thenReturn(true); when(backend.storageType()).thenReturn(UserStorage.SQLITE);
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var stored = mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class);
        var customData = new UserData(stored) {
            @Override public int getInt(String key) { throw new AssertionError("unexpected custom getter"); }
            @Override public void setInt(String key, int value) { throw new AssertionError("unexpected custom setter"); }
        };
        when(users.getUser(id, false)).thenReturn(stored); when(stored.getUserData()).thenReturn(customData);
        manager.bindSharedSqlBackend(backend, (uuid, operation) -> operation.run());
        manager.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend,
                mock(com.bencodez.advancedcore.core.user.runtime.UserCacheOwner.class)));
        var cache = new UserDataCache(manager, id); cache.configureSharedStorage(values -> {}, Runnable::run);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID();
        doReturn(new UserData(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class))).when(user).getData();
        try {
            var failure = assertThrows(ExecutionException.class, () -> user.addActivationAsync(1).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
            var replayFailure = assertThrows(ExecutionException.class, () -> user.addReplayActivationAsync(null, 1, "event:one").toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertInstanceOf(UnsupportedOperationException.class, replayFailure.getCause());
            var checkpointFailure = assertThrows(ExecutionException.class, () -> user.checkpointReplayActivationAsync("event:one").toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertInstanceOf(UnsupportedOperationException.class, checkpointFailure.getCause());
            assertEquals(1, cache.getIfPresent("Activations").getInt());
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void asyncReceiptAllowsSubclassWithInheritedIntegerCallbacks() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var backend = mock(com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend.class);
        when(backend.isOpen()).thenReturn(true); when(backend.storageType()).thenReturn(UserStorage.SQLITE);
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        manager.bindSharedSqlBackend(backend, (uuid, operation) -> operation.run());
        manager.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend,
                mock(com.bencodez.advancedcore.core.user.runtime.UserCacheOwner.class)));
        var cache = new UserDataCache(manager, id); cache.configureSharedStorage(values -> {}, Runnable::run);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID();
        doReturn(new UserData(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class)) { }).when(user).getData();
        try {
            user.addActivationAsync(1).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(2, cache.getIfPresent("Activations").getInt());
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void strictLegacyReceiptAllowsSubclassWithInheritedIntegerCallbacks() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        var table = mock(com.bencodez.advancedcore.api.user.userstorage.sql.UserTable.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        when(plugin.getSQLiteUserTable()).thenReturn(table);
        when(plugin.getNativeUserStorageOwner()).thenReturn(new AdvancedCorePlugin.UserStorageOwner(UserStorage.SQLITE, null, table));
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var cache = new UserDataCache(manager, id);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID();
        doReturn(new UserData(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class)) {
            @Override public boolean supportsStrictCompleteValues() { return true; }
        }).when(user).getData();
        try { user.addActivationAsync(1).toCompletableFuture().get(5, TimeUnit.SECONDS); assertEquals(2, cache.getIfPresent("Activations").getInt()); }
        finally { manager.getTimer().shutdownNow(); }
    }
    @Test void asyncReceiptAllowsUserManagerWithInheritedLookup() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(InheritedUserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var backend = mock(com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend.class);
        when(backend.isOpen()).thenReturn(true); when(backend.storageType()).thenReturn(UserStorage.SQLITE);
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        manager.bindSharedSqlBackend(backend, (uuid, operation) -> operation.run());
        manager.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend,
                mock(com.bencodez.advancedcore.core.user.runtime.UserCacheOwner.class)));
        var cache = new UserDataCache(manager, id); cache.configureSharedStorage(values -> {}, Runnable::run);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache); var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID();
        doReturn(new UserData(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class))).when(user).getData();
        try { user.addActivationAsync(1).toCompletableFuture().get(5, TimeUnit.SECONDS); assertEquals(2, cache.getIfPresent("Activations").getInt()); }
        finally { manager.getTimer().shutdownNow(); }
    }
    @Test void sharedSynchronousAwardDoesNotBypassCustomIntegerCallbacks() {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        var manager = mock(UserDataManager.class); when(plugin.getUserManager()).thenReturn(users);
        when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE); when(users.getDataManager()).thenReturn(manager);
        when(manager.usesSharedSqlStorage(UserStorage.SQLITE)).thenReturn(true);
        when(manager.getPlugin()).thenReturn(plugin); when(manager.getTimer()).thenReturn(mock(ScheduledExecutorService.class));
        var cache = new UserDataCache(manager, id); cache.configureSharedStorage(values -> {}, Runnable::run);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        when(manager.getPublishedCache(id)).thenReturn(cache);
        var balance = new AtomicInteger(1); var writes = new AtomicInteger();
        var custom = new UserData(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class)) {
            @Override public int getInt(String key) { return balance.get(); }
            @Override public void setInt(String key, int value) { writes.incrementAndGet(); balance.set(value); }
        };
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(custom).when(user).getData();
        user.addActivation(1);
        assertEquals(2, balance.get()); assertEquals(1, writes.get());
    }
    @Test void legacyReservationPreservesExistingCustomReaderFallback() {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var stored = mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class); var custom = mock(UserData.class);
        when(users.getUser(id, false)).thenReturn(stored); when(stored.getUserData()).thenReturn(custom);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(custom).when(user).getData();
        try {
            assertNull(user.reserveSharedActivations("vote"));
            verify(custom, never()).getValuesIfStrictComplete();
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void ordinaryLegacyCacheCannotFallBackToReadThenWriteForAwards() throws Exception {
        UUID id = UUID.randomUUID();
        var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var table = mock(com.bencodez.advancedcore.api.user.userstorage.sql.UserTable.class);
        when(plugin.getSQLiteUserTable()).thenReturn(table);
        when(plugin.getNativeUserStorageOwner()).thenReturn(new AdvancedCorePlugin.UserStorageOwner(UserStorage.SQLITE, null, table));
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var cache = new UserDataCache(manager, id);
        cache.updateCache(new HashMap<>(java.util.Map.of("PerkActivations_vote", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS); var data = mock(UserData.class);
        when(data.supportsStrictCompleteValues()).thenReturn(true);
        when(data.getValuesIfStrictComplete()).thenReturn(new HashMap<>(java.util.Map.of("PerkActivations_vote", new DataValueInt(1))));
        var stored = mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class);
        when(users.getUser(id, false)).thenReturn(stored); when(stored.getUserData()).thenReturn(data);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(data).when(user).getData();
        try {
            user.addActivation("vote", 1);
            manager.pendingLegacyIntegerMutations(id).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(2, cache.getIfPresent("PerkActivations_vote").getInt());
            verify(data, never()).getInt(anyString());
            verify(data, never()).setInt(anyString(), anyInt());
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void incompleteLegacyRowDoesNotInventCredit() {
        UUID id = UUID.randomUUID();
        var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        var stored = mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class); var data = mock(UserData.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        when(users.getUser(id, false)).thenReturn(stored); when(stored.getUserData()).thenReturn(data);
        when(data.supportsStrictCompleteValues()).thenReturn(true);
        when(data.getValuesIfStrictComplete()).thenReturn(null);
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(data).when(user).getData();
        try {
            assertThrows(IllegalStateException.class, () -> user.reserveSharedActivations("vote"));
            assertNull(manager.getPublishedCache(id));
            verify(data, never()).setInt(anyString(), anyInt());
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void coldLegacyReservationLoadsBothPoolsBeforeDebit() {
        UUID id = UUID.randomUUID();
        var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        var stored = mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class); var data = mock(UserData.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        when(users.getUser(id, false)).thenReturn(stored); when(stored.getUserData()).thenReturn(data);
        when(data.supportsStrictCompleteValues()).thenReturn(true);
        when(data.getValuesIfStrictComplete()).thenReturn(new HashMap<>(java.util.Map.of(
                "Activations", new DataValueInt(2), "PerkActivations_vote", new DataValueInt(3))));
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(data).when(user).getData();
        try {
            var result = user.reserveSharedActivations("vote");
            assertTrue(result.applied()); assertEquals(2, result.previous().get("Activations"));
            assertEquals(3, result.previous().get("PerkActivations_vote"));
            var cache = manager.getPublishedCache(id);
            assertEquals(1, cache.getIfPresent("Activations").getInt());
            assertEquals(2, cache.getIfPresent("PerkActivations_vote").getInt());
        } finally { manager.getTimer().shutdownNow(); }
    }
    @Test void publishedLegacyCacheSerializesAwardsAcrossWrappers() throws Exception {
        UUID id = UUID.randomUUID();
        var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var cache = new UserDataCache(manager, id);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("PerkActivations_vote", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache);
        var control = manager.tryMutateLegacyIntegers(id, java.util.Map.of("PerkActivations_vote", 1),
                UserDataCache.IntegerMutation.ADD, null);
        assertNotNull(control, "fixture must admit the supported legacy mutation");
        assertTrue(control.applied());
        assertEquals(2, cache.getIfPresent("PerkActivations_vote").getInt());
        assertTrue(manager.tryMutateLegacyIntegers(id, java.util.Map.of("PerkActivations_vote", 1),
                UserDataCache.IntegerMutation.SET, null).applied());
        var first = mock(MCPerksUser.class, CALLS_REAL_METHODS); var second = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(first).getPlugin(); doReturn(plugin).when(second).getPlugin();
        doReturn(id.toString()).when(first).getUUID(); doReturn(id.toString()).when(second).getUUID();
        var data = mock(UserData.class);
        when(data.supportsStrictCompleteValues()).thenReturn(true);
        doReturn(data).when(first).getData(); doReturn(data).when(second).getData();
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { start.await(); first.addActivation("vote", 1); return null; });
            var b = pool.submit(() -> { start.await(); second.addActivation("vote", 1); return null; });
            start.countDown(); a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
            assertEquals(3, cache.getIfPresent("PerkActivations_vote").getInt());
            verify(data, never()).getInt(anyString());
            verify(data, never()).setInt(anyString(), anyInt());
        } finally { pool.shutdownNow(); manager.getTimer().shutdownNow(); }
    }
    @Test void overriddenDataLookupKeepsSynchronousCallbacksAndRejectsReceipt() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        var manager = mock(UserDataManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        when(users.getDataManager()).thenReturn(manager); when(manager.usesSharedSqlStorage(UserStorage.SQLITE)).thenReturn(true);
        var data = mock(UserData.class); when(data.getInt("Activations")).thenReturn(1);
        var user = mock(CustomDataUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(data).when(user).getData();
        assertNull(user.reserveSharedActivations("vote"));
        user.addActivation(1); verify(data).setInt("Activations", 2);
        var failure = assertThrows(ExecutionException.class, () -> user.addActivationAsync(1).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
        verify(data, times(1)).setInt("Activations", 2);
        verify(manager, never()).getPublishedCache(any());
        verify(manager, never()).submitSharedIntegerMutation(any(), anyMap(), any(), any());
    }
    @Test void supportedSetterAfterAwardHasSerialResultAcrossWrappers() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = spy(new UserDataManager(plugin)); when(users.getDataManager()).thenReturn(manager);
        var cache = new UserDataCache(manager, id);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache);
        var data = mock(UserData.class); when(data.supportsStrictCompleteValues()).thenReturn(true);
        var award = mock(MCPerksUser.class, CALLS_REAL_METHODS); var setter = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(award).getPlugin(); doReturn(plugin).when(setter).getPlugin();
        doReturn(id.toString()).when(award).getUUID(); doReturn(id.toString()).when(setter).getUUID();
        doReturn(data).when(award).getData(); doReturn(data).when(setter).getData();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            if (call.getArgument(2) == UserDataCache.IntegerMutation.SET) {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return call.callRealMethod();
        }).when(manager).tryMutateLegacyIntegers(eq(id), anyMap(), any(), any());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> setter.setActivations(10));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            award.addActivation(1); assertEquals(2, cache.getIfPresent("Activations").getInt());
            release.countDown(); pending.get(5, TimeUnit.SECONDS);
            assertEquals(10, cache.getIfPresent("Activations").getInt());
            verify(data, never()).getInt(anyString()); verify(data, never()).setInt(anyString(), anyInt());
        } finally { release.countDown(); pool.shutdownNow(); manager.getTimer().shutdownNow(); }
    }
    @Test void opaqueSynchronousCallbacksRunOnceOnTheCallerThread() {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        var manager = mock(UserDataManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        when(users.getDataManager()).thenReturn(manager); when(manager.usesSharedSqlStorage(UserStorage.SQLITE)).thenReturn(true);
        var caller = Thread.currentThread(); var reads = new AtomicInteger(); var writes = new AtomicInteger(); var balance = new AtomicInteger(1);
        var custom = new UserData(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class)) {
            @Override public int getInt(String key) { assertSame(caller, Thread.currentThread()); reads.incrementAndGet(); return balance.get(); }
            @Override public void setInt(String key, int value) { assertSame(caller, Thread.currentThread()); writes.incrementAndGet(); balance.set(value); }
        };
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID(); doReturn(custom).when(user).getData();
        assertNull(user.reserveSharedActivations("vote"));
        user.addActivation(1);
        assertEquals(2, balance.get()); assertEquals(1, reads.get()); assertEquals(1, writes.get());
        verify(manager, never()).getPublishedCache(any());
        verify(manager, never()).submitSharedIntegerMutation(any(), anyMap(), any(), any());
    }
    @Test void supportedWrappersOrderAnAwardAfterACompletedSetter() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = spy(new UserDataManager(plugin)); when(users.getDataManager()).thenReturn(manager);
        var cache = new UserDataCache(manager, id);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache);
        var data = mock(UserData.class); when(data.supportsStrictCompleteValues()).thenReturn(true);
        var award = mock(MCPerksUser.class, CALLS_REAL_METHODS); var setter = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(award).getPlugin(); doReturn(plugin).when(setter).getPlugin();
        doReturn(id.toString()).when(award).getUUID(); doReturn(id.toString()).when(setter).getUUID();
        doReturn(data).when(award).getData(); doReturn(data).when(setter).getData();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            if (call.getArgument(2) == UserDataCache.IntegerMutation.ADD) {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return call.callRealMethod();
        }).when(manager).tryMutateLegacyIntegers(eq(id), anyMap(), any(), any());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> award.addActivation(1));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            setter.setActivations(10);
            assertEquals(10, cache.getIfPresent("Activations").getInt());
            release.countDown(); pending.get(5, TimeUnit.SECONDS);
            assertEquals(11, cache.getIfPresent("Activations").getInt());
            verify(data, never()).getInt(anyString()); verify(data, never()).setInt(anyString(), anyInt());
        } finally { release.countDown(); pool.shutdownNow(); manager.getTimer().shutdownNow(); }
    }
    @Test void supportedWrappersRetainBothAwardAndConcurrentDebit() throws Exception {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        var manager = spy(new UserDataManager(plugin)); when(users.getDataManager()).thenReturn(manager);
        var cache = new UserDataCache(manager, id);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("PerkActivations_vote", new DataValueInt(1))));
        manager.getUserDataCache().put(id, cache);
        var data = mock(UserData.class); when(data.supportsStrictCompleteValues()).thenReturn(true);
        var debit = mock(MCPerksUser.class, CALLS_REAL_METHODS); var award = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(debit).getPlugin(); doReturn(plugin).when(award).getPlugin();
        doReturn(id.toString()).when(debit).getUUID(); doReturn(id.toString()).when(award).getUUID();
        doReturn(data).when(debit).getData(); doReturn(data).when(award).getData();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            java.util.Map<String, Integer> values = call.getArgument(1);
            if (values.get("PerkActivations_vote") == -1) {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return call.callRealMethod();
        }).when(manager).tryMutateLegacyIntegers(eq(id), anyMap(), any(), any());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> debit.addActivation("vote", -1));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            award.addActivation("vote", 1);
            assertEquals(2, cache.getIfPresent("PerkActivations_vote").getInt());
            release.countDown(); pending.get(5, TimeUnit.SECONDS);
            assertEquals(1, cache.getIfPresent("PerkActivations_vote").getInt());
            verify(data, never()).getInt(anyString()); verify(data, never()).setInt(anyString(), anyInt());
        } finally { release.countDown(); pool.shutdownNow(); manager.getTimer().shutdownNow(); }
    }
    @Test void legacyConcurrentPopulationCannotReplaceAnAcceptedCacheGeneration() throws Exception {
        UUID id = UUID.randomUUID();
        var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        var user = mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class); var data = mock(UserData.class);
        when(plugin.getUserManager()).thenReturn(users); when(users.getUser(id, false)).thenReturn(user);
        when(user.getUserData()).thenReturn(data); when(data.getKeys()).thenReturn(new java.util.ArrayList<>());
        var loading = new CountDownLatch(1); var finish = new CountDownLatch(1);
        when(data.getValues()).thenAnswer(call -> {
            loading.countDown(); if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            return new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1)));
        });
        var manager = new UserDataManager(plugin); var pool = Executors.newSingleThreadExecutor();
        try {
            var population = pool.submit(() -> manager.cacheUser(id, null));
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            var published = manager.getUserDataCache().get(id);
            if (published == null) {
                published = new UserDataCache(manager, id);
                assertNull(manager.getUserDataCache().putIfAbsent(id, published));
            }
            published.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(1))));
            assertTrue(published.tryMutateLegacyIntegers(java.util.Map.of("Activations", 1),
                    UserDataCache.IntegerMutation.ADD, null).applied());
            finish.countDown(); population.get(5, TimeUnit.SECONDS);
            assertSame(published, manager.getUserDataCache().get(id), "an accepted generation must not be replaced by an older load");
            assertEquals(2, manager.getUserDataCache().get(id).getIfPresent("Activations").getInt());
        } finally { finish.countDown(); pool.shutdownNow(); manager.getTimer().shutdownNow(); }
    }
    @Test void minimumRemovalCannotWrapIntoAnAward() {
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS); var data = mock(UserData.class);
        doReturn(data).when(user).getData();
        assertThrows(ArithmeticException.class, () -> user.removeActivationClamped(Integer.MIN_VALUE));
        assertThrows(ArithmeticException.class, () -> user.removeActivationClamped("vote", Integer.MIN_VALUE));
        verifyNoInteractions(data);
    }
    @Test void coldSharedAwardIsAppliedOnceAfterWorkerPopulation() {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class);
        var users = mock(UserManager.class); var manager = mock(UserDataManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(users.getDataManager()).thenReturn(manager);
        when(plugin.getStorageType()).thenReturn(UserStorage.MYSQL);
        when(manager.usesSharedSqlStorage(UserStorage.MYSQL)).thenReturn(true);
        when(manager.getPlugin()).thenReturn(plugin); when(manager.getTimer()).thenReturn(mock(ScheduledExecutorService.class));
        var cache = new UserDataCache(manager, id); cache.configureSharedStorage(values -> {}, Runnable::run);
        cache.updateCache(new HashMap<>(java.util.Map.of("PerkActivations_vote", new DataValueInt(1))));
        when(manager.getPublishedCache(id)).thenReturn(null, cache);
        var queued = new AtomicReference<Runnable>(); when(manager.mustDeferSharedStorageAccess()).thenReturn(true);
        when(manager.submitSharedIntegerMutation(eq(id), anyMap(), eq(UserDataCache.IntegerMutation.ADD), any())).thenAnswer(call -> {
            var receipt = new CompletableFuture<UserDataCache.IntegerMutationResult>();
            queued.set(() -> { manager.cacheUser(id); var ready = manager.getPublishedCache(id);
                receipt.complete(ready.tryMutateIntegers(java.util.Map.of("PerkActivations_vote", 1), UserDataCache.IntegerMutation.ADD, null)); });
            return receipt;
        });
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID();
        user.addActivation("vote", 1); assertNotNull(queued.get());
        assertEquals(1, cache.getIfPresent("PerkActivations_vote").getInt());
        queued.get().run(); assertEquals(2, cache.getIfPresent("PerkActivations_vote").getInt());
        verify(manager).cacheUser(id);
    }
    @Test void awardForMissingDynamicKeyUsesCompleteStorageSnapshot() {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class);
        var users = mock(UserManager.class); var manager = mock(UserDataManager.class);
        var storedUser = mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class);
        var storedData = mock(UserData.class);
        when(plugin.getUserManager()).thenReturn(users); when(users.getDataManager()).thenReturn(manager);
        when(plugin.getStorageType()).thenReturn(UserStorage.MYSQL);
        when(manager.usesSharedSqlStorage(UserStorage.MYSQL)).thenReturn(true);
        when(manager.hasSharedSqlBackend()).thenReturn(true);
        when(manager.getPlugin()).thenReturn(plugin); when(manager.getTimer()).thenReturn(mock(ScheduledExecutorService.class));
        when(users.getUser(id, false)).thenReturn(storedUser); when(storedUser.getUserData()).thenReturn(storedData);
        when(storedData.getKeys()).thenReturn(new java.util.ArrayList<>());
        when(storedData.getValues()).thenReturn(new HashMap<>());
        var cache = new UserDataCache(manager, id); cache.configureSharedStorage(values -> {}, Runnable::run);
        cache.updateCompleteCachePreservingPending(storedData.getValues()); when(manager.getPublishedCache(id)).thenReturn(cache);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID();
        user.addActivation("new-perk", 1);
        assertEquals(1, cache.getIfPresent("PerkActivations_new-perk").getInt());
    }
    @Test void missingPersonalPoolDoesNotBlockGlobalReservationAndCompensation() {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class);
        var users = mock(UserManager.class); var manager = mock(UserDataManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(users.getDataManager()).thenReturn(manager);
        when(plugin.getStorageType()).thenReturn(UserStorage.MYSQL);
        when(manager.usesSharedSqlStorage(UserStorage.MYSQL)).thenReturn(true);
        when(manager.getPlugin()).thenReturn(plugin); when(manager.getTimer()).thenReturn(mock(ScheduledExecutorService.class));
        var cache = new UserDataCache(manager, id); cache.configureSharedStorage(values -> {}, Runnable::run);
        cache.updateCompleteCachePreservingPending(new HashMap<>(java.util.Map.of("Activations", new DataValueInt(2))));
        when(manager.getPublishedCache(id)).thenReturn(cache);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID();
        var reserved = user.reserveSharedActivations("new-perk");
        assertTrue(reserved.applied()); assertEquals(2, reserved.previous().get("Activations"));
        assertEquals(0, reserved.previous().get("PerkActivations_new-perk"));
        assertEquals(1, cache.getIfPresent("Activations").getInt());
        user.addActivation(1);
        assertEquals(2, cache.getIfPresent("Activations").getInt());
        assertEquals(0, cache.getIfPresent("PerkActivations_new-perk").getInt());
    }
    @Test void partialPublishedCacheCannotInventMissingDynamicBalance() {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class);
        var users = mock(UserManager.class); var manager = mock(UserDataManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(users.getDataManager()).thenReturn(manager);
        when(plugin.getStorageType()).thenReturn(UserStorage.MYSQL);
        when(manager.usesSharedSqlStorage(UserStorage.MYSQL)).thenReturn(true);
        when(manager.getPlugin()).thenReturn(plugin); when(manager.getTimer()).thenReturn(mock(ScheduledExecutorService.class));
        var cache = new UserDataCache(manager, id); cache.configureSharedStorage(values -> {}, Runnable::run);
        cache.updateCache(new HashMap<>(java.util.Map.of("other", new DataValueInt(12))));
        when(manager.getPublishedCache(id)).thenReturn(cache);
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID();
        assertThrows(IllegalStateException.class, () -> user.addActivation("missing", 1));
        assertNull(cache.getIfPresent("PerkActivations_missing"));
        assertEquals(12, cache.getIfPresent("other").getInt());
    }
    @Test void coldSharedAwardDefersPopulationAndDoesNotInventABalance() {
        UUID id = UUID.randomUUID(); var plugin = mock(AdvancedCorePlugin.class);
        var users = mock(UserManager.class); var manager = mock(UserDataManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(users.getDataManager()).thenReturn(manager);
        when(plugin.getStorageType()).thenReturn(UserStorage.MYSQL);
        when(manager.usesSharedSqlStorage(UserStorage.MYSQL)).thenReturn(true);
        var queued = new AtomicReference<Runnable>(); when(manager.mustDeferSharedStorageAccess()).thenReturn(true);
        when(manager.submitSharedIntegerMutation(eq(id), anyMap(), eq(UserDataCache.IntegerMutation.ADD), any())).thenAnswer(call -> {
            var receipt = new CompletableFuture<UserDataCache.IntegerMutationResult>();
            queued.set(() -> { manager.cacheUser(id); var failure = new IllegalStateException("unconfirmed balance");
                receipt.completeExceptionally(failure); throw failure; }); return receipt;
        });
        var user = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(user).getPlugin(); doReturn(id.toString()).when(user).getUUID();
        user.addActivation("missing", 1);
        assertNotNull(queued.get()); verify(manager, never()).cacheUser(id);
        assertThrows(IllegalStateException.class, () -> queued.get().run());
        verify(manager).cacheUser(id);
    }
    @Test void publishedSharedCacheSerializesPublicCreditAwardsAcrossWrappers() throws Exception {
        UUID id = UUID.randomUUID();
        var plugin = mock(AdvancedCorePlugin.class); var users = mock(UserManager.class);
        var manager = mock(UserDataManager.class); var timer = mock(ScheduledExecutorService.class);
        when(plugin.getUserManager()).thenReturn(users); when(users.getDataManager()).thenReturn(manager);
        when(plugin.getStorageType()).thenReturn(UserStorage.MYSQL);
        when(manager.usesSharedSqlStorage(UserStorage.MYSQL)).thenReturn(true);
        when(manager.getPlugin()).thenReturn(plugin); when(manager.getTimer()).thenReturn(timer);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(manager).dispatchSharedUserDataNotification(any());
        var cache = new UserDataCache(manager, id); cache.configureSharedStorage(values -> {}, Runnable::run);
        var initial = new HashMap<String, DataValue>(); initial.put("PerkActivations_vote", new DataValueInt(1));
        initial.put("Activations", new DataValueInt(1));
        cache.updateCache(initial); when(manager.getPublishedCache(id)).thenReturn(cache);
        var first = mock(MCPerksUser.class, CALLS_REAL_METHODS); var second = mock(MCPerksUser.class, CALLS_REAL_METHODS);
        doReturn(plugin).when(first).getPlugin(); doReturn(plugin).when(second).getPlugin();
        doReturn(id.toString()).when(first).getUUID(); doReturn(id.toString()).when(second).getUUID();
        var data = mock(UserData.class); var bothRead = new CountDownLatch(2);
        when(data.getInt("PerkActivations_vote")).thenAnswer(call -> {
            int value = cache.getIfPresent("PerkActivations_vote").getInt();
            bothRead.countDown(); if (!bothRead.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            return value;
        });
        doAnswer(call -> { cache.tryAddChangeBeforeDeferredSharedFlush(
                new com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeInt(call.getArgument(0), call.getArgument(1))); return null;
        }).when(data).setInt(eq("PerkActivations_vote"), anyInt());
        doReturn(data).when(first).getData(); doReturn(data).when(second).getData();
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); first.addActivation("vote", 1); return null; });
            var b = pool.submit(() -> { start.await(); second.addActivation("vote", 1); return null; });
            start.countDown(); a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
            assertEquals(3, cache.getIfPresent("PerkActivations_vote").getInt());
            var reserved = first.reserveSharedActivations("vote");
            assertTrue(reserved.applied());
            assertEquals(3, reserved.previous().get("PerkActivations_vote"));
            assertEquals(1, reserved.previous().get("Activations"));
            assertEquals(2, cache.getIfPresent("PerkActivations_vote").getInt());
            assertEquals(0, cache.getIfPresent("Activations").getInt());
            first.removeActivationClamped("vote", 9);
            assertEquals(0, cache.getIfPresent("PerkActivations_vote").getInt());
        } finally { pool.shutdownNow(); }
    }

}
