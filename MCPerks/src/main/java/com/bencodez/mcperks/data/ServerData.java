package com.bencodez.mcperks.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.configuration.file.FileConfiguration;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.perk.Perk;

/** Legacy runtime YAML. Owner mutations produce immutable, ordered worker snapshots. */
public class ServerData {
    private final MCPerksMain plugin;
    private final DurableFeatureFiles.Publisher publisher;
    private CalendarUpdate pendingCalendar;
    private volatile CompletableFuture<Void> initialization = CompletableFuture.completedFuture(null);
    private boolean canonicalLoaded = true;
    private boolean nativeTimedPermissionsObserved;
    private boolean nativeStartupDeferred;
    private String nativeStartupBaseline;
    private boolean nativeCreatedEmptySource;
    private Map<UUID, Map<String, Long>> nativePermissionSource = Map.of();
    private com.bencodez.advancedcore.api.permissions.PermissionHandler observedNativeHandler;
    private Map<UUID, NativePermissionNodes> nativePermissionBaseline = Map.of();
    private RuntimeException nativePermissionSnapshotFailure;
    /** Early native writes cannot overwrite a source that has not been strictly read. */
    public synchronized void deferNativeSaves(FileConfiguration original, boolean justCreated) {
        if (!nativeStartupDeferred) {
            nativeStartupBaseline = original.saveToString();
            nativeCreatedEmptySource = justCreated && original.getKeys(false).isEmpty();
            nativeStartupDeferred = true; canonicalLoaded = false;
            initialization = new CompletableFuture<>();
        }
    }
    /** The plugin getter captures state before the first native grant/revoke/login call. */
    public synchronized void observeNativePermissionHandler(com.bencodez.advancedcore.api.permissions.PermissionHandler handler) {
        if (observedNativeHandler == null) {
            observedNativeHandler = handler;
            try { nativePermissionBaseline = nativePermissionSnapshot(handler); }
            catch (RuntimeException failure) { nativePermissionSnapshotFailure = failure; }
        }
    }
    private final java.util.concurrent.atomic.AtomicInteger pendingSnapshots = new java.util.concurrent.atomic.AtomicInteger();
    private final ThreadLocal<List<Runnable>> activationWrites = new ThreadLocal<>();
    public ServerData(MCPerksMain plugin) { this(plugin, DurableFeatureFiles::publish); }
    ServerData(MCPerksMain plugin, DurableFeatureFiles.Publisher publisher) { this.plugin = plugin; this.publisher = publisher; }

    public CompletableFuture<Void> ready() { return initialization; }
    public boolean isReady() { return initialization.isDone() && !initialization.isCompletedExceptionally(); }
    /** Called by the early native-file hook before AdvancedCore constructs its handler. */
    public synchronized void observeNativePermissionSource(FileConfiguration data) {
        if (plugin.getPermissionHandler() == null && data != null) {
            var section = data.getConfigurationSection("TimedPermissions");
            if (!nativeTimedPermissionsObserved && section != null && !section.getKeys(false).isEmpty()) {
                nativeTimedPermissionsObserved = true; nativePermissionSource = permissionRows(data);
            }
        }
    }
    // These are the scalar fields mutated by AdvancedCore's transition API, verified against its
    // actual implementation. Copies/changes are bounded independently of perk/history contents.
    private static final List<String> CALENDAR_PATHS = calendarPaths();
    private final long[] calendarEdits = new long[CALENDAR_PATHS.size()];
    synchronized void calendarEdited(String path) {
        for (int i = 0; i < CALENDAR_PATHS.size(); i++)
            if (path.isEmpty() || CALENDAR_PATHS.get(i).equals(path) || CALENDAR_PATHS.get(i).startsWith(path + ".")) calendarEdits[i]++;
    }
    private static List<String> calendarPaths() {
        var paths = new ArrayList<>(List.of("PrevDay", "PrevWeek", "Month"));
        for (var type : com.bencodez.advancedcore.api.time.TimeType.values())
            for (String field : List.of("Id", "Period", "Marker", "Pending")) paths.add("TimeTransitions." + type.name() + "." + field);
        return List.copyOf(paths);
    }
    record TimeTransitionView(FileConfiguration data, Map<String, Object> before) {}
    synchronized TimeTransitionView timeTransitionView() {
        try {
            var copy = parse(getData().saveToString());
            var before = new LinkedHashMap<String, Object>();
            for (String path : CALENDAR_PATHS) before.put(path, snapshotValue(copy.get(path)));
            return new TimeTransitionView(copy, Collections.unmodifiableMap(before));
        } catch (org.bukkit.configuration.InvalidConfigurationException error) { throw new CompletionException(error); }
    }
    private static Object snapshotValue(Object value) {
        if (value instanceof org.bukkit.configuration.ConfigurationSection section) return snapshotValue(tree(section));
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<Object, Object>(); map.forEach((key, child) -> copy.put(key, snapshotValue(child)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(ServerData::snapshotValue).toList());
        if (value instanceof java.util.Date date) return date.toInstant();
        return value;
    }
    private record CalendarChange(int field, long edit, Object after) {}
    private static final class CalendarUpdate {
        private final Map<String, CalendarChange> changes;
        private volatile boolean published;
        private CalendarUpdate(Map<String, CalendarChange> changes) { this.changes = Collections.unmodifiableMap(changes); }
        private void apply(FileConfiguration data, long[] edits) {
            changes.forEach((path, change) -> {
                if (edits == null || edits[change.field()] == change.edit()) data.set(path, change.after());
            });
        }
    }
    /** Admit a private calendar mutation and immutable snapshot, then release ownership before waiting. */
    synchronized CompletableFuture<Void> confirmedTimeTransition(TimeTransitionView view) {
        if (!isReady()) return CompletableFuture.failedFuture(new IllegalStateException("ServerData initialization unavailable"));
        try {
            var changes = new LinkedHashMap<String, CalendarChange>();
            for (int i = 0; i < CALENDAR_PATHS.size(); i++) {
                String path = CALENDAR_PATHS.get(i); Object next = snapshotValue(view.data().get(path));
                if (!Objects.equals(view.before().get(path), next)) changes.put(path, new CalendarChange(i, calendarEdits[i], next));
            }
            var update = new CalendarUpdate(changes);
            var copy = parse(getData().saveToString()); update.apply(copy, null);
            byte[] snapshot = snapshotBytes(copy);
            pendingCalendar = update;
            return publishSnapshot(snapshot, List.of(), null, null, update);
        } catch (org.bukkit.configuration.InvalidConfigurationException | RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }
    /** Reconcile the cache loaded by AdvancedCore before a prior feature worker finished shutdown. */
    public synchronized CompletableFuture<Void> initialize(FeatureState state) {
        String baseline = nativeStartupDeferred ? nativeStartupBaseline : getData().saveToString();
        var nativePermissions = plugin.getPermissionHandler();
        if (nativePermissions != null) observeNativePermissionHandler(nativePermissions);
        Path file = plugin.getServerDataFile().getdFile().toPath();
        CompletableFuture<Void> initialized = nativeStartupDeferred && !initialization.isDone()
                ? initialization : new CompletableFuture<>();
        initialization = initialized; canonicalLoaded = false;
        state.runtimeStorage(() -> {
            try {
                if (nativePermissionSnapshotFailure != null) throw nativePermissionSnapshotFailure;
                boolean nativeReadFailed = plugin.getServerDataFile().isFailedToRead();
                boolean sourceExists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
                var loaded = sourceExists ? readSnapshot(file) : parse(baseline);
                // Early native saves are deferred: even malformed native empty fallbacks
                // still face the unchanged regular-file check and strict disk parser.
                // A native-created empty source may now contain a drained predecessor's
                // valid snapshot; existing nonempty native read failures remain fatal.
                if (nativeReadFailed && (!sourceExists || !loaded.getKeys(false).isEmpty() && !nativeCreatedEmptySource))
                    throw new IOException("Native ServerData could not be read");
                return loaded;
            } catch (IOException | org.bukkit.configuration.InvalidConfigurationException error) { throw new CompletionException(error); }
        }).thenCompose(loaded -> reconcileNativePermissions(state, nativePermissions, loaded).thenCompose(ignored ->
                state.runtimeStorage(() -> {
                    try { synchronized (ServerData.this) {
                        var old = parse(baseline); var current = parse(getData().saveToString());
                        var previousValues = tree(old); var currentValues = tree(current);
                        Set<String> keys = new HashSet<>(previousValues.keySet()); keys.addAll(currentValues.keySet());
                        // Changes made while predecessor/owner work drained remain authoritative.
                        for (String key : keys) if (!Objects.equals(previousValues.get(key), currentValues.get(key)))
                            loaded.set(key, currentValues.get(key));
                        if (nativePermissions != null) {
                            requireNativeLifecycle(state, nativePermissions);
                            loaded.set("TimedPermissions", currentValues.get("TimedPermissions"));
                        }
                        getData().loadFromString(loaded.saveToString()); canonicalLoaded = true;
                        return persistSnapshot(List.of());
                    } } catch (org.bukkit.configuration.InvalidConfigurationException failure) { throw new CompletionException(failure); }
                }).thenCompose(future -> future)))
        .whenComplete((ignored, error) -> {
            if (error == null) initialized.complete(null);
            else {
                for (Throwable cause = error; cause != null; cause = cause.getCause())
                    if (UNSUPPORTED_LEGACY.equals(cause.getMessage())) { plugin.getLogger().severe(UNSUPPORTED_LEGACY); break; }
                initialized.completeExceptionally(error);
            }
        });
        return initialized;
    }
    private static org.bukkit.configuration.file.YamlConfiguration parse(String text) throws org.bukkit.configuration.InvalidConfigurationException {
        var yaml = new org.bukkit.configuration.file.YamlConfiguration(); yaml.loadFromString(text); return yaml;
    }
    private static final String SNAPSHOT_HEADER = "# MCPerks ordered ServerData v1\n";
    private static final String UNSUPPORTED_LEGACY = "Legacy ServerData indispensable payload (including still-active TimedPermissions) exceeds four MB; original file retained. Revoke or expire excess timed permissions before upgrading, or reduce other indispensable legacy payload.";
    private record NativePermissionNodes(Map<String, Long> timed, Map<String, Long> pending, Set<String> permanent) {
        private static final NativePermissionNodes EMPTY = new NativePermissionNodes(Map.of(), Map.of(), Set.of());
        private Object value(String node) {
            if (permanent.contains(node)) return "permanent";
            if (timed.containsKey(node)) return List.of("timed", timed.get(node));
            return pending.containsKey(node) ? List.of("pending", pending.get(node)) : null;
        }
        private Set<String> keys() {
            Set<String> keys = new HashSet<>(timed.keySet()); keys.addAll(pending.keySet()); keys.addAll(permanent); return keys;
        }
    }
    /** Only these exact synchronized native snapshot methods are accessed, with no field fallback. */
    private static Map<String, Long> nativeSnapshot(com.bencodez.advancedcore.api.permissions.PlayerPermissionHandler handle, String method) {
        try {
            var getter = com.bencodez.advancedcore.api.permissions.PlayerPermissionHandler.class.getDeclaredMethod(method);
            if (getter.getReturnType() != Map.class || !java.lang.reflect.Modifier.isSynchronized(getter.getModifiers())
                    || !getter.trySetAccessible()) throw new IllegalStateException("Unsupported native permission snapshot API: " + method);
            Object result = getter.invoke(handle);
            if (!(result instanceof Map<?, ?> values)) throw new IllegalStateException("Invalid native permission snapshot: " + method);
            Map<String, Long> copy = new HashMap<>();
            for (var entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof Long value))
                    throw new IllegalStateException("Invalid native permission snapshot entry: " + method);
                copy.put(key, value);
            }
            return Map.copyOf(copy);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native permission snapshot unavailable: " + method, failure); }
    }
    private static Map<UUID, NativePermissionNodes> nativePermissionSnapshot(com.bencodez.advancedcore.api.permissions.PermissionHandler handler) {
        Map<UUID, NativePermissionNodes> values = new HashMap<>();
        handler.getPermsToAdd().forEach((owner, handle) -> values.put(owner, nativeNodes(handle)));
        handler.getPerms().forEach((owner, handle) -> values.put(owner, nativeNodes(handle)));
        return Map.copyOf(values);
    }
    private static NativePermissionNodes nativePermissionOwnerSnapshot(com.bencodez.advancedcore.api.permissions.PermissionHandler handler, UUID owner) {
        var handle = handler.getPerms().get(owner);
        if (handle == null) handle = handler.getPermsToAdd().get(owner);
        return handle == null ? NativePermissionNodes.EMPTY : nativeNodes(handle);
    }
    private static NativePermissionNodes nativeNodes(com.bencodez.advancedcore.api.permissions.PlayerPermissionHandler handle) {
        synchronized (handle) {
            return new NativePermissionNodes(nativeSnapshot(handle, "timedPermissionSnapshot"),
                    nativeSnapshot(handle, "offlinePermissionSnapshot"), Set.copyOf(handle.getPersistentPermissions()));
        }
    }
    private static Map<UUID, Map<String, Long>> permissionRows(FileConfiguration data) {
        Map<UUID, Map<String, Long>> result = new HashMap<>();
        var section = data.getConfigurationSection("TimedPermissions");
        if (section != null) for (String owner : section.getKeys(false)) {
            Map<String, Long> nodes = new HashMap<>();
            for (String row : section.getStringList(owner)) {
                String[] fields = row.split(java.util.regex.Pattern.quote("%line%"));
                if (fields.length != 2) continue;
                try {
                    long expiry = Long.parseLong(fields[1]);
                    // Match the native grant API's supported multi-node separator.
                    for (String node : fields[0].split(java.util.regex.Pattern.quote("|")))
                        if (!node.isEmpty()) nodes.put(node, expiry);
                } catch (NumberFormatException ignored) { }
            }
            result.put(UUID.fromString(owner), Map.copyOf(nodes));
        }
        return Map.copyOf(result);
    }
    private void requireNativeLifecycle(FeatureState state, com.bencodez.advancedcore.api.permissions.PermissionHandler handler) {
        if (!plugin.isEnabled() || plugin.getFeatureState() != state || plugin.getPermissionHandler() != handler
                || handler.getTimer().isShutdown()) throw new IllegalStateException("Native permission lifecycle retired during reconciliation");
    }
    private CompletableFuture<Void> reconcileNativePermissions(FeatureState state,
            com.bencodez.advancedcore.api.permissions.PermissionHandler handler, org.bukkit.configuration.file.YamlConfiguration loaded) {
        if (handler == null) return CompletableFuture.completedFuture(null);
        Map<UUID, Map<String, Long>> recovered = permissionRows(loaded);
        CompletableFuture<Void> result = new CompletableFuture<>();
        try { plugin.getBukkitScheduler().runTask(plugin, () -> {
            try {
                requireNativeLifecycle(state, handler);
                var current = nativePermissionSnapshot(handler);
                Set<UUID> owners = new HashSet<>(recovered.keySet()); owners.addAll(nativePermissionSource.keySet());
                owners.addAll(current.keySet());
                // Check the effective indispensable rows before any native effects or row clearing.
                var projection = parse(loaded.saveToString()); projection.set("TimedPermissions", null);
                long now = System.currentTimeMillis();
                for (UUID owner : owners) {
                    var base = nativePermissionBaseline.getOrDefault(owner, NativePermissionNodes.EMPTY);
                    var live = current.getOrDefault(owner, NativePermissionNodes.EMPTY);
                    var source = nativePermissionSource.getOrDefault(owner, Map.of());
                    var disk = recovered.getOrDefault(owner, Map.of());
                    Set<String> nodes = new HashSet<>(source.keySet()); nodes.addAll(disk.keySet()); nodes.addAll(live.keys());
                    List<String> rows = new ArrayList<>();
                    for (String node : nodes) {
                        Long expiry = disk.get(node);
                        if (!Objects.equals(base.value(node), live.value(node))) {
                            expiry = live.timed().get(node);
                            if (live.pending().containsKey(node)) expiry = live.pending().get(node) > 0 ? Math.addExact(now, live.pending().get(node)) : null;
                        } else if (Objects.equals(source.get(node), expiry) && live.value(node) == null) expiry = null;
                        if (expiry != null && expiry > now && !live.permanent().contains(node)) rows.add(node + "%line%" + expiry);
                    }
                    if (!rows.isEmpty()) projection.set("TimedPermissions." + owner, rows);
                }
                try { snapshotBytes(projection); } catch (IllegalStateException unsupported) { throw new IllegalStateException(UNSUPPORTED_LEGACY, unsupported); }
                reconcileNativeOwner(owners.iterator(), recovered, state, handler, result);
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        }); } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result;
    }
    /** At most one global/entity callback is admitted at a time; no callback collection is retained. */
    private void reconcileNativeOwner(Iterator<UUID> owners, Map<UUID, Map<String, Long>> recovered, FeatureState state,
            com.bencodez.advancedcore.api.permissions.PermissionHandler handler, CompletableFuture<Void> result) {
        if (!owners.hasNext()) { result.complete(null); return; }
        UUID owner = owners.next();
        try { plugin.getBukkitScheduler().runTask(plugin, () -> {
            try {
                requireNativeLifecycle(state, handler);
                var player = org.bukkit.Bukkit.getPlayer(owner);
                Runnable restore = () -> {
                    try {
                        requireNativeLifecycle(state, handler);
                        if (player != null && (!player.isValid() || org.bukkit.Bukkit.getPlayer(owner) != player))
                            throw new IllegalStateException("Native permission player owner changed");
                        var live = nativePermissionOwnerSnapshot(handler, owner);
                        var base = nativePermissionBaseline.getOrDefault(owner, NativePermissionNodes.EMPTY);
                        var source = nativePermissionSource.getOrDefault(owner, Map.of());
                        var disk = recovered.getOrDefault(owner, Map.of());
                        Set<String> nodes = new HashSet<>(source.keySet()); nodes.addAll(disk.keySet());
                        for (String node : nodes) {
                            if (!Objects.equals(base.value(node), live.value(node)) || live.permanent().contains(node)) continue;
                            // Already-restored rows include subsequently expired/revoked initial nodes.
                            if (Objects.equals(source.get(node), disk.get(node))) continue;
                            if (player == null && (handler.getPerms().containsKey(owner) || org.bukkit.Bukkit.getPlayer(owner) != null))
                                throw new IllegalStateException("Native permission attached owner unavailable");
                            Long expiry = disk.get(node); long remaining = expiry == null ? 0 : expiry - System.currentTimeMillis();
                            if (remaining <= 0) handler.removePermission(owner, null, node);
                            else handler.addPermission(owner, node, com.bencodez.simpleapi.time.ParsedDuration.ofMillis(remaining));
                        }
                        reconcileNativeOwner(owners, recovered, state, handler, result);
                    } catch (Throwable failure) { result.completeExceptionally(failure); }
                };
                if (player == null) restore.run();
                else com.bencodez.mcperks.scheduling.OwnerTasks.run(plugin, player, restore,
                        () -> result.completeExceptionally(new IllegalStateException("Native permission player owner retired")));
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        }); } catch (RuntimeException failure) { result.completeExceptionally(failure); }
    }
    private static org.bukkit.configuration.file.YamlConfiguration readSnapshot(Path file)
            throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid ServerData file");
        byte[] header = SNAPSHOT_HEADER.getBytes(StandardCharsets.UTF_8);
        boolean ordered;
        try (var input = Files.newInputStream(file)) { ordered = Arrays.equals(header, input.readNBytes(header.length)); }
        if (ordered) {
            // Count actual bytes again while reading, including a concurrent file growth.
            try (var input = Files.newInputStream(file)) {
                byte[] bytes = input.readNBytes(MAX_SNAPSHOT_BYTES + 1);
                if (bytes.length > MAX_SNAPSHOT_BYTES) throw new IOException("Ordered ServerData exceeds four MB");
                return parse(new String(bytes, StandardCharsets.UTF_8));
            }
        }
        // A native legacy file has already been parsed by AdvancedCore. Admit that
        // old format once, after predecessor drain, without inventing a larger legacy
        // size ceiling. Reconciliation and advisory-history pruning must yield the
        // bounded new format; indispensable oversized payloads leave this file intact.
        var legacy = new org.bukkit.configuration.file.YamlConfiguration();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { legacy.load(reader); }
        return legacy;
    }
    private static Map<String,Object> tree(org.bukkit.configuration.ConfigurationSection section) {
        Map<String,Object> values = new HashMap<>();
        for (String key : section.getKeys(false)) { Object value = section.get(key);
            values.put(key, value instanceof org.bukkit.configuration.ConfigurationSection child ? tree(child) : value); }
        return values;
    }
    public synchronized void addPerkHistory(String value) {
        String path = "PerkHistory." + plugin.getTimeChecker().getTime().getMonth().toString();
        var history = new ArrayList<>(getData().getStringList(path)); history.add(value);
        // Bound each retained month's activation history, including when the optional file log is enabled.
        getData().set(path, history.subList(Math.max(0, history.size() - 256), history.size()));
        saveData();
    }
    public FileConfiguration getData() { return plugin.getServerDataFile().getData(); }
    public synchronized long getPerkExperation(Perk perk) { return getData().getLong("Experation." + perk.getPerk()); }
    public synchronized void setServerCooldown(String perk, long time) { getData().set(perk + ".CoolDown", time); }
    public synchronized long getServerCooldown(String perk) { return getData().getLong(perk + ".CoolDown", 0); }
    /** The caller drains the feature worker before reloading the legacy file. */
    public synchronized void reloadData() { plugin.getServerDataFile().reloadData(); }
    synchronized void reloadFrom(com.bencodez.advancedcore.data.ServerData file) {
        // A new calendar/perk save may arrive after the reload drain but before its owner callback.
        // Keep its authoritative live YAML rather than replacing it with the earlier disk version.
        if (pendingSnapshots.get() == 0) file.reloadData();
    }

    public synchronized void saveData() {
        if (activationWrites.get() != null) return;
        if (!canonicalLoaded) {
            if (initialization.isCompletedExceptionally()) plugin.getLogger().warning("ServerData persistence unavailable after startup reconciliation failure; original file retained");
            return;
        }
        if (plugin.getFeatureState() == null) { plugin.getServerDataFile().saveData(); return; }
        // Native final save omits still-offline relative reservations from its timed-only snapshot.
        // Preserve those native-owned rows (and remove revoked ones) after its timer is retired.
        var handler = plugin.getPermissionHandler();
        if (handler != null && handler == observedNativeHandler && handler.getTimer().isShutdown()) {
            try {
                var snapshot = nativePermissionSnapshot(handler); long now = System.currentTimeMillis();
                getData().set("TimedPermissions", null);
                for (var owner : snapshot.entrySet()) {
                    Map<String, Long> timed = new HashMap<>(owner.getValue().timed());
                    owner.getValue().pending().forEach((node, duration) -> {
                        if (duration > 0) timed.put(node, Math.addExact(now, duration));
                    });
                    List<String> rows = new ArrayList<>();
                    timed.forEach((node, expiry) -> { if (expiry > now) rows.add(node + "%line%" + expiry); });
                    if (!rows.isEmpty()) getData().set("TimedPermissions." + owner.getKey(), rows);
                }
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("ServerData persistence failed: native final permission snapshot unavailable: " + failure.getMessage()); return;
            }
        }
        persistSnapshot(List.of()).exceptionally(error -> { plugin.getLogger().warning("ServerData persistence failed: " + error.getMessage()); return null; });
    }
    /** Gameplay stays on the caller's owner. No mutable YAML reaches the worker. */
    public synchronized CompletableFuture<Void> confirmedActivation(Runnable gameplay) {
        if (!isReady()) return CompletableFuture.failedFuture(new IllegalStateException("ServerData initialization unavailable"));
        if (activationWrites.get() != null) throw new IllegalStateException("Nested activation persistence");
        var writes = new ArrayList<Runnable>(); activationWrites.set(writes);
        try { gameplay.run(); return persistSnapshot(List.copyOf(writes)); }
        catch (Throwable error) { return CompletableFuture.failedFuture(error); }
        finally { activationWrites.remove(); }
    }
    /** The callback must contain only an already captured log message and its file writer. */
    public synchronized void activationLog(Runnable write) {
        var batch = activationWrites.get();
        if (batch != null) batch.add(write);
        else plugin.getFeatureState().runtimeStorage(() -> { write.run(); return null; });
    }
    private static final int MAX_SNAPSHOT_BYTES = 4_000_000;
    private static final int MAX_HISTORY_BYTES = 1_000_000;
    private static byte[] snapshotBytes(FileConfiguration data) {
        trimHistory(data, MAX_HISTORY_BYTES);
        byte[] snapshot = encodedSnapshot(data);
        if (snapshot.length > MAX_SNAPSHOT_BYTES && data.isConfigurationSection("PerkHistory")) {
            // History is advisory. Reclaim it before rejecting an authoritative timer/calendar save.
            var history = new org.bukkit.configuration.file.YamlConfiguration();
            history.set("PerkHistory", tree(data.getConfigurationSection("PerkHistory")));
            int historyBytes = history.saveToString().getBytes(StandardCharsets.UTF_8).length;
            trimHistory(data, Math.max(0, historyBytes - (snapshot.length - MAX_SNAPSHOT_BYTES)));
            snapshot = encodedSnapshot(data);
        }
        if (snapshot.length > MAX_SNAPSHOT_BYTES) throw new IllegalStateException("ServerData snapshot exceeds four MB");
        return snapshot;
    }
    private static byte[] encodedSnapshot(FileConfiguration data) {
        return (SNAPSHOT_HEADER + data.saveToString()).getBytes(StandardCharsets.UTF_8);
    }
    /** Keep complete recent entries in the existing monthly lists, using actual encoded YAML bytes. */
    private static void trimHistory(FileConfiguration data, int budget) {
        var section = data.getConfigurationSection("PerkHistory");
        if (section == null) return;
        var retained = new org.bukkit.configuration.file.YamlConfiguration();
        var months = new ArrayList<>(section.getKeys(false));
        if (months.isEmpty()) { data.set("PerkHistory", null); return; }
        for (String month : months) {
            var entries = section.getStringList(month);
            retained.set("PerkHistory." + month, new ArrayList<>(entries.subList(Math.max(0, entries.size() - 256), entries.size())));
        }
        if (retained.saveToString().getBytes(StandardCharsets.UTF_8).length <= budget) {
            data.createSection("PerkHistory", tree(retained.getConfigurationSection("PerkHistory"))); return;
        }
        retained = new org.bukkit.configuration.file.YamlConfiguration();
        Collections.reverse(months);
        // Prefer the last retained month, then fit suffixes of preceding months in the aggregate budget.
        for (String month : months) {
            var entries = section.getStringList(month);
            String path = "PerkHistory." + month;
            int low = 0, high = Math.min(256, entries.size());
            while (low < high) {
                int count = (low + high + 1) / 2;
                retained.set(path, entries.subList(entries.size() - count, entries.size()));
                if (retained.saveToString().getBytes(StandardCharsets.UTF_8).length <= budget) low = count;
                else high = count - 1;
            }
            retained.set(path, low == 0 ? null : new ArrayList<>(entries.subList(entries.size() - low, entries.size())));
        }
        var history = retained.getConfigurationSection("PerkHistory");
        if (history == null || history.getKeys(false).isEmpty()) data.set("PerkHistory", null);
        else data.createSection("PerkHistory", tree(history));
    }
    private CompletableFuture<Void> persistSnapshot(List<Runnable> writes) {
        // Serialization is confined to the mutation owner; publication alone runs on storage.
        try { return publishSnapshot(snapshotBytes(getData()), writes, pendingCalendar, calendarEdits.clone(), null); }
        catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }
    private CompletableFuture<Void> publishSnapshot(byte[] snapshot, List<Runnable> writes, CalendarUpdate overlay, long[] edits, CalendarUpdate commit) {
        Path file = plugin.getServerDataFile().getdFile().toPath();
        pendingSnapshots.incrementAndGet();
        return plugin.getFeatureState().<Void>runtimeStorage(() -> {
            Path staged = null;
            try {
                byte[] confirmed = snapshot;
                // This turn follows the calendar publication. Rebase only its confirmed fields in
                // a private worker YAML, never the live cache; failed transitions contribute nothing.
                // Per-field edit fences preserve even a later explicit write of the same old value.
                if (overlay != null && overlay.published) {
                    var copy = parse(new String(snapshot, StandardCharsets.UTF_8)); overlay.apply(copy, edits);
                    confirmed = snapshotBytes(copy);
                }
                Files.createDirectories(file.toAbsolutePath().getParent());
                if (Files.isSymbolicLink(file)) throw new IOException("ServerData is a symlink");
                staged = Files.createTempFile(file.toAbsolutePath().getParent(), ".server-data-", ".tmp");
                Files.write(staged, confirmed); publisher.publish(staged, file);
                if (commit != null) synchronized (ServerData.this) {
                    commit.apply(getData(), calendarEdits); commit.published = true;
                    if (pendingCalendar == commit) pendingCalendar = null;
                }
                for (Runnable write : writes) write.run();
                return null;
            } catch (IOException | org.bukkit.configuration.InvalidConfigurationException error) { throw new CompletionException(error); }
            finally { if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignored) {} }
        }).whenComplete((ignored, error) -> {
            pendingSnapshots.decrementAndGet();
            if (commit != null) synchronized (ServerData.this) { if (pendingCalendar == commit) pendingCalendar = null; }
        });
    }
    public synchronized void setPerkExperation(Perk perk, long time) {
        getData().set("Experation." + perk.getPerk(), time); saveData();
    }
    public synchronized void clearOfflinePerks() { getData().set("OfflinePerk", null); }
    public synchronized void saveOfflinePerk(Perk perk) {
        getData().set("OfflinePerk." + perk.getActivater().getUUID() + "." + perk.getPerk() + ".Experiaton",
                perk.isLastForever() ? -1 : perk.getExperation(perk.getActivater()));
    }
}
