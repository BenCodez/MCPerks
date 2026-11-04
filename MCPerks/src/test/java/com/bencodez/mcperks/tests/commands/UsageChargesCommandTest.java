package com.bencodez.mcperks.tests.commands;

import static com.bencodez.mcperks.tests.TestAccess.newUsageCharges;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.commands.CommandLoader;
import com.bencodez.mcperks.data.DurableFeatureFiles;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.bencodez.mcperks.userapi.UsageCharges;
import com.bencodez.mcperks.userapi.UsageCharges.Kind;
import com.bencodez.mcperks.userapi.UserManager;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

/** Exercises registered handlers against the real asynchronous charge ledger. */
class UsageChargesCommandTest {
    @TempDir Path directory;
    private final UUID owner = UUID.randomUUID();
    private final AtomicBoolean block = new AtomicBoolean(), failAfterPublish = new AtomicBoolean();
    private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    private final BlockingQueue<Runnable> replies = new LinkedBlockingQueue<>();
    private final ArrayList<CommandHandler> commands = new ArrayList<>();
    private UsageCharges ledger;
    private MCPerksMain plugin;
    private UserManager users;
    private MCPerksUser user;
    private BukkitScheduler scheduler;
    private CommandSender console;

    @BeforeEach void setup() throws Exception {
        ledger = newUsageCharges(directory.resolve("charges.dat"), (staged, file) -> {
            if (block.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Publication test timeout");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
            }
            DurableFeatureFiles.publish(staged, file);
            if (failAfterPublish.compareAndSet(true, false)) throw new IOException("Synthetic acknowledgement failure");
        });
        ledger.set(owner, Kind.AutoBlockRefill, 5).get(5, TimeUnit.SECONDS);
        plugin = mock(MCPerksMain.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getCommands()).thenReturn(commands);
        when(plugin.getOptions()).thenReturn(mock(AdvancedCoreConfigOptions.class));
        when(plugin.getUsageCharges()).thenReturn(ledger);
        user = mock(MCPerksUser.class);
        when(user.getUUID()).thenReturn(owner.toString());
        users = mock(UserManager.class);
        when(users.getMCPerksUser("SyntheticPlayer")).thenReturn(user);
        when(plugin.getMcperksUserManager()).thenReturn(users);
        scheduler = mock(BukkitScheduler.class);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        doAnswer(call -> { replies.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        var loader = new CommandLoader(plugin);
        var register = CommandLoader.class.getDeclaredMethod("loadUsageCommands");
        register.setAccessible(true);
        register.invoke(loader);
        console = mock(CommandSender.class);
    }

    @AfterEach void close() {
        release.countDown();
        if (ledger != null) ledger.close();
    }

    @Test void registeredCommandsKeepSeparatePermissionsAndArgumentContracts() {
        assertEquals(3, commands.size());
        for (String name : List.of("GetUsageCharges", "SetUsageCharges", "AddUsageCharges")) {
            var command = command(name);
            assertEquals("MCPerks." + name, command.getPerm());
            assertArrayEquals(name.equals("GetUsageCharges") ? new String[] {name, "(effect)"}
                    : new String[] {name, "(player)", "(effect)", "(number)"}, command.getArgs());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"SetUsageCharges", "AddUsageCharges"})
    void managementSuccessWaitsForDurablePublicationAndScheduledReply(String name) throws Exception {
        block.set(true);
        command(name).execute(console, new String[] {name, "SyntheticPlayer", "AutoBlockRefill", "7"});
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(5, ledger.get(owner, Kind.AutoBlockRefill));
            assertTrue(replies.isEmpty());
            verify(console, never()).sendMessage(anyString());
        } finally { release.countDown(); }
        Runnable reply = replies.poll(5, TimeUnit.SECONDS);
        assertNotNull(reply);
        int expected = name.equals("SetUsageCharges") ? 7 : 12;
        assertEquals(expected, ledger.get(owner, Kind.AutoBlockRefill));
        verify(console, never()).sendMessage(anyString());
        reply.run();
        verify(console).sendMessage(contains("AutoBlockRefill charges: " + expected));
        assertTrue(replies.isEmpty());
        ledger.close();
        try (var recovered = new UsageCharges(directory.resolve("charges.dat"))) {
            recovered.ready().get(5, TimeUnit.SECONDS);
            assertEquals(expected, recovered.get(owner, Kind.AutoBlockRefill));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"SetUsageCharges", "AddUsageCharges"})
    void uncertainPublicationReportsFailureWithoutClaimingSuccess(String name) throws Exception {
        failAfterPublish.set(true);
        command(name).execute(console, new String[] {name, "SyntheticPlayer", "AutoBlockRefill", "7"});
        Runnable reply = replies.poll(5, TimeUnit.SECONDS);
        assertNotNull(reply);
        assertFalse(ledger.available());
        verify(console, never()).sendMessage(anyString());
        reply.run();
        verify(console).sendMessage(contains("Charge update failed"));
        verify(console, never()).sendMessage(contains("charges:"));
        verify(console, never()).sendMessage(contains("Synthetic acknowledgement failure"));
    }

    @Test void invalidManagementInputNeverChangesThePersonalBalance() throws Exception {
        var command = command("SetUsageCharges");
        command.execute(console, new String[] {"SetUsageCharges", "SyntheticPlayer", "MissingEffect", "7"});
        command.execute(console, new String[] {"SetUsageCharges", "SyntheticPlayer", "AutoBlockRefill", "not-a-number"});
        command.execute(console, new String[] {"SetUsageCharges", "SyntheticPlayer", "AutoBlockRefill", "2147483648"});
        command.execute(console, new String[] {"SetUsageCharges", "SyntheticPlayer", "AutoBlockRefill", "-1"});
        Runnable negativeReply = replies.poll(5, TimeUnit.SECONDS);
        assertNotNull(negativeReply);
        negativeReply.run();
        assertEquals(5, ledger.get(owner, Kind.AutoBlockRefill));
        verify(console, times(3)).sendMessage(contains("supported effect and nonnegative integer"));
        verify(console).sendMessage(contains("Charge update failed"));
        assertTrue(ledger.available());
    }

    @Test void personalReadUsesTheSenderUuidAndRejectsConsole() {
        var read = command("GetUsageCharges");
        read.execute(console, new String[] {"GetUsageCharges", "AutoBlockRefill"});
        verify(console).sendMessage(contains("Player only"));
        verify(console, never()).sendMessage(contains("charges:"));
        var player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(owner);
        read.execute(player, new String[] {"GetUsageCharges", "AutoBlockRefill"});
        verify(player).sendMessage(contains("AutoBlockRefill charges: 5"));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        read.execute(player, new String[] {"GetUsageCharges", "AutoBlockRefill"});
        verify(player).sendMessage(contains("AutoBlockRefill charges: 0"));
        assertEquals(5, ledger.get(owner, Kind.AutoBlockRefill));
    }

    @ParameterizedTest @ValueSource(strings = {"SetUsageCharges", "AddUsageCharges"})
    void predecessorCommandCannotMutateSuccessorAfterLookupReturns(String name) throws Exception {
        if (org.bukkit.Bukkit.getServer() == null) {
            var server = mock(org.bukkit.Server.class);
            when(server.getLogger()).thenReturn(java.util.logging.Logger.getLogger("charge-command-test"));
            org.bukkit.Bukkit.setServer(server);
        }
        var loader = new CommandLoader(plugin);
        var register = CommandLoader.class.getDeclaredMethod("registerFeatureArgumentMatchers");
        register.setAccessible(true);
        register.invoke(loader);
        // Native startup registers the SDK's ordinary player/number argument names.
        var completion = com.bencodez.simpleapi.command.TabCompleteHandler.getInstance();
        completion.addTabCompleteOption("(player)", "SyntheticPlayer");
        completion.addTabCompleteOption("(number)", "7");
        var invocations = new LinkedBlockingQueue<Runnable>();
        doAnswer(call -> { invocations.add(call.getArgument(1)); return null; })
                .when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        when(console.hasPermission("MCPerks." + name)).thenReturn(true);
        var lookupEntered = new CountDownLatch(1);
        var lookupRelease = new CountDownLatch(1);
        when(users.getMCPerksUser("SyntheticPlayer")).thenAnswer(call -> {
            lookupEntered.countDown();
            if (!lookupRelease.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lookup was not released");
            return user;
        });
        assertTrue(command(name).runCommand(console,
                new String[] {name, "SyntheticPlayer", "AutoBlockRefill", "7"}));
        Runnable invocation = invocations.poll(5, TimeUnit.SECONDS);
        assertNotNull(invocation);
        var running = java.util.concurrent.CompletableFuture.runAsync(invocation);
        try {
            assertTrue(lookupEntered.await(5, TimeUnit.SECONDS));
            ledger.close();
            try (var successor = new UsageCharges(directory.resolve("charges.dat"))) {
                successor.ready().get(5, TimeUnit.SECONDS);
                successor.set(owner, Kind.AutoBlockRefill, 23).get(5, TimeUnit.SECONDS);
                when(plugin.getUsageCharges()).thenReturn(successor);
                lookupRelease.countDown();
                running.get(5, TimeUnit.SECONDS);
                Runnable reply = replies.poll(5, TimeUnit.SECONDS);
                assertNotNull(reply);
                assertEquals(23, successor.get(owner, Kind.AutoBlockRefill));
                reply.run();
                verify(console).sendMessage(contains("Charge update failed"));
                verify(console, never()).sendMessage(contains("charges:"));
                assertTrue(replies.isEmpty());
            }
        } finally {
            lookupRelease.countDown();
            running.get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"SetUsageCharges", "AddUsageCharges"})
    void queuedPredecessorCommandKeepsItsRegistrationLedger(String name) throws Exception {
        CommandHandler predecessor = command(name);
        ledger.close();
        try (var successor = new UsageCharges(directory.resolve("charges.dat"))) {
            successor.ready().get(5, TimeUnit.SECONDS);
            successor.set(owner, Kind.AutoBlockRefill, 23).get(5, TimeUnit.SECONDS);
            when(plugin.getUsageCharges()).thenReturn(successor);
            predecessor.execute(console, new String[] {name, "SyntheticPlayer", "AutoBlockRefill", "7"});
            Runnable reply = replies.poll(5, TimeUnit.SECONDS);
            assertNotNull(reply);
            assertEquals(23, successor.get(owner, Kind.AutoBlockRefill));
            reply.run();
            verify(console).sendMessage(contains("Charge update failed"));
            verify(console, never()).sendMessage(contains("charges:"));
            assertTrue(replies.isEmpty());
        }
    }

    @Test void predecessorReadDoesNotReportRetiredLedgerAsCurrent() throws Exception {
        CommandHandler predecessor = command("GetUsageCharges");
        var player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(owner);
        ledger.close();
        try (var successor = new UsageCharges(directory.resolve("charges.dat"))) {
            successor.ready().get(5, TimeUnit.SECONDS);
            successor.set(owner, Kind.AutoBlockRefill, 23).get(5, TimeUnit.SECONDS);
            when(plugin.getUsageCharges()).thenReturn(successor);
            predecessor.execute(player, new String[] {"GetUsageCharges", "AutoBlockRefill"});
            verify(player).sendMessage(contains("Charge state is unavailable"));
            verify(player, never()).sendMessage(contains("charges:"));
            assertEquals(23, successor.get(owner, Kind.AutoBlockRefill));
        }
    }

    @ParameterizedTest
    @CsvSource({"SetUsageCharges,true", "AddUsageCharges,true", "SetUsageCharges,false", "AddUsageCharges,false"})
    void retirementRejectsCommandsBeforeOldLedgerDrains(String name, boolean replaced) throws Exception {
        UsageCharges successor = null;
        try {
            if (replaced) {
                successor = new UsageCharges(directory.resolve("charges.dat"));
                when(plugin.getUsageCharges()).thenReturn(successor);
                assertFalse(successor.ready().isDone());
            } else {
                when(plugin.isEnabled()).thenReturn(false);
            }
            command(name).execute(console, new String[] {name, "SyntheticPlayer", "AutoBlockRefill", "7"});
            Runnable reply = replies.poll(5, TimeUnit.SECONDS);
            assertNotNull(reply);
            ledger.add(owner, Kind.AutoBlockRefill, 0).get(5, TimeUnit.SECONDS);
            assertEquals(5, ledger.get(owner, Kind.AutoBlockRefill));
            reply.run();
            verify(console).sendMessage(contains("Charge update failed"));
            verify(console, never()).sendMessage(contains("charges:"));
            assertTrue(replies.isEmpty());
            ledger.close();
            if (successor != null) {
                successor.ready().get(5, TimeUnit.SECONDS);
                assertEquals(5, successor.get(owner, Kind.AutoBlockRefill));
            }
        } finally {
            ledger.close();
            if (successor != null) successor.close();
        }
    }

    private CommandHandler command(String name) {
        return commands.stream().filter(command -> command.getArgs()[0].equals(name)).findFirst().orElseThrow();
    }
}
