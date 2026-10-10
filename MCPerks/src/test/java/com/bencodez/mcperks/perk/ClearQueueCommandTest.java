package com.bencodez.mcperks.perk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.commands.CommandLoader;
import com.bencodez.mcperks.data.*;
import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

class ClearQueueCommandTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(booleans={true,false})
    void commandWaitsForDurablePublicationAndDoesNotReportFailedClearAsSuccess(boolean publicationFails) throws Exception {
        var reject = new java.util.concurrent.atomic.AtomicBoolean();
        try (FeatureState state = FeatureStateFixture.create(directory.resolve("features.dat"), (staged, target) -> {
            if (reject.get()) throw new java.io.IOException("fixture clear rejected");
            DurableFeatureFiles.publish(staged, target);
        })) {
            state.ready().get(5, TimeUnit.SECONDS);
            var entry = state.enqueue(UUID.randomUUID(), "perk", "", 1).get(5, TimeUnit.SECONDS);
            MCPerksMain plugin = mock(MCPerksMain.class); when(plugin.getFeatureState()).thenReturn(state);
            when(plugin.getName()).thenReturn("MCPerks");
            when(plugin.getOptions()).thenReturn(mock(com.bencodez.advancedcore.AdvancedCoreConfigOptions.class));
            QueuePlanner planner = mock(QueuePlanner.class); when(plugin.getQueuePlanner()).thenReturn(planner);
            PerkHandler handler = new PerkHandler(plugin, List.of()); when(plugin.getPerkHandler()).thenReturn(handler);
            var commands = new ArrayList<CommandHandler>(); when(plugin.getCommands()).thenReturn(commands);
            var scheduler = mock(BukkitScheduler.class); when(plugin.getBukkitScheduler()).thenReturn(scheduler);
            BlockingQueue<Runnable> replies = new LinkedBlockingQueue<>();
            doAnswer(i -> { replies.add(i.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            var loader = new CommandLoader(plugin);
            var registration = CommandLoader.class.getDeclaredMethod("loadClearQueueCommand"); registration.setAccessible(true); registration.invoke(loader);
            assertEquals(1, commands.size()); assertEquals("MCPerks.ClearQueue", commands.getFirst().getPerm());
            CommandSender sender = mock(CommandSender.class);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var blocked = state.storage(() -> { entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new RuntimeException(failure); } return null; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                commands.getFirst().execute(sender, new String[]{"ClearQueue"});
                verify(sender, never()).sendMessage(anyString()); assertTrue(replies.isEmpty());
                assertEquals(entry.id(), state.queue().getFirst().id()); reject.set(publicationFails);
            } finally { release.countDown(); }
            blocked.get(5, TimeUnit.SECONDS);
            Objects.requireNonNull(replies.poll(5, TimeUnit.SECONDS)).run();
            if (publicationFails) {
                verify(sender).sendMessage(contains("failed")); verify(sender, never()).sendMessage(contains("Cleared"));
                assertEquals(entry.id(), state.queue().getFirst().id());
            } else {
                verify(sender).sendMessage(contains("Cleared")); assertTrue(state.queue().isEmpty());
            }
        }
    }
}
