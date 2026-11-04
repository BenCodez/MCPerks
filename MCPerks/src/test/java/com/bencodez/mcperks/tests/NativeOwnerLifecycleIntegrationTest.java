package com.bencodez.mcperks.tests;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.data.ServerData;
import com.bencodez.mcperks.scheduling.OwnerTasks;
import com.bencodez.mcperks.tests.scheduling.OwnerTaskHarness;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class NativeOwnerLifecycleIntegrationTest {
    @Test void postLoadOpensOwnerAdmissionBeforeConstructingNativeStorage() throws Exception {
        var plugin = mock(MCPerksMain.class);
        var player = mock(Player.class);
        // Constructing the harness while disabled cannot initialize an active epoch.
        var harness = new OwnerTaskHarness(plugin, player);
        var retired = new AtomicInteger();
        OwnerTasks.run(plugin, player, () -> fail("uninitialized admission"), retired::incrementAndGet);
        assertEquals(1, retired.get());
        when(plugin.isEnabled()).thenReturn(true);
        var stopBeforeStorageConstruction = new IllegalStateException("stop at native storage boundary");
        doThrow(stopBeforeStorageConstruction).when(plugin).getDataFolder();
        doCallRealMethod().when(plugin).onPostLoad();
        try {
            assertSame(stopBeforeStorageConstruction, assertThrows(IllegalStateException.class, plugin::onPostLoad));
            var ran = new AtomicInteger();
            OwnerTasks.run(plugin, player, ran::incrementAndGet, () -> fail("startup did not initialize owner epoch"));
            var callback = harness.tasks.poll(5, TimeUnit.SECONDS);
            assertNotNull(callback);
            callback.run();
            assertEquals(1, ran.get());
        } finally { OwnerTasks.shutdown(plugin); }
    }

    @Test void unloadRetiresPendingOwnerCallbacksBeforeNativeSaveAndRejectsLateCallbacks() throws Exception {
        var plugin = mock(MCPerksMain.class);
        when(plugin.isEnabled()).thenReturn(true);
        var player = mock(Player.class);
        var harness = new OwnerTaskHarness(plugin, player);
        var retired = new AtomicInteger();
        var ran = new AtomicInteger();
        OwnerTasks.run(plugin, player, ran::incrementAndGet, retired::incrementAndGet);
        var lateCallback = harness.tasks.poll(5, TimeUnit.SECONDS);
        assertNotNull(lateCallback);
        var data = mock(ServerData.class);
        when(plugin.getMcperksServerData()).thenReturn(data);
        doAnswer(ignored -> {
            assertEquals(1, retired.get(), "native save must follow retirement of existing admissions");
            assertEquals(0, ran.get());
            return null;
        }).when(data).saveData();
        doCallRealMethod().when(plugin).onUnLoad();
        MCPerksMain previous = MCPerksMain.plugin;
        MCPerksMain.plugin = plugin;
        try {
            plugin.onUnLoad();
            lateCallback.run();
            lateCallback.run();
            assertEquals(1, retired.get());
            assertEquals(0, ran.get());
            verify(data).saveData();
            OwnerTasks.run(plugin, player, ran::incrementAndGet, retired::incrementAndGet);
            assertEquals(2, retired.get());
            assertEquals(0, ran.get());
        } finally {
            OwnerTasks.shutdown(plugin);
            MCPerksMain.plugin = previous;
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"cancel", "success", "failure"})
    void shutdownPreparationObservationCannotAdvanceThePrivateOwner(String mutation) throws Exception {
        var plugin = mock(MCPerksMain.class);
        var preparation = new java.util.concurrent.CompletableFuture<Void>();
        var field = MCPerksMain.class.getDeclaredField("shutdownPreparation");
        field.setAccessible(true); field.set(plugin, preparation);
        doCallRealMethod().when(plugin).onBeforeStorageShutdown();
        var observed = plugin.onBeforeStorageShutdown().toCompletableFuture();
        switch (mutation) {
            case "cancel" -> observed.cancel(false);
            case "success" -> observed.complete(null);
            case "failure" -> observed.completeExceptionally(new IllegalStateException("caller observation"));
            default -> fail("unknown mutation");
        }
        assertFalse(preparation.isDone());
        assertFalse(plugin.onBeforeStorageShutdown().toCompletableFuture().isDone());
        preparation.complete(null);
        plugin.onBeforeStorageShutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
