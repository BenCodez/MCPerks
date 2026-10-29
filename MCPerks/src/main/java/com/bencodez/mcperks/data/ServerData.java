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
    private long runtimeEdits;
    private final ThreadLocal<List<Runnable>> activationWrites = new ThreadLocal<>();
    public ServerData(MCPerksMain plugin) { this(plugin, DurableFeatureFiles::publish); }
    ServerData(MCPerksMain plugin, DurableFeatureFiles.Publisher publisher) { this.plugin = plugin; this.publisher = publisher; }

    public CompletableFuture<Void> ready() { return initialization.copy(); }
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
        activationEdited(path);
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
        // YAML narrows small integral values to Integer. Compare their exact
        // 64-bit meaning without coercing floating-point values or large Longs.
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return ((Number) value).longValue();
        if (value instanceof java.util.Date date) return new java.util.Date(date.getTime());
        return value;
    }
    private record CalendarChange(int field, long edit, Object after) {}
    private static final class CalendarUpdate {
        private final Map<String, CalendarChange> changes;
        private final CalendarUpdate previous;
        private volatile boolean published;
        private CalendarUpdate(Map<String, CalendarChange> changes, CalendarUpdate previous) {
            this.changes = Collections.unmodifiableMap(changes); this.previous = previous;
        }
        private void applyPublishedChain(FileConfiguration data, long[] edits, Set<String> excluded) {
            var chain = new ArrayList<CalendarUpdate>();
            for (CalendarUpdate item = this; item != null; item = item.previous) chain.add(item);
            for (int i = chain.size() - 1; i >= 0; i--) {
                var item = chain.get(i);
                if (!item.published) continue;
                item.changes.forEach((path, change) -> {
                    if (!excluded.contains(path) && (edits == null || edits[change.field()] == change.edit())) data.set(path, change.after());
                });
            }
        }
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
            var predecessor = pendingCalendar;
            var update = new CalendarUpdate(changes, predecessor);
            var copy = parse(getData().saveToString()); update.apply(copy, null);
            byte[] snapshot = snapshotBytes(copy);
            long[] edits = calendarEdits.clone();
            pendingCalendar = update;
            return publishSnapshot(snapshot, List.of(), predecessor, edits, update);
        } catch (org.bukkit.configuration.InvalidConfigurationException | RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }
    /** Reconcile the cache loaded by AdvancedCore before a prior feature worker finished shutdown. */
    public synchronized CompletableFuture<Void> initialize(OrderedStorage state) {
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
        return initialized.copy();
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
    private void requireNativeLifecycle(OrderedStorage state, com.bencodez.advancedcore.api.permissions.PermissionHandler handler) {
        if (!plugin.isEnabled() || plugin.getStorage() != state || plugin.getPermissionHandler() != handler
                || handler.getTimer().isShutdown()) throw new IllegalStateException("Native permission lifecycle retired during reconciliation");
    }
    private CompletableFuture<Void> reconcileNativePermissions(OrderedStorage state,
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
    private void reconcileNativeOwner(Iterator<UUID> owners, Map<UUID, Map<String, Long>> recovered, OrderedStorage state,
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
    public synchronized void setServerCooldown(String perk, long time) { activationEdited(perk + ".CoolDown"); getData().set(perk + ".CoolDown", time); }
    public synchronized long getServerCooldown(String perk) { return getData().getLong(perk + ".CoolDown", 0); }
    private CompletableFuture<Void> nativeReload = CompletableFuture.completedFuture(null);
    public void reloadData() {
        reloadDataAsync().exceptionally(error -> {
            plugin.getLogger().warning("ServerData reload failed; current state retained: " + error.getMessage()); return null;
        });
    }
    public CompletableFuture<Void> reloadDataAsync() { return reloadFrom(plugin.getServerDataFile()); }
    public synchronized CompletableFuture<Void> reloadCompletion() { return nativeReload.copy(); }
    synchronized CompletableFuture<Void> reloadFrom(com.bencodez.advancedcore.data.ServerData file) {
        if (!nativeReload.isDone()) return nativeReload.copy();
        // A later calendar/perk mutation remains authoritative. Read a private YAML on the
        // ordered worker, then install it only if the observed live generation still matches.
        if (pendingSnapshots.get() != 0) return CompletableFuture.completedFuture(null);
        if (!isReady() || plugin.getStorage() == null) return CompletableFuture.failedFuture(
                new IllegalStateException("ServerData reload storage unavailable"));
        String baseline = getData().saveToString();
        long edit = runtimeEdits;
        Path path = file.getdFile().toPath();
        var operation = new CompletableFuture<Void>(); nativeReload = operation;
        plugin.getStorage().runtimeStorage(() -> {
            try { var loaded = readSnapshot(path); snapshotBytes(loaded); return loaded; }
            catch (IOException | org.bukkit.configuration.InvalidConfigurationException error) { throw new CompletionException(error); }
        }).whenComplete((loaded, error) -> {
            if (error != null) { operation.completeExceptionally(error); return; }
            try { synchronized (ServerData.this) {
                if (pendingSnapshots.get() == 0 && edit == runtimeEdits && baseline.equals(getData().saveToString()))
                    getData().loadFromString(loaded.saveToString());
            } operation.complete(null); }
            catch (Throwable failure) { operation.completeExceptionally(failure); }
        });
        return operation.copy();
    }

    public synchronized void saveData() {
        if (activationWrites.get() != null) return;
        if (!canonicalLoaded) {
            if (initialization.isCompletedExceptionally()) plugin.getLogger().warning("ServerData persistence unavailable after startup reconciliation failure; original file retained");
            return;
        }
        if (plugin.getStorage() == null) { plugin.getServerDataFile().saveData(); return; }
        confirmedFinalMutation(data -> {}).exceptionally(error -> {
            plugin.getLogger().warning("ServerData persistence failed: " + error.getMessage()); return null;
        });
    }

    /** Stages private mutations; only successful ordered publication changes the live YAML. */
    public synchronized CompletableFuture<Void> confirmedFinalMutation(
            java.util.function.Consumer<FileConfiguration> mutation) {
        if (!canonicalLoaded || plugin.getStorage() == null)
            return CompletableFuture.failedFuture(new IllegalStateException("ServerData persistence owner unavailable"));
        if (activationWrites.get() != null)
            return CompletableFuture.failedFuture(new IllegalStateException("Final save inside activation persistence"));
        if (pendingUpdates.size() >= 256)
            return CompletableFuture.failedFuture(new IllegalStateException("Snapshot staging capacity exceeded"));
        try {
            String serialized = getData().saveToString();
            var baseline = parse(serialized);
            var candidate = new FinalMutationYaml();
            candidate.loadFromString(serialized);
            candidate.recording = true;
            try {
                java.util.Objects.requireNonNull(mutation, "mutation").accept(candidate);
                reconcileFinalPermissions(candidate);
            } finally { candidate.recording = false; }
            byte[] snapshot = snapshotBytes(candidate);
            var values = new LinkedHashMap<String, Object>();
            // An explicit same-value write still owns its path. Ordinary empty saves
            // own no paths, so confirmed predecessors remain available for rebasing.
            candidate.writtenPaths.forEach(path -> values.put(path, snapshotValue(candidate.get(path))));
            var paths = new HashSet<>(baseline.getKeys(false)); paths.addAll(candidate.getKeys(false));
            for (String path : paths) collectFinalChanges(path, snapshotValue(baseline.get(path)),
                    snapshotValue(candidate.get(path)), candidate.writtenPaths, values);
            var update = new SnapshotUpdate(values, getData(), true);
            pendingUpdates.add(update);
            return publishSnapshot(snapshot, List.of(), pendingCalendar, calendarEdits.clone(), null, update);
        } catch (Throwable failure) { return CompletableFuture.failedFuture(failure); }
    }

    /** Mutation intent is local to one private candidate, never another storage owner. */
    private static final class FinalMutationYaml extends org.bukkit.configuration.file.YamlConfiguration {
        private final NavigableSet<String> writtenPaths = new TreeSet<>();
        private long writtenPathBytes;
        private boolean recording;
        private int internalWrites;
        private <T> T write(org.bukkit.configuration.ConfigurationSection section, String path,
                java.util.function.Supplier<T> action) {
            if (recording && internalWrites == 0) {
                String fullPath = org.bukkit.configuration.MemorySection.createPath(section, path);
                recordPath(fullPath);
            }
            internalWrites++;
            try { return action.get(); }
            finally { internalWrites--; }
        }
        private void recordPath(String path) {
            writtenPathBytes = captureMutationPath(writtenPaths, writtenPathBytes, path, true);
        }
        private org.bukkit.configuration.ConfigurationSection trackedSection(org.bukkit.configuration.ConfigurationSection section) {
            if (section instanceof FinalMutationSection) return section;
            var tracked = new FinalMutationSection(section.getParent(), section.getName(), this);
            section.getParent().set(section.getName(), tracked);
            return tracked;
        }
        @Override public void set(String path, Object value) {
            write(this, path, () -> { super.set(path, value); return null; });
        }
        @Override public org.bukkit.configuration.ConfigurationSection createSection(String path) {
            return write(this, path, () -> trackedSection(super.createSection(path)));
        }
    }
    private static long captureMutationPath(NavigableSet<String> paths, long bytes, String path, boolean record) {
        if (ownsPath(paths, path)) return bytes;
        String prefix = path.isEmpty() ? "" : path + ".";
        long retainedBytes = bytes;
        for (String field : paths.tailSet(prefix, true)) {
            if (!field.startsWith(prefix)) break;
            retainedBytes -= mutationPathBytes(field);
        }
        long nextBytes = retainedBytes + mutationPathBytes(path);
        // Bound even repeated absent clears, whose final YAML contains no values.
        if (nextBytes > MAX_SNAPSHOT_BYTES) throw new IllegalStateException("Mutation path capacity exceeded");
        if (record) {
            var descendants = paths.tailSet(prefix, true).iterator();
            while (descendants.hasNext()) {
                if (!descendants.next().startsWith(prefix)) break;
                descendants.remove();
            }
            paths.add(path);
        }
        return nextBytes;
    }
    private static long mutationPathBytes(String path) {
        return (long) path.getBytes(StandardCharsets.UTF_8).length + 16;
    }
    private static boolean ownsPath(Set<String> paths, String path) {
        if (paths.contains("") || paths.contains(path)) return true;
        for (int end = path.indexOf('.'); end >= 0; end = path.indexOf('.', end + 1))
            if (paths.contains(path.substring(0, end))) return true;
        return false;
    }

    private static final class FinalMutationSection extends org.bukkit.configuration.MemorySection {
        private final FinalMutationYaml owner;
        private FinalMutationSection(org.bukkit.configuration.ConfigurationSection parent, String path, FinalMutationYaml owner) {
            super(parent, path); this.owner = owner;
        }
        @Override public void set(String path, Object value) {
            owner.write(this, path, () -> { super.set(path, value); return null; });
        }
        @Override public org.bukkit.configuration.ConfigurationSection createSection(String path) {
            return owner.write(this, path, () -> owner.trackedSection(super.createSection(path)));
        }
    }
    private static void collectFinalChanges(String path, Object before, Object after,
            Set<String> explicitPaths, Map<String, Object> values) {
        if (Objects.equals(before, after) || explicitPaths.stream()
                .anyMatch(field -> path.equals(field) || path.startsWith(field + "."))) return;
        // Descend only where the consumer made narrower writes. In particular,
        // advisory-history pruning remains one bounded section replacement.
        if (explicitPaths.stream().anyMatch(field -> field.startsWith(path + "."))
                && (before == null || before instanceof Map<?, ?>) && after instanceof Map<?, ?> next) {
            Map<?, ?> previous = before instanceof Map<?, ?> map ? map : Map.of();
            var keys = new LinkedHashSet<Object>(previous.keySet()); keys.addAll(next.keySet());
            for (Object key : keys) collectFinalChanges(path + "." + key, previous.get(key), next.get(key), explicitPaths, values);
        } else values.put(path, after);
    }

    private void reconcileFinalPermissions(FileConfiguration candidate) {
        // Native final save omits still-offline relative reservations from its timed-only snapshot.
        // Preserve those native-owned rows (and remove revoked ones) after its timer is retired.
        var handler = plugin.getPermissionHandler();
        if (handler != null && handler == observedNativeHandler && handler.getTimer().isShutdown()) {
            try {
                var snapshot = nativePermissionSnapshot(handler); long now = System.currentTimeMillis();
                candidate.set("TimedPermissions", null);
                for (var owner : snapshot.entrySet()) {
                    Map<String, Long> timed = new HashMap<>(owner.getValue().timed());
                    owner.getValue().pending().forEach((node, duration) -> {
                        if (duration > 0) timed.put(node, Math.addExact(now, duration));
                    });
                    List<String> rows = new ArrayList<>();
                    timed.forEach((node, expiry) -> { if (expiry > now) rows.add(node + "%line%" + expiry); });
                    if (!rows.isEmpty()) candidate.set("TimedPermissions." + owner.getKey(), rows);
                }
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Native final permission snapshot unavailable", failure);
            }
        }
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

    // Admission metadata for this owner's existing ordered worker, shared by
    // activations and final mutations. It is not another persistence queue.
    private final List<SnapshotUpdate> pendingUpdates = new ArrayList<>();
    private static final class SnapshotUpdate {
        private final Map<String, Object> values;
        private final Map<String, Object> before;
        private final boolean candidateIncludesChanges;
        private final NavigableSet<String> edited = new TreeSet<>();
        private long editedPathBytes;
        private volatile boolean published;
        private SnapshotUpdate(Map<String, ?> values, FileConfiguration live, boolean candidateIncludesChanges) {
            var copied = new LinkedHashMap<String, Object>();
            values.forEach((path, value) -> copied.put(path, snapshotValue(value)));
            this.values = Collections.unmodifiableMap(copied);
            this.before = new HashMap<>();
            this.candidateIncludesChanges = candidateIncludesChanges;
            values.keySet().forEach(path -> before.put(path, snapshotValue(live.get(path))));
        }
        private void apply(FileConfiguration target, Set<String> capturedEdits) {
            values.forEach((path, value) -> {
                if (!ownsPath(capturedEdits, path) && Objects.equals(snapshotValue(target.get(path)), before.get(path))) {
                    target.set(path, null);
                    if (value instanceof Map<?, ?> map) setSection(target.createSection(path), map);
                    else if (value != null) target.set(path, value);
                }
            });
        }
        private void commit(FileConfiguration target, List<SnapshotView> predecessors) {
            values.forEach((path, value) -> {
                if (ownsPath(edited, path)) return;
                Set<String> protectedPaths = descendantsOf(edited, path);
                // A newer child edit protects only that child; the acknowledged
                // parent can still publish all of its unrelated siblings.
                if (matchesBeforeOrPredecessor(target, path, predecessors, protectedPaths))
                    putRetainingPaths(target, path, value, protectedPaths, target);
            });
        }
        private void rebase(FileConfiguration target, Set<String> capturedEdits,
                List<SnapshotView> predecessors, SnapshotUpdate candidate) {
            values.forEach((path, value) -> {
                if (ownsPath(capturedEdits, path)) return;
                Set<String> protectedPaths = descendantsOf(capturedEdits, path);
                if (candidate != null && candidate.candidateIncludesChanges) {
                    // A later parent replacement owns this whole field. Narrower later
                    // writes protect only their own paths, retaining unrelated siblings.
                    if (ownsPath(candidate.values.keySet(), path)) return;
                    protectedPaths.addAll(descendantsOf(candidate.values.keySet(), path));
                }
                if (matchesBeforeOrPredecessor(target, path, predecessors, protectedPaths))
                    putRetainingPaths(target, path, value, protectedPaths, target);
            });
        }
        private static Set<String> descendantsOf(Set<String> paths, String path) {
            return paths.stream().filter(field -> field.startsWith(path + "."))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        private static void putRetainingPaths(FileConfiguration target, String path, Object value,
                Set<String> protectedPaths, FileConfiguration source) {
            var retained = new LinkedHashMap<String, Object>();
            protectedPaths.forEach(field -> retained.put(field, snapshotValue(source.get(field))));
            putValue(target, path, value);
            retained.forEach((field, retainedValue) -> putValue(target, field, retainedValue));
        }
        private boolean matchesBeforeOrPredecessor(FileConfiguration target, String path, List<SnapshotView> predecessors) {
            return matchesBeforeOrPredecessor(target, path, predecessors, Set.of());
        }
        private boolean matchesBeforeOrPredecessor(FileConfiguration target, String path,
                List<SnapshotView> predecessors, Set<String> protectedPaths) {
            Object current = withoutProtectedPaths(snapshotValue(target.get(path)), path, protectedPaths);
            if (Objects.equals(current, withoutProtectedPaths(before.get(path), path, protectedPaths))) return true;
            // Parent and child mutations can overlap without sharing an exact map key.
            // Project only successfully published predecessors over our original value.
            var expected = new org.bukkit.configuration.file.YamlConfiguration();
            putValue(expected, path, before.get(path));
            for (SnapshotView view : predecessors) {
                if (view.update() == this) break;
                if (!view.update().published) continue;
                view.update().values.forEach((field, value) -> {
                    if (!ownsPath(view.edits(), field) && (field.equals(path)
                            || field.startsWith(path + ".") || path.startsWith(field + ".")))
                        putRetainingPaths(expected, field, value, descendantsOf(view.edits(), field), target);
                });
                if (Objects.equals(current, withoutProtectedPaths(snapshotValue(expected.get(path)), path, protectedPaths))) return true;
            }
            return false;
        }
        private static Object withoutProtectedPaths(Object value, String path, Set<String> protectedPaths) {
            if (protectedPaths.isEmpty()) return value;
            if (ownsPath(protectedPaths, path)) return null;
            if (!(value instanceof Map<?, ?> map)) return value;
            var remaining = new LinkedHashMap<Object, Object>();
            map.forEach((key, child) -> {
                Object retained = withoutProtectedPaths(child, path + "." + key, protectedPaths);
                if (retained != null) remaining.put(key, retained);
            });
            // Empty parents created only for a protected child do not change its siblings.
            return remaining.isEmpty() ? null : remaining;
        }
        private static void putValue(FileConfiguration target, String path, Object value) {
            target.set(path, null);
            if (value instanceof Map<?, ?> map) setSection(target.createSection(path), map);
            else if (value != null) target.set(path, value);
        }
        private static void setSection(org.bukkit.configuration.ConfigurationSection target, Map<?, ?> values) {
            values.forEach((key, value) -> {
                String path = key.toString();
                if (value instanceof Map<?, ?> map) setSection(target.createSection(path), map);
                else target.set(path, value);
            });
        }
    }
    private record SnapshotView(SnapshotUpdate update, Set<String> edits) {}
    private void activationEdited(String path) {
        // Check every owner before recording intent: rejected writes cannot leave
        // partial edit fences on an earlier acknowledged publication.
        for (SnapshotUpdate update : pendingUpdates) {
            if (update.values.keySet().stream().anyMatch(field -> path.isEmpty() || field.equals(path)
                    || field.startsWith(path + ".") || path.startsWith(field + ".")))
                captureMutationPath(update.edited, update.editedPathBytes, path, false);
        }
        for (SnapshotUpdate update : pendingUpdates) {
            if (update.values.keySet().stream().anyMatch(field -> path.isEmpty() || field.equals(path)
                    || field.startsWith(path + ".") || path.startsWith(field + ".")))
                update.editedPathBytes = captureMutationPath(update.edited, update.editedPathBytes, path, true);
        }
        runtimeEdits++;
    }
    private List<SnapshotView> snapshotViews() {
        return pendingUpdates.stream().map(update -> new SnapshotView(update, Set.copyOf(update.edited))).toList();
    }
    /**
     * Publishes activation admission before running any effect, reward, or command hook.
     * The gameplay callback is returned to the Bukkit/Folia owner after storage has
     * acknowledged both the shared snapshot and any caller-owned state.
     */
    public synchronized CompletableFuture<Void> confirmedActivationBeforeGameplay(Map<String, Long> fields,
            java.util.function.Supplier<CompletableFuture<Void>> callerStorage, Runnable gameplay,
            org.bukkit.entity.Entity owner) {
        return prepareActivation(fields, callerStorage, owner)
                .thenCompose(ignored -> ownerStep(owner, () -> confirmedActivation(gameplay))).copy();
    }
    /** Same admission boundary, with an asynchronous owner work completion (bank attachment). */
    public CompletableFuture<Void> confirmedActivationBeforeWork(Map<String, Long> fields,
            java.util.function.Supplier<CompletableFuture<Void>> callerStorage,
            java.util.function.Supplier<CompletableFuture<Void>> gameplay, org.bukkit.entity.Entity owner) {
        return prepareActivation(fields, callerStorage, owner).thenCompose(ignored -> ownerStep(owner, gameplay)).copy();
    }
    private synchronized CompletableFuture<Void> prepareActivation(Map<String, Long> fields,
            java.util.function.Supplier<CompletableFuture<Void>> callerStorage, org.bukkit.entity.Entity owner) {
        if (!isReady() || activationCallbacksRetired) return CompletableFuture.failedFuture(new IllegalStateException("ServerData activation lifecycle unavailable"));
        if (pendingUpdates.size() >= 256 || fields.size() > 2)
            return CompletableFuture.failedFuture(new IllegalStateException("Activation staging capacity exceeded"));
        final SnapshotUpdate update;
        final CompletableFuture<Void> published;
        try {
            update = new SnapshotUpdate(fields, getData(), false);
            // Validate size on a private copy. A rejected admission changes neither live store.
            var copy = parse(getData().saveToString()); update.apply(copy, Set.of()); snapshotBytes(copy);
            byte[] snapshot = snapshotBytes(getData());
            pendingUpdates.add(update);
            published = publishSnapshot(snapshot, List.of(), pendingCalendar,
                    calendarEdits.clone(), null, update);
        } catch (Throwable error) { return CompletableFuture.failedFuture(error); }
        return published.thenCompose(ignored -> ownerStep(owner, callerStorage));
    }
    /** Each phase runs once on its owner; retired or timed-out callbacks cannot execute later. */
    private boolean activationCallbacksRetired;
    private final Set<Runnable> activationRetirements = new HashSet<>();
    /** Retire uninvoked owner callbacks without expiring already accepted storage work. */
    public void retireActivationCallbacks() {
        List<Runnable> pending;
        synchronized (this) {
            activationCallbacksRetired = true;
            pending = List.copyOf(activationRetirements);
            activationRetirements.clear();
        }
        pending.forEach(Runnable::run);
    }
    private CompletableFuture<Void> ownerStep(org.bukkit.entity.Entity owner,
            java.util.function.Supplier<CompletableFuture<Void>> work) {
        var completed = new CompletableFuture<Void>();
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable retire = () -> {
            if (invoked.compareAndSet(false, true)) completed.completeExceptionally(
                    new IllegalStateException("Activation owner dispatch retired"));
        };
        synchronized (this) {
            if (activationCallbacksRetired) { retire.run(); return completed; }
            activationRetirements.add(retire);
        }
        completed.whenComplete((value, error) -> { synchronized (this) { activationRetirements.remove(retire); } });
        Runnable run = () -> {
            if (!invoked.compareAndSet(false, true) || completed.isDone()) return;
            try { work.get().whenComplete((value, error) -> {
                if (error == null) completed.complete(null); else completed.completeExceptionally(error);
            }); }
            catch (Throwable error) { completed.completeExceptionally(error); }
        };
        try {
            if (owner == null) plugin.getBukkitScheduler().runTask(plugin, run);
            else plugin.getBukkitScheduler().runTask(plugin, run, owner);
        } catch (Throwable error) { completed.completeExceptionally(error); }
        // Bound dispatch, but never declare accepted work finished while its storage callback
        // can still commit. Its own completion owns the activation fence until it settles.
        CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS).execute(() -> {
            if (invoked.compareAndSet(false, true)) completed.completeExceptionally(
                    new TimeoutException("Activation owner dispatch timed out"));
        });
        return completed;
    }
    /** The callback must contain only an already captured log message and its file writer. */
    public synchronized void activationLog(Runnable write) {
        var batch = activationWrites.get();
        if (batch != null) batch.add(write);
        else plugin.getStorage().runtimeStorage(() -> { write.run(); return null; });
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
    // Only the serial storage worker reads or replaces this fence. It retains the
    // exact private snapshot and staged intent until an uncertain move is settled.
    private volatile SnapshotFence uncertainSnapshot;
    private CompletableFuture<Void> calendarFenceSettlement;
    /** Never admit a recovered transition while a previous replacement is uncertain. */
    synchronized void requireCalendarAdmission() {
        if (uncertainSnapshot == null) return;
        if ((calendarFenceSettlement == null || calendarFenceSettlement.isDone()) && plugin.getStorage() != null) {
            Path file = plugin.getServerDataFile().getdFile().toPath();
            calendarFenceSettlement = plugin.getStorage().runtimeStorage(() -> {
                try { settleUncertainSnapshot(file); return null; }
                catch (IOException failure) { throw new CompletionException(failure); }
            });
        }
        throw new IllegalStateException("ServerData publication must settle before calendar admission");
    }
    private record SnapshotFence(byte[] bytes, List<SnapshotView> views, SnapshotUpdate update, CalendarUpdate calendar) { }
    private static java.security.MessageDigest snapshotDigest() {
        try { return java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static byte[] fileFingerprint(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid ServerData destination");
        var digest = snapshotDigest();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }
    private void settleUncertainSnapshot(Path file) throws IOException {
        SnapshotFence fence = uncertainSnapshot;
        if (fence == null) return;
        // File presence is not confirmation. Match the complete replacement and
        // explicitly force both the file and directory before allowing a new save.
        if (!Arrays.equals(snapshotDigest().digest(fence.bytes()), fileFingerprint(file))) throw new IOException("ServerData publication remains uncertain");
        DurableFeatureFiles.settle(file);
        synchronized (this) {
            if (fence.calendar() != null) {
                fence.calendar().apply(getData(), calendarEdits); fence.calendar().published = true;
                if (pendingCalendar == fence.calendar()) pendingCalendar = null;
            }
            if (fence.update() != null) {
                fence.update().commit(getData(), fence.views()); fence.update().published = true;
                pendingUpdates.remove(fence.update());
            }
            uncertainSnapshot = null;
        }
    }
    private CompletableFuture<Void> persistSnapshot(List<Runnable> writes) {
        // Serialization is confined to the mutation owner; publication alone runs on storage.
        try { return publishSnapshot(snapshotBytes(getData()), writes, pendingCalendar, calendarEdits.clone(), null); }
        catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }
    private CompletableFuture<Void> publishSnapshot(byte[] snapshot, List<Runnable> writes, CalendarUpdate overlay, long[] edits, CalendarUpdate commit) {
        return publishSnapshot(snapshot, writes, overlay, edits, commit, null);
    }
    private CompletableFuture<Void> publishSnapshot(byte[] snapshot, List<Runnable> writes, CalendarUpdate overlay,
            long[] edits, CalendarUpdate commit, SnapshotUpdate update) {
        List<SnapshotView> updates = snapshotViews();

        Path file = plugin.getServerDataFile().getdFile().toPath();
        pendingSnapshots.incrementAndGet();
        return plugin.getStorage().<Void>runtimeStorage(() -> {
            Path staged = null;
            try {
                settleUncertainSnapshot(file);
                byte[] confirmed = snapshot;
                // This turn follows the calendar publication. Rebase only its confirmed fields in
                // a private worker YAML, never the live cache; failed transitions contribute nothing.
                // Per-field edit fences preserve even a later explicit write of the same old value.
                if (overlay != null) {
                    var copy = parse(new String(snapshot, StandardCharsets.UTF_8));
                    overlay.applyPublishedChain(copy, edits, commit == null ? Set.of() : commit.changes.keySet());
                    confirmed = snapshotBytes(copy);
                }
                if (!updates.isEmpty()) {
                    var copy = parse(new String(confirmed, StandardCharsets.UTF_8));
                    for (SnapshotView view : updates) {
                        // The worker is serial: a following snapshot observes the prior publication result.
                        if (view.update() == update || view.update().published)
                            view.update().rebase(copy, view.edits(), updates, update);
                    }
                    confirmed = snapshotBytes(copy);
                }
                Files.createDirectories(file.toAbsolutePath().getParent());
                if (Files.isSymbolicLink(file)) throw new IOException("ServerData is a symlink");
                staged = Files.createTempFile(file.toAbsolutePath().getParent(), ".server-data-", ".tmp");
                Files.write(staged, confirmed);
                byte[] before = fileFingerprint(file);
                try { publisher.publish(staged, file); }
                catch (IOException | RuntimeException | Error publicationFailure) {
                    boolean unchanged = false;
                    try {
                        // A still-staged candidate and unchanged, different destination
                        // prove rejection before replacement under the file owner.
                        unchanged = Files.exists(staged, LinkOption.NOFOLLOW_LINKS)
                                && !Arrays.equals(before, snapshotDigest().digest(confirmed))
                                && Arrays.equals(before, fileFingerprint(file));
                    } catch (IOException inspectionFailure) { publicationFailure.addSuppressed(inspectionFailure); }
                    if (unchanged) throw publicationFailure;
                    uncertainSnapshot = new SnapshotFence(confirmed, updates, update, commit);
                    // A calendar failure callback can reopen a completed transition.
                    // Settle the already-replaced snapshot before reporting failure;
                    // if settlement cannot be established the fence remains active.
                    if (commit == null) throw publicationFailure;
                    try { settleUncertainSnapshot(file); }
                    catch (IOException | RuntimeException | Error settlementFailure) {
                        if (settlementFailure != publicationFailure) publicationFailure.addSuppressed(settlementFailure);
                        throw publicationFailure;
                    }
                }
                if (commit != null) synchronized (ServerData.this) {
                    commit.apply(getData(), calendarEdits); commit.published = true;
                    if (pendingCalendar == commit) pendingCalendar = null;
                }
                if (update != null) synchronized (ServerData.this) {
                    update.commit(getData(), updates);
                    update.published = true;
                }
                for (Runnable write : writes) write.run();
                return null;
            } catch (IOException | org.bukkit.configuration.InvalidConfigurationException error) { throw new CompletionException(error); }
            finally { if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignored) {} }
        }).whenComplete((ignored, error) -> {
            pendingSnapshots.decrementAndGet();
            if (update != null) synchronized (ServerData.this) {
                if (uncertainSnapshot == null || uncertainSnapshot.update() != update) pendingUpdates.remove(update);
            }
            if (commit != null) synchronized (ServerData.this) {
                if ((uncertainSnapshot == null || uncertainSnapshot.calendar() != commit) && pendingCalendar == commit)
                    pendingCalendar = uncertainSnapshot == null ? null : uncertainSnapshot.calendar();
            }
        }).copy();
    }
    public synchronized void setPerkExperation(Perk perk, long time) {
        activationEdited("Experation." + perk.getPerk());
        getData().set("Experation." + perk.getPerk(), time); saveData();
    }
    public synchronized void clearOfflinePerks() { getData().set("OfflinePerk", null); }
    /** Removes one shutdown record only while it still contains the value read by the restorer. */
    public synchronized boolean removeOfflinePerkIfMatches(String uuid, String perk, long expectedExpiration) {
        String path = "OfflinePerk." + uuid + "." + perk + ".Experiaton";
        if (!getData().contains(path) || getData().getLong(path) != expectedExpiration) return false;
        getData().set(path, null);
        if (getData().isConfigurationSection("OfflinePerk." + uuid + "." + perk)
                && getData().getConfigurationSection("OfflinePerk." + uuid + "." + perk).getKeys(false).isEmpty()) {
            getData().set("OfflinePerk." + uuid + "." + perk, null);
        }
        if (getData().isConfigurationSection("OfflinePerk." + uuid)
                && getData().getConfigurationSection("OfflinePerk." + uuid).getKeys(false).isEmpty()) {
            getData().set("OfflinePerk." + uuid, null);
        }
        if (getData().isConfigurationSection("OfflinePerk")
                && getData().getConfigurationSection("OfflinePerk").getKeys(false).isEmpty()) {
            getData().set("OfflinePerk", null);
        }
        return true;
    }
    public synchronized void saveOfflinePerk(Perk perk) {
        getData().set("OfflinePerk." + perk.getActivater().getUUID() + "." + perk.getPerk() + ".Experiaton",
                perk.isLastForever() ? -1 : perk.getExperation(perk.getActivater()));
    }
}
