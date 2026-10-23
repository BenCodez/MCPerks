package com.bencodez.mcperks.tests.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.commands.CommandLoader;
import com.bencodez.mcperks.data.DurableFeatureFiles;
import com.bencodez.mcperks.data.FeatureState;
import com.bencodez.mcperks.data.FeatureState.Bank;
import com.bencodez.mcperks.data.FeatureState.BankKey;
import com.bencodez.mcperks.perk.BankedPerks;
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.InteractionOptions;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.mcperks.perk.PerkHandler;
import com.bencodez.mcperks.perk.PerkSystemType;
import com.bencodez.mcperks.perk.QueuePlanner;
import com.bencodez.mcperks.tests.data.FeatureStateFixture;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.bencodez.mcperks.userapi.UserManager;
import com.bencodez.simpleapi.command.TabCompleteHandler;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

/** Registered bank commands with real storage; native refresh/eligibility is a separate boundary. */
class BankedTimeCommandAcceptanceTest {
    @TempDir Path directory;
    private final AtomicBoolean block = new AtomicBoolean(), failAfterPublish = new AtomicBoolean();
    private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    private final BlockingQueue<Runnable> dispatches = new LinkedBlockingQueue<>();
    private final BlockingQueue<Runnable> playerReplies = new LinkedBlockingQueue<>(), consoleReplies = new LinkedBlockingQueue<>();
    private final ArrayList<CommandHandler> commands = new ArrayList<>();
    private FeatureState state;
    private BankKey selfKey, otherKey;
    private MCPerksMain plugin;
    private Player self, other;
    private CommandSender console;
    private Perk perk;
    private PerkHandler perks;
    private UserManager users;
    private MCPerksUser user;
    private BankedPerks banked;
    private QueuePlanner planner;
    private MockedStatic<Bukkit> bukkit;
    private byte[] saved;
    private Map<BankKey, Bank> original;
    private List<FeatureState.QueueEntry> queued;

    @BeforeEach void setup() throws Exception {
        state = FeatureStateFixture.create(directory.resolve("features.dat"), (staged, target) -> {
            if (block.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Synthetic publication timeout");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException(failure);
                }
            }
            DurableFeatureFiles.publish(staged, target);
            if (failAfterPublish.compareAndSet(true, false)) throw new IOException("Synthetic acknowledgement failure");
        });
        state.ready().get(5, TimeUnit.SECONDS);
        self = player("SyntheticSelf"); other = player("SyntheticOther");
        selfKey = new BankKey(self.getUniqueId(), "probe"); otherKey = new BankKey(other.getUniqueId(), "probe");
        state.setBank(selfKey, 9000).get(5, TimeUnit.SECONDS);
        state.setBank(otherKey, 12000).get(5, TimeUnit.SECONDS);
        state.enqueue(otherKey.owner(), "queued", "PerkActivations_queued", 1).get(5, TimeUnit.SECONDS);
        original = state.banks(); queued = state.queue(); saved = Files.readAllBytes(directory.resolve("features.dat"));
        plugin = mock(MCPerksMain.class);
        when(plugin.getCommands()).thenReturn(commands);
        when(plugin.getFeatureState()).thenReturn(state);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var options = mock(AdvancedCoreConfigOptions.class);
        when(options.getFormatNoPerms()).thenReturn("Permission denied");
        when(plugin.getOptions()).thenReturn(options);
        perk = mock(Perk.class);
        when(perk.getPerk()).thenReturn("probe");
        when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.NoFallDamage)));
        when(perk.getInteractionOptions()).thenReturn(options(true));
        perks = mock(PerkHandler.class);
        when(perks.getLoadedPerks()).thenReturn(Map.of("probe", perk));
        when(perks.getPerk("probe")).thenReturn(perk);
        when(plugin.getPerkHandler()).thenReturn(perks);
        users = mock(UserManager.class); user = mock(MCPerksUser.class);
        when(users.getMCPerksUser("SyntheticOther")).thenReturn(user);
        when(user.getUUID()).thenReturn(otherKey.owner().toString());
        when(plugin.getMcperksUserManager()).thenReturn(users);
        // Keep the actual pause/checkpoint/storage chain, isolating only native online refresh.
        banked = spy(new BankedPerks(plugin, state));
        doNothing().when(banked).refresh(any(UUID.class));
        when(plugin.getBankedPerks()).thenReturn(banked);
        planner = mock(QueuePlanner.class);
        when(plugin.getQueuePlanner()).thenReturn(planner);
        var scheduler = mock(BukkitScheduler.class);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        doAnswer(call -> { dispatches.add(call.getArgument(1)); return null; })
                .when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(call -> { playerReplies.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class), eq(self));
        doAnswer(call -> { consoleReplies.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        TabCompleteHandler.getInstance().addTabCompleteOption("(Player)", "SyntheticOther");
        var loader = new CommandLoader(plugin);
        loader.loadTabComplete();
        for (String name : List.of("registerFeatureArgumentMatchers", "loadPlannerCommands")) {
            var method = CommandLoader.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(loader);
        }
        console = mock(CommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer("SyntheticOther")).thenReturn(other);
        clearInvocations(user, users, banked, perks);
    }

    @AfterEach void close() throws Exception {
        release.countDown();
        try { if (state != null) retire(state); }
        finally { if (bukkit != null) bukkit.close(); }
    }

    @ParameterizedTest @ValueSource(strings = {"GetBankedTime", "SetBankedTime", "PauseBankedTime", "ResumeBankedTime"})
    void deniedRegisteredCommandsCannotDispatchOrMutate(String name) throws Exception {
        assertEquals(name.equals("SetBankedTime") ? "MCPerks.SetBankedTime" : "MCPerks.BankedTime", command(name).getPerm());
        when(self.hasPermission(anyString())).thenReturn(false);
        assertTrue(command(name).runCommand(self, arguments(name)));
        verify(self).sendMessage(contains("Permission denied"));
        assertTrue(dispatches.isEmpty());
        assertUnchanged();
        verifyNoInteractions(users, user, banked, planner);
    }

    @ParameterizedTest @ValueSource(strings = {"GetBankedTime", "PauseBankedTime", "ResumeBankedTime"})
    void consoleCannotReadOrPauseSomeOtherPlayersBank(String name) throws Exception {
        invoke(console, arguments(name));
        verify(console).sendMessage(contains("Player only"));
        assertUnchanged(); verifyNoInteractions(users, user, banked, planner);
    }

    @Test void displayUsesTheSenderAndCannotSpendTimeOrMintAMissingGrant() throws Exception {
        invoke(self, "GetBankedTime", "probe");
        verify(self).sendMessage(contains("Banked: 9s; paused=false"));
        verify(self, never()).sendMessage(contains("Banked: 12s"));
        invoke(self, "GetBankedTime", "missing");
        verify(self).sendMessage(contains("No banked grant. Legacy timers retain their absolute expiration."));
        assertUnchanged(); verifyNoInteractions(users, user, banked, planner);
    }

    @ParameterizedTest @ValueSource(strings = {"SetBankedTime", "PauseBankedTime", "ResumeBankedTime"})
    void successWaitsForPublicationAndCorrectSenderReplyWithoutGrantingAnActivation(String name) throws Exception {
        if (name.equals("ResumeBankedTime")) state.pauseBank(selfKey, true).get(5, TimeUnit.SECONDS);
        Map<BankKey, Bank> before = state.banks();
        block.set(true);
        CommandSender sender = name.equals("SetBankedTime") ? console : self;
        invoke(sender, arguments(name));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(before, state.banks()); assertEquals(queued, state.queue());
            assertTrue(playerReplies.isEmpty()); assertTrue(consoleReplies.isEmpty());
            verify(sender, never()).sendMessage(anyString());
            verify(banked, never()).refresh(any(UUID.class));
        } finally { release.countDown(); }
        Runnable response = reply(sender);
        verify(sender, never()).sendMessage(anyString());
        response.run();
        assertTrue(sender == self ? consoleReplies.isEmpty() : playerReplies.isEmpty());
        verify(sender).sendMessage(contains(name.equals("SetBankedTime") ? "Banked time saved" : "Banked pause state saved"));
        Map<BankKey, Bank> expected = name.equals("SetBankedTime")
                ? Map.of(selfKey, before.get(selfKey), otherKey, new Bank(21000, false))
                : Map.of(selfKey, new Bank(9000, name.equals("PauseBankedTime")), otherKey, before.get(otherKey));
        assertEquals(expected, state.banks()); assertEquals(queued, state.queue());
        UUID target = name.equals("SetBankedTime") ? otherKey.owner() : selfKey.owner();
        verify(banked, atLeastOnce()).refresh(target);
        verify(banked, never()).refresh(eq(target.equals(selfKey.owner()) ? otherKey.owner() : selfKey.owner()));
        verify(planner).refreshLegacyView(); verify(planner).check();
        assertNoActivation();
        assertRecovered(expected);
    }

    @ParameterizedTest @ValueSource(strings = {"SetBankedTime", "PauseBankedTime", "ResumeBankedTime"})
    void acknowledgementFailureNeverClaimsSuccessOrRefreshesAnUnconfirmedBank(String name) throws Exception {
        if (name.equals("ResumeBankedTime")) state.pauseBank(selfKey, true).get(5, TimeUnit.SECONDS);
        Map<BankKey, Bank> before = state.banks();
        byte[] beforeBytes = Files.readAllBytes(directory.resolve("features.dat"));
        failAfterPublish.set(true);
        CommandSender sender = name.equals("SetBankedTime") ? console : self;
        invoke(sender, arguments(name));
        Runnable response = reply(sender);
        verify(sender, never()).sendMessage(anyString());
        response.run();
        assertFalse(state.available()); assertEquals(before, state.banks()); assertEquals(queued, state.queue());
        verify(sender).sendMessage(contains("failed or entry unavailable"));
        verify(sender, never()).sendMessage(contains("saved"));
        verify(sender, never()).sendMessage(contains("Synthetic acknowledgement failure"));
        verify(banked, never()).refresh(any(UUID.class));
        assertNoActivation();
        // Publication happened: an error is uncertainty, not a promise of disk rollback.
        assertFalse(java.util.Arrays.equals(beforeBytes, Files.readAllBytes(directory.resolve("features.dat"))));
        invoke(self, "GetBankedTime", "probe");
        verify(self).sendMessage(contains("Banked state is unavailable"));
        verify(self, never()).sendMessage(contains("Banked: "));
    }

    @ParameterizedTest @ValueSource(strings = {"PauseBankedTime", "ResumeBankedTime"})
    void absentGrantCannotBeRemintedByPauseOrResume(String name) throws Exception {
        invoke(self, name, "missing"); reply(self).run();
        verify(self).sendMessage(contains("failed or entry unavailable"));
        verify(self, never()).sendMessage(contains("saved"));
        assertUnchanged(); assertNoActivation();
    }

    @ParameterizedTest @ValueSource(strings = {"-1", "31536001", "9223372036854775807", "invalid"})
    void invalidAmountsCannotReplaceOrAddTime(String amount) throws Exception {
        invoke(console, "SetBankedTime", "SyntheticOther", "probe", amount);
        if (amount.equals("-1") || amount.equals("31536001")) {
            reply(console).run(); verify(console).sendMessage(contains("failed or entry unavailable"));
        } else verify(console).sendMessage(contains("Use a nonnegative time of at most one year"));
        assertUnchanged(); assertNoActivation();
        verify(banked, never()).refresh(any(UUID.class));
    }

    @ParameterizedTest @ValueSource(longs = {0, 31536000})
    void supportedEndpointsStoreSecondsAsMillisecondsForTheNamedOwner(long seconds) throws Exception {
        invoke(console, "SetBankedTime", "SyntheticOther", "probe", Long.toString(seconds)); reply(console).run();
        assertEquals(new Bank(seconds * 1000, false), state.banks().get(otherKey));
        assertEquals(original.get(selfKey), state.banks().get(selfKey));
        verify(users).getMCPerksUser("SyntheticOther"); verify(console).sendMessage(contains("Banked time saved"));
        assertNoActivation(); assertRecovered(state.banks());
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "disabled", "shared", "periodic", "reward"})
    void unsupportedPerksStopBeforeUserLookupOrStorage(String mode) throws Exception {
        switch (mode) {
            case "missing" -> when(perks.getPerk("probe")).thenReturn(null);
            case "disabled" -> when(perk.getInteractionOptions()).thenReturn(options(false));
            case "shared" -> when(perk.getPerkType()).thenReturn(PerkSystemType.ALL);
            case "periodic" -> when(perk.getReActivateEffectsTimer()).thenReturn(1);
            case "reward" -> when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.Rewards)));
            default -> fail("Unknown fixture mode");
        }
        invoke(console, arguments("SetBankedTime"));
        verify(console).sendMessage(contains("Enable BankedTime for a supported PLAYER perk first"));
        assertUnchanged(); verifyNoInteractions(users, user, banked, planner);
    }

    @Test void malformedStoredIdentityAndWrongArityDoNotMutate() throws Exception {
        when(user.getUUID()).thenReturn("invalid-uuid");
        invoke(console, arguments("SetBankedTime"));
        verify(console).sendMessage(contains("Use a nonnegative time of at most one year"));
        assertFalse(command("SetBankedTime").runCommand(console, new String[] {"SetBankedTime", "SyntheticOther", "probe"}));
        assertFalse(command("PauseBankedTime").runCommand(self, new String[] {"PauseBankedTime", "probe", "extra"}));
        assertTrue(dispatches.isEmpty()); assertUnchanged(); assertNoActivation();
    }

    private static InteractionOptions options(boolean banked) {
        return new InteractionOptions(false, banked, 16, 8, List.of(), List.of(), List.of(), List.of());
    }
    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getName()).thenReturn(name);
        when(player.hasPermission(anyString())).thenReturn(true); return player;
    }
    private CommandHandler command(String name) {
        return commands.stream().filter(c -> c.getArgs()[0].equals(name)).findFirst().orElseThrow();
    }
    private String[] arguments(String name) {
        return name.equals("SetBankedTime") ? new String[] {name, "SyntheticOther", "probe", "21"} : new String[] {name, "probe"};
    }
    private void invoke(CommandSender sender, String... args) throws Exception {
        assertTrue(command(args[0]).runCommand(sender, args));
        Runnable task = dispatches.poll(5, TimeUnit.SECONDS);
        assertNotNull(task, "accepted command must reach the registered dispatcher"); task.run();
    }
    private Runnable reply(CommandSender sender) throws Exception {
        Runnable task = (sender == self ? playerReplies : consoleReplies).poll(5, TimeUnit.SECONDS);
        assertNotNull(task, "durable completion must schedule the sender reply"); return task;
    }
    private void assertUnchanged() throws Exception {
        state.storage(() -> null).get(5, TimeUnit.SECONDS);
        assertEquals(original, state.banks()); assertEquals(queued, state.queue());
        assertArrayEquals(saved, Files.readAllBytes(directory.resolve("features.dat")));
        assertTrue(playerReplies.isEmpty()); assertTrue(consoleReplies.isEmpty());
    }
    private void assertNoActivation() {
        // UUID resolution is the only user operation permitted by these commands.
        assertTrue(mockingDetails(user).getInvocations().stream().allMatch(i -> i.getMethod().getName().equals("getUUID")));
        verify(banked, never()).grant(any(), any(), anyInt()); verify(banked, never()).grantConfirmed(any(), any(), anyInt());
        verify(banked, never()).granted(any(), any()); verify(banked, never()).grantedConfirmed(any(), any());
        assertTrue(mockingDetails(perk).getInvocations().stream().allMatch(i -> i.getMethod().getName().startsWith("get")));
    }
    private static void retire(FeatureState target) throws Exception {
        target.close(); var field = FeatureState.class.getDeclaredField("worker"); field.setAccessible(true);
        assertTrue(((ExecutorService) field.get(target)).awaitTermination(5, TimeUnit.SECONDS));
    }
    private void assertRecovered(Map<BankKey, Bank> expected) throws Exception {
        retire(state);
        FeatureState recovered = new FeatureState(directory.resolve("features.dat"));
        try {
            recovered.ready().get(5, TimeUnit.SECONDS);
            assertEquals(expected, recovered.banks()); assertEquals(queued, recovered.queue());
        } finally { retire(recovered); }
    }
}
