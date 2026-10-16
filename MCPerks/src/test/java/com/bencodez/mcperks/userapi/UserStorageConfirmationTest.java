package com.bencodez.mcperks.userapi;

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
import com.bencodez.mcperks.data.FeatureState;

class UserStorageConfirmationTest {
    @TempDir Path directory;
    @Test void actualAdvancedCoreCacheCompletionWaitsForWriterAndPropagatesFailure() throws Exception {
        var plugin=mock(MCPerksMain.class); var user=mock(MCPerksUser.class); UUID owner=UUID.randomUUID();
        when(user.getUUID()).thenReturn(owner.toString());
        var users=mock(com.bencodez.advancedcore.api.user.UserManager.class); when(plugin.getUserManager()).thenReturn(users);
        var manager=mock(UserDataManager.class); when(users.getDataManager()).thenReturn(manager); when(manager.getPlugin()).thenReturn(plugin);
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
        try (var state=new FeatureState(directory.resolve("features.dat"))) {
            state.ready().get(5,TimeUnit.SECONDS); when(plugin.getFeatureState()).thenReturn(state);
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
        try (var state = new FeatureState(directory.resolve("deferred.dat"))) {
            state.ready().get(5, TimeUnit.SECONDS); when(plugin.getFeatureState()).thenReturn(state);
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
}
