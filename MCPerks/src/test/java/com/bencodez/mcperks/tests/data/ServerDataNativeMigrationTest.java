package com.bencodez.mcperks.tests.data;
import static com.bencodez.mcperks.tests.TestAccess.*;
import com.bencodez.mcperks.data.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.advancedcore.api.permissions.PermissionHandler;
import com.bencodez.mcperks.MCPerksMain;

/** Uses the real shaded-library permission lifecycle and actual ordered YAML publication. */
class ServerDataNativeMigrationTest {
    @TempDir Path directory;
    private MCPerksMain plugin;
    private OrderedStorage state;
    private ServerData runtime;
    private OrderedServerDataFile ordered;
    private com.bencodez.advancedcore.data.ServerData originalFile;
    private YamlConfiguration live;
    private com.bencodez.mcperks.tests.scheduling.BundledOwnerTaskHarness owners;
    private boolean ownerContext;
    private int maximumOwnerCallbacks;
    private final List<PermissionHandler> nativeHandlers = new ArrayList<>();
    private Path file() { return directory.resolve("ServerData.yml"); }
    private <T> T await(CompletableFuture<T> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!future.isDone() && System.nanoTime() < deadline) {
            if (owners != null) maximumOwnerCallbacks = Math.max(maximumOwnerCallbacks, owners.tasks.size());
            Runnable task = owners == null ? null : owners.tasks.poll(1, TimeUnit.MILLISECONDS);
            if (task != null) onOwner(task);
        }
        return future.get(1, TimeUnit.SECONDS);
    }
    private void onOwner(Runnable task) {
        boolean previous = ownerContext; ownerContext = true;
        try { task.run(); } finally { ownerContext = previous; }
    }
    @BeforeEach void setup() throws Exception {
        if (Bukkit.getServer() == null) {
            var server = mock(org.bukkit.Server.class); when(server.getLogger()).thenReturn(Logger.getLogger("test")); Bukkit.setServer(server);
        }
        plugin = mock(MCPerksMain.class); when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getName()).thenReturn("MCPerks"); when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("MCPerks", "next", "test.Main"));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test")); when(plugin.isEnabled()).thenReturn(true);
        owners = new com.bencodez.mcperks.tests.scheduling.BundledOwnerTaskHarness(plugin, false);
        var scheduler = plugin.getBukkitScheduler();
        doAnswer(call -> { owners.tasks.add(call.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        live = new YamlConfiguration();
        var original = mock(com.bencodez.advancedcore.data.ServerData.class); originalFile = original;
        when(original.getData()).thenReturn(live); when(original.getdFile()).thenReturn(file().toFile());
        state = new OrderedStorage(directory.resolve("features.dat")); await(state.ready()); when(plugin.getStorage()).thenReturn(state);
        runtime = new ServerData(plugin); when(plugin.getMcperksServerData()).thenReturn(runtime);
        try (var constructors = mockConstruction(com.bencodez.simpleapi.scheduler.BukkitScheduler.class)) {
            ordered = new OrderedServerDataFile(plugin, original, runtime);
        }
        when(plugin.getServerDataFile()).thenAnswer(call -> {
            runtime.observeNativePermissionSource(live); return ordered;
        });
    }
    @AfterEach void close() {
        for (var handler : nativeHandlers) handler.getTimer().shutdownNow();
        state.close();
    }
    /** Run bundled setup and retain its actual failedToRead flag; only timer scheduling is stubbed. */
    private com.bencodez.advancedcore.data.ServerData setupNativeFile() { return setupNativeFile(false); }
    private com.bencodez.advancedcore.data.ServerData setupNativeFile(boolean beforeOrderedStorage) {
        com.bencodez.advancedcore.data.ServerData nativeFile;
        try (var constructors = mockConstruction(com.bencodez.simpleapi.scheduler.BukkitScheduler.class)) {
            nativeFile = new com.bencodez.advancedcore.data.ServerData(plugin);
            nativeFile.setup();
            originalFile = nativeFile; live = (YamlConfiguration) nativeFile.getData();
            runtime = new ServerData(plugin); when(plugin.getMcperksServerData()).thenReturn(runtime);
            if (beforeOrderedStorage) when(plugin.getStorage()).thenReturn(null);
            ordered = new OrderedServerDataFile(plugin, nativeFile, runtime);
            if (beforeOrderedStorage) when(plugin.getStorage()).thenReturn(state);
        }
        return nativeFile;
    }
    @ParameterizedTest @ValueSource(strings = {"new", "existing-empty", "comment-only", "empty-map"})
    void actualNativeEmptySetupInitializesAndRestartsWithConfirmedPersistence(String source) throws Exception {
        switch (source) {
            case "new" -> assertFalse(Files.exists(file()));
            case "existing-empty" -> Files.writeString(file(), "");
            case "comment-only" -> Files.writeString(file(), "# Valid empty ServerData\n");
            case "empty-map" -> Files.writeString(file(), "{}\n");
            default -> fail("unknown source");
        }
        var nativeFile = setupNativeFile(true);
        byte[] initialBytes = Files.readAllBytes(file());
        ordered.setLastUpdated(); // Real native TimeChecker order: mutation/save before initialize.
        assertTrue(live.getLong("LastUpdated") > 0); assertArrayEquals(initialBytes, Files.readAllBytes(file()));
        assertEquals(source.equals("new"), nativeFile.isJustCreated());
        assertTrue(nativeFile.isFailedToRead(), "Native setup flags a valid empty root as unreadable");
        assertEquals(Set.of("LastUpdated"), nativeFile.getData().getKeys(false));
        await(runtime.initialize(state));
        assertTrue(disk().getLong("LastUpdated") > 0); assertTrue(runtime.isReady());
        // Real wrapper writes after startup must now reach the ordered file owner.
        ordered.setPluginVersion(plugin);
        await(runtime.confirmedActivation(() -> runtime.setServerCooldown("fresh", 1234)));
        assertEquals("next", disk().getString("PluginVersions.MCPerks"));
        assertEquals(1234, disk().getLong("fresh.CoolDown"));
        assertTrue(Files.readString(file()).startsWith("# MCPerks ordered ServerData v1\n"));
        state.close(); state = new OrderedStorage(directory.resolve("features.dat"));
        await(state.ready()); when(plugin.getStorage()).thenReturn(state);
        var restarted = setupNativeFile();
        assertFalse(restarted.isJustCreated()); assertFalse(restarted.isFailedToRead());
        await(runtime.initialize(state)); assertTrue(runtime.isReady());
        assertEquals(1234, runtime.getServerCooldown("fresh"));
        await(runtime.confirmedActivation(() -> runtime.setServerCooldown("fresh", 5678)));
        assertEquals(5678, disk().getLong("fresh.CoolDown"));
    }
    @Test void actualNativeReadableNonemptyLegacySetupRetainsFields() throws Exception {
        Files.writeString(file(), "PrevDay: 9\nlegacy:\n  CoolDown: 5678\n");
        var nativeFile = setupNativeFile(); assertFalse(nativeFile.isFailedToRead());
        await(runtime.initialize(state)); assertTrue(runtime.isReady());
        assertEquals(9, disk().getInt("PrevDay")); assertEquals(5678, runtime.getServerCooldown("legacy"));
    }
    @ParameterizedTest @ValueSource(strings = {"malformed", "unreadable-directory", "symlink"})
    void actualNativeReadFailureCannotBecomeAnEmptySuccessfulMigration(String source) throws Exception {
        Path target = directory.resolve("target.yml");
        switch (source) {
            case "malformed" -> Files.writeString(file(), "broken: [unterminated\n");
            case "unreadable-directory" -> Files.createDirectory(file());
            case "symlink" -> { Files.writeString(target, "PrevDay: 9\n"); Files.createSymbolicLink(file(), target); }
            default -> fail("unknown source");
        }
        byte[] original = source.equals("unreadable-directory") ? null : Files.readAllBytes(file());
        var nativeFile = setupNativeFile(true);
        ordered.setLastUpdated();
        if (!source.equals("symlink")) {
            assertTrue(nativeFile.isFailedToRead()); assertEquals(Set.of("LastUpdated"), nativeFile.getData().getKeys(false));
        }
        assertThrows(ExecutionException.class, () -> await(runtime.initialize(state))); assertFalse(runtime.isReady());
        runtime.saveData();
        if (source.equals("unreadable-directory")) assertTrue(Files.isDirectory(file()));
        else assertArrayEquals(original, Files.readAllBytes(file()));
        if (source.equals("symlink")) assertTrue(Files.isSymbolicLink(file()));
    }
    @SuppressWarnings("unchecked")
    private Map<String, Long> nativePending(PermissionHandler handler, UUID owner) throws Exception {
        var handle = handler.getPermsToAdd().get(owner);
        if (handle == null) return Map.of();
        var method = com.bencodez.advancedcore.api.permissions.PlayerPermissionHandler.class.getDeclaredMethod("offlinePermissionSnapshot");
        assertTrue(method.trySetAccessible()); return (Map<String, Long>) method.invoke(handle);
    }
    private void legacy(YamlConfiguration yaml) throws Exception {
        String text = yaml.saveToString(); Files.writeString(file(), text, StandardCharsets.UTF_8); live.loadFromString(text);
    }
    private PermissionHandler restoreNative() {
        var handler = new PermissionHandler(plugin); nativeHandlers.add(handler);
        runtime.observeNativePermissionHandler(handler); // Actual MCPerks getter captures before its first external mutation.
        when(plugin.getPermissionHandler()).thenReturn(handler); return handler;
    }
    private void login(PermissionHandler handler, UUID owner) {
        var player = mock(org.bukkit.entity.Player.class); when(player.getUniqueId()).thenReturn(owner); when(player.isValid()).thenReturn(true);
        var attachment = mock(org.bukkit.permissions.PermissionAttachment.class);
        when(attachment.getPermissions()).thenReturn(new HashMap<>()); when(player.addAttachment(plugin)).thenReturn(attachment);
        handler.login(player); assertTrue(handler.getPerms().containsKey(owner));
    }
    private YamlConfiguration disk() { return YamlConfiguration.loadConfiguration(file().toFile()); }
    private YamlConfiguration permissionRows(UUID owner, String permission, long expiry) {
        var yaml = new YamlConfiguration(); yaml.set("TimedPermissions." + owner, List.of(permission + "%line%" + expiry));
        yaml.set("Experation.legacy", 1234); yaml.set("legacy.CoolDown", 5678); yaml.set("PrevDay", 9); return yaml;
    }
    @Test void nativeRestoreDelayedVersionSaveRevocationAndShutdownNeverRegrantClearedDiskRows() throws Exception {
        UUID revoked = UUID.randomUUID(), retained = UUID.randomUUID();
        var yaml = permissionRows(revoked, "perk.revoked", System.currentTimeMillis() + 600_000);
        yaml.set("TimedPermissions." + retained, List.of("perk.retained%line%" + (System.currentTimeMillis() + 600_000)));
        legacy(yaml); var handler = restoreNative();
        assertTrue(handler.getPermsToAdd().containsKey(revoked)); assertNull(live.get("TimedPermissions"));
        login(handler, retained);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocker = state.runtimeStorage(() -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch (InterruptedException failure) { throw new CompletionException(failure); } return null;
        });
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS)); var initialization = runtime.initialize(state);
            ordered.setPluginVersion(plugin); // Actual native version mutation/save while canonical load is pending.
            assertTrue(disk().isConfigurationSection("TimedPermissions")); assertFalse(initialization.isDone());
            release.countDown(); await(blocker); await(initialization);
        } finally { release.countDown(); }
        assertNull(disk().get("TimedPermissions")); assertEquals("next", disk().getString("PluginVersions.MCPerks"));
        handler.removePermission(revoked, null, "perk.revoked");
        assertFalse(handler.getPermsToAdd().containsKey(revoked)); handler.shutDown(); await(state.runtimeStorage(() -> null));
        assertTrue(disk().getStringList("TimedPermissions." + revoked).isEmpty());
        assertFalse(disk().getStringList("TimedPermissions." + retained).isEmpty());
        live.loadFromString(Files.readString(file())); var restarted = restoreNative();
        assertFalse(restarted.getPermsToAdd().containsKey(revoked)); assertTrue(restarted.getPermsToAdd().containsKey(retained));
    }
    @Test void actualAsyncFinalPermissionSaveUsesOrderedOwnerAndRetainsPendingNodesWithoutRevocationReplay() throws Exception {
        UUID retained = UUID.randomUUID(), revoked = UUID.randomUUID(), online = UUID.randomUUID();
        long now = System.currentTimeMillis();
        var yaml = permissionRows(retained, "perk.retained", now + 600_000);
        yaml.set("TimedPermissions." + revoked, List.of("perk.revoked%line%" + (now + 600_000)));
        yaml.set("TimedPermissions." + online, List.of("perk.online%line%" + (now + 600_000)));
        legacy(yaml); var handler = restoreNative(); login(handler, online);
        await(runtime.initialize(state));
        handler.removePermission(revoked, null, "perk.revoked");
        handler.addPermission(retained, "perk.retained", com.bencodez.simpleapi.time.ParsedDuration.ofMillis(1_200_000));
        assertTrue(nativePending(handler, retained).containsKey("perk.retained"));
        byte[] before = Files.readAllBytes(file());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocker = state.runtimeStorage(() -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch (InterruptedException failure) { throw new CompletionException(failure); }
            return null;
        });
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var finalWorker = new ArrayDeque<Runnable>();
            var finalFiles = handler.shutDownAsync(finalWorker::add).toCompletableFuture();
            assertTrue(handler.getTimer().isShutdown());
            assertFalse(finalFiles.isDone()); assertEquals(1, finalWorker.size());
            var ownerField = MCPerksMain.class.getDeclaredField("storage"); ownerField.setAccessible(true); ownerField.set(plugin, state);
            var preparationField = MCPerksMain.class.getDeclaredField("shutdownPreparation"); preparationField.setAccessible(true);
            preparationField.set(plugin, CompletableFuture.completedFuture(null));
            doCallRealMethod().when(plugin).onShutdownComplete(any(java.util.concurrent.CompletionStage.class));
            doCallRealMethod().when(plugin).onShutdownComplete();
            plugin.onShutdownComplete(finalFiles);
            finalWorker.remove().run(); // Production PermissionHandler -> OrderedServerDataFile -> runtime owner.
            assertFalse(finalFiles.isDone()); assertArrayEquals(before, Files.readAllBytes(file()));
            var laterAccepted = state.runtimeStorage(() -> null);
            release.countDown(); await(blocker); await(finalFiles); await(laterAccepted);
            var written = disk();
            assertTrue(written.getStringList("TimedPermissions." + revoked).isEmpty());
            var pending = written.getStringList("TimedPermissions." + retained);
            assertEquals(1, pending.size()); assertTrue(pending.getFirst().startsWith("perk.retained%line%"));
            assertTrue(Long.parseLong(pending.getFirst().split("%line%")[1]) >= now + 1_200_000);
            assertEquals(1, written.getStringList("TimedPermissions." + online).size());
            assertTrue(Files.readString(file()).startsWith("# MCPerks ordered ServerData v1"));
            assertEquals(1234, written.getLong("Experation.legacy"));
            assertEquals(5678, written.getLong("legacy.CoolDown"));
            try (OrderedStorage successor = new OrderedStorage(directory.resolve("features.dat"))) { await(successor.ready()); }
            live.loadFromString(Files.readString(file())); var restarted = restoreNative();
            assertFalse(restarted.getPermsToAdd().containsKey(revoked));
            assertTrue(nativePending(restarted, retained).containsKey("perk.retained"));
            assertTrue(nativePending(restarted, online).containsKey("perk.online"));
            verify(originalFile, never()).saveData(); verify(originalFile, never()).saveDataChecked();
        } finally { release.countDown(); }
    }

    @Test void genericEmptyCacheWithoutNativeHandlerDoesNotClearRecoveredPermissions() throws Exception {
        UUID owner = UUID.randomUUID(); legacy(permissionRows(owner, "perk.recovered", System.currentTimeMillis() + 600_000));
        live.set("TimedPermissions", null); await(runtime.initialize(state));
        assertFalse(disk().getStringList("TimedPermissions." + owner).isEmpty());
        assertTrue(restoreNative().getPermsToAdd().containsKey(owner));
    }
    @Test void nativeHandlerConstructedOverAnEmptyCacheDoesNotClaimUnobservedDiskPermissions() throws Exception {
        var emptyHandler = restoreNative(); assertTrue(emptyHandler.getPermsToAdd().isEmpty());
        UUID owner = UUID.randomUUID(); Files.writeString(file(), permissionRows(owner, "perk.recovered", System.currentTimeMillis() + 600_000).saveToString());
        await(runtime.initialize(state));
        assertTrue(emptyHandler.getPermsToAdd().containsKey(owner));
        assertTrue(nativePending(emptyHandler, owner).containsKey("perk.recovered"));
        assertNull(disk().get("TimedPermissions"), "Rows may clear only after the real native pending owner restored them");
    }
    @ParameterizedTest @ValueSource(strings = {"empty", "older"})
    void drainedPredecessorRowsRestoreRenewRemoveAndPreserveSuccessorChanges(String initial) throws Exception {
        UUID renewed = UUID.randomUUID(), fresh = UUID.randomUUID(), revoked = UUID.randomUUID(), removed = UUID.randomUUID(), local = UUID.randomUUID();
        long now = System.currentTimeMillis(); var older = new YamlConfiguration(); older.set("PrevDay", 9);
        if (initial.equals("older")) {
            older.set("TimedPermissions." + renewed, List.of("perk.renewed%line%" + (now + 600_000)));
            older.set("TimedPermissions." + revoked, List.of("perk.revoked|perk.retained%line%" + (now + 600_000)));
            older.set("TimedPermissions." + removed, List.of("perk.removed%line%" + (now + 600_000)));
            older.set("TimedPermissions." + local, List.of("perk.local%line%" + (now + 600_000)));
        }
        if (initial.equals("empty")) { setupNativeFile(true); ordered.setLastUpdated(); }
        else legacy(older);
        var handler = restoreNative();
        if (initial.equals("older")) {
            handler.removePermission(revoked, null, "perk.revoked");
            handler.addPermission(local, "perk.local", com.bencodez.simpleapi.time.ParsedDuration.ofMillis(1_200_000));
            assertTrue(handler.getPermsToAdd().containsKey(revoked), "Keep another node so owner-presence alone cannot detect revocation");
        }
        var newest = new YamlConfiguration(); newest.set("PrevDay", 10);
        newest.set("TimedPermissions." + renewed, List.of("perk.renewed%line%" + (now + 900_000)));
        newest.set("TimedPermissions." + fresh, List.of("perk.fresh%line%" + (now + 900_000)));
        if (initial.equals("older")) {
            newest.set("TimedPermissions." + revoked, List.of("perk.revoked|perk.retained%line%" + (now + 900_000)));
            newest.set("TimedPermissions." + local, List.of("perk.local%line%" + (now + 900_000)));
        }
        OrderedStorage predecessor = state;
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var published = predecessor.runtimeStorage(() -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                Path staged = Files.createTempFile(directory, "predecessor-final-", ".tmp");
                Files.writeString(staged, newest.saveToString()); DurableFeatureFiles.publish(staged, file());
                return null;
            } catch (Exception failure) { throw new CompletionException(failure); }
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        var retiring = CompletableFuture.runAsync(predecessor::close);
        state = new OrderedStorage(directory.resolve("features.dat")); when(plugin.getStorage()).thenReturn(state);
        var initialization = runtime.initialize(state);
        assertFalse(initialization.isDone()); assertFalse(runtime.isReady());
        try { release.countDown(); await(retiring); await(published); await(initialization); }
        finally { release.countDown(); }
        assertTrue(nativePending(handler, renewed).get("perk.renewed") > 800_000);
        assertTrue(nativePending(handler, fresh).get("perk.fresh") > 800_000);
        if (initial.equals("older")) {
            assertFalse(nativePending(handler, revoked).containsKey("perk.revoked"));
            assertTrue(nativePending(handler, revoked).get("perk.retained") > 800_000);
            assertFalse(handler.getPermsToAdd().containsKey(removed));
            assertEquals(1_200_000, nativePending(handler, local).get("perk.local"));
        }
        assertNull(disk().get("TimedPermissions")); assertEquals(10, disk().getInt("PrevDay"));
        handler.shutDown(); await(state.runtimeStorage(() -> null));
        assertFalse(disk().getStringList("TimedPermissions." + fresh).isEmpty());
        assertFalse(disk().getStringList("TimedPermissions." + renewed).isEmpty());
        assertTrue(disk().getStringList("TimedPermissions." + revoked).stream().noneMatch(row -> row.startsWith("perk.revoked%line%")));
        live.loadFromString(Files.readString(file())); var restarted = restoreNative();
        assertTrue(nativePending(restarted, fresh).containsKey("perk.fresh"));
        assertTrue(nativePending(restarted, renewed).containsKey("perk.renewed"));
        assertFalse(nativePending(restarted, revoked).containsKey("perk.revoked"));
        assertTrue(owners.tasks.isEmpty());
    }
    @Test void reconciledOnlineAttachmentRunsOnRealNativeOwnerAndRetiredLifecyclePreservesDisk() throws Exception {
        UUID owner = UUID.randomUUID(); var handler = restoreNative();
        long expiry = System.currentTimeMillis() + 600_000;
        Files.writeString(file(), permissionRows(owner, "perk.recovered", expiry).saveToString());
        byte[] original = Files.readAllBytes(file());
        var player = mock(org.bukkit.entity.Player.class); when(player.getUniqueId()).thenReturn(owner); when(player.isValid()).thenReturn(true);
        var attachment = mock(org.bukkit.permissions.PermissionAttachment.class); when(attachment.getPermissions()).thenReturn(new HashMap<>());
        when(player.addAttachment(plugin)).thenAnswer(call -> { assertTrue(ownerContext); return attachment; });
        doAnswer(call -> { assertTrue(ownerContext); return null; }).when(attachment).setPermission(anyString(), anyBoolean());
        var server = Bukkit.getServer();
        when(server.getPlayer(owner)).thenAnswer(call -> { assertTrue(ownerContext, "Player lookup must not run on feature storage"); return player; });
        try {
            var initialization = runtime.initialize(state);
            await(initialization);
            assertTrue(handler.getPerms().containsKey(owner));
            verify(player).addAttachment(plugin); verify(attachment).setPermission("perk.recovered", true);
            assertNull(disk().get("TimedPermissions"));
            assertTrue(handler.getPerms().get(owner).getTimedPermissions().get("perk.recovered") >= expiry - 1000);
            // A new lifecycle cannot inherit an in-flight restore callback or clear its predecessor evidence.
            Files.write(file(), original); live.set("TimedPermissions", null);
            var retired = runtime.initialize(state);
            Runnable global = owners.tasks.poll(10, TimeUnit.SECONDS); assertNotNull(global);
            when(plugin.isEnabled()).thenReturn(false); onOwner(global);
            assertThrows(ExecutionException.class, () -> await(retired)); assertArrayEquals(original, Files.readAllBytes(file()));
        } finally { doReturn(null).when(server).getPlayer(owner); }
    }
    @Test void nativeRecoveredOwnersAdmitOnlyOneCallbackAtATime() throws Exception {
        var handler = restoreNative(); var yaml = new YamlConfiguration();
        long expiry = System.currentTimeMillis() + 600_000;
        for (int i = 0; i < 1024; i++) yaml.set("TimedPermissions." + UUID.randomUUID(), List.of("perk.recovered%line%" + expiry));
        Files.writeString(file(), yaml.saveToString()); await(runtime.initialize(state));
        assertEquals(1024, handler.getPermsToAdd().size()); assertTrue(maximumOwnerCallbacks <= 1);
        assertNull(disk().get("TimedPermissions")); assertTrue(owners.tasks.isEmpty());
    }
    @Test void malformedNativeSnapshotSchemaFailsBeforeClearingValidRecoveredDisk() throws Exception {
        var handler = restoreNative(); UUID owner = UUID.randomUUID();
        Files.writeString(file(), permissionRows(owner, "perk.recovered", System.currentTimeMillis() + 600_000).saveToString());
        byte[] original = Files.readAllBytes(file());
        var handle = mock(com.bencodez.advancedcore.api.permissions.PlayerPermissionHandler.class);
        var method = com.bencodez.advancedcore.api.permissions.PlayerPermissionHandler.class.getDeclaredMethod("offlinePermissionSnapshot");
        assertTrue(method.trySetAccessible());
        // Invoke the real bundled method signature through Mockito's stub boundary, returning hostile schema.
        doReturn(Map.of("perk.invalid", "not-a-long")).when(handle); method.invoke(handle);
        handler.getPermsToAdd().put(owner, handle);
        var failure = assertThrows(ExecutionException.class, () -> await(runtime.initialize(state)));
        assertTrue(failure.getCause().toString().contains("Invalid native permission snapshot"));
        assertFalse(runtime.isReady()); assertArrayEquals(original, Files.readAllBytes(file()));
    }
    @ParameterizedTest @ValueSource(strings = {"expired", "revoked"})
    void oversizedNativeLegacyConsumedPermissionsMigrateWithoutBlockingLegacyActivation(String consumed) throws Exception {
        UUID owner = UUID.randomUUID(); String largePermission = "perk." + "雪".repeat(1_400_000);
        var yaml = permissionRows(owner, largePermission, System.currentTimeMillis() + (consumed.equals("expired") ? -600_000 : 600_000));
        legacy(yaml); assertTrue(Files.size(file()) > 4_000_000); var handler = restoreNative();
        if (consumed.equals("revoked")) handler.removePermission(owner, null, largePermission);
        assertFalse(handler.getPermsToAdd().containsKey(owner)); await(runtime.initialize(state));
        assertTrue(runtime.isReady()); assertTrue(Files.size(file()) <= 4_000_000); assertNull(disk().get("TimedPermissions"));
        assertEquals(1234, disk().getLong("Experation.legacy")); assertEquals(5678, disk().getLong("legacy.CoolDown")); assertEquals(9, disk().getInt("PrevDay"));
        await(runtime.confirmedActivation(() -> runtime.setServerCooldown("legacy", 6789)));
        handler.shutDown(); await(state.runtimeStorage(() -> null));
        live.loadFromString(Files.readString(file())); assertFalse(restoreNative().getPermsToAdd().containsKey(owner));
        assertEquals(6789, disk().getLong("legacy.CoolDown"));
    }
    @Test void oversizedStillActivePermissionPayloadRetainsOriginalAndReportsShutdownFailure() throws Exception {
        UUID owner = UUID.randomUUID(); legacy(permissionRows(owner, "perk." + "雪".repeat(1_400_000), System.currentTimeMillis() + 600_000));
        byte[] original = Files.readAllBytes(file()); var handler = restoreNative(); login(handler, owner);
        var failure = assertThrows(ExecutionException.class, () -> await(runtime.initialize(state)));
        assertTrue(failure.getCause().getMessage().contains("still-active TimedPermissions"));
        assertFalse(runtime.isReady()); assertArrayEquals(original, Files.readAllBytes(file()));
        var logger = mock(Logger.class); when(plugin.getLogger()).thenReturn(logger);
        handler.shutDown(); verify(logger).warning(contains("original file retained"));
        assertArrayEquals(original, Files.readAllBytes(file()));
    }
    @Test void oversizedLegacyAdvisoryHistoryMigratesWithIndispensableFieldsIntact() throws Exception {
        var yaml = new YamlConfiguration(); yaml.set("PerkHistory.OCTOBER", List.of("雪".repeat(1_400_000)));
        yaml.set("Experation.legacy", 1234); yaml.set("legacy.CoolDown", 5678); yaml.set("TimeTransitions.DAY.Pending", true);
        legacy(yaml); assertTrue(Files.size(file()) > 4_000_000); await(runtime.initialize(state));
        assertTrue(runtime.isReady()); assertTrue(Files.size(file()) <= 4_000_000);
        assertEquals(1234, disk().getLong("Experation.legacy")); assertEquals(5678, disk().getLong("legacy.CoolDown"));
        assertTrue(disk().getBoolean("TimeTransitions.DAY.Pending")); assertTrue(disk().getStringList("PerkHistory.OCTOBER").isEmpty());
        await(runtime.initialize(state)); assertTrue(runtime.isReady());
    }
    @Test void markedOversizedRecoveryAndIrreducibleLegacyPayloadStayUnchanged() throws Exception {
        String payload = "LegacyPayload: '" + "雪".repeat(1_400_000) + "'\n";
        for (String header : List.of("# MCPerks ordered ServerData v1\n", "")) {
            Files.writeString(file(), header + payload); byte[] original = Files.readAllBytes(file());
            assertThrows(ExecutionException.class, () -> await(runtime.initialize(state)));
            assertFalse(runtime.isReady()); assertArrayEquals(original, Files.readAllBytes(file()));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"malformed", "symlink", "native-unreadable"})
    void legacyMigrationDoesNotWeakenReadFailures(String invalid) throws Exception {
        Files.writeString(file(), "PrevDay: 9\n");
        switch (invalid) {
            case "malformed" -> Files.writeString(file(), "broken: [unterminated\n");
            case "symlink" -> { Path target = directory.resolve("target.yml"); Files.move(file(), target); Files.createSymbolicLink(file(), target); }
            case "native-unreadable" -> when(originalFile.isFailedToRead()).thenReturn(true);
            default -> fail("unknown invalid case");
        }
        byte[] original = Files.readAllBytes(file()); assertThrows(ExecutionException.class, () -> await(runtime.initialize(state)));
        assertFalse(runtime.isReady()); assertArrayEquals(original, Files.readAllBytes(file()));
    }
}
