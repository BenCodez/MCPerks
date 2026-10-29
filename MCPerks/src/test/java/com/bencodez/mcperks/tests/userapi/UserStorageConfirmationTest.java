package com.bencodez.mcperks.tests.userapi;
import static com.bencodez.mcperks.tests.TestAccess.*;
import com.bencodez.mcperks.userapi.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.advancedcore.api.user.usercache.*;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeString;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.data.OrderedStorage;

class UserStorageConfirmationTest {
    @TempDir Path directory;
    @Test void confirmationCannotOvertakeAQueuedSharedAward() throws Exception {
        var plugin = mock(MCPerksMain.class); var user = mock(MCPerksUser.class); UUID owner = UUID.randomUUID();
        when(user.getUUID()).thenReturn(owner.toString());
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class); when(plugin.getUserManager()).thenReturn(users);
        var manager = new UserDataManager(plugin); when(users.getDataManager()).thenReturn(manager);
        var backend = mock(com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend.class); when(backend.isOpen()).thenReturn(true);
        manager.bindSharedRuntime(new com.bencodez.advancedcore.core.user.runtime.SharedUserDataRuntime(backend,
                mock(com.bencodez.advancedcore.core.user.runtime.UserCacheOwner.class)));
        var written = new ConcurrentHashMap<String, Integer>(); var cache = new UserDataCache(manager, owner);
        cache.configureSharedStorage(values -> values.forEach((key, value) -> written.put(key, value.getInt())), Runnable::run, Runnable::run);
        cache.updateCompleteCachePreservingPending(new HashMap<>(Map.of("Points", new com.bencodez.simpleapi.sql.data.DataValueInt(1))));
        manager.getUserDataCache().put(owner, cache);
        var begin = UserDataManager.class.getDeclaredMethod("beginSharedSynchronousIntegerMutation", UUID.class); begin.setAccessible(true);
        var end = UserDataManager.class.getDeclaredMethod("endSharedSynchronousIntegerMutation", UUID.class); end.setAccessible(true);
        boolean admitted = false;
        try (var storage = new OrderedStorage(directory.resolve("shared-pending.dat"))) {
            storage.ready().get(5, TimeUnit.SECONDS); when(plugin.getStorage()).thenReturn(storage);
            assertEquals(true, begin.invoke(manager, owner)); admitted = true;
            var award = manager.submitSharedIntegerMutation(owner, Map.of("Points", 1), UserDataCache.IntegerMutation.ADD, null);
            var confirmation = UserStorageConfirmation.confirm(plugin, user);
            storage.runtimeStorage(() -> null).get(5, TimeUnit.SECONDS); manager.getTimer().submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertFalse(confirmation.isDone(), "confirmation must not pass an admitted but unscheduled shared award");
            end.invoke(manager, owner); admitted = false;
            award.toCompletableFuture().get(5, TimeUnit.SECONDS); confirmation.get(5, TimeUnit.SECONDS);
            assertEquals(2, written.get("Points"));
        } finally { if (admitted) end.invoke(manager, owner); manager.getTimer().shutdownNow(); }
    }
    @Test void confirmationWaitsForRetainedLogicalMutationBeforeFlushing() throws Exception {
        var plugin = mock(MCPerksMain.class); var user = mock(MCPerksUser.class); UUID owner = UUID.randomUUID();
        when(user.getUUID()).thenReturn(owner.toString());
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class); var manager = mock(UserDataManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(users.getDataManager()).thenReturn(manager);
        var pending = new CompletableFuture<Void>(); when(manager.pendingLegacyIntegerMutations(owner)).thenReturn(pending);
        when(manager.pendingSharedIntegerMutations(owner)).thenReturn(CompletableFuture.completedFuture(null));
        var worker = Executors.newSingleThreadScheduledExecutor(); when(manager.getTimer()).thenReturn(worker);
        var cache = mock(UserDataCache.class); when(manager.getCache(owner)).thenReturn(cache);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(cache).flushChangesAndRun(any(Runnable.class));
        try (var storage = new OrderedStorage(directory.resolve("pending.dat"))) {
            storage.ready().get(5, TimeUnit.SECONDS); when(plugin.getStorage()).thenReturn(storage);
            var confirmation = UserStorageConfirmation.confirm(plugin, user);
            storage.runtimeStorage(() -> null).get(5, TimeUnit.SECONDS);
            assertFalse(confirmation.isDone()); verify(manager, never()).getCache(owner);
            pending.complete(null); confirmation.get(5, TimeUnit.SECONDS); verify(cache).flushChangesAndRun(any(Runnable.class));
        } finally { worker.shutdownNow(); }
    }
    @Test void failedPendingMutationCannotBeConfirmedAsStored() throws Exception {
        var plugin = mock(MCPerksMain.class); var user = mock(MCPerksUser.class); UUID owner = UUID.randomUUID();
        when(user.getUUID()).thenReturn(owner.toString());
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class); var manager = mock(UserDataManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(users.getDataManager()).thenReturn(manager);
        var pending = new CompletableFuture<Void>(); when(manager.pendingLegacyIntegerMutations(owner)).thenReturn(pending);
        when(manager.pendingSharedIntegerMutations(owner)).thenReturn(CompletableFuture.completedFuture(null));
        try (var storage = new OrderedStorage(directory.resolve("failed-pending.dat"))) {
            storage.ready().get(5, TimeUnit.SECONDS); when(plugin.getStorage()).thenReturn(storage);
            var confirmation = UserStorageConfirmation.confirm(plugin, user);
            storage.runtimeStorage(() -> null).get(5, TimeUnit.SECONDS);
            assertFalse(confirmation.isDone()); pending.completeExceptionally(new IllegalStateException("mutation failed"));
            assertThrows(ExecutionException.class, () -> confirmation.get(5, TimeUnit.SECONDS));
            verify(manager, never()).getCache(owner);
        }
    }
    @Test void failedPendingSharedMutationCannotBeConfirmedAsStored() throws Exception {
        var plugin = mock(MCPerksMain.class); var user = mock(MCPerksUser.class); UUID owner = UUID.randomUUID();
        when(user.getUUID()).thenReturn(owner.toString());
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class); var manager = mock(UserDataManager.class);
        when(plugin.getUserManager()).thenReturn(users); when(users.getDataManager()).thenReturn(manager);
        when(manager.pendingLegacyIntegerMutations(owner)).thenReturn(CompletableFuture.completedFuture(null));
        var pending = new CompletableFuture<Void>(); when(manager.pendingSharedIntegerMutations(owner)).thenReturn(pending);
        try (var storage = new OrderedStorage(directory.resolve("failed-shared.dat"))) {
            storage.ready().get(5, TimeUnit.SECONDS); when(plugin.getStorage()).thenReturn(storage);
            var confirmation = UserStorageConfirmation.confirm(plugin, user);
            storage.runtimeStorage(() -> null).get(5, TimeUnit.SECONDS);
            assertFalse(confirmation.isDone()); pending.completeExceptionally(new IllegalStateException("shared mutation failed"));
            assertThrows(ExecutionException.class, () -> confirmation.get(5, TimeUnit.SECONDS)); verify(manager, never()).getCache(owner);
        }
    }
    @Test void actualAdvancedCoreCacheCompletionWaitsForWriterAndPropagatesFailure() throws Exception {
        var plugin=mock(MCPerksMain.class); var user=mock(MCPerksUser.class); UUID owner=UUID.randomUUID();
        when(user.getUUID()).thenReturn(owner.toString());
        var users=mock(com.bencodez.advancedcore.api.user.UserManager.class); when(plugin.getUserManager()).thenReturn(users);
        var manager=mock(UserDataManager.class); when(users.getDataManager()).thenReturn(manager); when(manager.getPlugin()).thenReturn(plugin);
        when(manager.pendingLegacyIntegerMutations(owner)).thenReturn(CompletableFuture.completedFuture(null));
        when(manager.pendingSharedIntegerMutations(owner)).thenReturn(CompletableFuture.completedFuture(null));
        when(users.getUser(owner,false)).thenReturn(user);
        // Suppress only the cache's three-second background timer; exercise its real exclusive flush.
        var storageWorker = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "test-canonical-user-storage"); thread.setDaemon(true); return thread;
        });
        when(manager.getTimer()).thenReturn(storageWorker);
        var cache=new UserDataCache(manager,owner); cache.updateCache(new HashMap<>()); when(manager.getCache(owner)).thenReturn(cache);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var fail=new AtomicBoolean();
        var written=new ConcurrentHashMap<String,String>();
        cache.configureSharedStorage(values -> {
            assertEquals("test-canonical-user-storage",Thread.currentThread().getName()); entered.countDown();
            try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch (InterruptedException error) { throw new RuntimeException(error); }
            if (fail.get()) throw new IllegalStateException("SQL write failed");
            values.forEach((key,value) -> written.put(key,value.getString()));
        },Runnable::run,Runnable::run);
        try (var state=new OrderedStorage(directory.resolve("features.dat"))) {
            state.ready().get(5,TimeUnit.SECONDS); when(plugin.getStorage()).thenReturn(state);
            cache.addChange(new UserDataChangeString("Experation_passive","123456"),true);
            cache.addChange(new UserDataChangeString("passive_CoolDown","234567"),true);
            var confirmed=UserStorageConfirmation.confirm(plugin,user);
            try { assertTrue(entered.await(5,TimeUnit.SECONDS)); assertFalse(confirmed.isDone()); assertTrue(written.isEmpty()); }
            finally { release.countDown(); }
            confirmed.get(5,TimeUnit.SECONDS); assertEquals(Map.of("Experation_passive","123456","passive_CoolDown","234567"),written);
            fail.set(true); cache.addChange(new UserDataChangeString("Experation_passive","999999"),true);
            assertThrows(ExecutionException.class, () -> UserStorageConfirmation.confirm(plugin,user).get(5,TimeUnit.SECONDS));
            assertEquals("123456",written.get("Experation_passive")); assertTrue(cache.hasChangesToProcess());
        } finally { storageWorker.shutdownNow(); }
    }
    @Test void confirmationWaitsBehindAnAdmittedDeferredMutation() throws Exception {
        var plugin = mock(MCPerksMain.class); var user = mock(MCPerksUser.class);
        UUID owner = UUID.randomUUID(); when(user.getUUID()).thenReturn(owner.toString());
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class);
        var manager = mock(UserDataManager.class);
        when(manager.pendingLegacyIntegerMutations(owner)).thenReturn(CompletableFuture.completedFuture(null));
        when(manager.pendingSharedIntegerMutations(owner)).thenReturn(CompletableFuture.completedFuture(null));
        when(plugin.getUserManager()).thenReturn(users); when(users.getDataManager()).thenReturn(manager);
        when(manager.getPlugin()).thenReturn(plugin); when(users.getUser(owner, false)).thenReturn(user);
        var worker = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "test-canonical-user-storage"); thread.setDaemon(true); return thread;
        });
        when(manager.getTimer()).thenReturn(worker);
        var cache = new UserDataCache(manager, owner); cache.updateCache(new HashMap<>());
        when(manager.getCache(owner)).thenReturn(cache);
        var written = new ConcurrentHashMap<String, String>();
        cache.configureSharedStorage(values -> values.forEach((key, value) -> written.put(key, value.getString())),
                Runnable::run, Runnable::run);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var state = new OrderedStorage(directory.resolve("deferred.dat"))) {
            state.ready().get(5, TimeUnit.SECONDS); when(plugin.getStorage()).thenReturn(state);
            worker.execute(() -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Deferred mutation timed out"); }
                catch (InterruptedException failure) { throw new RuntimeException(failure); }
                cache.addChange(new UserDataChangeString("Experation_passive", "123456"), true);
                cache.addChange(new UserDataChangeString("passive_CoolDown", "234567"), true);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var confirmation = UserStorageConfirmation.confirm(plugin, user);
            state.runtimeStorage(() -> null).get(5, TimeUnit.SECONDS);
            assertFalse(confirmation.isDone()); assertTrue(written.isEmpty());
            release.countDown(); confirmation.get(5, TimeUnit.SECONDS);
            assertEquals(Map.of("Experation_passive", "123456", "passive_CoolDown", "234567"), written);
            worker.shutdownNow();
            assertThrows(ExecutionException.class,
                    () -> UserStorageConfirmation.confirm(plugin, user).get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test void acceptedWriteDoesNotExpireWhileCanonicalWorkerIsStillQueued() throws Exception {
        var plugin = mock(MCPerksMain.class); var user = mock(MCPerksUser.class);
        UUID owner = UUID.randomUUID(); when(user.getUUID()).thenReturn(owner.toString());
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class);
        var manager = mock(UserDataManager.class);
        when(manager.pendingLegacyIntegerMutations(owner)).thenReturn(CompletableFuture.completedFuture(null));
        when(manager.pendingSharedIntegerMutations(owner)).thenReturn(CompletableFuture.completedFuture(null));
        when(plugin.getUserManager()).thenReturn(users); when(users.getDataManager()).thenReturn(manager);
        when(manager.getPlugin()).thenReturn(plugin); when(users.getUser(owner, false)).thenReturn(user);
        var worker = Executors.newSingleThreadScheduledExecutor(); when(manager.getTimer()).thenReturn(worker);
        var cache = new UserDataCache(manager, owner); cache.updateCache(new HashMap<>());
        when(manager.getCache(owner)).thenReturn(cache);
        var written = new ConcurrentHashMap<String, String>();
        cache.configureSharedStorage(values -> values.forEach((key, value) -> written.put(key, value.getString())),
                Runnable::run, Runnable::run);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var state = new OrderedStorage(directory.resolve("slow-user.dat"))) {
            state.ready().get(5, TimeUnit.SECONDS); when(plugin.getStorage()).thenReturn(state);
            worker.execute(() -> {
                entered.countDown();
                try { if (!release.await(45, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out"); }
                catch (InterruptedException failure) { throw new RuntimeException(failure); }
                cache.addChange(new UserDataChangeString("Experation_passive", "123456"), true);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var confirmation = UserStorageConfirmation.confirm(plugin, user);
            // Cross the old timeout while the accepted mutation is still waiting on its actual owner.
            assertThrows(TimeoutException.class, () -> confirmation.get(31, TimeUnit.SECONDS));
            assertFalse(confirmation.isDone()); assertTrue(written.isEmpty());
            release.countDown(); confirmation.get(5, TimeUnit.SECONDS);
            assertEquals("123456", written.get("Experation_passive"));
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
