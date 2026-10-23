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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.commands.CommandLoader;
import com.bencodez.mcperks.data.DurableFeatureFiles;
import com.bencodez.mcperks.data.FeatureState;
import com.bencodez.mcperks.data.FeatureState.QueueEntry;
import com.bencodez.mcperks.perk.PerkHandler;
import com.bencodez.mcperks.perk.QueuePlanner;
import com.bencodez.mcperks.tests.data.FeatureStateFixture;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;
import com.bencodez.simpleapi.command.TabCompleteHandler;

/** Registered dispatcher and reply boundaries backed by the real durable queue. */
class QueueCommandAcceptanceTest {
    @TempDir Path directory;
    private final AtomicBoolean block = new AtomicBoolean(), failAfterPublish = new AtomicBoolean();
    private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    private final BlockingQueue<Runnable> dispatches = new LinkedBlockingQueue<>(), replies = new LinkedBlockingQueue<>();
    private final ArrayList<CommandHandler> commands = new ArrayList<>();
    private FeatureState state;
    private QueueEntry first, second;
    private MCPerksMain plugin;
    private QueuePlanner planner;
    private CommandSender console;
    private Player owner;
    private CompletableFuture<Map<UUID, Long>> estimates;

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
        first = state.enqueue(UUID.randomUUID(), "first", "PerkActivations_first", 1).get(5, TimeUnit.SECONDS);
        second = state.enqueue(UUID.randomUUID(), "second", "PerkActivations_second", 1).get(5, TimeUnit.SECONDS);
        plugin = mock(MCPerksMain.class);
        when(plugin.getCommands()).thenReturn(commands);
        when(plugin.getFeatureState()).thenReturn(state);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var options = mock(AdvancedCoreConfigOptions.class);
        when(options.getFormatNoPerms()).thenReturn("&cPermission denied");
        when(plugin.getOptions()).thenReturn(options);
        planner = mock(QueuePlanner.class);
        when(plugin.getQueuePlanner()).thenReturn(planner);
        estimates = new CompletableFuture<>();
        when(planner.estimatedWaits()).thenReturn(estimates);
        var perks = mock(PerkHandler.class);
        when(perks.getActivePerks()).thenReturn(new java.util.concurrent.CopyOnWriteArrayList<>());
        when(plugin.getPerkHandler()).thenReturn(perks);
        var scheduler = mock(BukkitScheduler.class);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        doAnswer(call -> { dispatches.add(call.getArgument(1)); return null; })
                .when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(call -> { replies.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        doAnswer(call -> { replies.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class), any(Player.class));
        var loader = new CommandLoader(plugin);
        // AdvancedCore.loadTabComplete() installs this shared wildcard before MCPerks commands.
        TabCompleteHandler.getInstance().addTabCompleteOption("(Boolean)", "True", "False");
        for (String method : List.of("registerFeatureArgumentMatchers", "loadPlannerCommands")) {
            var register = CommandLoader.class.getDeclaredMethod(method);
            register.setAccessible(true);
            register.invoke(loader);
        }
        console = mock(CommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        owner = mock(Player.class);
        when(owner.getUniqueId()).thenReturn(first.owner());
        when(owner.hasPermission(anyString())).thenReturn(true);
    }

    @AfterEach void close() throws Exception {
        release.countDown();
        if (state != null) {
            state.close();
            var field = FeatureState.class.getDeclaredField("worker");
            field.setAccessible(true);
            assertTrue(((ExecutorService) field.get(state)).awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"Queue", "CancelQueue", "QueueRemove", "QueuePause", "QueueMove"})
    void permissionDenialStopsActualDispatcherBeforeAnyStateOrWorkerAdmission(String name) throws Exception {
        byte[] saved = Files.readAllBytes(directory.resolve("features.dat"));
        assertEquals(name.equals("Queue") ? "MCPerks.Queue" : name.equals("CancelQueue")
                ? "MCPerks.CancelQueue" : "MCPerks.Queue.Manage", command(name).getPerm());
        when(owner.hasPermission(anyString())).thenReturn(false);
        assertTrue(command(name).runCommand(owner, arguments(name)));
        verify(owner).sendMessage(contains("Permission denied"));
        assertTrue(dispatches.isEmpty());
        state.storage(() -> null).get(5, TimeUnit.SECONDS);
        assertTrue(replies.isEmpty());
        assertArrayEquals(saved, Files.readAllBytes(directory.resolve("features.dat")));
        verifyNoInteractions(planner);
    }

    @Test void selfCancellationCannotRemoveAnotherOwnerButRemovesOnlyItsOwnReservedEntry() throws Exception {
        invoke(owner, "CancelQueue", second.id().toString());
        reply().run();
        verify(owner).sendMessage(contains("failed or entry unavailable"));
        assertEquals(List.of(first, second), state.queue());
        invoke(owner, "CancelQueue", first.id().toString());
        reply().run();
        verify(owner).sendMessage(contains("entry removed; consumed activations are not refunded"));
        assertEquals(List.of(second), state.queue());
        assertEquals(0, state.reserved(first.owner(), first.credit()));
        assertEquals(1, state.reserved(second.owner(), second.credit()));
        assertRecovered(List.of(second), false);
    }

    @Test void consoleSelfCancellationIsRejectedButStaffRemovalUsesItsManagePermission() throws Exception {
        invoke(console, "CancelQueue", first.id().toString());
        verify(console).sendMessage(contains("Player only"));
        assertTrue(replies.isEmpty());
        assertEquals(List.of(first, second), state.queue());
        assertEquals("MCPerks.CancelQueue", command("CancelQueue").getPerm());
        assertEquals("MCPerks.Queue.Manage", command("QueueRemove").getPerm());
        invoke(console, "QueueRemove", second.id().toString());
        reply().run();
        assertEquals(List.of(first), state.queue());
        verify(console).sendMessage(contains("entry removed"));
    }

    @Test void startingFenceSurvivesSelfAndStaffCancellationAndStaffReordering() throws Exception {
        assertNotNull(state.claim(first.id()).get(5, TimeUnit.SECONDS));
        var saved = state.queue();
        invoke(owner, "CancelQueue", first.id().toString()); reply().run();
        invoke(console, "QueueRemove", first.id().toString()); reply().run();
        invoke(console, "QueueMove", second.id().toString(), "1"); reply().run();
        assertEquals(saved, state.queue());
        verify(owner).sendMessage(contains("failed or entry unavailable"));
        verify(console, times(2)).sendMessage(contains("failed or entry unavailable"));
        verify(console, never()).sendMessage(contains("entry removed"));
        verify(console, never()).sendMessage(contains("order saved"));
    }

    @Test void uncertainFenceRequiresStaffAndIsNeverReportedAsRefunded() throws Exception {
        state.uncertain(first.id()).get(5, TimeUnit.SECONDS);
        invoke(owner, "CancelQueue", first.id().toString()); reply().run();
        assertEquals(FeatureState.Stage.UNCERTAIN, state.queue().getFirst().stage());
        invoke(console, "QueueRemove", first.id().toString()); reply().run();
        assertEquals(List.of(second), state.queue());
        verify(console).sendMessage(contains("consumed activations are not refunded"));
    }

    @Test void pauseAndResumePreserveWaitingReservationsAndPersistTheConfirmedFinalState() throws Exception {
        invoke(console, "QueuePause", "TRUE"); reply().run();
        assertTrue(state.paused());
        assertEquals(List.of(first, second), state.queue());
        invoke(console, "QueuePause", "FALSE"); reply().run();
        assertFalse(state.paused());
        assertEquals(1, state.reserved(first.owner(), first.credit()));
        assertEquals(1, state.reserved(second.owner(), second.credit()));
        verify(console, times(2)).sendMessage(contains("pause state saved"));
        assertRecovered(List.of(first, second), false);
    }

    @ParameterizedTest @ValueSource(strings = {"QueuePause", "QueueMove", "QueueRemove"})
    void staffSuccessWaitsForRealDurablePublicationAndItsScheduledReply(String name) throws Exception {
        block.set(true);
        invoke(console, arguments(name));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(first, second), state.queue());
            assertFalse(state.paused());
            assertTrue(replies.isEmpty());
            verify(console, never()).sendMessage(anyString());
        } finally { release.countDown(); }
        Runnable response = reply();
        verify(console, never()).sendMessage(anyString());
        response.run();
        List<QueueEntry> expected = name.equals("QueueMove") ? List.of(second, first)
                : name.equals("QueueRemove") ? List.of(first) : List.of(first, second);
        assertEquals(expected, state.queue());
        assertEquals(name.equals("QueuePause"), state.paused());
        verify(console).sendMessage(contains(name.equals("QueuePause") ? "pause state saved"
                : name.equals("QueueMove") ? "order saved" : "entry removed"));
        verify(planner).refreshLegacyView();
        verify(planner).check();
        assertRecovered(expected, name.equals("QueuePause"));
    }

    @ParameterizedTest @ValueSource(strings = {"QueuePause", "QueueMove", "QueueRemove"})
    void unknownPublicationReportsFailureAndNeverClaimsThePersistedActionSucceeded(String name) throws Exception {
        failAfterPublish.set(true);
        invoke(console, arguments(name));
        Runnable response = reply();
        assertFalse(state.available());
        verify(console, never()).sendMessage(anyString());
        response.run();
        verify(console).sendMessage(contains("failed or entry unavailable"));
        verify(console, never()).sendMessage(contains("saved"));
        verify(console, never()).sendMessage(contains("entry removed"));
        verify(console, never()).sendMessage(contains("Synthetic acknowledgement failure"));
        assertEquals(List.of(first, second), state.queue());
        assertFalse(state.paused());
    }

    @Test void malformedArgumentsAndWrongArityCannotMutateOrSchedulePlannerWork() throws Exception {
        byte[] saved = Files.readAllBytes(directory.resolve("features.dat"));
        invoke(console, "QueuePause", "maybe");
        invoke(console, "QueueMove", second.id().toString(), "0");
        invoke(console, "QueueMove", second.id().toString(), "2147483648");
        invoke(console, "QueueMove", "bad-id", "1");
        invoke(owner, "CancelQueue", "bad-id");
        assertFalse(command("QueueMove").runCommand(console, new String[] {"QueueMove", second.id().toString()}));
        assertFalse(command("QueuePause").runCommand(console, new String[] {"QueuePause", "true", "extra"}));
        assertTrue(dispatches.isEmpty());
        state.storage(() -> null).get(5, TimeUnit.SECONDS);
        assertTrue(replies.isEmpty());
        assertArrayEquals(saved, Files.readAllBytes(directory.resolve("features.dat")));
        verifyNoInteractions(planner);
    }

    @Test void queueDisplayIsReadOnlyAndKnownEstimatesAreRepliedOnlyThroughTheScheduler() throws Exception {
        byte[] saved = Files.readAllBytes(directory.resolve("features.dat"));
        when(planner.startUnavailable(first)).thenReturn("requires eligibility");
        invoke(console, "Queue");
        verify(console).sendMessage(contains("1. first requester=" + first.owner()));
        verify(console).sendMessage(contains("2. second requester=" + second.owner()));
        verify(console).sendMessage(contains("start=requires eligibility ETA=unknown"));
        verify(console).sendMessage(contains("Queue paused: false"));
        assertTrue(replies.isEmpty());
        estimates.complete(Map.of(second.id(), 7L));
        Runnable response = reply();
        verify(console, never()).sendMessage(contains("Estimated start"));
        response.run();
        verify(console).sendMessage(contains("Estimated start for " + second.id() + ": 7s"));
        verify(console, never()).sendMessage(contains("Estimated start for " + first.id()));
        state.storage(() -> null).get(5, TimeUnit.SECONDS);
        assertArrayEquals(saved, Files.readAllBytes(directory.resolve("features.dat")));
        verify(planner, never()).check();
        verify(planner, never()).refreshLegacyView();
    }

    private String[] arguments(String name) {
        return switch (name) {
            case "QueuePause" -> new String[] {name, "true"};
            case "QueueMove" -> new String[] {name, second.id().toString(), "1"};
            case "QueueRemove" -> new String[] {name, second.id().toString()};
            case "CancelQueue" -> new String[] {name, first.id().toString()};
            default -> new String[] {name};
        };
    }

    private void invoke(CommandSender sender, String... args) throws Exception {
        assertTrue(command(args[0]).runCommand(sender, args));
        Runnable task = dispatches.poll(5, TimeUnit.SECONDS);
        assertNotNull(task, "accepted command must reach the registered asynchronous dispatcher");
        task.run();
    }

    private Runnable reply() throws Exception {
        Runnable reply = replies.poll(5, TimeUnit.SECONDS);
        assertNotNull(reply, "durable completion must schedule a reply");
        return reply;
    }

    private CommandHandler command(String name) {
        return commands.stream().filter(c -> c.getArgs()[0].equals(name)).findFirst().orElseThrow();
    }

    private void assertRecovered(List<QueueEntry> expected, boolean paused) throws Exception {
        state.close();
        var field = FeatureState.class.getDeclaredField("worker"); field.setAccessible(true);
        assertTrue(((ExecutorService) field.get(state)).awaitTermination(5, TimeUnit.SECONDS));
        var recovered = new FeatureState(directory.resolve("features.dat"));
        try {
            recovered.ready().get(5, TimeUnit.SECONDS);
            assertEquals(expected, recovered.queue());
            assertEquals(paused, recovered.paused());
        } finally {
            recovered.close();
            assertTrue(((ExecutorService) field.get(recovered)).awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
