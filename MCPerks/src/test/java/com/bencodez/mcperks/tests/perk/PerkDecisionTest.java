package com.bencodez.mcperks.tests.perk;
import static com.bencodez.mcperks.tests.TestAccess.*;
import com.bencodez.mcperks.perk.*;
import com.bencodez.mcperks.perk.Effect;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.bencodez.mcperks.configs.Config;
import com.bencodez.advancedcore.api.rewards.RewardBuilder;
class PerkDecisionTest {

    @Test void persistedRestoreUsesSavedExpirationAndSuppressesActivationHooks() { verifyRestore(false); }
    @Test void persistedPermanentRestoreRemainsPermanentWithoutReplayingHooks() { verifyRestore(true); }
    @Test void capturedRestoreDeadlineDoesNotRereadNoCacheUserOnTheOwner() {
        try (var timers = mockConstruction(java.util.Timer.class)) { verifyRestore(false, true); }
    }
    @Test void publishedCloneReleasesCapacityBeforeReentrantActivationHooks() {
        try (var timers = mockConstruction(java.util.Timer.class)) { verifyRestore(false, false, true); }
    }
    @Test void timedGrantIsPublishedBeforeExpirationCanRun() { verifyRestore(false, false, false, true); }
    @Test void timerScheduleFailureDoesNotLeaveAnUnexpiringActiveClone() { verifyRestore(false, false, false, true, true); }
    @Test void expirationAfterRetirementDoesNotRunDeactivation() throws Exception {
        var scheduled = new java.util.concurrent.atomic.AtomicReference<java.util.TimerTask>();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var perk = mock(Perk.class, CALLS_REAL_METHODS); var user = mock(MCPerksUser.class);
        doReturn(System.currentTimeMillis() + 60000).when(perk).getExperation(user);
        perk.setActive(true);
        doReturn(false).when(perk).isLastForever();
        try (var timers = mockConstruction(java.util.Timer.class, (timer, context) ->
                doAnswer(call -> { scheduled.set(call.getArgument(0)); return null; })
                        .when(timer).schedule(any(java.util.TimerTask.class), any(java.util.Date.class)))) {
            perk.scheduleDeactivation(user); assertNotNull(scheduled.get());
            Thread expiration = new Thread(() -> { entered.countDown(); try { scheduled.get().run(); } catch (Throwable error) { failure.set(error); } finally { done.countDown(); } });
            synchronized (perk) {
                expiration.start(); assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(done.await(250, java.util.concurrent.TimeUnit.MILLISECONDS), "expiration must wait for retirement under the same monitor");
                perk.setActive(false);
            }
            assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(failure.get(), "retired expiration must not perform deactivation side effects");
        }
    }
    @Test void periodicTimerFailureDoesNotLeaveAnActiveClone() {
        try (var timers = mockConstruction(java.util.Timer.class, (timer, context) ->
                doThrow(new IllegalStateException("periodic scheduler failure")).when(timer)
                        .scheduleAtFixedRate(any(java.util.TimerTask.class), anyLong(), anyLong()))) {
            verifyRestore(false, false, false, false, false, true);
        }
    }
    @Test void laterPeriodicTimerFailureDoesNotLeaveAnActiveClone() {
        var firstTask = new java.util.concurrent.atomic.AtomicReference<java.util.TimerTask>();
        var registrations = new java.util.concurrent.atomic.AtomicInteger();
        try (var timers = mockConstruction(java.util.Timer.class, (timer, context) ->
                doAnswer(call -> {
                    if (registrations.getAndIncrement() == 0) { firstTask.set(call.getArgument(0)); return null; }
                    throw new IllegalStateException("later periodic scheduler failure");
                }).when(timer).scheduleAtFixedRate(any(java.util.TimerTask.class), anyLong(), anyLong()))) {
            verifyRestore(false, false, false, false, false, true, true);
            assertNotNull(firstTask.get()); assertDoesNotThrow(() -> firstTask.get().run());
        }
    }
    private void verifyRestore(boolean permanent) { verifyRestore(permanent, false); }
    private void verifyRestore(boolean permanent, boolean ownerNoCache) { verifyRestore(permanent, ownerNoCache, false); }
    private void verifyRestore(boolean permanent, boolean ownerNoCache, boolean reentrant) { verifyRestore(permanent, ownerNoCache, reentrant, false); }
    private void verifyRestore(boolean permanent, boolean ownerNoCache, boolean reentrant, boolean checkTimerOrder) { verifyRestore(permanent, ownerNoCache, reentrant, checkTimerOrder, false); }
    private void verifyRestore(boolean permanent, boolean ownerNoCache, boolean reentrant, boolean checkTimerOrder, boolean timerFailure) { verifyRestore(permanent, ownerNoCache, reentrant, checkTimerOrder, timerFailure, false); }
    private void verifyRestore(boolean permanent, boolean ownerNoCache, boolean reentrant, boolean checkTimerOrder, boolean timerFailure, boolean periodicFailure) { verifyRestore(permanent, ownerNoCache, reentrant, checkTimerOrder, timerFailure, periodicFailure, false); }
    private void verifyRestore(boolean permanent, boolean ownerNoCache, boolean reentrant, boolean checkTimerOrder, boolean timerFailure, boolean periodicFailure, boolean laterPeriodicFailure) {
        var handlerRef = new java.util.concurrent.atomic.AtomicReference<PerkHandler>();
        UUID owner = UUID.randomUUID(); MCPerksMain plugin = mock(MCPerksMain.class);
        MCPerksUser user = mock(MCPerksUser.class); when(user.getUUID()).thenReturn(owner.toString());
        EffectHandler effects = mock(EffectHandler.class); when(plugin.getEffectHandler()).thenReturn(effects);
        Config config = mock(Config.class); when(plugin.getConfigFile()).thenReturn(config);
        when(config.getBossBarEnabled()).thenReturn(false); when(config.getLogActivation()).thenReturn(false);
        var serverData = mock(com.bencodez.mcperks.data.ServerData.class); when(plugin.getMcperksServerData()).thenReturn(serverData);
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class); when(plugin.getUserManager()).thenReturn(users);
        when(users.getUser(owner)).thenReturn(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class));
        var rewardHandler = mock(com.bencodez.advancedcore.api.rewards.RewardHandler.class); when(plugin.getRewardHandler()).thenReturn(rewardHandler);
        when(rewardHandler.hasRewards(any(), anyString())).thenReturn(false);
        if (ownerNoCache) {
            when(user.getPlugin()).thenReturn(plugin);
            when(plugin.getStorageType()).thenReturn(com.bencodez.advancedcore.api.user.UserStorage.SQLITE);
            when(user.getUserDataFetchMode()).thenReturn(com.bencodez.advancedcore.api.user.UserDataFetchMode.NO_CACHE);
            var manager = mock(com.bencodez.advancedcore.api.user.usercache.UserDataManager.class);
            when(users.getDataManager()).thenReturn(manager);
            when(manager.effectiveStorageType(any())).thenAnswer(call -> call.getArgument(0));
            when(manager.usesSharedSqlStorage(any())).thenReturn(true);
            when(manager.mustDeferSharedStorageAccess()).thenReturn(true);
            var data = new com.bencodez.advancedcore.api.user.UserData(user);
            when(user.getData()).thenReturn(data);
            doCallRealMethod().when(user).getPerkExperation(any());
            // Exercise the selected SDK's real rejection, rather than assuming NO_CACHE is safe.
            assertThrows(IllegalStateException.class, () -> data.getString(
                    com.bencodez.advancedcore.api.user.UserStorage.SQLITE, "Experation_restore",
                    com.bencodez.advancedcore.api.user.UserDataFetchMode.NO_CACHE));
        }
        Perk definition = mock(Perk.class), clone = mock(Perk.class, call -> {
            if (reentrant && call.getMethod().getName().equals("setKnownExpiration")) return call.callRealMethod();
            if (reentrant && call.getMethod().getName().equals("getKnownExpiration")) return call.callRealMethod();
            if (checkTimerOrder && call.getMethod().getName().equals("scheduleDeactivation")) {
                assertTrue(handlerRef.get().getActivePerks().contains(call.getMock()),
                        "timed clone must be visible before expiration can run");
                if (timerFailure) throw new IllegalStateException("synthetic scheduler failure");
                return null;
            }
            if (ownerNoCache && Set.of("scheduleDeactivation", "getExperation").contains(call.getMethod().getName()))
                return call.callRealMethod();
            return RETURNS_DEFAULTS.answer(call);
        });
        if (ownerNoCache) try {
            var field = Perk.class.getDeclaredField("plugin"); field.setAccessible(true); field.set(clone, plugin);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        when(definition.clone()).thenReturn(clone); when(definition.getPerk()).thenReturn("restore");
        when(definition.getPerkType()).thenReturn(PerkSystemType.PLAYER); when(definition.getTime()).thenReturn(30);
        when(definition.isTimed()).thenReturn(true); when(definition.isLastForever()).thenReturn(false);
        when(definition.getEffects()).thenReturn(laterPeriodicFailure ? new ArrayList<>(List.of(Effect.ToolRepair)) : new ArrayList<>());
        var audience = new ArrayList<String>();
        when(clone.getEffectedPlayers()).thenReturn(audience); doAnswer(i -> { audience.add(i.getArgument(0)); return null; }).when(clone).addEffectedPlayer(anyString());
        when(clone.getPerk()).thenReturn("restore");
        var cloneTime = new java.util.concurrent.atomic.AtomicInteger(30);
        when(clone.getPerkType()).thenReturn(PerkSystemType.PLAYER); when(clone.getTime()).thenAnswer(call -> cloneTime.get());
        doAnswer(call -> { cloneTime.set(call.getArgument(0)); return null; }).when(clone).setTime(anyInt());
        when(clone.isTimed()).thenReturn(true); when(clone.isLastForever()).thenAnswer(call -> cloneTime.get() < 0);
        var persistentEffect = timerFailure || periodicFailure ? mock(Effect.class) : null;
        when(clone.getEffects()).thenReturn(timerFailure || periodicFailure ? new ArrayList<>(List.of(persistentEffect)) : new ArrayList<>());
        if (periodicFailure) when(clone.getReActivateEffectsTimer()).thenReturn(1);
        if (laterPeriodicFailure) when(clone.getIncreasePercent(Effect.ToolRepair)).thenReturn(1000.0);
        PerkHandler handler = newPerkHandler(plugin, List.of()); handlerRef.set(handler);
        when(plugin.getPerkHandler()).thenReturn(handler);
        var nested = new java.util.concurrent.atomic.AtomicReference<Object>();
        var rewardUser = users.getUser(owner);
        if (reentrant) {
            when(config.getLimitPermission()).thenReturn(true); when(user.getPerkLimit()).thenReturn(3);
            when(user.getActivePerks()).thenAnswer(call -> handler.getActivePerks().size());
        }
        long expiration = System.currentTimeMillis() + 20_000L;
        var language = mock(com.bencodez.mcperks.configs.Lang.class); when(language.getCountDownTimer()).thenReturn("");
        try (var langs = mockStatic(com.bencodez.mcperks.configs.Lang.class);
             var configs = mockStatic(com.bencodez.mcperks.configs.ConfigPerks.class);
             var rewards = mockConstruction(RewardBuilder.class, (mock, context) -> {
                 when(mock.setGiveOffline(false)).thenReturn(mock);
                 if (reentrant) doAnswer(call -> {
                     nested.set(nativeCall(handler, "beginNativeActivation",
                             new Class<?>[] {MCPerksUser.class, boolean.class}, user, true)); return null;
                 }).when(mock).send(rewardUser);
             })) {
            var perkConfig = mock(com.bencodez.mcperks.configs.ConfigPerks.class);
            configs.when(com.bencodez.mcperks.configs.ConfigPerks::getInstance).thenReturn(perkConfig);
            when(perkConfig.getData("restore")).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
            when(perkConfig.getActivationEffectPath()).thenReturn("ActivationEffect");
            langs.when(com.bencodez.mcperks.configs.Lang::getInstance).thenReturn(language);
            if (timerFailure || periodicFailure) {
                assertThrows(IllegalStateException.class, () -> handler.restoreActivePerk(definition, user, new java.sql.Timestamp(expiration)));
                assertTrue(handler.getActivePerks().isEmpty(), "a failed expiration schedule must not retain an unexpiring clone");
                verify(clone).setActive(false);
                if (periodicFailure) assertTrue(mockingDetails(clone).getInvocations().stream().noneMatch(
                        call -> call.getMethod().getName().equals("scheduleDeactivation")),
                        "expiration must not be registered after periodic registration fails");
                verify(persistentEffect).removeEffect(plugin, clone, audience);
                return;
            }
            handler.restoreActivePerk(definition, user, permanent ? null : new java.sql.Timestamp(expiration));
        verify(effects).activate(any(Perk.class), eq(user), eq(false));
        assertEquals(1, handler.getActivePerks().size());
        verify(clone).setExperation(eq(user), longThat(value -> value == (permanent ? -1L : expiration)));
        if (permanent) verify(clone).setTime(-1);
        verify(definition, never()).setTime(anyInt());
        assertTrue(rewards.constructed().isEmpty()); verify(serverData, never()).addPerkHistory(anyString());
        verify(clone, never()).announcePerk(any(), anyInt());
            Object outer = reentrant ? nativeCall(handler, "beginNativeActivation",
                    new Class<?>[] {MCPerksUser.class, boolean.class}, user, true) : null;
            Class<?> admission = reentrant ? outer.getClass() : null;
            if (reentrant) nativeCall(handler, "enterNativeGameplay", new Class<?>[] {admission}, outer);
            try { cloneTime.set(30); reset(effects); handler.activePerk(definition, user, 30, null); }
            finally { if (reentrant) nativeCall(handler, "leaveNativeGameplay", new Class<?>[] {admission}, new Object[] {null}); }
            verify(effects).activate(any(Perk.class), eq(user), eq(true));
            assertEquals(1, rewards.constructed().size());
            verify(rewards.constructed().get(0)).send(users.getUser(owner));
            verify(clone).announcePerk(any(), eq(30));
            verify(serverData).addPerkHistory(anyString());
            if (reentrant) {
                verify(clone, times(1)).setExperation(eq(user), anyLong());
                assertTrue(mockingDetails(clone).getInvocations().stream().anyMatch(call ->
                        call.getMethod().getName().equals("setKnownExpiration")),
                        "confirmed native activation must not rewrite user storage on the owner");
                assertTrue(clone.getKnownExpiration() > System.currentTimeMillis(),
                        "the live clone must retain the confirmed expiration");
                assertNotNull(nested.get(), "the activation hook must observe free capacity after clone publication");
                var drain = handler.retireNativeActivations().toCompletableFuture(); assertFalse(drain.isDone());
                nativeCall(handler, "finishNativeActivation", new Class<?>[] {admission}, outer);
                assertFalse(drain.isDone(), "the nested operation still owns its settlement");
                nativeCall(handler, "finishNativeActivation", new Class<?>[] {admission}, nested.get());
                assertTrue(drain.isDone());
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void quitBeforeSessionBindingIsScopedToExactSession(boolean sameSession) throws Exception {
        var plugin = mock(MCPerksMain.class); var config = mock(Config.class);
        when(plugin.getConfigFile()).thenReturn(config);
        var handler = newPerkHandler(plugin, List.of());
        var user = mock(MCPerksUser.class); var player = mock(org.bukkit.entity.Player.class);
        UUID uuid = UUID.randomUUID(); when(user.getUUID()).thenReturn(uuid.toString()); when(player.getUniqueId()).thenReturn(uuid);
        try {
            Object activation = nativeCall(handler, "beginNativeActivation", new Class<?>[] {MCPerksUser.class, boolean.class}, user, false);
            handler.retirePendingPlayerSession(player);
            var captured = sameSession ? player : mock(org.bukkit.entity.Player.class);
            nativeCall(handler, "bindNativeSession", new Class<?>[] {activation.getClass(), org.bukkit.entity.Player.class}, activation, captured);
            var field = activation.getClass().getDeclaredField("sessionRetired"); field.setAccessible(true);
            assertEquals(sameSession, field.getBoolean(activation), "quit must retire only the exact captured session");
            nativeCall(handler, "finishNativeActivation", new Class<?>[] {activation.getClass()}, activation);
        } finally { handler.retireTimers(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void actualQuitListenerRespectsLogoutPolicyBeforeSessionBinding(boolean enabled) throws Exception {
        var plugin = mock(MCPerksMain.class); var config = mock(Config.class);
        when(plugin.getConfigFile()).thenReturn(config); when(config.getDeactivateOnLogout()).thenReturn(enabled);
        var handler = newPerkHandler(plugin, List.of()); when(plugin.getPerkHandler()).thenReturn(handler);
        var user = mock(MCPerksUser.class); var player = mock(org.bukkit.entity.Player.class);
        var world = mock(org.bukkit.World.class); when(world.getName()).thenReturn("test-world"); when(player.getWorld()).thenReturn(world);
        UUID uuid = UUID.randomUUID(); when(user.getUUID()).thenReturn(uuid.toString()); when(player.getUniqueId()).thenReturn(uuid);
        var event = mock(org.bukkit.event.player.PlayerQuitEvent.class); when(event.getPlayer()).thenReturn(player);
        try {
            Object activation = nativeCall(handler, "beginNativeActivation", new Class<?>[] {MCPerksUser.class, boolean.class}, user, false);
            new com.bencodez.mcperks.listeners.EffectListeners(plugin).onDisconnect(event);
            nativeCall(handler, "bindNativeSession", new Class<?>[] {activation.getClass(), org.bukkit.entity.Player.class}, activation, player);
            var field = activation.getClass().getDeclaredField("sessionRetired"); field.setAccessible(true);
            assertEquals(enabled, field.getBoolean(activation));
            nativeCall(handler, "finishNativeActivation", new Class<?>[] {activation.getClass()}, activation);
        } finally { handler.retireTimers(); }
    }

    private static Object nativeCall(PerkHandler handler, String name, Class<?>[] types, Object... arguments) {
        try {
            var method = PerkHandler.class.getDeclaredMethod(name, types); method.setAccessible(true);
            return method.invoke(handler, arguments);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }







}
