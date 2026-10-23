package com.bencodez.mcperks.tests.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
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
import com.bencodez.mcperks.configs.Config;
import com.bencodez.mcperks.data.FeatureState;
import com.bencodez.mcperks.data.ServerData;
import com.bencodez.mcperks.listeners.UsageEffectListener;
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.InteractionOptions;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.mcperks.perk.PerkHandler;
import com.bencodez.mcperks.perk.PerkSystemType;
import com.bencodez.mcperks.perk.QueuePlanner;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.bencodez.mcperks.userapi.UsageCharges;
import com.bencodez.mcperks.userapi.UserManager;
import com.bencodez.simpleapi.command.TabCompleteHandler;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

/** Registered command routing and reply ownership backed by real durable state. */
class DiagnosticsCommandAcceptanceTest {
    @TempDir Path directory;
    private final ArrayList<CommandHandler> commands = new ArrayList<>();
    private final BlockingQueue<Runnable> dispatches = new LinkedBlockingQueue<>();
    private final BlockingQueue<Runnable> selfTasks = new LinkedBlockingQueue<>();
    private final BlockingQueue<Runnable> targetTasks = new LinkedBlockingQueue<>();
    private final BlockingQueue<Runnable> consoleTasks = new LinkedBlockingQueue<>();
    private FeatureState state;
    private UsageCharges charges;
    private MCPerksMain plugin;
    private Player self, target;
    private CommandSender console;
    private UserManager users;
    private MCPerksUser user;
    private ServerData server;
    private PerkHandler perks;
    private QueuePlanner planner;
    private MockedStatic<Bukkit> bukkit;
    private byte[] featureBytes, chargeBytes;

    @BeforeEach void setup() throws Exception {
        state = new FeatureState(directory.resolve("features.dat"));
        charges = new UsageCharges(directory.resolve("charges.dat"));
        state.ready().get(5, TimeUnit.SECONDS);
        charges.ready().get(5, TimeUnit.SECONDS);
        self = player("SyntheticSelf"); target = player("SyntheticTarget");
        console = mock(CommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        for (Player player : List.of(self, target)) {
            state.setBank(new FeatureState.BankKey(player.getUniqueId(), "probe"), 9000).get(5, TimeUnit.SECONDS);
            charges.set(player.getUniqueId(), UsageCharges.Kind.AutoBlockRefill, 7).get(5, TimeUnit.SECONDS);
        }
        state.enqueue(target.getUniqueId(), "queued", "PerkActivations_queued", 1).get(5, TimeUnit.SECONDS);
        featureBytes = Files.readAllBytes(directory.resolve("features.dat"));
        chargeBytes = Files.readAllBytes(directory.resolve("charges.dat"));
        plugin = mock(MCPerksMain.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getCommands()).thenReturn(commands);
        when(plugin.getFeatureState()).thenReturn(state);
        when(plugin.getUsageCharges()).thenReturn(charges);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var options = mock(AdvancedCoreConfigOptions.class);
        when(options.getFormatNoPerms()).thenReturn("Permission denied");
        when(plugin.getOptions()).thenReturn(options);
        when(plugin.getConfigFile()).thenReturn(mock(Config.class));
        user = mock(MCPerksUser.class);
        when(user.getActivations()).thenReturn(5);
        when(user.getActivations("probe")).thenReturn(3);
        users = mock(UserManager.class);
        when(users.getMCPerksUser(any(UUID.class))).thenReturn(user);
        when(plugin.getMcperksUserManager()).thenReturn(users);
        server = mock(ServerData.class);
        when(server.getData()).thenReturn(new YamlConfiguration());
        when(plugin.getMcperksServerData()).thenReturn(server);
        Perk perk = mock(Perk.class);
        when(perk.getPerk()).thenReturn("probe");
        when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        when(perk.getEffectedPlayers()).thenReturn(new ArrayList<>());
        when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.AutoBlockRefill)));
        when(perk.isNotInDisabledWorld(anyString())).thenReturn(true);
        when(perk.getInteractionOptions()).thenReturn(new InteractionOptions(true, false, 16, 8,
                List.of(), List.of(), List.of(), List.of()));
        perks = mock(PerkHandler.class);
        when(perks.getLoadedPerks()).thenReturn(Map.of("probe", perk));
        when(perks.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>());
        when(perks.effectDecision(any(), anyString(), anyString())).thenReturn(PerkHandler.Decision.ACTIVE);
        when(plugin.getPerkHandler()).thenReturn(perks);
        when(plugin.getUsageEffects()).thenReturn(mock(UsageEffectListener.class));
        planner = mock(QueuePlanner.class);
        when(planner.startUnavailable(any())).thenReturn("awaiting eligibility");
        when(plugin.getQueuePlanner()).thenReturn(planner);
        var scheduler = mock(BukkitScheduler.class);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        doAnswer(call -> { dispatches.add(call.getArgument(1)); return null; })
                .when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(call -> { selfTasks.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class), eq(self));
        doAnswer(call -> { targetTasks.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class), eq(target));
        doAnswer(call -> { consoleTasks.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        // AdvancedCore registers this player placeholder before MCPerks loads commands.
        TabCompleteHandler.getInstance().addTabCompleteOption("(Player)", "SyntheticTarget", "Missing");
        var register = CommandLoader.class.getDeclaredMethod("loadPlannerCommands");
        register.setAccessible(true);
        register.invoke(new CommandLoader(plugin));
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer("SyntheticTarget")).thenReturn(target);
        bukkit.when(() -> Bukkit.getPlayerExact("SyntheticTarget")).thenReturn(target);
        clearInvocations(self, target, user, users, server, perks, planner);
    }

    @AfterEach void close() throws Exception {
        if (bukkit != null) bukkit.close();
        for (Object store : new Object[] {state, charges}) {
            if (store == null) continue;
            ((AutoCloseable) store).close();
            var field = store.getClass().getDeclaredField("worker");
            field.setAccessible(true);
            assertTrue(((ExecutorService) field.get(store)).awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void deniedSelfAndOtherCommandsNeverReachDiagnosticLookupStorageOrSchedulers(boolean other) throws Exception {
        when(self.hasPermission(anyString())).thenReturn(false);
        assertEquals(other ? "MCPerks.Diagnose.Other" : "MCPerks.Diagnose", command(other).getPerm());
        assertTrue(command(other).runCommand(self, arguments(other, "SyntheticTarget")));
        verify(self).sendMessage(contains("Permission denied"));
        // SimpleAPI may normalize the player argument before checking permission.
        // The diagnostic handler's exact lookup and all later work must still be denied.
        bukkit.verify(() -> Bukkit.getPlayerExact(anyString()), never());
        verifyNoInteractions(users, user);
        assertTrue(dispatches.isEmpty()); assertTrue(selfTasks.isEmpty()); assertTrue(targetTasks.isEmpty());
        assertUnchanged();
    }

    @Test void selfPermissionCannotAuthorizeInspectionOfAnotherPlayer() throws Exception {
        when(self.hasPermission("MCPerks.Diagnose.Other")).thenReturn(false);
        assertTrue(command(true).runCommand(self, arguments(true, "SyntheticTarget")));
        verify(self).sendMessage(contains("Permission denied"));
        assertTrue(dispatches.isEmpty());
        bukkit.verify(() -> Bukkit.getPlayerExact(anyString()), never());
        verifyNoInteractions(users);
        assertUnchanged();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void playerCommandsInspectOnlySelectedIdentityAndReplyOnRequester(boolean other) throws Exception {
        Player selected = other ? target : self;
        BlockingQueue<Runnable> selectedTasks = other ? targetTasks : selfTasks;
        invoke(self, other, "SyntheticTarget");
        verifyNoInteractions(users);
        verify(selected, never()).getWorld();
        take(selectedTasks).run();
        Runnable render = take(selectedTasks);
        verify(users).getMCPerksUser(selected.getUniqueId());
        verify(users, never()).getMCPerksUser((other ? self : target).getUniqueId());
        verify(selected, never()).getWorld();
        verify(self, never()).sendMessage(anyString());
        render.run();
        Runnable reply = take(selfTasks);
        verify(self, never()).sendMessage(anyString());
        reply.run();
        verify(self).sendMessage(contains("Perk decisions for " + selected.getName()));
        verify(self).sendMessage(contains("charges=7"));
        verify(self).sendMessage(contains("banked=9s"));
        if (other) {
            bukkit.verify(() -> Bukkit.getPlayerExact("SyntheticTarget"));
            verify(target, never()).sendMessage(anyString());
            verify(self).sendMessage(contains("Queue position=1"));
        } else bukkit.verifyNoInteractions();
        assertTrue(targetTasks.isEmpty()); assertTrue(selfTasks.isEmpty()); assertTrue(consoleTasks.isEmpty());
        assertUnchanged();
    }

    @Test void consoleOtherInspectionUsesTargetOwnerAndConsoleReplyScheduler() throws Exception {
        invoke(console, true, "SyntheticTarget");
        take(targetTasks).run();
        take(targetTasks).run();
        Runnable reply = take(consoleTasks);
        verify(console, never()).sendMessage(anyString());
        reply.run();
        verify(console).sendMessage(contains("Perk decisions for SyntheticTarget"));
        verify(target, never()).sendMessage(anyString());
        assertTrue(selfTasks.isEmpty()); assertTrue(targetTasks.isEmpty());
        assertUnchanged();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void missingOnlineTargetAndConsoleSelfRejectWithoutStorage(boolean other) throws Exception {
        invoke(console, other, "Missing");
        verify(console).sendMessage(contains("An online player is required"));
        if (other) bukkit.verify(() -> Bukkit.getPlayerExact("Missing"));
        else bukkit.verifyNoInteractions();
        verifyNoInteractions(users, user);
        assertTrue(targetTasks.isEmpty()); assertTrue(selfTasks.isEmpty()); assertTrue(consoleTasks.isEmpty());
        assertUnchanged();
    }

    @Test void disconnectBeforeRenderingCannotReportAnOnlineDiagnosis() throws Exception {
        invoke(self, true, "SyntheticTarget");
        take(targetTasks).run();
        Runnable render = take(targetTasks);
        when(target.isOnline()).thenReturn(false);
        render.run();
        take(selfTasks).run();
        verify(self).sendMessage(contains("Player is offline; banked time is paused"));
        verify(self, never()).sendMessage(contains("Perk decisions for"));
        verify(target, never()).getWorld();
        assertUnchanged();
    }

    @Test void wrongArityNeverFallsThroughIntoAnotherRegisteredDiagnosis() throws Exception {
        for (CommandHandler command : List.of(command(false), command(true))) {
            assertFalse(command.runCommand(self, new String[] {"Diagnose", "SyntheticTarget", "extra"}));
            assertFalse(command.runCommand(self, new String[] {"Other"}));
        }
        assertFalse(command(true).runCommand(self, new String[] {"Diagnose"}));
        assertFalse(command(false).runCommand(self, arguments(true, "SyntheticTarget")));
        assertTrue(dispatches.isEmpty()); bukkit.verifyNoInteractions(); verifyNoInteractions(users);
        assertUnchanged();
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(true);
        World world = mock(World.class); when(world.getName()).thenReturn("synthetic_world");
        when(player.getWorld()).thenReturn(world);
        return player;
    }

    private CommandHandler command(boolean other) {
        return commands.stream().filter(c -> c.getArgs()[0].equals("Diagnose")
                && c.getArgs().length == (other ? 2 : 1)).findFirst().orElseThrow();
    }

    private String[] arguments(boolean other, String name) {
        return other ? new String[] {"Diagnose", name} : new String[] {"Diagnose"};
    }

    private void invoke(CommandSender sender, boolean other, String name) throws Exception {
        assertTrue(command(other).runCommand(sender, arguments(other, name)));
        take(dispatches).run();
    }

    private Runnable take(BlockingQueue<Runnable> tasks) throws Exception {
        Runnable task = tasks.poll(5, TimeUnit.SECONDS);
        assertNotNull(task, "accepted command stage must reach its scheduler");
        return task;
    }

    private void assertUnchanged() throws Exception {
        state.storage(() -> null).get(5, TimeUnit.SECONDS);
        // A feature-worker barrier cannot observe a delayed mutation on the separate ledger worker.
        var worker = UsageCharges.class.getDeclaredField("worker");
        worker.setAccessible(true);
        ((ExecutorService) worker.get(charges)).submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertArrayEquals(featureBytes, Files.readAllBytes(directory.resolve("features.dat")));
        assertArrayEquals(chargeBytes, Files.readAllBytes(directory.resolve("charges.dat")));
        for (Player player : List.of(self, target)) {
            assertEquals(7, charges.get(player.getUniqueId(), UsageCharges.Kind.AutoBlockRefill));
            assertEquals(0, charges.successful(player.getUniqueId(), UsageCharges.Kind.AutoBlockRefill));
        }
        verify(user, never()).setActivations(anyInt());
        verify(user, never()).setActivations(anyString(), anyInt());
        verify(user, never()).setPerkCoolDown(any(Perk.class), anyLong());
        verify(user, never()).setPerkExperation(any(Perk.class), anyLong());
        verify(user, never()).setPerkTimesUsed(any(Perk.class), anyInt());
        assertFalse(mockingDetails(user).getInvocations().stream()
                .anyMatch(call -> call.getMethod().getName().startsWith("addActivation")));
        verify(server, never()).saveData();
        verify(planner, never()).check();
        verify(planner, never()).refreshLegacyView();
        assertFalse(mockingDetails(perks).getInvocations().stream()
                .anyMatch(call -> call.getMethod().getName().equals("runPerk")));
    }
}
