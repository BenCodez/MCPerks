package com.bencodez.mcperks.tests.perk;
import com.bencodez.mcperks.perk.*;

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
    @Test void nativeDebitAndRefundMutationsUseTheSameOrderedStorageOwner() {
        var f = fixture(false); var balance = paidFixture(f);
        var inside = new ThreadLocal<Boolean>(); var owners = new java.util.ArrayList<Boolean>();
        var storage = f.plugin().getStorage();
        doAnswer(call -> {
            Boolean previous = inside.get(); inside.set(true);
            try { return CompletableFuture.completedFuture(call.<Supplier<?>>getArgument(0).get()); }
            catch (Throwable error) { return CompletableFuture.failedFuture(error); }
            finally { if (previous == null) inside.remove(); else inside.set(previous); }
        }).when(storage).runtimeStorage(any());
        doAnswer(call -> { owners.add(Boolean.TRUE.equals(inside.get())); balance.addAndGet(call.getArgument(1)); return null; })
                .when(f.user()).addActivation(eq("admission"), anyInt());
        try (var confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user()))
                    .thenReturn(f.callerStorage(), CompletableFuture.completedFuture(null));
            f.perk().runPerk(f.user()); f.published().complete(null);
            f.callerStorage().completeExceptionally(new IllegalStateException("synthetic caller failure"));
            assertEquals(2, balance.get()); assertEquals(2, owners.size());
            assertTrue(owners.stream().allMatch(Boolean.TRUE::equals), "debit and compensation cannot race on different owners");
        }
    }

    @Test void activeCapacityIsNotCountedAgainWhileItsFinalPublicationIsPending() throws Exception {
        var f = fixture(true); paidFixture(f);
        var actual = spy(com.bencodez.mcperks.tests.TestAccess.newPerkHandler(f.plugin(), java.util.List.of()));
        when(f.plugin().getPerkHandler()).thenReturn(actual); actual.setPerk("admission", f.perk());
        when(f.user().getPerkLimit()).thenReturn(2);
        when(f.user().getActivePerks()).thenAnswer(call -> actual.getActivePerks().size());
        when(f.plugin().getConfigFile().getLimitPermission()).thenReturn(true);
        doAnswer(call -> { actual.getActivePerks().add(f.perk()); return null; })
                .when(actual).activePerk(eq(f.perk()), eq(f.user()), eq(30), any(Timestamp.class));
        var publication = new CompletableFuture<Void>();
        doAnswer(call -> {
            Supplier<CompletableFuture<Void>> caller = call.getArgument(1); Runnable gameplay = call.getArgument(2);
            return f.published().thenCompose(ignored -> caller.get()).thenCompose(ignored -> {
                gameplay.run(); return publication;
            });
        }).when(f.data()).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
        var other = fixture(true).perk();
        var field = Perk.class.getDeclaredField("plugin"); field.setAccessible(true); field.set(other, f.plugin());
        doReturn("other").when(other).getPerk();
        var method = Perk.class.getDeclaredMethod("executeConfirmed", MCPerksUser.class, int.class, boolean.class);
        method.setAccessible(true);
        try (var confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user())).thenReturn(f.callerStorage());
            f.perk().runPerk(f.user()); f.published().complete(null); f.callerStorage().complete(null);
            assertEquals(1, actual.getActivePerks().size()); assertFalse(publication.isDone());
            // Hold the next admission at sampling, before it can create a second clone.
            var next = new CompletableFuture<Void>();
            doReturn(next).when(f.data()).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
            @SuppressWarnings("unchecked") var second = (CompletableFuture<Void>) method.invoke(other, f.user(), 30, true);
            assertFalse(second.isDone(), "one active grant must leave the second permitted slot available");
            verify(f.data(), times(2)).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
            var drain = actual.retireNativeActivations().toCompletableFuture(); assertFalse(drain.isDone());
            publication.complete(null); assertFalse(drain.isDone(), "the second operation still owns settlement");
            next.completeExceptionally(new IllegalStateException("synthetic next failure"));
            drain.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { actual.retireTimers(); }
    }
    @Test void directStartsWaitForTheActualRestorationOwner() throws Exception {
        var f = fixture(false);
        var actual = com.bencodez.mcperks.tests.TestAccess.newPerkHandler(f.plugin(), java.util.List.of());
        var restored = new CompletableFuture<Void>();
        var field = PerkHandler.class.getDeclaredField("restoration"); field.setAccessible(true); field.set(actual, restored);
        when(f.plugin().getPerkHandler()).thenReturn(actual);
        try {
            assertTrue(f.perk().executeConfirmed(f.user(), 30).isCompletedExceptionally());
            verify(f.data(), never()).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
            restored.complete(null);
            assertFalse(f.perk().executeConfirmed(f.user(), 30).isDone());
            verify(f.data()).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
            f.published().completeExceptionally(new IllegalStateException("synthetic cleanup"));
        } finally { actual.retireTimers(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"cancel", "success", "failure"})
    void shutdownPreparationWaitsForAnInstantaneousActivationRefund(String observation) throws Exception {
        var f = fixture(false); var balance = paidFixture(f);
        var actual = com.bencodez.mcperks.tests.TestAccess.newPerkHandler(f.plugin(), java.util.List.of());
        when(f.plugin().getPerkHandler()).thenReturn(actual); actual.setPerk("admission", f.perk());
        doCallRealMethod().when(f.plugin()).getFeatureLifecycle();
        doCallRealMethod().when(f.plugin()).onBeforeStorageShutdown();
        var refund = new AtomicReference<Supplier<?>>();
        var refundDispatch = new CompletableFuture<Object>();
        var storage = f.plugin().getStorage();
        try (var confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user()))
                    .thenReturn(f.callerStorage(), CompletableFuture.completedFuture(null));
            f.perk().runPerk(f.user()); f.published().complete(null); assertEquals(1, balance.get());
            // Block compensation after the debit has actually run on this same storage owner.
            doAnswer(call -> { refund.set(call.getArgument(0)); return refundDispatch; })
                    .when(storage).runtimeStorage(any());
            var prepare = MCPerksMain.class.getDeclaredMethod("prepareShutdown"); prepare.setAccessible(true); prepare.invoke(f.plugin());
            assertFalse(f.plugin().onBeforeStorageShutdown().toCompletableFuture().isDone(),
                    "user storage must remain alive while the accepted debit is unsettled");
            var observed = actual.retireNativeActivations().toCompletableFuture();
            switch (observation) {
                case "cancel" -> observed.cancel(false);
                case "success" -> observed.complete(null);
                case "failure" -> observed.completeExceptionally(new IllegalStateException("caller observation"));
                default -> fail("unknown observation");
            }
            assertFalse(f.plugin().onBeforeStorageShutdown().toCompletableFuture().isDone());
            f.callerStorage().complete(null);
            assertNotNull(refund.get());
            assertFalse(f.plugin().onBeforeStorageShutdown().toCompletableFuture().isDone());
            assertEquals(1, balance.get(), "delayed file owner has not dispatched the refund yet");
            refundDispatch.complete(refund.get().get());
            f.plugin().onBeforeStorageShutdown().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(2, balance.get());
            assertTrue(f.perk().executeConfirmed(f.user(), 30).isCompletedExceptionally(), "shutdown closes new admissions");
            assertTrue(actual.getActivePerks().isEmpty());
        } finally { actual.retireTimers(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, -1})
    void playerCapacityIncludesOtherPendingPerksAndReleasesFailedStarts(int limit) throws Exception {
        var f = fixture(false); paidFixture(f);
        var other = fixture(false).perk();
        var field = Perk.class.getDeclaredField("plugin"); field.setAccessible(true); field.set(other, f.plugin());
        doReturn("other").when(other).getPerk();
        doReturn(false).when(other).isPerkActive(f.user());
        doReturn(true).when(other).checkTimesUsed(f.user());
        doReturn(true).when(other).checkCoolDown(f.user());
        doReturn(true).when(other).checkServerWideCoolDown(f.user());
        when(f.user().getActivations("other")).thenReturn(1);
        when(f.user().getPerkLimit()).thenReturn(limit);
        when(f.plugin().getConfigFile().getLimitPermission()).thenReturn(true);
        var actual = com.bencodez.mcperks.tests.TestAccess.newPerkHandler(f.plugin(), java.util.List.of());
        when(f.plugin().getPerkHandler()).thenReturn(actual);
        actual.setPerk("admission", f.perk()); actual.setPerk("other", other);
        try {
            f.perk().runPerk(f.user()); other.runPerk(f.user());
            verify(f.data(), times(limit == 1 ? 1 : 2)).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
            f.published().completeExceptionally(new IllegalStateException("synthetic rejected publication"));
            other.runPerk(f.user());
            verify(f.data(), times(limit == 1 ? 2 : 3)).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
        } finally { actual.retireTimers(); }
    }

    @Test void playerCapacityTransfersFromPendingReservationToTheActiveGrant() throws Exception {
        var f = fixture(true); paidFixture(f);
        var actual = spy(com.bencodez.mcperks.tests.TestAccess.newPerkHandler(f.plugin(), java.util.List.of()));
        when(f.plugin().getPerkHandler()).thenReturn(actual); actual.setPerk("admission", f.perk());
        when(f.user().getPerkLimit()).thenReturn(1);
        when(f.user().getActivePerks()).thenAnswer(call -> actual.getActivePerks().size());
        when(f.plugin().getConfigFile().getLimitPermission()).thenReturn(true);
        doAnswer(call -> { actual.getActivePerks().add(f.perk()); return null; })
                .when(actual).activePerk(eq(f.perk()), eq(f.user()), eq(30), any(Timestamp.class));
        try (var confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user())).thenReturn(f.callerStorage());
            var method = Perk.class.getDeclaredMethod("executeConfirmed", MCPerksUser.class, int.class, boolean.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked") var first = (CompletableFuture<Void>) method.invoke(f.perk(), f.user(), 30, true);
            f.published().complete(null); f.callerStorage().complete(null); first.get();
            @SuppressWarnings("unchecked") var limited = (CompletableFuture<Void>) method.invoke(f.perk(), f.user(), 30, true);
            assertTrue(limited.isCompletedExceptionally());
            verify(f.data()).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
            // An explicitly forced API call retains its established permission-limit bypass.
            f.perk().executeConfirmed(f.user(), 30).get();
            verify(f.data(), times(2)).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
        } finally { actual.retireTimers(); }
    }

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
        when(handler.isRestorationReady()).thenReturn(true);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("activation-test"));
        var storage = mock(com.bencodez.mcperks.data.OrderedStorage.class);
        when(plugin.getStorage()).thenReturn(storage);
        doAnswer(call -> {
            try { return CompletableFuture.completedFuture(call.<Supplier<?>>getArgument(0).get()); }
            catch (Throwable failure) { return CompletableFuture.failedFuture(failure); }
        }).when(storage).runtimeStorage(any());
        when(data.isReady()).thenReturn(true);
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

    private java.util.concurrent.atomic.AtomicInteger paidFixture(Fixture f) {
        var balance = new java.util.concurrent.atomic.AtomicInteger(2);
        when(f.handler().isRestorationReady()).thenReturn(true);
        when(f.handler().getPerk("admission")).thenReturn(f.perk());
        when(f.data().getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        var config = mock(com.bencodez.mcperks.configs.Config.class);
        when(f.plugin().getConfigFile()).thenReturn(config); when(config.getLimitActivations()).thenReturn(true);
        doReturn(false).when(f.perk()).isPerkActive(f.user());
        doReturn(true).when(f.perk()).checkTimesUsed(f.user());
        doReturn(true).when(f.perk()).checkCoolDown(f.user());
        doReturn(true).when(f.perk()).checkServerWideCoolDown(f.user());
        when(f.user().getActivations("admission")).thenAnswer(call -> balance.get());
        doAnswer(call -> { balance.addAndGet(call.getArgument(1)); return null; })
                .when(f.user()).addActivation(eq("admission"), anyInt());
        return balance;
    }

    @Test void playerLogoutBeforeOwnerGameplayRollsBackPendingPaidStart() throws Exception {
        var f = fixture(false); var balance = paidFixture(f); var player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(f.user().getPlayer()).thenReturn(player);
        when(f.plugin().getConfigFile().getDeactivateOnLogout()).thenReturn(true);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") Supplier<CompletableFuture<Void>> caller = invocation.getArgument(1);
            Runnable gameplay = invocation.getArgument(2);
            return f.published().thenCompose(ignored -> caller.get()).thenCompose(ignored -> {
                try { gameplay.run(); return CompletableFuture.completedFuture(null); }
                catch (Throwable error) { return CompletableFuture.failedFuture(error); }
            });
        }).when(f.data()).confirmedActivationBeforeGameplay(any(), any(), any(), eq(player));
        try (var confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user()))
                    .thenReturn(f.callerStorage(), CompletableFuture.completedFuture(null));
            f.perk().runPerk(f.user()); f.published().complete(null);
            assertEquals(1, balance.get());
            when(player.isOnline()).thenReturn(false);
            f.callerStorage().complete(null);
            assertEquals(2, balance.get(), "a retired player session must refund the pending debit");
            verify(f.handler(), never()).activePerk(any(), any(), anyInt(), any());
        }
    }

    @Test void disabledLogoutPolicyDoesNotCancelPendingStartForAnOfflinePlayer() {
        var f = fixture(false); var balance = paidFixture(f); var player = mock(Player.class);
        when(player.isOnline()).thenReturn(false); when(f.user().getPlayer()).thenReturn(player);
        when(f.plugin().getConfigFile().getDeactivateOnLogout()).thenReturn(false);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") Supplier<CompletableFuture<Void>> caller = invocation.getArgument(1);
            Runnable gameplay = invocation.getArgument(2);
            return f.published().thenCompose(ignored -> caller.get()).thenCompose(ignored -> {
                try { gameplay.run(); return CompletableFuture.completedFuture(null); }
                catch (Throwable error) { return CompletableFuture.failedFuture(error); }
            });
        }).when(f.data()).confirmedActivationBeforeGameplay(any(), any(), any(), eq(player));
        try (var confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user())).thenReturn(f.callerStorage());
            f.perk().runPerk(f.user()); f.published().complete(null); f.callerStorage().complete(null);
            assertEquals(1, balance.get());
            verify(f.handler()).activePerk(f.perk(), f.user(), 30, null);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void quitFenceRetiresOnlyTheCapturedPlayerSession(boolean matchingSession) throws Exception {
        var f = fixture(false); var balance = paidFixture(f); var player = mock(Player.class);
        when(player.isOnline()).thenReturn(true); when(f.user().getPlayer()).thenReturn(player);
        when(f.plugin().getConfigFile().getDeactivateOnLogout()).thenReturn(true);
        var handler = spy(com.bencodez.mcperks.tests.TestAccess.newPerkHandler(f.plugin(), java.util.List.of()));
        handler.setPerk("admission", f.perk()); when(f.plugin().getPerkHandler()).thenReturn(handler);
        doNothing().when(handler).activePerk(any(), any(), anyInt(), any());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") Supplier<CompletableFuture<Void>> caller = invocation.getArgument(1);
            Runnable gameplay = invocation.getArgument(2);
            return f.published().thenCompose(ignored -> caller.get()).thenCompose(ignored -> {
                try { gameplay.run(); return CompletableFuture.completedFuture(null); }
                catch (Throwable error) { return CompletableFuture.failedFuture(error); }
            });
        }).when(f.data()).confirmedActivationBeforeGameplay(any(), any(), any(), eq(player));
        try (var confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user()))
                    .thenReturn(f.callerStorage(), CompletableFuture.completedFuture(null));
            f.perk().runPerk(f.user()); f.published().complete(null);
            handler.retirePendingPlayerSession(matchingSession ? player : mock(Player.class));
            f.callerStorage().complete(null);
            assertEquals(matchingSession ? 2 : 1, balance.get());
            verify(handler, times(matchingSession ? 0 : 1)).activePerk(any(), any(), anyInt(), any());
        } finally { handler.retireTimers(); }
    }

    @Test void paidStartsDoNotConsumeCreditsBeforeAdmissionAndPublication() throws Exception {
        var f = fixture(false); var balance = paidFixture(f);
        try (var confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user())).thenReturn(f.callerStorage());
            f.perk().runPerk(f.user()); f.perk().runPerk(f.user());
            assertEquals(2, balance.get(), "rejected duplicate and unconfirmed publication must consume no credit");
            f.published().complete(null); assertEquals(1, balance.get());
            f.callerStorage().complete(null);
            verify(f.handler()).activePerk(f.perk(), f.user(), 30, null);
            assertEquals(1, balance.get());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void failedPaidPublicationOrCallerWriteRestoresCreditsBeforeAnotherAdmission(boolean sharedFailure) {
        var f = fixture(false); var balance = paidFixture(f);
        try (var confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user()))
                    .thenReturn(f.callerStorage(), CompletableFuture.completedFuture(null));
            f.perk().runPerk(f.user());
            if (sharedFailure) f.published().completeExceptionally(new IllegalStateException("file unavailable"));
            else {
                f.published().complete(null); assertEquals(1, balance.get());
                f.callerStorage().completeExceptionally(new IllegalStateException("user write failed"));
            }
            assertEquals(2, balance.get());
            verify(f.handler(), never()).activePerk(any(), any(), anyInt(), any());
            f.perk().executeConfirmed(f.user(), 30);
            verify(f.data(), times(2)).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
        }
    }
    @Test void failedCallerWriteCompensatesBothRealSharedCachePools() {
        var f = fixture(false); paidFixture(f);
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class);
        var manager = mock(com.bencodez.advancedcore.api.user.usercache.UserDataManager.class);
        when(f.plugin().getUserManager()).thenReturn(users); when(users.getDataManager()).thenReturn(manager);
        when(f.plugin().getStorageType()).thenReturn(com.bencodez.advancedcore.api.user.UserStorage.MYSQL);
        when(manager.usesSharedSqlStorage(com.bencodez.advancedcore.api.user.UserStorage.MYSQL)).thenReturn(true);
        when(manager.getPlugin()).thenReturn(f.plugin());
        when(manager.getTimer()).thenReturn(mock(java.util.concurrent.ScheduledExecutorService.class));
        UUID id = UUID.fromString(f.user().getUUID());
        var cache = new com.bencodez.advancedcore.api.user.usercache.UserDataCache(manager, id);
        cache.configureSharedStorage(values -> {}, Runnable::run);
        cache.updateCompleteCachePreservingPending(new java.util.HashMap<>(java.util.Map.of(
                "Activations", new com.bencodez.simpleapi.sql.data.DataValueInt(1),
                "PerkActivations_admission", new com.bencodez.simpleapi.sql.data.DataValueInt(2))));
        when(manager.getPublishedCache(id)).thenReturn(cache);
        when(f.user().getPlugin()).thenReturn(f.plugin());
        // This fixture exercises canonical cache admission, not an opaque UserData override.
        when(f.user().getData()).thenReturn(mock(com.bencodez.advancedcore.api.user.UserData.class));
        doCallRealMethod().when(f.user()).reserveSharedActivations("admission");
        doCallRealMethod().when(f.user()).addActivation(eq("admission"), anyInt());
        doCallRealMethod().when(f.user()).addActivation(anyInt());
        try (var confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user()))
                    .thenReturn(f.callerStorage(), CompletableFuture.completedFuture(null));
            f.perk().runPerk(f.user()); f.published().complete(null);
            assertEquals(0, cache.getIfPresent("Activations").getInt());
            assertEquals(1, cache.getIfPresent("PerkActivations_admission").getInt());
            f.callerStorage().completeExceptionally(new IllegalStateException("synthetic caller failure"));
            assertEquals(1, cache.getIfPresent("Activations").getInt());
            assertEquals(2, cache.getIfPresent("PerkActivations_admission").getInt());
            verify(f.handler(), never()).activePerk(any(), any(), anyInt(), any());
        }
    }

    @Test void countdownRegistrationFailureBeforeEffectsRefundsThePaidStart() throws Exception {
        var f = fixture(true); var balance = paidFixture(f);
        var expiration = new java.util.concurrent.atomic.AtomicLong(12345L);
        when(f.user().getPerkExperation(f.perk())).thenAnswer(call -> expiration.get());
        doAnswer(call -> { expiration.set(call.getArgument(1)); return null; })
                .when(f.user()).setPerkExperation(eq(f.perk()), anyLong());
        var actual = com.bencodez.mcperks.tests.TestAccess.newPerkHandler(f.plugin(), java.util.List.of());
        when(f.plugin().getPerkHandler()).thenReturn(actual); actual.setPerk("admission", f.perk());
        var effects = mock(com.bencodez.mcperks.perk.EffectHandler.class); when(f.plugin().getEffectHandler()).thenReturn(effects);
        var clone = mock(Perk.class); doReturn(clone).when(f.perk()).clone();
        when(clone.getEffectedPlayers()).thenReturn(new java.util.ArrayList<>());
        when(clone.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        var timer = mock(java.util.Timer.class);
        doThrow(new IllegalStateException("synthetic countdown registration failure"))
                .when(timer).schedule(any(java.util.TimerTask.class), anyLong());
        var timerField = PerkHandler.class.getDeclaredField("timer"); timerField.setAccessible(true);
        var originalTimer = (java.util.Timer) timerField.get(actual); originalTimer.cancel(); timerField.set(actual, timer);
        var lang = mock(com.bencodez.mcperks.configs.Lang.class); when(lang.getCountDownTimer()).thenReturn("");
        var perkConfig = mock(com.bencodez.mcperks.configs.ConfigPerks.class);
        when(perkConfig.getCountDownTimes("admission")).thenReturn(new java.util.ArrayList<>(java.util.List.of("5")));
        try (var langs = mockStatic(com.bencodez.mcperks.configs.Lang.class);
             var configs = mockStatic(com.bencodez.mcperks.configs.ConfigPerks.class);
             var confirmation = mockStatic(UserStorageConfirmation.class)) {
            langs.when(com.bencodez.mcperks.configs.Lang::getInstance).thenReturn(lang);
            configs.when(com.bencodez.mcperks.configs.ConfigPerks::getInstance).thenReturn(perkConfig);
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user()))
                    .thenReturn(f.callerStorage(), CompletableFuture.completedFuture(null));
            f.perk().runPerk(f.user()); f.published().complete(null);
            assertEquals(1, balance.get());
            f.callerStorage().complete(null);
            verify(timer).schedule(any(java.util.TimerTask.class), anyLong());
            verify(effects, never()).activate(any(), any(), anyBoolean());
            assertEquals(2, balance.get(), "a registration failure before effects must refund the debit");
            assertEquals(12345L, expiration.get(), "the unstarted grant must restore its previous expiration");
        } finally { actual.retireTimers(); }
    }

    @Test void effectFailureAfterGameplayBeginsNeverRefundsAnUncertainSideEffect() throws Exception {
        var f = fixture(true); var balance = paidFixture(f);
        var actual = com.bencodez.mcperks.tests.TestAccess.newPerkHandler(f.plugin(), java.util.List.of());
        when(f.plugin().getPerkHandler()).thenReturn(actual); actual.setPerk("admission", f.perk());
        var effects = mock(com.bencodez.mcperks.perk.EffectHandler.class); when(f.plugin().getEffectHandler()).thenReturn(effects);
        doThrow(new IllegalStateException("synthetic effect failure")).when(effects).activate(any(), any(), anyBoolean());
        var clone = mock(Perk.class); doReturn(clone).when(f.perk()).clone();
        when(clone.getEffectedPlayers()).thenReturn(new java.util.ArrayList<>());
        when(clone.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        var lang = mock(com.bencodez.mcperks.configs.Lang.class); when(lang.getCountDownTimer()).thenReturn("");
        var perkConfig = mock(com.bencodez.mcperks.configs.ConfigPerks.class);
        when(perkConfig.getCountDownTimes("admission")).thenReturn(new java.util.ArrayList<>());
        try (var langs = mockStatic(com.bencodez.mcperks.configs.Lang.class);
             var configs = mockStatic(com.bencodez.mcperks.configs.ConfigPerks.class);
             var confirmation = mockStatic(UserStorageConfirmation.class)) {
            langs.when(com.bencodez.mcperks.configs.Lang::getInstance).thenReturn(lang);
            configs.when(com.bencodez.mcperks.configs.ConfigPerks::getInstance).thenReturn(perkConfig);
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user()))
                    .thenReturn(f.callerStorage(), CompletableFuture.completedFuture(null));
            f.perk().runPerk(f.user()); f.published().complete(null);
            assertEquals(1, balance.get()); f.callerStorage().complete(null);
            verify(effects).activate(any(), any(), eq(true));
            assertEquals(1, balance.get(), "an effect failure may have produced side effects and must not refund");
        } finally { actual.retireTimers(); }
    }

    private static final class CustomActivationHandler extends PerkHandler {
        CustomActivationHandler() { super(mock(MCPerksMain.class)); }
        @Override public void activePerk(Perk perk, MCPerksUser user, int length, Timestamp timestamp) { }
    }

    @Test void customActivationOverrideRetainsConservativeCompensationBoundary() throws Exception {
        var handler = mock(CustomActivationHandler.class, CALLS_REAL_METHODS);
        var method = PerkHandler.class.getDeclaredMethod("usesManagedGameplayBoundary"); method.setAccessible(true);
        assertEquals(false, method.invoke(handler));
    }

    @Test void unconfirmedPaidRefundRetainsThePrivateAdmissionEvenAfterAnErrorIsReported() {
        var f = fixture(false); var balance = paidFixture(f);
        try (var confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user())).thenReturn(f.callerStorage());
            f.perk().runPerk(f.user()); f.published().complete(null);
            f.callerStorage().completeExceptionally(new IllegalStateException("backend unavailable"));
            assertEquals(2, balance.get(), "the cache must contain the refund even while its durability is uncertain");
            assertTrue(f.perk().executeConfirmed(f.user(), 30).isCompletedExceptionally());
            verify(f.data(), times(1)).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
            verify(f.handler(), never()).activePerk(any(), any(), anyInt(), any());
        }
    }
    @Test void reloadAdmissionRejectsPaidStartWithoutTakingCredits() throws Exception {
        var f = fixture(false); var balance = paidFixture(f);
        var actual = com.bencodez.mcperks.tests.TestAccess.newPerkHandler(f.plugin(), java.util.List.of());
        when(f.plugin().getPerkHandler()).thenReturn(actual); actual.setPerk("admission", f.perk());
        try {
            actual.pauseNativeActivationsForReload(); f.perk().runPerk(f.user());
            assertEquals(2, balance.get());
            verify(f.data(), never()).confirmedActivationBeforeGameplay(any(), any(), any(), isNull());
        } finally { actual.retireTimers(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void paidRollbackRestoresOnlyItsOwnSharedMetadata(boolean newerExpiration) {
        var f = fixture(true); var balance = paidFixture(f);
        doReturn(PerkSystemType.ALL).when(f.perk()).getPerkType();
        doReturn(30).when(f.perk()).getServerWideCoolDown();
        var yaml = f.data().getData(); yaml.set("Experation.admission", 10L); yaml.set("admission.CoolDown", 20L);
        when(f.data().confirmedFinalMutation(any())).thenAnswer(call -> {
            call.<java.util.function.Consumer<org.bukkit.configuration.file.FileConfiguration>>getArgument(0).accept(yaml);
            return CompletableFuture.completedFuture(null);
        });
        try (var confirmation = mockStatic(UserStorageConfirmation.class)) {
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user()))
                    .thenReturn(f.callerStorage(), CompletableFuture.completedFuture(null));
            f.perk().runPerk(f.user());
            f.state().get().forEach(yaml::set);
            long expiration = f.state().get().get("Experation.admission");
            f.published().complete(null);
            if (newerExpiration) yaml.set("Experation.admission", expiration + 123L);
            f.callerStorage().completeExceptionally(new IllegalStateException("user write failed"));
            assertEquals(2, balance.get());
            assertEquals(newerExpiration ? expiration + 123L : 10L, yaml.getLong("Experation.admission"));
            assertEquals(20L, yaml.getLong("admission.CoolDown"));
            verify(f.data()).confirmedFinalMutation(any());
            verify(f.handler(), never()).activePerk(any(), any(), anyInt(), any());
        }
    }
    @Test void queuedPaidPerkConsumesNothingUntilItsSingleAdmittedStart() {
        var f = fixture(false); var balance = paidFixture(f);
        when(f.plugin().getConfigFile().getPerkQue()).thenReturn(true);
        var active = new java.util.concurrent.CopyOnWriteArrayList<Perk>(); active.add(mock(Perk.class));
        when(f.handler().getActivePerks()).thenReturn(active);
        var previous = MCPerksMain.plugin; MCPerksMain.plugin = f.plugin();
        var perkConfig = mock(com.bencodez.mcperks.configs.ConfigPerks.class);
        try (var configs = mockStatic(com.bencodez.mcperks.configs.ConfigPerks.class);
             var confirmation = mockStatic(UserStorageConfirmation.class)) {
            configs.when(com.bencodez.mcperks.configs.ConfigPerks::getInstance).thenReturn(perkConfig);
            confirmation.when(() -> UserStorageConfirmation.confirm(f.plugin(), f.user())).thenReturn(f.callerStorage());
            f.perk().runPerk(f.user()); assertEquals(2, balance.get());
            verify(f.handler()).addQue(f.user(), f.perk());
            active.clear(); f.perk().runPerk(f.user()); assertEquals(2, balance.get());
            f.published().complete(null); f.callerStorage().complete(null);
            assertEquals(1, balance.get());
            verify(f.handler()).activePerk(f.perk(), f.user(), 30, null);
        } finally { MCPerksMain.plugin = previous; }
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
    @Test void pendingNativePublicationPreventsLegacyQueueFromStartingAnotherPerk() throws Exception {
        Fixture f = fixture(false);
        var actual = com.bencodez.mcperks.tests.TestAccess.newPerkHandler(f.plugin(), java.util.List.of());
        var config = mock(com.bencodez.mcperks.configs.Config.class);
        when(config.getPerkQue()).thenReturn(true);
        when(f.plugin().getConfigFile()).thenReturn(config);
        when(f.plugin().getPerkHandler()).thenReturn(actual);
        when(f.plugin().getBukkitScheduler()).thenReturn(mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class));
        var nextUser = mock(MCPerksUser.class);
        var nextPerk = mock(Perk.class);
        try {
            var first = f.perk().executeConfirmed(f.user(), 30);
            assertFalse(first.isDone());
            actual.addQue(nextUser, nextPerk);
            actual.checkQue();
            actual.checkQue();
            verify(nextPerk, never()).runPerk(nextUser);
            assertEquals(nextPerk, actual.que.get(nextUser));
            f.published().completeExceptionally(new IllegalStateException("first publication failed"));
            assertThrows(ExecutionException.class, () -> first.get(5, java.util.concurrent.TimeUnit.SECONDS));
            actual.checkQue();
            verify(nextPerk, times(1)).runPerk(nextUser);
        } finally { actual.retireTimers(); }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"cancel", "success", "failure"})
    void callerObservationCannotRetireOrStrandActualNativeAdmission(String mutation) throws Exception {
        Fixture f = fixture(false);
        var actual = com.bencodez.mcperks.tests.TestAccess.newPerkHandler(f.plugin(), java.util.List.of());
        var config = mock(com.bencodez.mcperks.configs.Config.class);
        when(config.getPerkQue()).thenReturn(true);
        when(f.plugin().getConfigFile()).thenReturn(config);
        when(f.plugin().getPerkHandler()).thenReturn(actual);
        when(f.plugin().getBukkitScheduler()).thenReturn(mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class));
        var nextUser = mock(MCPerksUser.class);
        var nextPerk = mock(Perk.class);
        try {
            var first = f.perk().executeConfirmed(f.user(), 30);
            assertFalse(first.isDone());
            switch (mutation) {
                case "cancel" -> first.cancel(false);
                case "success" -> first.complete(null);
                case "failure" -> first.completeExceptionally(new IllegalStateException("caller observation"));
                default -> fail("unknown mutation");
            }
            actual.addQue(nextUser, nextPerk);
            actual.checkQue();
            actual.checkQue();
            verify(nextPerk, never()).runPerk(nextUser);
            assertEquals(nextPerk, actual.que.get(nextUser));
            f.published().completeExceptionally(new IllegalStateException("first publication failed"));
            actual.checkQue();
            verify(nextPerk, times(1)).runPerk(nextUser);
        } finally { actual.retireTimers(); }
    }
}
