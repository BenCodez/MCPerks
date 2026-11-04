package com.bencodez.mcperks.tests;
import com.bencodez.mcperks.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.bencodez.mcperks.data.OrderedStorage;

/** Production reload admission remains pending until the ordered snapshot settles. */
@org.junit.jupiter.api.Timeout(10)
class NativeReloadLifecycleTest {
    @Test
    void storageFailureJoinsPendingReloadAndPermitsRetry() throws Exception {
        MCPerksMain plugin = mock(MCPerksMain.class, CALLS_REAL_METHODS);
        OrderedStorage state = mock(OrderedStorage.class);
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        when(plugin.getStorage()).thenReturn(state);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reload-test"));
        set(plugin, "storage", state);
        set(plugin, "reloadPending", new java.util.concurrent.atomic.AtomicBoolean());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reload-test"));
        doReturn(barrier).when(state).runtimeStorage(any());

        CompletableFuture<Void> first = plugin.reloadAsync();
        assertFalse(first.isDone(), "reload must not report success before the storage barrier");
        assertSame(first, plugin.reloadAsync(), "concurrent reload callers must join one lifecycle operation");

        barrier.completeExceptionally(new IllegalStateException("fixture publication failure"));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> first.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("fixture publication failure", failure.getCause().getMessage());

        CompletableFuture<Void> successor = plugin.reloadAsync();
        assertNotSame(first, successor, "a completed failure must not poison a later reload request");
    }

    @Test
    void storageBarrierDoesNotCompleteReloadBeforeItsOwnerCallback() throws Exception {
        MCPerksMain plugin = mock(MCPerksMain.class, CALLS_REAL_METHODS);
        OrderedStorage state = mock(OrderedStorage.class);
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
        var folia = mock(com.bencodez.simpleapi.folialib.FoliaLib.class);
        var implementation = mock(com.bencodez.simpleapi.folialib.impl.PlatformScheduler.class);
        CompletableFuture<Void> dispatch = new CompletableFuture<>();
        when(scheduler.getFoliaLib()).thenReturn(folia);
        when(folia.getImpl()).thenReturn(implementation);
        doAnswer(invocation -> { scheduled.set(() -> invocation.<Consumer<com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask>>getArgument(0)
                .accept(mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class))); return dispatch; })
                .when(implementation).runNextTick(any());
        when(plugin.getStorage()).thenReturn(state);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reload-test"));
        set(plugin, "storage", state);
        set(plugin, "reloadPending", new java.util.concurrent.atomic.AtomicBoolean());
        doReturn(barrier).when(state).runtimeStorage(any());

        CompletableFuture<Void> operation = plugin.reloadAsync();
        barrier.complete(null);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (scheduled.get() == null && System.nanoTime() < deadline) Thread.yield();
        assertNotNull(scheduled.get(), "reload must dispatch its lifecycle work to Bukkit");
        assertFalse(operation.isDone(), "reload must remain pending until scheduled lifecycle work completes");
    }

    @Test
    void rejectedOwnerDispatchFailsReloadWithoutPoisoningRetry() throws Exception {
        MCPerksMain plugin = mock(MCPerksMain.class, CALLS_REAL_METHODS);
        OrderedStorage state = mock(OrderedStorage.class);
        CompletableFuture<Void> barrier = CompletableFuture.completedFuture(null);
        CompletableFuture<Void> dispatch = new CompletableFuture<>();
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
        var folia = mock(com.bencodez.simpleapi.folialib.FoliaLib.class);
        var implementation = mock(com.bencodez.simpleapi.folialib.impl.PlatformScheduler.class);
        when(scheduler.getFoliaLib()).thenReturn(folia);
        when(folia.getImpl()).thenReturn(implementation);
        when(implementation.runNextTick(any())).thenReturn(dispatch);
        when(plugin.getStorage()).thenReturn(state);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reload-test"));
        set(plugin, "storage", state);
        set(plugin, "reloadPending", new java.util.concurrent.atomic.AtomicBoolean());
        doReturn(barrier).when(state).runtimeStorage(any());

        CompletableFuture<Void> operation = plugin.reloadAsync();
        dispatch.completeExceptionally(new IllegalStateException("owner dispatch rejected"));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> operation.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("owner dispatch rejected", failure.getCause().getMessage());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"none", "cancel", "success", "failure"})
    void successfulOwnerCallbackCompletesReloadOnceAfterNativeWork(String mutation) throws Exception {
        var plugin = mock(MCPerksMain.class, CALLS_REAL_METHODS);
        var storage = mock(OrderedStorage.class);
        var barrier = new CompletableFuture<Void>();
        doReturn(barrier).when(storage).runtimeStorage(any());
        set(plugin, "storage", storage);
        set(plugin, "reloadPending", new java.util.concurrent.atomic.AtomicBoolean());
        when(plugin.isEnabled()).thenReturn(true);
        var config = mock(com.bencodez.mcperks.configs.Config.class);
        var data = mock(com.bencodez.mcperks.data.ServerData.class);
        var perks = mock(com.bencodez.mcperks.perk.PerkHandler.class);
        var commands = mock(com.bencodez.mcperks.commands.CommandLoader.class);
        set(plugin, "configFile", config); set(plugin, "mcperksServerData", data);
        set(plugin, "perkHandler", perks); set(plugin, "commandLoader", commands);
        doNothing().when(plugin).updateHook();
        var coreReload = new CompletableFuture<Void>();
        doReturn(coreReload).when(plugin).reloadAdvancedCoreAsync(true);
        when(data.reloadDataAsync()).thenReturn(CompletableFuture.completedFuture(null));
        var finalFiles = new CompletableFuture<Void>();
        when(data.reloadCompletion()).thenReturn(finalFiles);
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
        var folia = mock(com.bencodez.simpleapi.folialib.FoliaLib.class);
        var implementation = mock(com.bencodez.simpleapi.folialib.impl.PlatformScheduler.class);
        var dispatch = new CompletableFuture<Void>();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        when(scheduler.getFoliaLib()).thenReturn(folia); when(folia.getImpl()).thenReturn(implementation);
        when(implementation.runNextTick(any())).thenAnswer(invocation -> {
            scheduled.set(() -> invocation.<Consumer<com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask>>getArgument(0).accept(null));
            return dispatch;
        });
        var language = mock(com.bencodez.mcperks.configs.Lang.class);
        try (var languages = mockStatic(com.bencodez.mcperks.configs.Lang.class)) {
            languages.when(com.bencodez.mcperks.configs.Lang::getInstance).thenReturn(language);
            var operation = plugin.reloadAsync();
            verifyNoInteractions(config, data, commands, language);
            verify(perks).pauseNativeActivationsForReload();
            verify(perks, never()).reload();
            barrier.complete(null);
            assertFalse(operation.isDone());
            switch (mutation) {
                case "none" -> { }
                case "cancel" -> operation.cancel(false);
                case "success" -> operation.complete(null);
                case "failure" -> operation.completeExceptionally(new IllegalStateException("caller observation"));
                default -> fail("unknown mutation");
            }
            scheduled.get().run();
            scheduled.get().run();
            assertSame(operation, plugin.reloadAsync(), "native storage replacement still owns the reload admission");
            verify(perks, never()).resumeNativeActivationsAfterReload();
            dispatch.completeExceptionally(new IllegalStateException("late dispatch failure after owner admission"));
            coreReload.complete(null);
            verify(perks, never()).resumeNativeActivationsAfterReload();
            finalFiles.complete(null);
            if (mutation.equals("none")) operation.get(5, java.util.concurrent.TimeUnit.SECONDS);
            verify(perks).resumeNativeActivationsAfterReload();
            verify(config, times(1)).reloadData(); verify(data, times(1)).reloadDataAsync();
            verify(data).reloadCompletion();
            verify(perks, times(1)).reload(); verify(commands, times(1)).loadTabComplete();
            verify(language, times(1)).reloadData(); verify(plugin, times(1)).reloadAdvancedCoreAsync(true);
            dispatch.completeExceptionally(new IllegalStateException("late dispatch failure"));
            if (mutation.equals("none") || mutation.equals("success")) assertFalse(operation.isCompletedExceptionally());
            assertNotSame(operation, plugin.reloadAsync(), "actual owner completion must release reload admission");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void reloadPostponesWithoutRetiringAcceptedWorkOrStartupRestoration(boolean activationPending) throws Exception {
        var plugin = mock(MCPerksMain.class, CALLS_REAL_METHODS);
        var storage = mock(OrderedStorage.class);
        var perks = com.bencodez.mcperks.tests.TestAccess.newPerkHandler(plugin, java.util.List.of());
        when(plugin.getPerkHandler()).thenReturn(perks);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reload-test"));
        set(plugin, "storage", storage);
        set(plugin, "perkHandler", perks);
        set(plugin, "reloadPending", new java.util.concurrent.atomic.AtomicBoolean());
        var outstanding = new CompletableFuture<Void>();
        doReturn(new CompletableFuture<Void>()).when(storage).runtimeStorage(any());
        var pending = com.bencodez.mcperks.perk.PerkHandler.class.getDeclaredField("pendingNativeActivations");
        pending.setAccessible(true);
        var count = (java.util.concurrent.atomic.AtomicInteger) pending.get(perks);
        var restoration = com.bencodez.mcperks.perk.PerkHandler.class.getDeclaredField("restoration");
        restoration.setAccessible(true);
        if (activationPending) count.incrementAndGet(); else restoration.set(perks, outstanding);
        try {
            var reload = plugin.reloadAsync();
            assertTrue(reload.isCompletedExceptionally(), "reload must explicitly postpone while accepted work is pending");
            assertEquals(0, plugin.getFeatureLifecycle());
            verifyNoInteractions(storage);
            if (activationPending) assertEquals(1, count.get()); else assertSame(outstanding, restoration.get(perks));
            assertFalse(outstanding.isDone());
            // Completing the same startup owner must still establish readiness; reload installs no competing one.
            if (activationPending) count.decrementAndGet(); else outstanding.complete(null);
            assertTrue(perks.isRestorationReady());
        } finally { perks.retireTimers(); }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
