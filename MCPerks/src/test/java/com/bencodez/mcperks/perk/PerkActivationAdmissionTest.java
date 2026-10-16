package com.bencodez.mcperks.perk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.data.ServerData;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.bencodez.mcperks.userapi.UserStorageConfirmation;

class PerkActivationAdmissionTest {
    private record Fixture(Perk perk, MCPerksMain plugin, ServerData data,
            PerkHandler handler, MCPerksUser user, AtomicReference<Runnable> gameplay,
            AtomicReference<java.util.Map<String,Long>> state, CompletableFuture<Void> published,
            CompletableFuture<Void> callerStorage) {}

    private Fixture fixture(boolean timed) {
        MCPerksMain plugin = mock(MCPerksMain.class);
        ServerData data = mock(ServerData.class);
        PerkHandler handler = mock(PerkHandler.class);
        MCPerksUser user = mock(MCPerksUser.class);
        Perk perk = mock(Perk.class, CALLS_REAL_METHODS);
        AtomicReference<Runnable> gameplay = new AtomicReference<>();
        AtomicReference<java.util.Map<String,Long>> state = new AtomicReference<>();
        CompletableFuture<Void> published = new CompletableFuture<>();
        CompletableFuture<Void> callerStorage = new CompletableFuture<>();
        try {
            var field = Perk.class.getDeclaredField("plugin"); field.setAccessible(true); field.set(perk, plugin);
            field = Perk.class.getDeclaredField("activationAdmissions"); field.setAccessible(true); field.set(perk, new java.util.LinkedHashMap<String,Long>());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        when(plugin.getMcperksServerData()).thenReturn(data);
        when(plugin.getPerkHandler()).thenReturn(handler);
        when(plugin.isEnabled()).thenReturn(true);
        when(data.isReady()).thenReturn(true);
        doReturn(new InteractionOptions(false, false, 16, 8, java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of())).when(perk).getInteractionOptions();
        doReturn("admission").when(perk).getPerk();
        doReturn(PerkSystemType.PLAYER).when(perk).getPerkType();
        doReturn(timed).when(perk).isTimed();
        doReturn(false).when(perk).isLastForever();
        doReturn(0).when(perk).getCoolDown();
        doReturn(0).when(perk).getServerWideCoolDown();
        doReturn(30).when(perk).getTime();
        when(user.getPlayer()).thenReturn((Player) null);
        when(user.getUUID()).thenReturn(UUID.randomUUID().toString());
        doAnswer(invocation -> {
            state.set(invocation.getArgument(0));
            gameplay.set(invocation.getArgument(2));
            @SuppressWarnings("unchecked") Supplier<CompletableFuture<Void>> caller = invocation.getArgument(1);
            return published.thenCompose(ignored -> caller.get()).thenCompose(ignored -> {
                try { gameplay.get().run(); return CompletableFuture.completedFuture(null); }
                catch (Throwable error) { return CompletableFuture.failedFuture(error); }
            });
        }).when(data).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
        return new Fixture(perk, plugin, data, handler, user, gameplay, state, published, callerStorage);
    }

    @Test void actualExecuteWaitsForPublicationAndUserStorageBeforeHooks() throws Exception {
        Fixture f = fixture(true);
        CompletableFuture<Void> userStorage = new CompletableFuture<>();
        try (MockedStatic<UserStorageConfirmation> confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user())).thenReturn(userStorage);
            CompletableFuture<Void> execution = f.perk().executeConfirmed(f.user(), 30);
            assertFalse(execution.isDone());
            verify(f.handler(), never()).activePerk(any(), any(), anyInt(), any());
            f.published().complete(null);
            assertFalse(execution.isDone());
            verify(f.handler(), never()).activePerk(any(), any(), anyInt(), any());
            userStorage.complete(null);
            execution.get();
            verify(f.handler()).activePerk(eq(f.perk()), eq(f.user()), eq(30), any(Timestamp.class));
        }
    }

    @Test void actualExecuteRejectsASecondStartWhileFirstAdmissionIsPending() {
        Fixture f = fixture(false);
        try (MockedStatic<UserStorageConfirmation> confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user())).thenReturn(f.callerStorage());
            assertFalse(f.perk().executeConfirmed(f.user(), 30).isDone());
            CompletableFuture<Void> second = f.perk().executeConfirmed(f.user(), 30);
            assertTrue(second.isCompletedExceptionally());
            verify(f.data(), times(1)).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
        }
    }

    @Test void directLimitedActivationKeepsPerkAdmissionThroughDurablePlannerCompletion() throws Exception {
        Fixture f = fixture(false);
        var config = mock(com.bencodez.mcperks.configs.Config.class);
        var features = mock(com.bencodez.mcperks.data.FeatureState.class);
        when(f.plugin().getConfigFile()).thenReturn(config);
        when(f.plugin().getFeatureState()).thenReturn(features);
        when(config.getLimitActivations()).thenReturn(true);
        when(features.available()).thenReturn(true);
        when(f.handler().isRestorationReady()).thenReturn(true);
        when(f.data().getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        doReturn(false).when(f.perk()).isPerkActive(f.user());
        doReturn(true).when(f.perk()).checkTimesUsed(f.user());
        doReturn(true).when(f.perk()).checkCoolDown(f.user());
        doReturn(true).when(f.perk()).checkServerWideCoolDown(f.user());
        var planner = mock(QueuePlanner.class); when(f.plugin().getQueuePlanner()).thenReturn(planner);
        var completed = new CompletableFuture<Void>();
        when(planner.startDirect(eq(f.user()), eq(f.perk()), any(), any())).thenReturn(completed);
        f.perk().runPerk(f.user()); f.perk().runPerk(f.user());
        verify(planner, times(1)).startDirect(eq(f.user()), eq(f.perk()), any(), any());
        verify(f.user(), never()).addActivation(anyInt()); verify(f.user(), never()).addActivation(anyString(), anyInt());
        verify(f.user()).sendMessage(contains("still being confirmed"));
        completed.complete(null); f.perk().runPerk(f.user());
        verify(planner, times(2)).startDirect(eq(f.user()), eq(f.perk()), any(), any());
    }

    @Test void differentPersonalOwnersDoNotShareAnAdmissionFence() {
        Fixture f = fixture(false);
        MCPerksUser other = mock(MCPerksUser.class);
        when(other.getUUID()).thenReturn(UUID.randomUUID().toString());
        assertFalse(f.perk().executeConfirmed(f.user(), 30).isDone());
        assertFalse(f.perk().executeConfirmed(other, 30).isDone());
        verify(f.data(), times(2)).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
    }

    @Test void actualReloadGenerationChangeRetiresPendingUserConfirmation() throws Exception {
        Fixture f = fixture(false);
        doCallRealMethod().when(f.plugin()).getFeatureLifecycle();
        var config = mock(com.bencodez.mcperks.configs.Config.class);
        var field = MCPerksMain.class.getDeclaredField("configFile"); field.setAccessible(true); field.set(f.plugin(), config);
        doThrow(new IllegalStateException("stop fixture after configuration generation change"))
                .when(config).reloadData();
        try (MockedStatic<UserStorageConfirmation> confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user())).thenReturn(f.callerStorage());
            var execution = f.perk().executeConfirmed(f.user(), 30);
            f.published().complete(null);
            var reload = MCPerksMain.class.getDeclaredMethod("reloadAfterDrain"); reload.setAccessible(true);
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> reload.invoke(f.plugin()));
            assertEquals(1, f.plugin().getFeatureLifecycle());
            f.callerStorage().complete(null);
            assertThrows(ExecutionException.class, execution::get);
            verify(f.handler(), never()).activePerk(any(), any(), anyInt(), any());
        }
    }

    @Test void actualExecuteDoesNotRunHooksWhenAdmissionStorageFails() throws Exception {
        Fixture f = fixture(false);
        try (MockedStatic<UserStorageConfirmation> confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user())).thenReturn(f.callerStorage());
            CompletableFuture<Void> execution = f.perk().executeConfirmed(f.user(), 30);
            f.published().completeExceptionally(new IllegalStateException("snapshot exceeds four MB"));
            assertThrows(ExecutionException.class, execution::get);
            verify(f.handler(), never()).activePerk(any(), any(), anyInt(), any());
            verify(f.user(), never()).setPerkCoolDown(any(), anyLong());
            verify(f.user(), never()).setPerkExperation(any(), anyLong());
        }
    }

    @Test void actualExecuteDoesNotRunHooksWhenCallerStorageFails() throws Exception {
        Fixture f = fixture(false);
        try (MockedStatic<UserStorageConfirmation> confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user())).thenReturn(f.callerStorage());
            CompletableFuture<Void> execution = f.perk().executeConfirmed(f.user(), 30);
            f.published().complete(null);
            f.callerStorage().completeExceptionally(new IllegalStateException("user cache unavailable"));
            assertThrows(ExecutionException.class, execution::get);
            verify(f.handler(), never()).activePerk(any(), any(), anyInt(), any());
        }
    }

    @Test void actualExecuteSuppressesAStaleLifecycleCallback() throws Exception {
        Fixture f = fixture(false);
        PerkHandler replacement = mock(PerkHandler.class);
        AtomicReference<PerkHandler> current = new AtomicReference<>(f.handler());
        java.util.concurrent.atomic.AtomicInteger epoch = new java.util.concurrent.atomic.AtomicInteger();
        when(f.plugin().getPerkHandler()).thenAnswer(invocation -> current.get());
        when(f.plugin().getFeatureLifecycle()).thenAnswer(invocation -> epoch.get());
        try (MockedStatic<UserStorageConfirmation> confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user())).thenReturn(f.callerStorage());
            CompletableFuture<Void> execution = f.perk().executeConfirmed(f.user(), 30);
            f.published().complete(null); current.set(replacement); epoch.incrementAndGet(); f.callerStorage().complete(null);
            assertThrows(ExecutionException.class, execution::get);
            verify(f.handler(), never()).activePerk(any(), any(), anyInt(), any());
        }
    }

    @Test void actualExecutePersistsTheSameDeadlineUsedByRuntimeActivation() throws Exception {
        Fixture f = fixture(true);
        AtomicReference<Long> persisted = new AtomicReference<>();
        doAnswer(invocation -> { persisted.set(invocation.getArgument(1)); return null; })
                .when(f.user()).setPerkExperation(eq(f.perk()), anyLong());
        try (MockedStatic<UserStorageConfirmation> confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user())).thenReturn(f.callerStorage());
            CompletableFuture<Void> execution = f.perk().executeConfirmed(f.user(), 30);
            f.published().complete(null); f.callerStorage().complete(null); execution.get();
            var timestamp = ArgumentCaptor.forClass(Timestamp.class);
            verify(f.handler()).activePerk(eq(f.perk()), eq(f.user()), eq(30), timestamp.capture());
            assertEquals(persisted.get(), timestamp.getValue().getTime());
        }
    }
}
