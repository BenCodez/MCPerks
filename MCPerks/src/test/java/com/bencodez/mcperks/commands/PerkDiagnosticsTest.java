package com.bencodez.mcperks.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.data.FeatureState;
import com.bencodez.mcperks.data.ServerData;
import com.bencodez.mcperks.perk.*;
import com.bencodez.mcperks.userapi.*;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

class PerkDiagnosticsTest {
    @TempDir Path directory;

    private Perk perk(String name, UUID owner, boolean charged, Effect effect) {
        Perk perk = mock(Perk.class);
        when(perk.getPerk()).thenReturn(name);
        doCallRealMethod().when(perk).isActivationLimitReached(anyInt());
        when(perk.getPerkType()).thenReturn(PerkSystemType.PLAYER);
        when(perk.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(owner.toString())));
        when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(effect)));
        when(perk.isNotInDisabledWorld(anyString())).thenReturn(true);
        when(perk.getInteractionOptions()).thenReturn(new InteractionOptions(charged, false, 16, 8,
                List.of(), List.of(), List.of(), List.of()));
        return perk;
    }

    private enum ReloadedPolicy { WORLD_BLOCKED, LIMIT_REACHED, WORLD_ALLOWED }
    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> reloadedPolicies() {
        return Arrays.stream(ReloadedPolicy.values()).flatMap(policy -> java.util.stream.Stream.of(false, true)
                .map(charged -> org.junit.jupiter.params.provider.Arguments.of(charged, policy)));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("reloadedPolicies")
    void explainUsesRetainedGameplayOptionsAfterReloadAndStaysReadOnly(boolean retainedCharged, ReloadedPolicy policy) throws Exception {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        try (FeatureState state = new FeatureState(directory.resolve("features.dat"));
             UsageCharges charges = new UsageCharges(directory.resolve("charges.dat"))) {
            state.ready().get(5, TimeUnit.SECONDS); charges.ready().get(5, TimeUnit.SECONDS);
            charges.set(owner, UsageCharges.Kind.AutoBlockRefill, 7).get(5, TimeUnit.SECONDS);
            MCPerksMain plugin = mock(MCPerksMain.class);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getFeatureState()).thenReturn(state); when(plugin.getUsageCharges()).thenReturn(charges);
            when(plugin.getConfigFile()).thenReturn(mock(com.bencodez.mcperks.configs.Config.class));
            ServerData server = mock(ServerData.class); when(server.getData()).thenReturn(new YamlConfiguration());
            when(plugin.getMcperksServerData()).thenReturn(server);
            UserManager users = mock(UserManager.class); MCPerksUser user = mock(MCPerksUser.class);
            when(users.getMCPerksUser(owner)).thenReturn(user); when(plugin.getMcperksUserManager()).thenReturn(users);
            Perk retained = perk("refill", owner, retainedCharged, Effect.AutoBlockRefill);
            // Reload can change both options and the effect list; the running clone is authoritative.
            Perk reloaded = perk("refill", owner, !retainedCharged, Effect.NoFallDamage);
            when(retained.isNotInDisabledWorld("world")).thenReturn(policy != ReloadedPolicy.WORLD_ALLOWED);
            when(reloaded.isNotInDisabledWorld("world")).thenReturn(policy != ReloadedPolicy.WORLD_BLOCKED);
            when(reloaded.getLimit()).thenReturn(policy == ReloadedPolicy.LIMIT_REACHED ? 3 : 0);
            when(user.getPerkTimesUsed(reloaded)).thenReturn(policy == ReloadedPolicy.LIMIT_REACHED ? 3 : 0);
            // Run the same limit predicate used by actual new activation requests.
            doCallRealMethod().when(reloaded).checkTimesUsed(user);

            Perk unrelated = perk("other", other, true, Effect.AutoBlockRefill);
            Perk configuredOther = perk("other", owner, false, Effect.NoFallDamage);
            PerkHandler handler = mock(PerkHandler.class);
            when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>(List.of(retained, unrelated)));
            when(handler.getLoadedPerks()).thenReturn(Map.of("refill", reloaded, "other", configuredOther));
            when(handler.effectDecision(any(), anyString(), anyString())).thenReturn(PerkHandler.Decision.ACTIVE);
            when(plugin.getPerkHandler()).thenReturn(handler);
            var effects = mock(com.bencodez.mcperks.listeners.UsageEffectListener.class);
            when(plugin.getUsageEffects()).thenReturn(effects);
            Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(owner);
            when(player.isOnline()).thenReturn(true); when(player.getName()).thenReturn("Tester");
            World world = mock(World.class); when(world.getName()).thenReturn("world"); when(player.getWorld()).thenReturn(world);
            when(player.hasPermission(anyString())).thenReturn(true);
            CommandSender sender = mock(CommandSender.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class); when(plugin.getBukkitScheduler()).thenReturn(scheduler);
            BlockingQueue<Runnable> ownerTasks = new LinkedBlockingQueue<>(), replies = new LinkedBlockingQueue<>();
            doAnswer(i -> { ownerTasks.add(i.getArgument(1)); return null; }).when(scheduler)
                    .runTask(eq(plugin), any(Runnable.class), eq(player));
            doAnswer(i -> { replies.add(i.getArgument(1)); return null; }).when(scheduler)
                    .runTask(eq(plugin), any(Runnable.class));
            List<String> messages = new ArrayList<>();
            doAnswer(i -> { messages.add(i.getArgument(0)); return null; }).when(sender).sendMessage(anyString());
            new PerkDiagnostics(plugin).explain(sender, player);
            Runnable render = Objects.requireNonNull(ownerTasks.poll(5, TimeUnit.SECONDS));
            verify(player, never()).getWorld(); verify(sender, never()).sendMessage(anyString());
            render.run(); Objects.requireNonNull(replies.poll(5, TimeUnit.SECONDS)).run();
            assertTrue(messages.stream().anyMatch(s -> s.startsWith("refill (retained active grant)")));
            String activation = messages.stream().filter(s -> s.startsWith("refill (retained active grant)")).findFirst().orElseThrow();
            String expected = policy == ReloadedPolicy.WORLD_BLOCKED ? "disabled world"
                    : policy == ReloadedPolicy.LIMIT_REACHED ? "activation limit" : "activation checks passed";
            assertTrue(activation.contains(expected), activation);
            assertEquals(policy != ReloadedPolicy.LIMIT_REACHED, reloaded.checkTimesUsed(user));

            assertFalse(messages.stream().anyMatch(s -> s.startsWith("refill (configured definition)")));
            assertTrue(messages.stream().anyMatch(s -> s.startsWith("other (configured definition)")));
            String refill = messages.stream().filter(s -> s.startsWith("  AutoBlockRefill:")).findFirst().orElseThrow();
            assertTrue(refill.contains(retainedCharged ? "charges=7" : "unlimited"), refill);
            assertFalse(refill.contains(retainedCharged ? "unlimited" : "charges="), refill);
            verify(reloaded, never()).getInteractionOptions();
            assertEquals(7, charges.get(owner, UsageCharges.Kind.AutoBlockRefill));
            assertEquals(0, charges.successful(owner, UsageCharges.Kind.AutoBlockRefill));
            assertTrue(state.queue().isEmpty()); assertTrue(state.banks().isEmpty());
            verify(user, never()).setActivations(anyInt()); verify(server, never()).saveData();
        }
    }
    private enum DuplicateCase { OWN_PERSONAL, OTHER_PERSONAL, GLOBAL_RETAINED, PAUSED_BANK, ZERO_BANK, LEGACY_WITH_OLD_BANK }
    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> duplicateCases() {
        return Arrays.stream(DuplicateCase.values()).flatMap(kind -> java.util.stream.Stream.of(false, true)
                .map(allowed -> org.junit.jupiter.params.provider.Arguments.of(kind, allowed)));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("duplicateCases")
    void explainMatchesProductionDuplicateChecksForRetainedGrantsAndBanks(DuplicateCase kind, boolean allowed) throws Exception {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        Path file = directory.resolve("duplicates.dat"); MCPerksMain previous = MCPerksMain.plugin;
        try (FeatureState state = new FeatureState(file); UsageCharges charges = new UsageCharges(directory.resolve("duplicate-charges.dat"))) {
            state.ready().get(5, TimeUnit.SECONDS); charges.ready().get(5, TimeUnit.SECONDS);
            MCPerksMain plugin = mock(MCPerksMain.class); MCPerksMain.plugin = plugin;
            when(plugin.isEnabled()).thenReturn(true); when(plugin.getFeatureState()).thenReturn(state);
            when(plugin.getUsageCharges()).thenReturn(charges);
            var config = mock(com.bencodez.mcperks.configs.Config.class, CALLS_REAL_METHODS);
            var configData = new YamlConfiguration(); configData.set("LimitActivations", true);
            // Exercise the actual optional configuration getter, including its disabled default.
            if (allowed || kind == DuplicateCase.OWN_PERSONAL) configData.set("DuplicatePerksActive", allowed);
            doReturn(configData).when(config).getData(); when(plugin.getConfigFile()).thenReturn(config);
            assertEquals(allowed, config.getAllowDuplicatePerksActive());
            ServerData server = mock(ServerData.class); when(server.getData()).thenReturn(new YamlConfiguration());
            when(server.isReady()).thenReturn(true); when(plugin.getMcperksServerData()).thenReturn(server);
            MCPerksUser user = mock(MCPerksUser.class), foreign = mock(MCPerksUser.class);
            when(user.getUUID()).thenReturn(owner.toString()); when(user.getPlayerName()).thenReturn("Tester");
            when(user.getActivations()).thenReturn(5); when(user.getActivations("passive")).thenReturn(7);
            when(foreign.getUUID()).thenReturn(other.toString()); when(foreign.getPlayerName()).thenReturn("Other");
            UserManager users = mock(UserManager.class); when(users.getMCPerksUser(owner)).thenReturn(user);
            when(plugin.getMcperksUserManager()).thenReturn(users);
            PerkHandler handler = mock(PerkHandler.class); when(plugin.getPerkHandler()).thenReturn(handler);
            when(handler.isRestorationReady()).thenReturn(true);
            var active = new CopyOnWriteArrayList<Perk>(); when(handler.getActivePerks()).thenReturn(active);
            when(handler.effectDecision(any(), anyString(), anyString())).thenReturn(PerkHandler.Decision.ACTIVE);
            var banked = new BankedPerks(plugin, state); when(plugin.getBankedPerks()).thenReturn(banked);
            boolean bankMode = kind == DuplicateCase.PAUSED_BANK || kind == DuplicateCase.ZERO_BANK;
            var yaml = new YamlConfiguration();
            yaml.loadFromString("Enabled: true\nPerkType: PLAYER\nTimeLasts: 10\nEffects: [NoFallDamage]\nBankedTime:\n  Enabled: " + bankMode + "\n");
            var perkConfig = mock(com.bencodez.mcperks.configs.ConfigPerks.class, CALLS_REAL_METHODS);
            doReturn(yaml).when(perkConfig).getData("passive");
            doReturn(new ArrayList<String>()).when(perkConfig).getPerkDisabledWorlds("passive");
            doReturn("duplicate blocked").when(perkConfig).getPerkAlreayActive("passive");
            try (var configs = mockStatic(com.bencodez.mcperks.configs.ConfigPerks.class)) {
                configs.when(com.bencodez.mcperks.configs.ConfigPerks::getInstance).thenReturn(perkConfig);
                Perk definition = new Perk("passive");
                when(handler.getLoadedPerks()).thenReturn(Map.of("passive", definition));
                when(handler.getPerk("passive")).thenReturn(definition);
                boolean retained = kind == DuplicateCase.OWN_PERSONAL || kind == DuplicateCase.GLOBAL_RETAINED;
                if (retained || kind == DuplicateCase.OTHER_PERSONAL) {
                    Perk clone = new Perk(definition); clone.setActivater(kind == DuplicateCase.OWN_PERSONAL ? user : foreign);
                    clone.getEffectedPlayers().add((retained ? owner : other).toString()); clone.setActive(true);
                    if (kind == DuplicateCase.GLOBAL_RETAINED) {
                        var type = Perk.class.getDeclaredField("perkType"); type.setAccessible(true); type.set(clone, PerkSystemType.ALL);
                    }
                    active.add(clone);
                }
                var key = new FeatureState.BankKey(owner, "passive");
                if (kind == DuplicateCase.PAUSED_BANK || kind == DuplicateCase.ZERO_BANK || kind == DuplicateCase.LEGACY_WITH_OLD_BANK) {
                    state.setBank(key, kind == DuplicateCase.ZERO_BANK ? 0 : 10000).get(5, TimeUnit.SECONDS);
                    state.pauseBank(key, true).get(5, TimeUnit.SECONDS);
                }
                boolean productionDuplicate = (definition.isPerkActive(user) || definition.getInteractionOptions().banked()
                        && banked.hasEntitlement(owner, definition)) && !allowed;
                assertEquals(!allowed && (kind == DuplicateCase.OWN_PERSONAL || kind == DuplicateCase.GLOBAL_RETAINED
                        || kind == DuplicateCase.PAUSED_BANK), productionDuplicate);
                byte[] before = java.nio.file.Files.exists(file) ? java.nio.file.Files.readAllBytes(file) : null;
                Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(owner);
                when(player.isOnline()).thenReturn(true); when(player.getName()).thenReturn("Tester");
                World world = mock(World.class); when(world.getName()).thenReturn("world"); when(player.getWorld()).thenReturn(world);
                when(player.hasPermission(anyString())).thenReturn(true);
                CommandSender sender = mock(CommandSender.class);
                var effects = mock(com.bencodez.mcperks.listeners.UsageEffectListener.class); when(plugin.getUsageEffects()).thenReturn(effects);
                BukkitScheduler scheduler = mock(BukkitScheduler.class); when(plugin.getBukkitScheduler()).thenReturn(scheduler);
                BlockingQueue<Runnable> ownerTasks = new LinkedBlockingQueue<>(), replies = new LinkedBlockingQueue<>();
                doAnswer(i -> { ownerTasks.add(i.getArgument(1)); return null; }).when(scheduler)
                        .runTask(eq(plugin), any(Runnable.class), eq(player));
                doAnswer(i -> { replies.add(i.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
                List<String> messages = new ArrayList<>(); doAnswer(i -> { messages.add(i.getArgument(0)); return null; })
                        .when(sender).sendMessage(anyString());
                new PerkDiagnostics(plugin).explain(sender, player);
                Runnable render = Objects.requireNonNull(ownerTasks.poll(5, TimeUnit.SECONDS));
                verify(player, never()).getWorld(); verify(sender, never()).sendMessage(anyString());
                render.run(); Objects.requireNonNull(replies.poll(5, TimeUnit.SECONDS)).run();
                String activation = messages.stream().filter(line -> line.startsWith("passive (")).findFirst().orElseThrow();
                assertTrue(activation.contains(retained ? "retained active grant" : "configured definition"), activation);
                assertTrue(activation.contains(productionDuplicate ? "already active; duplicate activations disabled" : "activation checks passed"), activation);
                assertEquals(before == null, !java.nio.file.Files.exists(file));
                if (before != null) assertArrayEquals(before, java.nio.file.Files.readAllBytes(file));
                assertEquals(0, charges.get(owner, UsageCharges.Kind.AutoBlockRefill));
                assertEquals(0, charges.successful(owner, UsageCharges.Kind.AutoBlockRefill));
                verify(user, never()).setActivations(anyInt()); verify(user, never()).addActivation(anyInt());
                verify(user, never()).addActivation(anyString(), anyInt()); verify(server, never()).saveData();
                // The rejected production request reaches the identical predicate without
                // consuming credits or scheduling an effect. Allowed requests remain read-only here.
                if (productionDuplicate) {
                    definition.runPerk(user); verify(user).sendMessage("duplicate blocked");
                    verify(user, never()).addActivation(anyInt()); verify(user, never()).addActivation(anyString(), anyInt());
                    if (before != null) assertArrayEquals(before, java.nio.file.Files.readAllBytes(file));
                }
            }
        } finally { MCPerksMain.plugin = previous; }
    }

}
