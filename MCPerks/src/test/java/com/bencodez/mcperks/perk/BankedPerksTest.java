package com.bencodez.mcperks.perk;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.data.*;
import com.bencodez.mcperks.userapi.*;
import com.bencodez.mcperks.scheduling.OwnerTaskHarness;
import com.bencodez.mcperks.scheduling.BundledOwnerTaskHarness;
class BankedPerksTest {
    @TempDir Path directory;
    private UUID owner; private Player player; private World world; private MCPerksMain plugin; private MCPerksUser user;
    private Perk source; private FeatureState state; private FeatureState.BankKey key; private BankedPerks banked;
    private OwnerTaskHarness scheduler; private CopyOnWriteArrayList<Perk> active; private AtomicLong time;
    @BeforeEach void setup() throws Exception {
        if (Bukkit.getServer()==null) { Server server=mock(Server.class); when(server.getLogger()).thenReturn(Logger.getLogger("test")); Bukkit.setServer(server); }
        owner=UUID.randomUUID(); player=mock(Player.class); when(player.getUniqueId()).thenReturn(owner); when(player.isOnline()).thenReturn(true); when(Bukkit.getServer().getPlayer(owner)).thenReturn(player);
        world=mock(World.class); when(world.getName()).thenReturn("world"); when(player.getWorld()).thenReturn(world);
        plugin=mock(MCPerksMain.class); when(plugin.getConfigFile()).thenReturn(mock(com.bencodez.mcperks.configs.Config.class)); when(plugin.isEnabled()).thenReturn(true); when(plugin.getLocalStatistics()).thenReturn(new LocalStatistics());
        user=mock(MCPerksUser.class); when(user.getUUID()).thenReturn(owner.toString());
        com.bencodez.mcperks.userapi.UserManager users=mock(com.bencodez.mcperks.userapi.UserManager.class); when(users.getMCPerksUser(owner)).thenReturn(user); when(users.getMCPerksUser(player)).thenReturn(user); when(plugin.getMcperksUserManager()).thenReturn(users);
        source=mock(Perk.class); when(source.getPerk()).thenReturn("passive"); when(source.getPerkType()).thenReturn(PerkSystemType.PLAYER); when(source.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.NoFallDamage)));
        when(source.getInteractionOptions()).thenReturn(new InteractionOptions(false,true,16,8,List.of(),List.of(),List.of(),List.of())); when(source.isNotInDisabledWorld("world")).thenReturn(true);
        when(source.clone()).thenAnswer(i -> { Perk clone=mock(Perk.class); when(clone.getPerk()).thenReturn("passive"); when(clone.getActivater()).thenReturn(user); when(clone.getEffectedPlayers()).thenReturn(new ArrayList<>()); field(clone,"plugin",plugin); doCallRealMethod().when(clone).deactivatePerk(user); return clone; });
        PerkHandler handler=mock(PerkHandler.class); when(handler.isRestorationReady()).thenReturn(true); when(plugin.getPerkHandler()).thenReturn(handler); when(handler.getPerk("passive")).thenReturn(source); active=new CopyOnWriteArrayList<>(); when(handler.getActivePerks()).thenReturn(active); when(plugin.getQueuePlanner()).thenReturn(mock(QueuePlanner.class));
        scheduler=new OwnerTaskHarness(plugin,player,true); time=new AtomicLong(1_000_000_000L); state=new FeatureState(directory.resolve("features.dat")); key=new FeatureState.BankKey(owner,"passive"); state.setBank(key,10000).get(5,TimeUnit.SECONDS);
        banked=new BankedPerks(plugin,state,time::get); when(plugin.getBankedPerks()).thenReturn(banked); when(plugin.getFeatureState()).thenReturn(state); when(plugin.getQueuePlanner()).thenReturn(new QueuePlanner(plugin, state));
    }
    private static void field(Perk perk,String name,Object value) throws Exception {
        var field=Perk.class.getDeclaredField(name); field.setAccessible(true); field.set(perk,value);
        if (name.equals("plugin")) {
            var admissions = Perk.class.getDeclaredField("activationAdmissions"); admissions.setAccessible(true);
            if (admissions.get(perk) == null) admissions.set(perk, new LinkedHashMap<String, Long>());
        }
    }
    private void nextOwner() throws Exception { Objects.requireNonNull(scheduler.tasks.poll(5,TimeUnit.SECONDS)).run(); }
    private void barrier() throws Exception { state.storage(() -> null).get(5,TimeUnit.SECONDS); }
    @ParameterizedTest @ValueSource(strings = {"omitted", "-1", "-12", "0", "1", "60"})
    void configuredLegacyDisabledTimersGrantAndAttachWithoutAnOverride(String timer) throws Exception {
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.loadFromString("Enabled: true\nPerkType: PLAYER\nTimeLasts: 10\nEffects: [NoFallDamage]\nBankedTime:\n  Enabled: true\n");
        if (!timer.equals("omitted")) yaml.set("ReActivateEffectsTimer", Integer.parseInt(timer));
        var config = mock(com.bencodez.mcperks.configs.ConfigPerks.class, CALLS_REAL_METHODS);
        doReturn(yaml).when(config).getData("passive");
        doReturn(new ArrayList<String>()).when(config).getPerkDisabledWorlds("passive");
        var previous = MCPerksMain.plugin; MCPerksMain.plugin = plugin;
        try (var configs = mockStatic(com.bencodez.mcperks.configs.ConfigPerks.class)) {
            configs.when(com.bencodez.mcperks.configs.ConfigPerks::getInstance).thenReturn(config);
            var configured = new Perk("passive");
            when(plugin.getPerkHandler().getPerk("passive")).thenReturn(configured);
            int value = timer.equals("omitted") ? -1 : Integer.parseInt(timer);
            assertEquals(value, configured.getReActivateEffectsTimer());
            assertEquals(value <= 0, BankedPerks.supported(configured));
            if (value <= 0) {
                banked.grant(user, configured, 10).get(5, TimeUnit.SECONDS); nextOwner(); nextOwner();
                assertEquals(20000, state.banks().get(key).milliseconds()); assertEquals(1, active.size());
                assertTrue(banked.available(active.getFirst()));
            } else {
                assertThrows(ExecutionException.class, () -> banked.grant(user, configured, 10).get(5, TimeUnit.SECONDS));
                banked.start(player); nextOwner(); assertTrue(active.isEmpty());
                assertEquals(10000, state.banks().get(key).milliseconds());
            }
        } finally { MCPerksMain.plugin = previous; }
    }
    @Test void actualControllerReplacementStartsWithTheEntireNewBalance() throws Exception {
        banked.start(player); nextOwner(); time.addAndGet(2_000_000_000L);
        state.setBank(key, 10000).get(5, TimeUnit.SECONDS); banked.refresh(owner); nextOwner();
        assertTrue(banked.available(active.getFirst()));
        time.addAndGet(1_000_000_000L); banked.pause(key, true).get(5, TimeUnit.SECONDS); barrier();
        assertEquals(new FeatureState.Bank(9000, true), state.banks().get(key));
    }
    @Test void actualControllerReplacementFencesAnEarlierDebitAdmittedAfterPublication() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        state = spy(state); when(plugin.getFeatureState()).thenReturn(state); when(plugin.getQueuePlanner()).thenReturn(new QueuePlanner(plugin, state));
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("old-bank-debit")) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("checkpoint timeout"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }
            return invocation.callRealMethod();
        }).when(state).elapsed(eq(key), anyLong(), any(java.util.function.BooleanSupplier.class));
        banked = new BankedPerks(plugin, state, time::get); when(plugin.getBankedPerks()).thenReturn(banked);
        banked.start(player); nextOwner(); time.addAndGet(2_000_000_000L);
        Objects.requireNonNull(scheduler.timers.poll(5, TimeUnit.SECONDS)).run();
        Runnable pulse = Objects.requireNonNull(scheduler.tasks.poll(5, TimeUnit.SECONDS));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(() -> { Thread.currentThread().setName("old-bank-debit"); pulse.run(); });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            state.setBank(key, 10000).get(5, TimeUnit.SECONDS);
            release.countDown(); running.get(5, TimeUnit.SECONDS); barrier();
            assertEquals(10000, state.banks().get(key).milliseconds());
            time.addAndGet(1_000_000_000L); banked.pause(key, true).get(5, TimeUnit.SECONDS); barrier();
            assertEquals(new FeatureState.Bank(9000, true), state.banks().get(key));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    private void publishingState(DurableFeatureFiles.Publisher publisher) throws Exception {
        banked.retire(); state.close();
        state = FeatureStateFixture.create(directory.resolve("features.dat"), publisher); state.ready().get(5, TimeUnit.SECONDS);
        banked = new BankedPerks(plugin, state, time::get);
        when(plugin.getBankedPerks()).thenReturn(banked); when(plugin.getFeatureState()).thenReturn(state); when(plugin.getQueuePlanner()).thenReturn(new QueuePlanner(plugin, state));
        scheduler.tasks.clear(); scheduler.timers.clear();
    }
    @Test void actualControllerAttachingDuringReplacementPublicationUsesItsCommitBoundary() throws Exception {
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        publishingState((staged, file) -> {
            if (block.get()) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("publication timeout"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
            }
            DurableFeatureFiles.publish(staged, file);
        });
        banked.start(player);
        Runnable initialPulse = Objects.requireNonNull(scheduler.tasks.poll(5, TimeUnit.SECONDS));
        block.set(true); var replacement = state.setBank(key, 10000);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); initialPulse.run(); assertEquals(1, active.size());
            time.addAndGet(2_000_000_000L); release.countDown(); replacement.get(5, TimeUnit.SECONDS);
            time.addAndGet(1_000_000_000L); banked.pause(key, true).get(5, TimeUnit.SECONDS); barrier();
            assertEquals(new FeatureState.Bank(9000, true), state.banks().get(key));
        } finally { release.countDown(); }
    }
    @ParameterizedTest @ValueSource(strings = {"quit", "reload", "retire"})
    void actualReplacementFencesLateClocksRemovedBeforeCommit(String removal) throws Exception {
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        publishingState((staged, file) -> {
            if (block.get()) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("publication timeout"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
            }
            DurableFeatureFiles.publish(staged, file);
        });
        banked.start(player);
        Runnable initialPulse = Objects.requireNonNull(scheduler.tasks.poll(5, TimeUnit.SECONDS));
        block.set(true); var replacement = state.setBank(key, 10000);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); initialPulse.run(); assertEquals(1, active.size());
            time.addAndGet(2_000_000_000L);
            switch (removal) {
                case "quit" -> banked.quit(new PlayerQuitEvent(player, "quit"));
                case "reload" -> banked.reload();
                case "retire" -> banked.retire();
                default -> fail("unknown removal");
            }
            assertTrue(active.isEmpty());
            block.set(false); release.countDown(); replacement.get(5, TimeUnit.SECONDS); barrier();
            assertEquals(10000, state.banks().get(key).milliseconds(), "removed old clock cannot debit replacement");
            // A new controller proves the fence belongs to the existing state owner,
            // while a new clock still acknowledges and spends its own reservations.
            banked.retire(); scheduler.tasks.clear(); scheduler.timers.clear();
            banked = new BankedPerks(plugin, state, time::get); when(plugin.getBankedPerks()).thenReturn(banked);
            banked.start(player); nextOwner(); assertEquals(1, active.size());
            time.addAndGet(1_000_000_000L); banked.pause(key, true).get(5, TimeUnit.SECONDS); barrier();
            assertEquals(new FeatureState.Bank(9000, true), state.banks().get(key));
            time.addAndGet(5_000_000_000L); state.addBank(key, 1000).get(5, TimeUnit.SECONDS);
            assertEquals(new FeatureState.Bank(10000, true), state.banks().get(key));
        } finally { release.countDown(); }
    }
    @Test void actualControllerAttachingDuringTopUpCannotSpendNewTimeBeforePublication() throws Exception {
        state.setBank(key, 1000).get(5, TimeUnit.SECONDS);
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        publishingState((staged, file) -> {
            if (block.get()) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("publication timeout"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
            }
            DurableFeatureFiles.publish(staged, file);
        });
        banked.start(player);
        Runnable initialPulse = Objects.requireNonNull(scheduler.tasks.poll(5, TimeUnit.SECONDS));
        block.set(true); var topUp = state.addBank(key, 10000);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); initialPulse.run(); assertEquals(1, active.size());
            time.addAndGet(5_000_000_000L);
            assertFalse(banked.available(active.getFirst())); // Only the old one second was spendable.
            release.countDown(); topUp.get(5, TimeUnit.SECONDS); barrier();
            assertEquals(10000, state.banks().get(key).milliseconds());
            time.addAndGet(1_000_000_000L); banked.pause(key, true).get(5, TimeUnit.SECONDS); barrier();
            assertEquals(new FeatureState.Bank(9000, true), state.banks().get(key));
        } finally { release.countDown(); }
    }
    @ParameterizedTest @CsvSource({"add,quit", "add,reload", "add,retire", "replace,quit", "replace,reload", "replace,retire"})
    void actualConfirmedBankBoundaryFencesLateClockRetirement(String publication, String removal) throws Exception {
        state.setBank(key, 1000).get(5, TimeUnit.SECONDS);
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var publicationEntered = new CountDownLatch(1); var releasePublication = new CountDownLatch(1);
        var commitEntered = new CountDownLatch(1); var releaseCommit = new CountDownLatch(1);
        var checkpointEntered = new CountDownLatch(1);
        publishingState((staged, file) -> {
            try { assertFalse(FeatureStateFixture.ownsBankBoundary(state), "filesystem publication cannot hold the bank boundary"); }
            catch (ReflectiveOperationException failure) { throw new java.io.IOException(failure); }
            if (block.get()) {
                publicationEntered.countDown();
                try { if (!releasePublication.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("publication timeout"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
            }
            DurableFeatureFiles.publish(staged, file);
        });
        state = spy(state); when(plugin.getFeatureState()).thenReturn(state); when(plugin.getQueuePlanner()).thenReturn(new QueuePlanner(plugin, state));
        banked = new BankedPerks(plugin, state, time::get); when(plugin.getBankedPerks()).thenReturn(banked);
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("late-bank-retirement")) checkpointEntered.countDown();
            return call.callRealMethod();
        }).when(state).bankCheckpoint(any());
        FeatureStateFixture.beforeBankCommit(state, () -> {
            commitEntered.countDown();
            try { if (!releaseCommit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("commit timeout"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        });
        banked.start(player); Runnable initialPulse = Objects.requireNonNull(scheduler.tasks.poll(5, TimeUnit.SECONDS));
        var ownerExecutor = Executors.newSingleThreadExecutor(); block.set(true);
        var publishing = publication.equals("add") ? state.addBank(key, 10000) : state.setBank(key, 10000);
        try {
            assertTrue(publicationEntered.await(5, TimeUnit.SECONDS)); initialPulse.run(); assertEquals(1, active.size());
            time.addAndGet(5_000_000_000L); block.set(false); releasePublication.countDown();
            assertTrue(commitEntered.await(5, TimeUnit.SECONDS));
            assertEquals(1000, state.banks().get(key).milliseconds(), "new time cannot appear before its clock boundary");
            var retiring = ownerExecutor.submit(() -> {
                Thread.currentThread().setName("late-bank-retirement");
                switch (removal) {
                    case "quit" -> banked.quit(new PlayerQuitEvent(player, "quit"));
                    case "reload" -> banked.reload();
                    case "retire" -> banked.retire();
                    default -> fail("unknown removal");
                }
            });
            assertTrue(checkpointEntered.await(5, TimeUnit.SECONDS)); assertFalse(retiring.isDone());
            releaseCommit.countDown(); publishing.get(5, TimeUnit.SECONDS); retiring.get(5, TimeUnit.SECONDS); barrier();
            assertEquals(10000, state.banks().get(key).milliseconds()); assertTrue(active.isEmpty());
            banked.retire(); scheduler.tasks.clear(); scheduler.timers.clear();
            banked = new BankedPerks(plugin, state, time::get); when(plugin.getBankedPerks()).thenReturn(banked);
            banked.start(player); nextOwner(); assertEquals(1, active.size());
            time.addAndGet(1_000_000_000L); banked.pause(key, true).get(5, TimeUnit.SECONDS); barrier();
            assertEquals(new FeatureState.Bank(9000, true), state.banks().get(key));
        } finally { releasePublication.countDown(); releaseCommit.countDown(); ownerExecutor.shutdownNow(); }
    }
    @ParameterizedTest @ValueSource(strings = {"add", "replace"})
    void actualLateClockAfterCompletePublicationStartsAtAttachment(String publication) throws Exception {
        state.setBank(key, 1000).get(5, TimeUnit.SECONDS); banked.start(player);
        Runnable initialPulse = Objects.requireNonNull(scheduler.tasks.poll(5, TimeUnit.SECONDS));
        time.addAndGet(5_000_000_000L);
        if (publication.equals("add")) state.addBank(key, 10000).get(5, TimeUnit.SECONDS);
        else state.setBank(key, 10000).get(5, TimeUnit.SECONDS);
        initialPulse.run(); assertEquals(1, active.size());
        time.addAndGet(1_000_000_000L); banked.pause(key, true).get(5, TimeUnit.SECONDS); barrier();
        assertEquals(new FeatureState.Bank(publication.equals("add") ? 10000 : 9000, true), state.banks().get(key));
    }
    @Test void failedLateClockTopUpNeverExposesUnconfirmedTimeOrRunsCommit() throws Exception {
        state.setBank(key, 1000).get(5, TimeUnit.SECONDS);
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        publishingState((staged, file) -> {
            if (fail.get()) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("publication timeout"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
                throw new java.io.IOException("top-up publication rejected");
            }
            DurableFeatureFiles.publish(staged, file);
        });
        var commits = new java.util.concurrent.atomic.AtomicInteger(); FeatureStateFixture.beforeBankCommit(state, commits::incrementAndGet);
        banked.start(player); Runnable initialPulse = Objects.requireNonNull(scheduler.tasks.poll(5, TimeUnit.SECONDS));
        fail.set(true); var topUp = state.addBank(key, 10000);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); initialPulse.run(); time.addAndGet(5_000_000_000L);
            assertFalse(banked.available(active.getFirst())); release.countDown();
            assertThrows(ExecutionException.class, () -> topUp.get(5, TimeUnit.SECONDS));
            assertEquals(0, commits.get()); assertEquals(1000, state.banks().get(key).milliseconds()); state.close();
            try (var recovered = new FeatureState(directory.resolve("features.dat"))) {
                recovered.ready().get(5, TimeUnit.SECONDS); assertEquals(1000, recovered.banks().get(key).milliseconds());
            }
        } finally { release.countDown(); }
    }
    @Test void actualControllerManualPauseDuringGrantPublicationNeverChargesThePausedInterval() throws Exception {
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        publishingState((staged, file) -> {
            if (block.get()) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("publication timeout"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
            }
            DurableFeatureFiles.publish(staged, file);
        });
        banked.start(player); nextOwner(); time.addAndGet(2_000_000_000L);
        block.set(true); var grant = state.addBank(key, 10000);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var pause = banked.pause(key, true); assertTrue(active.isEmpty()); assertFalse(pause.isDone());
            time.addAndGet(5_000_000_000L); release.countDown(); grant.get(5, TimeUnit.SECONDS); pause.get(5, TimeUnit.SECONDS);
            assertEquals(new FeatureState.Bank(18000, true), state.banks().get(key));
            time.addAndGet(3_000_000_000L); state.addBank(key, 10000).get(5, TimeUnit.SECONDS);
            assertEquals(new FeatureState.Bank(28000, true), state.banks().get(key)); assertTrue(active.isEmpty());
            time.addAndGet(10_000_000_000L); banked.pause(key, false).get(5, TimeUnit.SECONDS);
            nextOwner(); nextOwner(); // pause/resume refreshes cannot bypass the existing pulse.
            Objects.requireNonNull(scheduler.timers.poll(5, TimeUnit.SECONDS)).run(); nextOwner(); assertEquals(1, active.size());
            time.addAndGet(1_000_000_000L); banked.pause(key, true).get(5, TimeUnit.SECONDS); barrier();
            assertEquals(new FeatureState.Bank(27000, true), state.banks().get(key));
        } finally { release.countDown(); }
    }
    @Test void actualControllerPauseAndResumeDuringReplacementSaveExcludePausedTime() throws Exception {
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        publishingState((staged, file) -> {
            if (block.get()) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("publication timeout"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
            }
            DurableFeatureFiles.publish(staged, file);
        });
        banked.start(player); nextOwner(); time.addAndGet(2_000_000_000L);
        block.set(true); var replacement = state.setBank(key, 10000);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); var pause = banked.pause(key, true); assertTrue(active.isEmpty());
            time.addAndGet(5_000_000_000L); var resume = banked.pause(key, false);
            release.countDown(); replacement.get(5, TimeUnit.SECONDS); pause.get(5, TimeUnit.SECONDS); resume.get(5, TimeUnit.SECONDS);
            assertEquals(new FeatureState.Bank(10000, false), state.banks().get(key));
            time.addAndGet(3_000_000_000L); nextOwner(); nextOwner();
            Objects.requireNonNull(scheduler.timers.poll(5, TimeUnit.SECONDS)).run(); nextOwner(); assertEquals(1, active.size());
            time.addAndGet(1_000_000_000L); banked.pause(key, true).get(5, TimeUnit.SECONDS); barrier();
            assertEquals(new FeatureState.Bank(9000, true), state.banks().get(key));
        } finally { release.countDown(); }
    }
    @Test void actualControllerFailedReplacementPreservesItsOldClockAndPendingOwnership() throws Exception {
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        publishingState((staged, file) -> {
            if (fail.get()) throw new java.io.IOException("replacement publication failed");
            DurableFeatureFiles.publish(staged, file);
        });
        banked.start(player); nextOwner(); time.addAndGet(2_000_000_000L); fail.set(true);
        assertThrows(ExecutionException.class, () -> state.setBank(key, 20000).get(5, TimeUnit.SECONDS));
        assertEquals(10000, state.banks().get(key).milliseconds()); assertFalse(banked.available(active.getFirst()));
        state.close();
        try (var recovered = new FeatureState(directory.resolve("features.dat"))) {
            recovered.ready().get(5, TimeUnit.SECONDS); assertEquals(10000, recovered.banks().get(key).milliseconds());
        }
    }
    @Test void actualControllerRebasesClockWhenDepletedBankIsToppedUpBeforeCheckpoint() throws Exception {
        state.setBank(key, 1000).get(5, TimeUnit.SECONDS);
        banked.start(player); nextOwner();
        time.addAndGet(2_000_000_000L);
        state.addBank(key, 10_000).get(5, TimeUnit.SECONDS);
        time.addAndGet(1_000_000_000L);
        banked.pause(key, true).get(5, TimeUnit.SECONDS);
        barrier();
        assertEquals(9000, state.banks().get(key).milliseconds());
    }
    @Test void actualControllerNondepletedTopUpChargesTimeAcrossTheSameBoundary() throws Exception {
        banked.start(player); nextOwner();
        time.addAndGet(2_000_000_000L);
        state.addBank(key, 10_000).get(5, TimeUnit.SECONDS);
        time.addAndGet(1_000_000_000L);
        banked.pause(key, true).get(5, TimeUnit.SECONDS);
        barrier();
        assertEquals(17_000, state.banks().get(key).milliseconds());
    }
    private com.bencodez.advancedcore.api.user.usercache.UserDataCache directRuntime() throws Exception {
        field(source,"plugin",plugin); field(source,"perk","passive"); when(source.getTime()).thenReturn(10);
        // Direct activation's admission callback uses the global scheduler in
        // this fixture; the bank attachment itself still uses the owner task.
        var directScheduler = plugin.getBukkitScheduler();
        doAnswer(invocation -> { invocation.<Runnable>getArgument(1).run(); return null; })
                .when(directScheduler).runTask(eq(plugin), any(Runnable.class));
        when(source.checkTimesUsed(any())).thenReturn(true); when(source.checkCoolDown(any())).thenReturn(true); when(source.checkServerWideCoolDown(any())).thenReturn(true);
        var config=mock(com.bencodez.mcperks.configs.Config.class); when(plugin.getConfigFile()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        var file=mock(com.bencodez.advancedcore.data.ServerData.class); when(file.getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        when(file.getdFile()).thenReturn(directory.resolve("ServerData.yml").toFile()); when(plugin.getServerDataFile()).thenReturn(file);
        when(plugin.getMcperksServerData()).thenReturn(new ServerData(plugin));
        var users=mock(com.bencodez.advancedcore.api.user.UserManager.class); when(plugin.getUserManager()).thenReturn(users);
        var data=mock(com.bencodez.advancedcore.api.user.usercache.UserDataManager.class); when(users.getDataManager()).thenReturn(data);
        var storageWorker = mock(java.util.concurrent.ScheduledExecutorService.class);
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                .when(storageWorker).execute(any(Runnable.class));
        when(data.getTimer()).thenReturn(storageWorker);
        var cache=mock(com.bencodez.advancedcore.api.user.usercache.UserDataCache.class); when(data.getCache(any())).thenReturn(cache);
        doAnswer(i -> { i.getArgument(0,Runnable.class).run(); return null; }).when(cache).flushChangesAndRun(any());
        doCallRealMethod().when(source).runPerk(any()); doCallRealMethod().when(source).executeConfirmed(any(),anyInt());
        return cache;
    }
    @AfterEach void close() { banked.retire(); state.close(); }
    @Test void concurrentPauseWaitsForPulseCheckpointDebitAdmission() throws Exception {
        var checkpointEntered = new CountDownLatch(1); var releaseCheckpoint = new CountDownLatch(1);
        state = spy(state); when(plugin.getFeatureState()).thenReturn(state); when(plugin.getQueuePlanner()).thenReturn(new QueuePlanner(plugin, state));
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("pulse-checkpoint")) {
                checkpointEntered.countDown();
                try { if (!releaseCheckpoint.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("checkpoint timeout"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }
            return invocation.callRealMethod();
        }).when(state).elapsed(eq(key), anyLong(), any(java.util.function.BooleanSupplier.class));
        banked = new BankedPerks(plugin, state, time::get);
        when(plugin.getBankedPerks()).thenReturn(banked);
        banked.start(player); nextOwner(); time.addAndGet(1_000_000_000L);
        Objects.requireNonNull(scheduler.timers.poll(5, TimeUnit.SECONDS)).run();
        Runnable pulse = Objects.requireNonNull(scheduler.tasks.poll(5, TimeUnit.SECONDS));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var running = executor.submit(() -> { Thread.currentThread().setName("pulse-checkpoint"); pulse.run(); });
            assertTrue(checkpointEntered.await(5, TimeUnit.SECONDS));
            var pauseEntered = new CountDownLatch(1);
            var paused = executor.submit(() -> { pauseEntered.countDown(); return banked.pause(key, true).get(5, TimeUnit.SECONDS); });
            assertTrue(pauseEntered.await(5, TimeUnit.SECONDS));
            assertFalse(paused.isDone(), "pause cannot overtake an in-progress pulse checkpoint");
            releaseCheckpoint.countDown(); running.get(5, TimeUnit.SECONDS); paused.get(5, TimeUnit.SECONDS); barrier();
            assertEquals(9000, state.banks().get(key).milliseconds()); assertTrue(state.banks().get(key).paused());
            assertTrue(active.isEmpty());
        } finally { releaseCheckpoint.countDown(); executor.shutdownNow(); }
    }
    @Test void actualControllerPausesOfflineManualTimeAndRejectsStaleReloadCallbackWithoutRegrant() throws Exception {
        banked.start(player); nextOwner(); assertEquals(1,active.size()); Runnable oldTimer=Objects.requireNonNull(scheduler.timers.poll(5,TimeUnit.SECONDS));
        banked.pause(key,true).get(); nextOwner(); assertTrue(active.isEmpty()); banked.quit(new PlayerQuitEvent(player,"quit")); long remaining=state.banks().get(key).milliseconds();
        state.pauseBank(key,false).get(); banked.start(player); nextOwner(); assertEquals(1,active.size()); assertEquals(remaining,state.banks().get(key).milliseconds()); oldTimer.run(); assertEquals(1,active.size());
        banked.reload(); nextOwner(); nextOwner(); assertEquals(1,active.size()); oldTimer.run(); assertEquals(1,active.size()); banked.retire(); assertTrue(active.isEmpty()); barrier();
        assertEquals(remaining,state.banks().get(key).milliseconds()); verify(plugin,never()).getEffectHandler(); verify(user,never()).addActivation(anyInt()); verify(user,never()).setPerkExperation(any(Perk.class),anyLong());
        verify(scheduler.implementation,atLeastOnce()).runAtEntityLater(eq(player),any(Runnable.class),any(Runnable.class),eq(20L));
    }
    @Test void attachedShortBankExpiresWhileContextReadWaitsWithoutMutatingInspection() throws Exception {
        state.setBank(key,1000).get(); banked.start(player); nextOwner();
        Perk effect=active.getFirst(); assertTrue(banked.available(effect));
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        var blocker=state.storage(()-> { entered.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
            catch(InterruptedException failure) { throw new RuntimeException(failure); } return null; });
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        try {
            Objects.requireNonNull(scheduler.timers.poll(5,TimeUnit.SECONDS)).run();
            time.addAndGet(1_000_000_000L);
            assertFalse(banked.available(effect)); assertFalse(banked.available(effect));
            assertEquals(1000,state.banks().get(key).milliseconds());
            assertSame(effect,active.getFirst());
        } finally { release.countDown(); }
        blocker.get(5,TimeUnit.SECONDS); nextOwner(); barrier(); nextOwner();
        assertTrue(active.isEmpty()); assertEquals(0,state.banks().get(key).milliseconds());
    }
    @Test void shortSessionsWorldReloadAndRetirementPersistEveryElapsedEligibleInterval() throws Exception {
        banked.start(player); nextOwner(); time.addAndGet(600_000_000); banked.quit(new PlayerQuitEvent(player,"quit")); barrier(); assertEquals(9400,state.banks().get(key).milliseconds());
        time.set(10_000_000_000L); banked.start(player); nextOwner(); assertEquals(9400,state.banks().get(key).milliseconds()); time.addAndGet(300_000_000); banked.world(new PlayerChangedWorldEvent(player,world)); barrier(); assertEquals(9100,state.banks().get(key).milliseconds());
        banked.reload(); nextOwner(); nextOwner(); time.addAndGet(200_000_000); banked.reload(); barrier(); assertEquals(8900,state.banks().get(key).milliseconds()); nextOwner(); nextOwner(); time.addAndGet(100_000_000); banked.retire(); barrier(); assertEquals(8800,state.banks().get(key).milliseconds());
        state.close(); try(FeatureState restarted=new FeatureState(directory.resolve("features.dat"))) { restarted.ready().get(); assertEquals(8800,restarted.banks().get(key).milliseconds()); }
    }
    @Test void reloadRestartsSuspendedOnlineBanksAndLegacyDeactivationPausesOwnedEntitlement() throws Exception {
        when(source.isNotInDisabledWorld("world")).thenReturn(false); banked.start(player); nextOwner(); assertTrue(active.isEmpty()); banked.reload(); nextOwner(); nextOwner(); assertTrue(active.isEmpty());
        when(source.isNotInDisabledWorld("world")).thenReturn(true); Objects.requireNonNull(scheduler.timers.poll(5,TimeUnit.SECONDS)).run(); // stale timer is fenced
        Objects.requireNonNull(scheduler.timers.poll(5,TimeUnit.SECONDS)).run(); nextOwner(); assertEquals(1,active.size());
        time.addAndGet(500_000_000); active.getFirst().deactivatePerk(user);
        assertTrue(active.isEmpty());
        // Pause publication follows the elapsed checkpoint's completion callback;
        // one file-worker barrier may precede that callback's next admitted write.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!state.banks().get(key).paused() && System.nanoTime() < deadline) barrier();
        assertTrue(state.banks().get(key).paused()); assertEquals(9500,state.banks().get(key).milliseconds());
        verify(user,never()).setPerkExperation(any(),anyLong());
    }
    @Test void stalePositiveBankCannotAuthorizeEffectsAfterStorageFailure() throws Exception {
        banked.start(player); nextOwner(); Perk attached=active.getFirst(); Files.delete(directory.resolve("features.dat")); Files.createDirectory(directory.resolve("features.dat")); assertThrows(ExecutionException.class,()->state.setBank(key,10000).get());
        assertEquals(10000,state.banks().get(key).milliseconds()); assertFalse(banked.available(attached));
    }
    @Test void newBankGrantRecordsCooldownOnceAndResumeDoesNotRecordItAgain() throws Exception {
        field(source,"plugin",plugin); when(source.getCoolDown()).thenReturn(30); when(source.getServerWideCoolDown()).thenReturn(60); doCallRealMethod().when(source).executeConfirmed(user,10);
        directRuntime();
        long before=System.currentTimeMillis(); source.executeConfirmed(user,10).get(5,TimeUnit.SECONDS); verify(user,times(1)).setPerkCoolDown(eq(source),longThat(value->value>=before+29000)); long serverCooldown = plugin.getMcperksServerData().getServerCooldown("passive"); assertTrue(serverCooldown >= before + 59000);
        var written = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("ServerData.yml").toFile()); assertEquals(serverCooldown, written.getLong("passive.CoolDown"));
        banked.pause(key,true).get(); banked.pause(key,false).get(); verify(user,times(1)).setPerkCoolDown(any(),anyLong());
        assertEquals(serverCooldown, plugin.getMcperksServerData().getServerCooldown("passive"));
    }
    @Test void directBankSerializesBeforeChecksAndKeepsFenceThroughUserConfirmation() throws Exception {
        var cache=directRuntime(); state.setBank(key,0).get(5,TimeUnit.SECONDS);
        var callback=new CompletableFuture<Runnable>(); doAnswer(i -> { callback.complete(i.getArgument(0)); return null; }).when(cache).flushChangesAndRun(any());
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        state.storage(() -> { entered.countDown(); try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch (InterruptedException error) { throw new RuntimeException(error); } return null; });
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        try {
            source.runPerk(user); source.runPerk(user); assertEquals(0,state.banks().get(key).milliseconds());
            verify(source,times(1)).checkTimesUsed(user); verify(user).sendMessage(contains("still being confirmed"));
            verify(user, never()).setPerkCoolDown(eq(source), anyLong());
            release.countDown(); var completed=callback.get(5,TimeUnit.SECONDS);
            assertEquals(0,state.banks().get(key).milliseconds()); assertTrue(active.isEmpty());
            source.runPerk(user); verify(source,times(1)).checkTimesUsed(user);
            completed.run(); barrier(); barrier();
            assertEquals(10000,state.banks().get(key).milliseconds());
            assertTrue(banked.beginDirect(source)); banked.finishDirect(source);
            try (var configs=mockStatic(com.bencodez.mcperks.configs.ConfigPerks.class)) {
                var config=mock(com.bencodez.mcperks.configs.ConfigPerks.class); configs.when(com.bencodez.mcperks.configs.ConfigPerks::getInstance).thenReturn(config);
                when(config.getPerkAlreayActive("passive")).thenReturn("already active");
                source.runPerk(user); verify(user).sendMessage("already active");
                verify(user,times(1)).setPerkCoolDown(eq(source),anyLong()); assertEquals(10000,state.banks().get(key).milliseconds());
            }
        } finally { release.countDown(); }
    }
    @Test void idleLimitedBankedActivationUsesDirectStartFenceInsteadOfWaitingQueue() throws Exception {
        directRuntime(); state.setBank(key, 0).get(5, TimeUnit.SECONDS);
        when(plugin.getConfigFile().getLimitActivations()).thenReturn(true);
        when(plugin.getConfigFile().getPerkQue()).thenReturn(true);
        when(user.getActivations()).thenReturn(1);
        var planner = mock(QueuePlanner.class);
        when(plugin.getQueuePlanner()).thenReturn(planner);
        when(planner.beginDirect(owner, source)).thenReturn(true);
        when(planner.startDirect(eq(user), eq(source), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        source.runPerk(user);
        verify(planner).startDirect(eq(user), eq(source), any(), any());
        verify(plugin.getPerkHandler(), never()).addQue(user, source);
    }
    @Test void directBankDoesNotPublishEntitlementUntilCooldownConfirmationSucceeds() throws Exception {
        var cache = directRuntime(); state.setBank(key, 0).get(5, TimeUnit.SECONDS);
        var confirmation = new CompletableFuture<Runnable>();
        doAnswer(invocation -> { confirmation.complete(invocation.getArgument(0)); return null; })
                .when(cache).flushChangesAndRun(any());
        var granting = source.executeConfirmed(user, 10);
        var callback = confirmation.get(5, TimeUnit.SECONDS);
        assertEquals(0, state.banks().get(key).milliseconds(),
                "bank publication must wait for the caller cooldown confirmation");
        assertFalse(granting.isDone());
        callback.run();
        granting.get(5, TimeUnit.SECONDS);
        assertEquals(10000, state.banks().get(key).milliseconds());
    }
    @Test void directBankPublishesServerCooldownBeforeReleasingAdmissionAndRejectsOtherPlayerAfterReload() throws Exception {
        directRuntime(); state.setBank(key,0).get(5,TimeUnit.SECONDS); when(source.getServerWideCoolDown()).thenReturn(60);
        doCallRealMethod().when(source).setServerCoolDownTime(anyLong());
        var previous=MCPerksMain.plugin; MCPerksMain.plugin=plugin;
        try {
            source.runPerk(user); barrier(); barrier(); barrier();
            assertTrue(banked.beginDirect(source)); banked.finishDirect(source);
            long cooldown=plugin.getMcperksServerData().getServerCooldown("passive"); assertTrue(cooldown>System.currentTimeMillis()+59000);
            var written=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("ServerData.yml").toFile());
            assertEquals(cooldown,written.getLong("passive.CoolDown"));
            plugin.getMcperksServerData().getData().loadFromString(written.saveToString());
            UUID other=UUID.randomUUID(); var otherUser=mock(MCPerksUser.class); when(otherUser.getUUID()).thenReturn(other.toString());
            doCallRealMethod().when(source).checkServerWideCoolDown(any());
            try (var configs=mockStatic(com.bencodez.mcperks.configs.ConfigPerks.class)) {
                var config=mock(com.bencodez.mcperks.configs.ConfigPerks.class); configs.when(com.bencodez.mcperks.configs.ConfigPerks::getInstance).thenReturn(config);
                when(config.getPerkInCoolDown("passive")).thenReturn("cooldown"); source.runPerk(otherUser);
                verify(otherUser).sendMessage(eq("cooldown"),eq("cooldown"),anyString());
            }
            assertFalse(state.banks().containsKey(new FeatureState.BankKey(other,"passive"))); verify(user,times(1)).setPerkCoolDown(eq(source),anyLong());
        } finally { MCPerksMain.plugin=previous; }
    }
    @Test void failedDirectBankPublicationReleasesAdmissionAndRetainsConfirmedCooldown() throws Exception {
        directRuntime(); state.setBank(key,0).get(5,TimeUnit.SECONDS);
        when(plugin.getConfigFile().getPerkQue()).thenReturn(true);
        Files.delete(directory.resolve("features.dat")); Files.createDirectory(directory.resolve("features.dat"));
        source.runPerk(user);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (plugin.getQueuePlanner().hasDirectStart() && System.nanoTime() < deadline)
            state.runtimeStorage(() -> null).get(5,TimeUnit.SECONDS);
        assertFalse(plugin.getQueuePlanner().hasDirectStart());
        assertTrue(banked.beginDirect(source)); banked.finishDirect(source);
        assertEquals(0, state.banks().get(key).milliseconds());
        verify(user,times(1)).setPerkCoolDown(eq(source),anyLong());
        assertTrue(Files.exists(directory.resolve("ServerData.yml")));
        Files.delete(directory.resolve("features.dat"));
    }
    @Test void unavailableDirectBankPurchaseRejectsBeforeAnyActivationDebit() throws Exception {
        field(source,"plugin",plugin); field(source,"perk","passive"); when(source.getTime()).thenReturn(10); when(source.checkTimesUsed(user)).thenReturn(true); when(source.checkCoolDown(user)).thenReturn(true); when(source.checkServerWideCoolDown(user)).thenReturn(true);
        var config=mock(com.bencodez.mcperks.configs.Config.class); when(config.getLimitActivations()).thenReturn(true); when(plugin.getConfigFile()).thenReturn(config); var serverData=mock(ServerData.class); when(serverData.isReady()).thenReturn(true); when(serverData.getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration()); when(plugin.getMcperksServerData()).thenReturn(serverData);
        Files.delete(directory.resolve("features.dat")); Files.createDirectory(directory.resolve("features.dat")); assertThrows(ExecutionException.class,()->state.setBank(key,10000).get()); doCallRealMethod().when(source).runPerk(user); source.runPerk(user);
        verify(user,never()).addActivation(anyInt()); verify(user,never()).addActivation(anyString(),anyInt()); verify(user).sendMessage(contains("unavailable")); verify(source,never()).executeConfirmed(any(),anyInt());
    }
    @Test void oldCheckpointCompletionCannotSuspendAReloadedSuccessorGrant() throws Exception {
        state.setBank(key,1000).get(); banked.start(player); nextOwner(); time.addAndGet(1_000_000_000);
        scheduler.timers.remove().run(); nextOwner(); Runnable oldCompletion=Objects.requireNonNull(scheduler.tasks.poll(5,TimeUnit.SECONDS)); barrier(); assertEquals(0,state.banks().get(key).milliseconds());
        state.setBank(key,10000).get(); banked.reload(); nextOwner(); nextOwner(); assertEquals(1,active.size()); oldCompletion.run(); assertEquals(1,active.size()); assertTrue(banked.available(active.getFirst()));
    }
    @Test void legacyLogoutPolicyDoesNotTurnAutomaticOfflinePauseIntoManualBankPause() throws Exception {
        var config=mock(com.bencodez.mcperks.configs.Config.class); when(config.getDeactivateOnLogout()).thenReturn(true); when(plugin.getConfigFile()).thenReturn(config);
        banked.start(player); nextOwner(); time.addAndGet(250_000_000); var event=new PlayerQuitEvent(player,"quit"); new com.bencodez.mcperks.listeners.EffectListeners(plugin).onDisconnect(event); banked.quit(event); barrier();
        assertEquals(9750,state.banks().get(key).milliseconds()); assertFalse(state.banks().get(key).paused()); assertTrue(active.isEmpty());
    }

    @Test void defaultPlayersWithoutBankedEntitlementDoNotStartPollingUserStorage() throws Exception {
        state.setBank(key,0).get(); banked.start(player); assertTrue(scheduler.tasks.isEmpty()); assertTrue(scheduler.timers.isEmpty()); verify(user,never()).isIgnorePerkEffects();
    }

    @Test void failedOrRejectedCheckpointDetachesBankEvenWithAvailablePositiveSnapshot() throws Exception {
        banked.start(player); nextOwner(); time.addAndGet(1_000_000_000); scheduler.timers.remove().run();
        // Ignore read is admitted, but saturate the actual bounded store before the owner checkpoint.
        Runnable checkpoint=Objects.requireNonNull(scheduler.tasks.poll(5,TimeUnit.SECONDS));
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        state.storage(()->{ entered.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new RuntimeException(e); } return null; });
        assertTrue(entered.await(5,TimeUnit.SECONDS)); List<CompletableFuture<?>> queued=new ArrayList<>(); for(int i=0;i<256;i++) queued.add(state.storage(()->null));
        checkpoint.run(); assertTrue(state.available()); assertEquals(10000,state.banks().get(key).milliseconds()); assertTrue(active.isEmpty()); assertTrue(scheduler.timers.isEmpty());
        release.countDown(); CompletableFuture.allOf(queued.toArray(CompletableFuture[]::new)).get(5,TimeUnit.SECONDS); barrier();
    }
    @Test void retirementDrainsAdditionalElapsedReservedWhileDebitIsInFlight() throws Exception {
        banked.start(player); nextOwner(); time.addAndGet(1_000_000_000L);
        scheduler.timers.remove().run();
        Runnable checkpoint = Objects.requireNonNull(scheduler.tasks.poll(5, TimeUnit.SECONDS));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        state.storage(() -> { entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new RuntimeException(failure); }
            return null; });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            checkpoint.run(); time.addAndGet(1_000_000_000L);
            var retirement = banked.retireAsync();
            assertSame(retirement, banked.retireAsync()); assertFalse(retirement.isDone());
            release.countDown(); retirement.get(5, TimeUnit.SECONDS);
            assertEquals(8000, state.banks().get(key).milliseconds());
            assertTrue(active.isEmpty());
        } finally { release.countDown(); }
    }
    @Test void retirementSurfacesFinalDebitFailureInsteadOfAcknowledgingIt() throws Exception {
        banked.start(player); nextOwner(); time.addAndGet(1_000_000_000L);
        banked.retireAsync().get(5, TimeUnit.SECONDS);
        state = spy(state);
        when(plugin.getFeatureState()).thenReturn(state);
        // The controller must capture the failing persistence owner at construction.
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("checkpoint rejected")))
                .when(state).elapsed(eq(key), anyLong(), any(java.util.function.BooleanSupplier.class));
        banked = new BankedPerks(plugin, state, time::get); when(plugin.getBankedPerks()).thenReturn(banked);
        banked.start(player); nextOwner(); time.addAndGet(1_000_000_000L);
        var retirement = banked.retireAsync();
        assertThrows(ExecutionException.class, () -> retirement.get(5, TimeUnit.SECONDS));
        assertSame(retirement, banked.retireAsync());
        assertEquals(9000, state.banks().get(key).milliseconds());
    }
    @Test void reconnectRetainsAnInFlightDisconnectDebitReservation() throws Exception {
        banked.start(player); nextOwner(); time.addAndGet(1_000_000_000L);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        state.storage(() -> { entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new RuntimeException(failure); }
            return null; });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        banked.quit(new PlayerQuitEvent(player, "quit"));
        banked.start(player);
        release.countDown();
        Runnable reconnectPulse = Objects.requireNonNull(scheduler.tasks.poll(5, TimeUnit.SECONDS));
        reconnectPulse.run(); barrier();
        assertEquals(9000, state.banks().get(key).milliseconds(), "the reserved interval must debit once across reconnect");
    }
    @Test void reconnectRetriesARejectedDisconnectDebitWithoutRemintingElapsedTime() throws Exception {
        var reject = new java.util.concurrent.atomic.AtomicBoolean(true);
        state = spy(state); when(plugin.getFeatureState()).thenReturn(state); when(plugin.getQueuePlanner()).thenReturn(new QueuePlanner(plugin, state));
        doAnswer(call -> reject.getAndSet(false)
                ? CompletableFuture.failedFuture(new IllegalStateException("checkpoint rejected"))
                : call.callRealMethod()).when(state).elapsed(eq(key), anyLong(), any(java.util.function.BooleanSupplier.class));
        banked = new BankedPerks(plugin, state, time::get); when(plugin.getBankedPerks()).thenReturn(banked);
        banked.start(player); nextOwner(); time.addAndGet(1_000_000_000L); banked.quit(new PlayerQuitEvent(player, "quit"));
        banked.start(player); nextOwner(); barrier();
        assertEquals(9000, state.banks().get(key).milliseconds(), "a failed debit must be retried once, not replaced by a fresh interval");
        assertTrue(banked.available(active.getFirst()));
    }
    @Test void storageDelayBeforeInitialAttachAndResumeDoesNotSpendUnavailableTime() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        state.storage(()->{ entered.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new RuntimeException(e); } return null; });
        assertTrue(entered.await(5,TimeUnit.SECONDS)); banked.start(player); time.addAndGet(4_000_000_000L); assertTrue(active.isEmpty()); release.countDown(); nextOwner();
        time.addAndGet(250_000_000L); banked.pause(key,true).get(); barrier(); assertEquals(9750,state.banks().get(key).milliseconds()); nextOwner();
        state.pauseBank(key,false).get(); CountDownLatch resumed=new CountDownLatch(1), resumeRelease=new CountDownLatch(1);
        state.storage(()->{ resumed.countDown(); try { assertTrue(resumeRelease.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new RuntimeException(e); } return null; });
        assertTrue(resumed.await(5,TimeUnit.SECONDS)); scheduler.timers.remove().run(); time.addAndGet(3_000_000_000L); resumeRelease.countDown(); nextOwner();
        assertEquals(1,active.size()); time.addAndGet(100_000_000L); banked.quit(new PlayerQuitEvent(player,"quit")); barrier(); assertEquals(9650,state.banks().get(key).milliseconds());
    }
    @Test void retiredRefreshGrantAndReloadDispatchesCannotStopReconnectSuccessor() throws Exception {
        banked.start(player); nextOwner(); banked.refresh(owner); Runnable refresh=Objects.requireNonNull(scheduler.tasks.poll(5,TimeUnit.SECONDS));
        banked.grant(user,source,1).get(); Runnable grant=Objects.requireNonNull(scheduler.tasks.poll(5,TimeUnit.SECONDS));
        banked.reload(); Runnable reload=Objects.requireNonNull(scheduler.tasks.poll(5,TimeUnit.SECONDS));
        when(player.isOnline()).thenReturn(false); Player successor=mock(Player.class); when(successor.getUniqueId()).thenReturn(owner); when(successor.isOnline()).thenReturn(true); when(successor.getWorld()).thenReturn(world); when(Bukkit.getServer().getPlayer(owner)).thenReturn(successor);
        OwnerTaskHarness newScheduler=new OwnerTaskHarness(plugin,successor,true); banked.start(successor); Objects.requireNonNull(newScheduler.tasks.poll(5,TimeUnit.SECONDS)).run(); assertEquals(1,active.size()); Perk current=active.getFirst();
        scheduler.retirement.get(refresh).run(); scheduler.retirement.get(grant).run(); scheduler.retirement.get(reload).run(); refresh.run(); grant.run(); reload.run();
        assertSame(current,active.getFirst()); assertTrue(banked.available(current)); time.addAndGet(100_000_000L); banked.quit(new PlayerQuitEvent(player,"old quit")); assertSame(current,active.getFirst());
        banked.quit(new PlayerQuitEvent(successor,"quit")); barrier(); assertTrue(active.isEmpty()); assertEquals(10900,state.banks().get(key).milliseconds());
    }

    @Test void persistentDoubleJumpFlagsAreRejectedBeforeBankGrantOrAttachment() throws Exception {
        for(Effect effect:List.of(Effect.DoubleJump,Effect.DoubleJumpUp)) {
            when(source.getEffects()).thenReturn(new ArrayList<>(List.of(effect)));
            assertNotNull(banked.grantUnavailable(owner,source,10));
            assertThrows(ExecutionException.class,()->banked.grant(user,source,10).get(5,TimeUnit.SECONDS));
            banked.start(player); while(!scheduler.tasks.isEmpty()) nextOwner();
            assertTrue(active.isEmpty()); assertEquals(10000,state.banks().get(key).milliseconds());
        }
        verify(player,never()).setAllowFlight(anyBoolean()); verify(user,never()).addActivation(anyInt());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void bundledSchedulerPulsesExpireTheAttachedEffectAtPurchasedTime(boolean paper) throws Exception {
        var bundled=new BundledOwnerTaskHarness(plugin,paper);
        state.setBank(key,1000).get(5,TimeUnit.SECONDS);
        banked.start(player);
        Objects.requireNonNull(bundled.tasks.poll(5,TimeUnit.SECONDS)).run();
        assertEquals(1,active.size()); Perk effect=active.getFirst(); assertTrue(banked.available(effect));
        verify(bundled.nativeScheduler).runTaskLater(eq(plugin),any(Runnable.class),eq(20L));
        Runnable pulse=Objects.requireNonNull(bundled.timers.poll(5,TimeUnit.SECONDS));
        time.addAndGet(1_000_000_000L); pulse.run();
        Objects.requireNonNull(bundled.tasks.poll(5,TimeUnit.SECONDS)).run(); // owner checkpoint
        Objects.requireNonNull(bundled.tasks.poll(5,TimeUnit.SECONDS)).run(); // confirmed zero balance
        barrier(); assertEquals(0,state.banks().get(key).milliseconds());
        assertTrue(active.isEmpty()); assertFalse(banked.attached(effect)); verify(effect).setActive(false);
        Objects.requireNonNull(bundled.timers.poll(5,TimeUnit.SECONDS)).run(); // zero bank stops polling
        assertTrue(bundled.tasks.isEmpty()); assertTrue(bundled.timers.isEmpty());
        verify(plugin,never()).getEffectHandler(); verify(user,never()).addActivation(anyInt());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void bundledSchedulerRejectionDetachesTheBankInsteadOfLeavingAnUnpulsedEffect(boolean paper) throws Exception {
        var bundled=new BundledOwnerTaskHarness(plugin,paper);
        when(bundled.nativeScheduler.runTaskLater(eq(plugin),any(Runnable.class),eq(20L))).thenThrow(new IllegalStateException("scheduler retired"));
        banked.start(player); Objects.requireNonNull(bundled.tasks.poll(5,TimeUnit.SECONDS)).run();
        barrier(); assertTrue(active.isEmpty()); assertEquals(10000,state.banks().get(key).milliseconds());
        assertTrue(bundled.tasks.isEmpty()); assertTrue(bundled.timers.isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void bundledDelayedCallbacksCannotReactivateARetiredBankController(boolean paper) throws Exception {
        var bundled=new BundledOwnerTaskHarness(plugin,paper);
        banked.start(player); Objects.requireNonNull(bundled.tasks.poll(5,TimeUnit.SECONDS)).run();
        Runnable oldPulse=Objects.requireNonNull(bundled.timers.poll(5,TimeUnit.SECONDS));
        time.addAndGet(250_000_000L); banked.retire(); barrier();
        assertTrue(active.isEmpty()); assertEquals(9750,state.banks().get(key).milliseconds());
        time.addAndGet(2_000_000_000L); oldPulse.run(); oldPulse.run(); barrier();
        assertTrue(active.isEmpty()); assertEquals(9750,state.banks().get(key).milliseconds());
        assertTrue(bundled.tasks.isEmpty()); assertTrue(bundled.timers.isEmpty());
    }

    private Perk secondBank() throws Exception {
        var second = mock(Perk.class); when(second.getPerk()).thenReturn("other");
        when(second.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        when(second.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.NoFallDamage)));
        var options = source.getInteractionOptions(); when(second.getInteractionOptions()).thenReturn(options); when(second.isNotInDisabledWorld("world")).thenReturn(true);
        when(second.clone()).thenAnswer(i -> {
            var clone = mock(Perk.class); when(clone.getPerk()).thenReturn("other"); when(clone.getActivater()).thenReturn(user);
            when(clone.getEffectedPlayers()).thenReturn(new ArrayList<>()); return clone;
        });
        when(plugin.getPerkHandler().getPerk("other")).thenReturn(second);
        field(second, "plugin", plugin); field(second, "perk", "other"); when(second.getTime()).thenReturn(10);
        when(second.checkTimesUsed(any())).thenReturn(true); when(second.checkCoolDown(any())).thenReturn(true); when(second.checkServerWideCoolDown(any())).thenReturn(true);
        doCallRealMethod().when(second).runPerk(any()); doCallRealMethod().when(second).executeConfirmed(any(), anyInt());
        return second;
    }
    private void activeLimit(boolean enabled, int limit) {
        when(plugin.getConfigFile().getLimitPermission()).thenReturn(enabled); when(user.getPerkLimit()).thenReturn(limit);
        when(user.getPlayerName()).thenReturn(owner.toString()); doCallRealMethod().when(user).getActivePerks();
    }
    private MCPerksUser otherPlayer() {
        UUID other = UUID.randomUUID(); var otherPlayer = mock(Player.class); var otherUser = mock(MCPerksUser.class);
        when(otherPlayer.getUniqueId()).thenReturn(other); when(otherPlayer.isOnline()).thenReturn(true);
        when(otherPlayer.isValid()).thenReturn(true); when(otherPlayer.getWorld()).thenReturn(world);
        when(Bukkit.getServer().getPlayer(other)).thenReturn(otherPlayer); when(otherUser.getUUID()).thenReturn(other.toString());
        when(plugin.getMcperksUserManager().getMCPerksUser(other)).thenReturn(otherUser);
        // Both players use the same deterministic owner queues, preserving each callback's player identity.
        when(scheduler.implementation.runAtEntityWithFallback(eq(otherPlayer), any(), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask> action = invocation.getArgument(1);
            Runnable fallback = invocation.getArgument(2);
            var result = new CompletableFuture<com.bencodez.simpleapi.folialib.enums.EntityTaskResult>();
            Runnable task = () -> { action.accept(null); result.complete(com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS); };
            scheduler.retirement.put(task, fallback); scheduler.tasks.add(task); return result;
        });
        when(scheduler.implementation.runAtEntityLater(eq(otherPlayer), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(invocation -> {
            scheduler.timers.add(invocation.getArgument(1)); return mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class);
        });
        return otherUser;
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void queueEnabledDirectBankOwnsPublicationAndEmptyActiveAttachmentGap(boolean secondBanked) throws Exception {
        directRuntime(); state.setBank(key, 0).get(5, TimeUnit.SECONDS);
        when(plugin.getConfigFile().getPerkQue()).thenReturn(true);
        var planner = plugin.getQueuePlanner(); var second = secondBank(); var other = otherPlayer();
        if (!secondBanked) when(second.getInteractionOptions()).thenReturn(new InteractionOptions(false, false, 16, 8, List.of(), List.of(), List.of(), List.of()));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        banked.retire(); state.close();
        state = FeatureStateFixture.create(directory.resolve("features.dat"), (staged, file) -> {
            if (staged.toFile().length() > 0) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("test publication timeout"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
            }
            DurableFeatureFiles.publish(staged, file);
        });
        state.ready().get(5, TimeUnit.SECONDS); when(plugin.getFeatureState()).thenReturn(state);
        planner = new QueuePlanner(plugin, state); when(plugin.getQueuePlanner()).thenReturn(planner);
        banked = new BankedPerks(plugin, state, time::get); when(plugin.getBankedPerks()).thenReturn(banked);
        var queued = new CopyOnWriteArrayList<Perk>();
        var handler = plugin.getPerkHandler();
        doAnswer(invocation -> { queued.add(invocation.getArgument(1)); return null; }).when(handler).addQue(any(), any());
        try {
            source.runPerk(user); assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(planner.hasDirectStart()); assertTrue(active.isEmpty()); assertEquals(0, state.banks().get(key).milliseconds());
            second.runPerk(other); assertEquals(List.of(second), queued); verify(second, never()).executeConfirmed(any(), anyInt());
            release.countDown(); barrier(); barrier();
            assertEquals(10000, state.banks().get(key).milliseconds()); assertTrue(active.isEmpty()); assertTrue(planner.hasDirectStart());
            state.enqueue(UUID.fromString(other.getUUID()), "other", "", 1).get(5, TimeUnit.SECONDS);
            planner.check(); assertFalse(planner.isStarting());
            nextOwner(); barrier(); assertTrue(active.isEmpty()); assertTrue(planner.hasDirectStart());
            planner.check(); assertFalse(planner.isStarting());
            nextOwner(); barrier(); barrier();
            assertEquals(1, active.size()); assertEquals("passive", active.getFirst().getPerk()); assertFalse(planner.hasDirectStart());
            assertEquals(FeatureState.Stage.WAITING, state.queue().getFirst().stage());
            assertFalse(state.banks().containsKey(new FeatureState.BankKey(UUID.fromString(other.getUUID()), "other")));
            verify(user, never()).addActivation(anyInt()); verify(other, never()).addActivation(anyInt());
        } finally { release.countDown(); }
    }
    @Test void queueEnabledManualResumeWaitsBehindUnrelatedActivePerkWithoutChargingTime() throws Exception {
        when(plugin.getConfigFile().getPerkQue()).thenReturn(true);
        banked.start(player); nextOwner(); time.addAndGet(250_000_000L);
        banked.pause(key, true).get(5, TimeUnit.SECONDS); nextOwner(); barrier();
        var legacy = mock(Perk.class); when(legacy.getPerk()).thenReturn("unrelated"); active.add(legacy);
        banked.pause(key, false).get(5, TimeUnit.SECONDS); nextOwner();
        time.addAndGet(4_000_000_000L); Objects.requireNonNull(scheduler.timers.poll(5, TimeUnit.SECONDS)).run(); nextOwner(); barrier();
        assertEquals(List.of(legacy), active); assertEquals(9750, state.banks().get(key).milliseconds());
        active.remove(legacy); Objects.requireNonNull(scheduler.timers.poll(5, TimeUnit.SECONDS)).run(); nextOwner();
        assertEquals(1, active.size()); assertTrue(banked.attached(active.getFirst()));
        time.addAndGet(100_000_000L); banked.quit(new PlayerQuitEvent(player, "quit")); barrier();
        assertEquals(9650, state.banks().get(key).milliseconds());
        verify(source, never()).executeConfirmed(any(), anyInt()); verify(user, never()).setPerkCoolDown(any(), anyLong());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void retiredInitialGrantAttachmentReleasesQueueOwnershipWithoutEffectsOrLostBank(boolean retireController) throws Exception {
        directRuntime(); state.setBank(key, 0).get(5, TimeUnit.SECONDS); when(plugin.getConfigFile().getPerkQue()).thenReturn(true);
        var planner = plugin.getQueuePlanner(); source.runPerk(user); barrier(); barrier();
        assertTrue(planner.hasDirectStart()); assertTrue(active.isEmpty());
        Runnable callback = Objects.requireNonNull(scheduler.tasks.poll(5, TimeUnit.SECONDS));
        if (retireController) { banked.retire(); callback.run(); }
        else { scheduler.retirement.get(callback).run(); callback.run(); }
        barrier(); barrier();
        assertFalse(planner.hasDirectStart()); assertTrue(active.isEmpty()); assertEquals(10000, state.banks().get(key).milliseconds());
        assertEquals(!retireController, banked.beginDirect(source)); banked.finishDirect(source); verify(user, never()).addActivation(anyInt());
    }
    @Test void manualResumeWaitsForLimitSlotWithoutSpendingTimeOrReplayingHooks() throws Exception {
        activeLimit(true, 1); var previous = MCPerksMain.plugin; MCPerksMain.plugin = plugin;
        try {
            banked.start(player); nextOwner(); assertEquals(1, active.size());
            time.addAndGet(250_000_000L); banked.pause(key, true).get(5, TimeUnit.SECONDS); nextOwner(); barrier();
            var legacy = mock(Perk.class); when(legacy.getPerk()).thenReturn("legacy"); when(legacy.getActivater()).thenReturn(user);
            when(legacy.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(owner.toString()))); active.add(legacy);
            banked.pause(key, false).get(5, TimeUnit.SECONDS); nextOwner();
            time.addAndGet(4_000_000_000L); Objects.requireNonNull(scheduler.timers.poll(5, TimeUnit.SECONDS)).run(); nextOwner(); barrier();
            assertEquals(List.of(legacy), active); assertEquals(9750, state.banks().get(key).milliseconds());
            active.remove(legacy); Objects.requireNonNull(scheduler.timers.poll(5, TimeUnit.SECONDS)).run(); nextOwner();
            assertEquals(1, active.size()); assertTrue(banked.attached(active.getFirst()));
            time.addAndGet(100_000_000L); banked.quit(new PlayerQuitEvent(player, "quit")); barrier();
            assertEquals(9650, state.banks().get(key).milliseconds()); assertFalse(state.banks().get(key).paused());
            verify(source, never()).executeConfirmed(any(), anyInt()); verify(plugin, never()).getEffectHandler();
            verify(user, never()).addActivation(anyInt()); verify(user, never()).setPerkCoolDown(any(), anyLong());
        } finally { MCPerksMain.plugin = previous; }
    }
    @Test void parallelDistinctDirectBankPurchasesAttachOnlyUpToLimitAndPreserveWaitingTime() throws Exception {
        directRuntime(); state.setBank(key, 0).get(5, TimeUnit.SECONDS); var second = secondBank();
        activeLimit(true, 1); var previous = MCPerksMain.plugin; MCPerksMain.plugin = plugin;
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        state.storage(() -> {
            entered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); } return null;
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); source.runPerk(user); second.runPerk(user);
            assertTrue(active.isEmpty()); release.countDown(); barrier(); barrier(); barrier();
            nextOwner(); nextOwner(); nextOwner(); // two confirmed grants, then one controller pulse
            assertEquals(1, active.size());
            var running = new FeatureState.BankKey(owner, active.getFirst().getPerk());
            var waiting = new FeatureState.BankKey(owner, running.perk().equals("passive") ? "other" : "passive");
            assertEquals(10000, state.banks().get(running).milliseconds()); assertEquals(10000, state.banks().get(waiting).milliseconds());
            time.addAndGet(5_000_000_000L); Objects.requireNonNull(scheduler.timers.poll(5, TimeUnit.SECONDS)).run(); nextOwner(); nextOwner(); barrier();
            assertEquals(1, active.size()); assertEquals(5000, state.banks().get(running).milliseconds()); assertEquals(10000, state.banks().get(waiting).milliseconds());
            banked.pause(running, true).get(5, TimeUnit.SECONDS); nextOwner();
            Objects.requireNonNull(scheduler.timers.poll(5, TimeUnit.SECONDS)).run(); nextOwner(); barrier();
            assertEquals(1, active.size()); assertEquals(waiting.perk(), active.getFirst().getPerk()); assertEquals(10000, state.banks().get(waiting).milliseconds());
            verify(user, times(1)).setPerkCoolDown(eq(source), anyLong()); verify(user, times(1)).setPerkCoolDown(eq(second), anyLong());
            verify(user, never()).addActivation(anyInt()); verify(plugin, never()).getEffectHandler();
        } finally { release.countDown(); MCPerksMain.plugin = previous; }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disabledPermissionLimitOrAbsentPermissionStillAllowsMultipleBanks(boolean enabled) throws Exception {
        secondBank(); state.setBank(new FeatureState.BankKey(owner, "other"), 10000).get(5, TimeUnit.SECONDS);
        activeLimit(enabled, enabled ? -1 : 1); var previous = MCPerksMain.plugin; MCPerksMain.plugin = plugin;
        try { banked.start(player); nextOwner(); assertEquals(2, active.size()); }
        finally { MCPerksMain.plugin = previous; }
    }
    @Test void attachedBankAtExactLimitKeepsRunningAndCountsOtherAffectedPerks() throws Exception {
        activeLimit(true, 1); var previous = MCPerksMain.plugin; MCPerksMain.plugin = plugin;
        try {
            banked.start(player); nextOwner(); var attached = active.getFirst();
            time.addAndGet(250_000_000L); Objects.requireNonNull(scheduler.timers.poll(5, TimeUnit.SECONDS)).run(); nextOwner(); nextOwner(); barrier();
            assertEquals(List.of(attached), active); assertEquals(9750, state.banks().get(key).milliseconds());
            var otherUser = mock(MCPerksUser.class); when(otherUser.getPlayerName()).thenReturn("other-player");
            var shared = mock(Perk.class); when(shared.getPerk()).thenReturn("shared"); when(shared.getActivater()).thenReturn(otherUser);
            when(shared.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(owner.toString()))); active.add(shared);
            Objects.requireNonNull(scheduler.timers.poll(5, TimeUnit.SECONDS)).run(); nextOwner(); barrier();
            assertEquals(List.of(shared), active); assertEquals(9750, state.banks().get(key).milliseconds());
        } finally { MCPerksMain.plugin = previous; }
    }
    @Test void bankControllerAdmissionWaitsForLegacyRestoration() throws Exception {
        when(plugin.getPerkHandler().isRestorationReady()).thenReturn(false); banked.start(player);
        assertTrue(active.isEmpty()); assertTrue(scheduler.tasks.isEmpty()); assertTrue(scheduler.timers.isEmpty());
        when(plugin.getPerkHandler().isRestorationReady()).thenReturn(true); banked.start(player); nextOwner();
        assertEquals(1, active.size()); assertEquals(10000, state.banks().get(key).milliseconds());
    }

}
