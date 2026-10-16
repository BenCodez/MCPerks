package com.bencodez.mcperks.perk;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.data.FeatureState;
import com.bencodez.mcperks.data.FeatureState.QueueEntry;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.bencodez.mcperks.scheduling.OwnerTasks;
/** Durable start fencing around the existing perk and activation models. */
public final class QueuePlanner {
    private final MCPerksMain plugin;
    private final FeatureState state;
    private final AtomicBoolean starting = new AtomicBoolean();
    private FeatureState.BankKey directStart, startingBank;
    private volatile boolean retired, suspended;
    public void suspend() { suspended = true; invalidate(); }
    public void resume() { suspended = false; check(); }
    private volatile int generation;
    public void invalidate() { generation++; }
    public QueuePlanner(MCPerksMain plugin, FeatureState state) { this.plugin = plugin; this.state = state; }
    public void startChecks() {
        if (!retired && plugin.isEnabled()) plugin.getBukkitScheduler().runTaskLater(plugin, () -> { try { check(); } finally { startChecks(); } }, 1);
    }
    public void retire() { retired = true; }
    public boolean isStarting() { return starting.get(); }
    public synchronized boolean hasDirectStart() { return directStart != null; }
    public synchronized boolean beginDirect(UUID owner, Perk perk) {
        if (retired || suspended || starting.get() || directStart != null || hasAdmissions()
                || state.paused() || !state.queue().isEmpty() || !plugin.getPerkHandler().getActivePerks().isEmpty()) return false;
        directStart = new FeatureState.BankKey(owner, perk.getPerk());
        return true;
    }
    public void finishDirect(UUID owner, Perk perk) {
        synchronized (this) {
            if (Objects.equals(directStart, new FeatureState.BankKey(owner, perk.getPerk()))) directStart = null;
        }
        check();
    }
    /** Hold the existing queue fence through the actual active-list insertion. */
    public synchronized boolean attachBank(FeatureState.BankKey key, Perk current, Runnable attach) {
        if (plugin.getConfigFile().getPerkQue()) {
            if (directStart != null && !directStart.equals(key) || starting.get() && !key.equals(startingBank)
                    || plugin.getPerkHandler().getActivePerks().stream().anyMatch(active -> active != current)) return false;
        }
        attach.run();
        return true;
    }
    private record Credit(String key, int available) {}
    private final Set<UUID> admissions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private synchronized boolean admit(UUID owner) { return !retired && admissions.size() < 256 && admissions.add(owner); }
    public boolean hasAdmissions() { return !admissions.isEmpty(); }
    /** A limited direct start uses the same durable identity and atomic debit as a queued start. */
    public java.util.concurrent.CompletableFuture<Void> startDirect(MCPerksUser user, Perk perk,
            Runnable verifyAdmission,
            java.util.function.Function<java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>>,
                    java.util.concurrent.CompletableFuture<Void>> execute) {
        UUID owner = UUID.fromString(user.getUUID());
        Player player = Bukkit.getPlayer(owner);
        boolean bypass = player != null && player.hasPermission("MCPerks.Perks.BypassCoolDown");
        int version = generation;
        if (!admit(owner)) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Activation admission busy"));
        var work = state.storage(() -> {
            verifyAdmission.run();
            if (!plugin.getUserManager().getDataManager().hasSharedRuntime())
                throw new IllegalStateException("Transactional activation ownership unavailable before debit");
            if (retired || suspended || version != generation || !storedEligible(user, perk, bypass, System.currentTimeMillis()))
                throw new IllegalStateException("Activation eligibility changed");
            int personal = user.getActivations(perk.getPerk());
            if (personal > state.reserved(owner, "PerkActivations_" + perk.getPerk())) return new Credit("PerkActivations_" + perk.getPerk(), personal);
            return new Credit("Activations", user.getActivations());
        }).thenCompose(credit -> state.startDirect(owner, perk.getPerk(), credit.key(), credit.available()))
                .thenCompose(entry -> {
                    var completed = new java.util.concurrent.CompletableFuture<Void>();
                    AtomicBoolean debitAttempted = new AtomicBoolean(), executed = new AtomicBoolean();
                    var consumption = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
                    AtomicBoolean executionSettled = new AtomicBoolean();
                    java.util.function.BiConsumer<Void, Throwable> finish = (ignored, error) -> {
                        final java.util.concurrent.CompletableFuture<Void> accepted;
                        synchronized (consumption) {
                            if (!executionSettled.compareAndSet(false, true)) return;
                            accepted = consumption.get();
                        }
                        var settled = accepted == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null) : accepted;
                        settled.whenComplete((value, debitError) -> {
                            Throwable outcome = error == null ? debitError : error;
                            var saved = outcome != null && debitAttempted.get() ? state.uncertain(entry.id()) : state.complete(entry.id());
                            saved.whenComplete((nothing, saveError) -> {
                                if (saveError != null) {
                                    plugin.getLogger().warning("Direct activation completion persistence failed; retained start fence prevents replay");
                                    completed.completeExceptionally(saveError);
                                } else if (outcome != null) completed.completeExceptionally(outcome);
                                else completed.complete(null);
                            });
                        });
                    };
                    java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> consume = () -> {
                        final java.util.concurrent.CompletableFuture<Void> once;
                        synchronized (consumption) {
                            if (executionSettled.get()) return java.util.concurrent.CompletableFuture.failedFuture(
                                    new IllegalStateException("Activation execution already settled"));
                            if (consumption.get() != null) return consumption.get();
                            once = new java.util.concurrent.CompletableFuture<Void>(); consumption.set(once);
                        }
                        state.storage(() -> {
                            verifyAdmission.run();
                            if (retired || suspended || version != generation) throw new IllegalStateException("Activation lifecycle retired");
                            java.util.function.BooleanSupplier debitOnce = () -> {
                                debitAttempted.set(true);
                                boolean consumed = debit(entry);
                                if (!consumed) debitAttempted.set(false);
                                return consumed;
                            };
                            boolean consumed;
                            if (perk.getInteractionOptions().banked()) {
                                if (plugin.getBankedPerks().grantUnavailable(owner, perk, perk.getTime()) != null)
                                    throw new IllegalStateException("Bank grant unavailable before debit");
                                consumed = state.addBankOnWorker(new FeatureState.BankKey(owner, perk.getPerk()),
                                        Math.multiplyExact((long) perk.getTime(), 1000), debitOnce) != null;
                            } else consumed = debitOnce.getAsBoolean();
                            if (!consumed) throw new IllegalStateException("Activation credit unavailable; no activation consumed");
                            var manager = plugin.getUserManager().getDataManager();
                            manager.cacheUser(owner);
                            if (manager.getPublishedCache(owner) == null) throw new IllegalStateException("Activation cache publication unavailable after debit");
                            return null;
                        }).whenComplete((ignored, error) -> {
                            if (error == null) once.complete(null); else once.completeExceptionally(error);
                        });
                        return once;
                    };
                    try {
                        reply(owner, () -> {
                            if (!executed.compareAndSet(false, true)) return;
                            try { verifyAdmission.run(); execute.apply(consume).whenComplete(finish); }
                            catch (Throwable error) { finish.accept(null, error); }
                        }, () -> { if (executed.compareAndSet(false, true)) finish.accept(null, new IllegalStateException("Activation owner retired")); });
                    } catch (Throwable error) { if (executed.compareAndSet(false, true)) finish.accept(null, error); }
                    return completed;
                });
        return work.whenComplete((ignored, error) -> { admissions.remove(owner); refreshLegacyView(); check(); });
    }
    public void enqueue(MCPerksUser user, Perk perk) {
        UUID owner = UUID.fromString(user.getUUID());
        if (!state.available()) { user.sendMessage("&cQueue state is unavailable; no activation consumed."); return; }
        if (perk.getInteractionOptions().banked() && plugin.getBankedPerks().grantUnavailable(owner, perk, perk.getTime()) != null) { user.sendMessage("&cBank grant is unavailable; no activation consumed."); return; }
        if (!admit(owner)) return;
        state.storage(() -> {
            if (!plugin.getConfigFile().getLimitActivations()) return new Credit("", Integer.MAX_VALUE);
            int personal = user.getActivations(perk.getPerk());
            if (personal - state.reserved(owner, "PerkActivations_" + perk.getPerk()) > 0) return new Credit("PerkActivations_" + perk.getPerk(), personal);
            return new Credit("Activations", user.getActivations());
        }).thenCompose(credit -> state.enqueue(owner, perk.getPerk(), credit.key(), credit.available())).whenComplete((entry, error) -> {
            admissions.remove(owner);
            reply(owner, () -> user.sendMessage(error == null ? "&aPerk queued. Entry: " + entry.id() : "&cPerk could not be queued; no activation consumed."));
            if (error == null) { refreshLegacyView(); check(); }
        });
    }

    public void refreshLegacyView() {
        if (retired) return;
        state.storage(() -> {
            Map<MCPerksUser, Perk> snapshot = new LinkedHashMap<>();
            for (QueueEntry entry : state.queue()) {
                Perk perk = plugin.getPerkHandler().getPerk(entry.perk());
                if (perk != null) snapshot.put(plugin.getMcperksUserManager().getMCPerksUser(entry.owner()), perk);
            }
            if (!retired && plugin.isEnabled()) plugin.getBukkitScheduler().runTask(plugin, () -> {
                synchronized (plugin.getPerkHandler().que) { plugin.getPerkHandler().que.clear(); plugin.getPerkHandler().que.putAll(snapshot); }
            });
            return null;
        });
    }
    public void check() {
        if (retired || suspended || !plugin.getPerkHandler().isRestorationReady() || !plugin.isEnabled() || !state.available() || state.paused() || !plugin.getPerkHandler().getActivePerks().isEmpty()) return;
        List<QueueEntry> snapshot = state.queue();
        synchronized (this) {
            if (snapshot.isEmpty() || directStart != null || !plugin.getPerkHandler().getActivePerks().isEmpty() || !starting.compareAndSet(false, true)) return;
            startingBank = new FeatureState.BankKey(snapshot.getFirst().owner(), snapshot.getFirst().perk());
        }
        try {
            QueueEntry entry = snapshot.getFirst();
            if (entry.stage() != FeatureState.Stage.WAITING) { starting.set(false); return; }
            reply(entry.owner(), () -> prepare(entry), () -> starting.set(false));
            // Offline players are deliberately left queued; next login/expiry checks again.
            if (Bukkit.getPlayer(entry.owner()) == null) starting.set(false);
        } catch (RuntimeException failure) {
            starting.set(false);
            throw failure;
        }
    }
    private void prepare(QueueEntry entry) {
        int version = generation;
        Player player = Bukkit.getPlayer(entry.owner()); Perk perk = plugin.getPerkHandler().getPerk(entry.perk());
        if (retired || suspended || player == null || perk == null || startUnavailable(entry) != null || !canStart(player, perk)) { starting.set(false); return; }
        boolean bypassCooldown = player.hasPermission("MCPerks.Perks.BypassCoolDown");
        java.util.concurrent.atomic.AtomicReference<MCPerksUser> resolvedUser = new java.util.concurrent.atomic.AtomicReference<>();
        state.claim(entry.id()).whenComplete((claimed, failure) -> {
            if (failure != null || claimed == null) { starting.set(false); return; }
            if (retired) { starting.set(false); return; }
            state.storage(() -> {
                if (retired || suspended || version != generation) return Confirmation.INELIGIBLE;
                MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(claimed.owner()); resolvedUser.set(user);
                if (!storedEligible(user, perk, bypassCooldown, System.currentTimeMillis())) return Confirmation.INELIGIBLE;
                if (perk.getInteractionOptions().banked()) {
                    if (plugin.getBankedPerks().grantUnavailable(claimed.owner(), perk, perk.getTime()) != null) return Confirmation.INELIGIBLE;
                    if (state.addBankOnWorker(new FeatureState.BankKey(claimed.owner(), perk.getPerk()),
                            Math.multiplyExact((long) perk.getTime(), 1000), () -> debit(claimed)) == null) return Confirmation.INELIGIBLE;
                } else if (!debit(claimed)) return Confirmation.INELIGIBLE;
                if (!claimed.credit().isEmpty()) {
                    // Atomic SQL commit retires the old cache. Populate its canonical successor off the owner
                    // before legacy activation reads ignore/expiration state on the game thread.
                    var manager = plugin.getUserManager().getDataManager();
                    manager.cacheUser(claimed.owner());
                    if (manager.getPublishedCache(claimed.owner()) == null) throw new IllegalStateException("Activation cache publication unavailable after debit");
                }
                return Confirmation.CONFIRMED;
            }).whenComplete((confirmed, debitError) -> {
                if (confirmed == Confirmation.INELIGIBLE) {
                    state.releaseClaim(claimed.id()).whenComplete((ignored, error) -> starting.set(false)); return;
                }
                if (debitError != null || confirmed != Confirmation.CONFIRMED) {
                    state.uncertain(claimed.id()); starting.set(false);
                    reply(claimed.owner(), () -> { if (resolvedUser.get() != null) resolvedUser.get().sendMessage("&cQueued start requires review: activation persistence could not be confirmed. Entry " + claimed.id()); });
                    return;
                }
                AtomicBoolean executed = new AtomicBoolean();
                reply(claimed.owner(), () -> {
                    if (!executed.compareAndSet(false, true)) return;
                    try {
                        Player current = Bukkit.getPlayer(claimed.owner());
                        if (retired || suspended || version != generation || current == null || !canStart(current, perk)) { state.uncertain(claimed.id()); starting.set(false); return; }
                        (perk.getInteractionOptions().banked() ? perk.executePublishedBank(resolvedUser.get()) : perk.executeConfirmed(resolvedUser.get(), perk.getTime())).whenComplete((nothing, executionError) -> {
                            starting.set(false);
                            if (executionError != null) state.uncertain(claimed.id());
                            else { plugin.getLocalStatistics().queueStart(System.currentTimeMillis() - claimed.admitted()); state.complete(claimed.id()).whenComplete((ignored, error) -> { if (error != null) plugin.getLogger().warning("Queue completion persistence failed; start fence prevents replay"); else { refreshLegacyView(); check(); } }); }
                        });
                    } catch (Throwable uncertain) { state.uncertain(claimed.id()); starting.set(false); }
                }, () -> { state.uncertain(claimed.id()); starting.set(false); });
                if (Bukkit.getPlayer(claimed.owner()) == null) { state.uncertain(claimed.id()); starting.set(false); }
            });
        });
    }
    private enum Confirmation { CONFIRMED, INELIGIBLE, UNCERTAIN }
    private boolean canStart(Player player, Perk perk) {
        return plugin.getPerkHandler().isRestorationReady() && plugin.getPerkHandler().getActivePerks().isEmpty() && perk.isNotInDisabledWorld(player.getWorld().getName());
    }
    private boolean storedEligible(MCPerksUser user, Perk perk, boolean bypass, long now) {
        if (!plugin.getMcperksServerData().isReady()) return false;
        long cooldown = Math.max(user.getPerkCoolDown(perk), plugin.getMcperksServerData().getServerCooldown(perk.getPerk()));
        return (bypass || cooldown <= now) && (perk.getLimit() <= 0 || user.getPerkTimesUsed(perk) < perk.getLimit());
    }
    /** Advisory read on the storage worker; actual consumption still uses the atomic debit. */
    private boolean creditAvailable(QueueEntry entry) {
        if (entry.credit().isEmpty()) return true;
        var manager = plugin.getUserManager().getDataManager();
        if (!manager.hasSharedRuntime()) return false;
        boolean available = manager.withAtomicUserTransaction(entry.owner(), plugin.getStorageType(), Map.of(), transaction ->
                transaction.readRow().stream().filter(column -> column.getName().equals(entry.credit()))
                        .findFirst().map(column -> column.getValue().getInt() > 0).orElse(false));
        // Even read-only transactions retire the canonical cache on successful completion.
        manager.cacheUser(entry.owner());
        if (manager.getPublishedCache(entry.owner()) == null) throw new IllegalStateException("Activation cache publication unavailable after inspection");
        return available;
    }
    private boolean debit(QueueEntry entry) {
        if (entry.credit().isEmpty()) return true;
        var manager = plugin.getUserManager().getDataManager();
        // Shared runtimes expose actual transactional completion. Unsupported stores fail closed.
        if (manager.hasSharedRuntime()) {
            return manager.withAtomicUserTransaction(entry.owner(), plugin.getStorageType(), Map.of(), transaction -> {
                int current = transaction.readRow().stream().filter(column -> column.getName().equals(entry.credit())).findFirst().map(column -> column.getValue().getInt()).orElse(0);
                if (current < 1) return false;
                transaction.writeValues(Map.of(entry.credit(), new com.bencodez.simpleapi.sql.data.DataValueInt(current - 1)));
                return true;
            });
        }
        throw new IllegalStateException("Transactional activation ownership unavailable");
    }
    public String startUnavailable(QueueEntry entry) {
        if (!state.available()) return "feature storage unavailable";
        if (!plugin.getPerkHandler().isRestorationReady()) return "legacy perk restoration unavailable";
        if (!entry.credit().isEmpty() && !plugin.getUserManager().getDataManager().hasSharedRuntime()) return "transactional activation storage unavailable";
        Perk perk = plugin.getPerkHandler().getPerk(entry.perk());
        if (perk == null) return "perk definition unavailable";
        if (perk.getInteractionOptions().banked()) return plugin.getBankedPerks().grantUnavailable(entry.owner(), perk, perk.getTime());
        return null;
    }
    /** Estimated waits only when every predecessor has a finite absolute duration and known eligibility. */
    public java.util.concurrent.CompletableFuture<Map<UUID, Long>> estimatedWaits() {
        List<QueueEntry> entries = state.queue().stream().limit(32).toList();
        List<Perk> active = List.copyOf(plugin.getPerkHandler().getActivePerks());
        long now = System.currentTimeMillis(), initial = 0;
        if (!plugin.getPerkHandler().isRestorationReady() || !state.available() || state.paused() || starting.get()) return java.util.concurrent.CompletableFuture.completedFuture(Map.of());
        for (Perk perk : active) {
            if (perk.getInteractionOptions().banked() || perk.isLastForever() || perk.getKnownExpiration() <= 0) return java.util.concurrent.CompletableFuture.completedFuture(Map.of());
            initial = Math.max(initial, Math.max(0, perk.getKnownExpiration() - now));
        }
        List<java.util.concurrent.CompletableFuture<Boolean>> checks = new ArrayList<>();
        for (QueueEntry entry : entries) {
            var eligible = new java.util.concurrent.CompletableFuture<Boolean>(); checks.add(eligible);
            Perk perk = plugin.getPerkHandler().getPerk(entry.perk());
            if (entry.stage() != FeatureState.Stage.WAITING || startUnavailable(entry) != null || perk == null || perk.getInteractionOptions().banked() || perk.getTime() <= 0) { eligible.complete(false); continue; }
            Player player = Bukkit.getPlayer(entry.owner());
            OwnerTasks.run(plugin, player, () -> {
                boolean world = perk.isNotInDisabledWorld(player.getWorld().getName());
                boolean bypass = player.hasPermission("MCPerks.Perks.BypassCoolDown");
                // Starts wait until every active perk ends; the existing positive per-player cap then permits one start.
                if (!world) { eligible.complete(false); return; }
                state.storage(() -> {
                    var user = plugin.getMcperksUserManager().getMCPerksUser(entry.owner());
                    return storedEligible(user, perk, bypass, now) && creditAvailable(entry);
                }).whenComplete((value, error) -> eligible.complete(error == null && Boolean.TRUE.equals(value)));
            }, () -> eligible.complete(false));
        }
        long base = initial;
        return java.util.concurrent.CompletableFuture.allOf(checks.toArray(java.util.concurrent.CompletableFuture[]::new)).thenApply(ignored -> {
            if (!state.available() || state.paused() || !state.queue().stream().limit(32).toList().equals(entries) || !List.copyOf(plugin.getPerkHandler().getActivePerks()).equals(active)) return Map.of();
            Map<UUID, Long> result = new HashMap<>(); long wait = base;
            for (int i = 0; i < entries.size(); i++) {
                if (!checks.get(i).join()) break;
                result.put(entries.get(i).id(), wait / 1000);
                wait += (long) plugin.getPerkHandler().getPerk(entries.get(i).perk()).getTime() * 1000;
            }
            return Map.copyOf(result);
        });
    }
    private void reply(UUID owner, Runnable task) { reply(owner, task, () -> {}); }
    private void reply(UUID owner, Runnable task, Runnable dropped) {
        if (retired || !plugin.isEnabled()) { dropped.run(); return; }
        OwnerTasks.run(plugin, Bukkit.getPlayer(owner), task, dropped);
    }
}
