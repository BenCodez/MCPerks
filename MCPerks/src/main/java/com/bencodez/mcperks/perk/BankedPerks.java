package com.bencodez.mcperks.perk;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.scheduling.OwnerTasks;
import com.bencodez.mcperks.data.FeatureState;
import com.bencodez.mcperks.data.FeatureState.BankKey;
import com.bencodez.mcperks.userapi.MCPerksUser;
/** Online/eligible personal time. Suspension never repeats activation/reward hooks. */
public final class BankedPerks implements Listener {
    private final MCPerksMain plugin;
    private final FeatureState state;
    private final Map<BankKey, Perk> attached = new ConcurrentHashMap<>();
    private final Map<BankKey, BankedClock> clocks = new ConcurrentHashMap<>();
    private final Set<BankKey> pausing = ConcurrentHashMap.newKeySet();
    private final java.util.function.LongSupplier monotonic;
    private final Map<UUID, Player> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> ticking = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong tickIds = new java.util.concurrent.atomic.AtomicLong();
    private volatile int generation;
    private volatile boolean retired;
    // Per-perk admission also covers a server-wide cooldown shared by different players.
    private final Set<String> directAdmissions = ConcurrentHashMap.newKeySet();
    public synchronized boolean beginDirect(Perk perk) {
        return !retired && directAdmissions.size() < 256 && directAdmissions.add(perk.getPerk());
    }
    public void finishDirect(Perk perk) { directAdmissions.remove(perk.getPerk()); }
    private String directKey(MCPerksUser user, Perk perk) {
        return perk.getServerWideCoolDown() > 0 ? perk.getPerk() : "PLAYER:" + user.getUUID() + ":" + perk.getPerk();
    }
    public synchronized boolean beginDirect(MCPerksUser user, Perk perk) {
        return !retired && directAdmissions.size() < 256 && directAdmissions.add(directKey(user, perk));
    }
    public void finishDirect(MCPerksUser user, Perk perk) { directAdmissions.remove(directKey(user, perk)); }
    public boolean hasEntitlement(UUID owner, Perk perk) {
        var bank = state.banks().get(new BankKey(owner, perk.getPerk()));
        return bank != null && bank.milliseconds() > 0;
    }
    private static final Set<Effect> UNSUPPORTED = EnumSet.of(Effect.Commands, Effect.GlobalCommands, Effect.Rewards,
            Effect.CureHunger, Effect.CureSpells, Effect.HealAll, Effect.RandomToolRepair, Effect.ToolRepair,
            Effect.ArmorRepair, Effect.RandomArmorRepair, Effect.AllRepair, Effect.RandomAllRepair,
            Effect.Fly, Effect.DoubleJump, Effect.DoubleJumpUp, Effect.Potions, Effect.TempPermission, Effect.IncreaseMaxHealth, Effect.IncreaseStrength, Effect.IncreaseLuck, Effect.MoveSpeed);
    public BankedPerks(MCPerksMain plugin, FeatureState state) { this(plugin, state, System::nanoTime); }
    BankedPerks(MCPerksMain plugin, FeatureState state, java.util.function.LongSupplier monotonic) {
        this.plugin = plugin; this.state = state; this.monotonic = monotonic;
        state.onBankGrant((key, oldBalance) -> {
            BankedClock clock = clocks.get(key);
            FeatureState.BankGrant boundary = clock == null ? new FeatureState.BankGrant(0, () -> {}, () -> {})
                    : clock.prepareGrant(monotonic.getAsLong(), oldBalance, monotonic);
            return new FeatureState.BankGrant(boundary.elapsed(), () -> {
                boundary.commit().run();
                BankedClock current = clocks.get(key);
                if (current != null && current != clock) {
                    var debit = current.rebaseLateGrant(monotonic.getAsLong(), Math.max(0, oldBalance - boundary.elapsed()));
                    if (debit.milliseconds() > 0) state.elapsed(key, debit.milliseconds(), () -> current.owns(debit))
                            .thenRun(() -> current.acknowledge(debit));
                }
            }, boundary.rollback());
        });
        state.onBankReplacement(key -> {
            BankedClock clock = clocks.get(key);
            FeatureState.BankGrant boundary = clock == null ? new FeatureState.BankGrant(0, () -> {}, () -> {})
                    : clock.prepareReplacement(monotonic);
            return new FeatureState.BankGrant(0, () -> {
                boundary.commit().run();
                // A player can attach/reconnect while this worker's publication is blocked.
                BankedClock current = clocks.get(key);
                if (current != null && current != clock) current.prepareReplacement(monotonic).commit().run();
            }, boundary.rollback());
        });
    }
    public String grantUnavailable(UUID owner, Perk perk, int seconds) {
        if (!state.available()) return "bank storage unavailable";
        if (!supported(perk) || seconds < 1) return "unsupported banked perk";
        if ((long) seconds * 1000 > 31_536_000_000L) return "bank grant exceeds one year";
        BankKey key = new BankKey(owner, perk.getPerk());
        if (!state.banks().containsKey(key) && (state.banks().size() >= 10000 || state.banks().keySet().stream().filter(k -> k.owner().equals(owner)).count() >= 64)) return "bank capacity reached";
        var old = state.banks().get(key);
        if (old != null && (long) seconds * 1000 > 31_536_000_000L - old.milliseconds()) return "bank capacity reached";
        return null;
    }
    public static boolean supported(Perk perk) { return perk.getPerkType() == PerkSystemType.PLAYER && perk.getReActivateEffectsTimer() <= 0 && Collections.disjoint(perk.getEffects(), UNSUPPORTED); }
    public CompletableFuture<Void> grant(MCPerksUser user, Perk perk, int seconds) {
        return publishGrant(user, perk, seconds).thenRun(() -> granted(user, perk));
    }
    public CompletableFuture<Void> grantConfirmed(MCPerksUser user, Perk perk, int seconds) {
        if (!plugin.getConfigFile().getPerkQue()) return grant(user, perk, seconds);
        return publishGrant(user, perk, seconds).thenCompose(ignored -> grantedConfirmed(user, perk));
    }
    private CompletableFuture<FeatureState.Bank> publishGrant(MCPerksUser user, Perk perk, int seconds) {
        String unavailable = grantUnavailable(UUID.fromString(user.getUUID()), perk, seconds);
        if (unavailable != null) return CompletableFuture.failedFuture(new IllegalArgumentException(unavailable));
        BankKey key = new BankKey(UUID.fromString(user.getUUID()), perk.getPerk());
        return state.addBank(key, Math.multiplyExact((long) seconds, 1000));
    }
    /** Attach only a grant that the state worker already published; never adds time a second time. */
    public void granted(MCPerksUser user, Perk perk) {
        plugin.getLocalStatistics().activation();
        Player player = Bukkit.getPlayer(UUID.fromString(user.getUUID()));
        if (player != null) OwnerTasks.run(plugin, player, () -> start(player), () -> stopSession(player));
    }
    public CompletableFuture<Void> grantedConfirmed(MCPerksUser user, Perk perk) {
        var attachment = new CompletableFuture<Void>();
        plugin.getLocalStatistics().activation();
        Player player = Bukkit.getPlayer(UUID.fromString(user.getUUID()));
        if (player == null) attachment.complete(null);
        else OwnerTasks.run(plugin, player, () -> start(player, attachment), () -> { stopSession(player); attachment.complete(null); });
        return attachment;
    }
    public synchronized void start(Player player) {
        start(player, null);
    }
    private static void settled(CompletableFuture<Void> attachment) { if (attachment != null) attachment.complete(null); }
    private synchronized void start(Player player, CompletableFuture<Void> attachment) {
        UUID owner = player.getUniqueId();
        if (retired || !player.isOnline() || Bukkit.getPlayer(owner) != player) { settled(attachment); return; }
        if (state.banks().entrySet().stream().noneMatch(e -> e.getKey().owner().equals(owner) && e.getValue().milliseconds() > 0)) { settled(attachment); return; }
        Long current = ticking.get(owner);
        if (current != null) {
            // A grant to an already ticking owner gets one attachment attempt, not a second timer loop.
            if (attachment != null) pulse(player, generation, current, attachment, false);
            return;
        }
        long token = tickIds.incrementAndGet();
        if (!plugin.getPerkHandler().isRestorationReady() || !state.available() || ticking.size() >= 256 || ticking.putIfAbsent(player.getUniqueId(), token) != null) { settled(attachment); return; }
        sessions.put(owner, player);
        pulse(player, generation, token, attachment, true);
    }
    @EventHandler public void join(PlayerJoinEvent event) { start(event.getPlayer()); }
    @EventHandler public synchronized void world(PlayerChangedWorldEvent event) {
        UUID owner = event.getPlayer().getUniqueId();
        if (sessions.get(owner) != event.getPlayer()) return;
        for (BankKey key : List.copyOf(clocks.keySet())) if (key.owner().equals(owner)) suspend(key);
        start(event.getPlayer());
    }
    @EventHandler public void quit(PlayerQuitEvent event) { stopSession(event.getPlayer()); }
    public void refresh(UUID owner) {
        Player player = Bukkit.getPlayer(owner); if (player != null) OwnerTasks.run(plugin, player, () -> {
            synchronized (BankedPerks.this) {
                if (Bukkit.getPlayer(owner) != player || !player.isOnline()) return;
                for (BankKey key : List.copyOf(attached.keySet())) if (key.owner().equals(owner)) {
                    var bank = state.banks().get(key); if (bank == null || bank.paused() || bank.milliseconds() == 0) suspend(key);
                }
                start(player);
            }
        }, () -> stopSession(player));
    }
    private record Context(MCPerksUser user, boolean ignored) {}
    private void pulse(Player player, int version, long token) {
        pulse(player, version, token, null, true);
    }
    private void pulse(Player player, int version, long token, CompletableFuture<Void> attachment, boolean repeat) {
        UUID owner = player.getUniqueId();
        if (retired || version != generation || !Objects.equals(ticking.get(owner), token)) { settled(attachment); return; }
        if (!player.isOnline()) { stopIfCurrent(owner, token); settled(attachment); return; }
        var ownedBanks = state.banks().entrySet().stream().filter(e -> e.getKey().owner().equals(owner)).limit(64).toList();
        if (ownedBanks.stream().noneMatch(e -> e.getValue().milliseconds() > 0)) { stopIfCurrent(owner, token); settled(attachment); return; }
        // User ignore state can require SQL. Fetch on the bounded feature worker before returning to owner.
        state.storage(() -> { MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(owner); return new Context(user, user.isIgnorePerkEffects()); }).whenComplete((context, readError) -> {
            if (retired || version != generation || !Objects.equals(ticking.get(owner), token) || !plugin.isEnabled()) { settled(attachment); return; }
            OwnerTasks.run(plugin, player, () -> {
                try { synchronized (BankedPerks.this) {
                    if (retired || version != generation || !Objects.equals(ticking.get(owner), token)) { settled(attachment); return; }
                    if (!player.isOnline()) { stopIfCurrent(owner, token); settled(attachment); return; }
                    List<CompletableFuture<?>> checkpoints = new ArrayList<>();
                    for (var entry : ownedBanks) {
                        BankKey key = entry.getKey(); Perk perk = plugin.getPerkHandler().getPerk(key.perk()); var bank = state.banks().get(key);
                        boolean eligible = plugin.getPerkHandler().isRestorationReady() && state.available() && !pausing.contains(key) && readError == null && context != null && !context.ignored() && bank != null && !bank.paused() && bank.milliseconds() > 0
                                && withinActiveLimit(context.user(), key) && perk != null && perk.getInteractionOptions().banked() && supported(perk) && perk.isNotInDisabledWorld(player.getWorld().getName())
                                && plugin.getPerkHandler().getActivePerks().stream().noneMatch(active -> !attached.containsValue(active) && active.getPerk().equals(key.perk()) && active.getEffectedPlayers().contains(owner.toString()));
                        if (eligible) eligible = plugin.getQueuePlanner().attachBank(key, attached.get(key), () -> {
                            if (!attached.containsKey(key)) attach(key, perk, context.user());
                        });
                        ClockDebit checkpoint = reserve(key, eligible, true);
                        BankedClock clock = checkpoint.clock(); BankedClock.Debit debit = checkpoint.debit();
                        long elapsed = debit.milliseconds();
                        if (elapsed > 0) checkpoints.add(debitElapsed(key, clock, debit));
                        if (!eligible) { suspend(key); continue; }
                        if (elapsed > 0) checkpoints.add(state.storage(() -> state.banks().get(key)).whenComplete((updated, error) -> {
                            if (!retired && version == generation && Objects.equals(ticking.get(owner), token) && plugin.isEnabled()) OwnerTasks.run(plugin, player, () -> {
                                synchronized (BankedPerks.this) {
                                    if (retired || version != generation || !Objects.equals(ticking.get(owner), token)) return;
                                    if (error != null || updated == null || updated.paused() || updated.milliseconds() == 0) suspend(key);
                                }
                            }, () -> stopIfCurrent(owner, token));
                        }));
                    }
                    CompletableFuture.allOf(checkpoints.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
                        settled(attachment);
                        if (failure != null) { stopIfCurrent(owner, token); return; }
                        if (repeat && !retired && plugin.isEnabled()) OwnerTasks.later(plugin, player, () -> pulse(player, version, token), () -> stopIfCurrent(owner, token), 20);
                    });
                } } catch (RuntimeException failure) {
                    if (attachment != null) attachment.completeExceptionally(failure);
                    stopIfCurrent(owner, token);
                }
            }, () -> { stopIfCurrent(owner, token); settled(attachment); });
        });
    }
    /** Recheck on the player owner at attachment, after asynchronous grants and manual resume. */
    private boolean withinActiveLimit(MCPerksUser user, BankKey key) {
        if (!plugin.getConfigFile().getLimitPermission()) return true;
        int limit = user.getPerkLimit();
        return limit == -1 || user.getActivePerks() + (attached.containsKey(key) ? 0 : 1) <= limit;
    }
    private void attach(BankKey key, Perk source, MCPerksUser user) {
        Perk clone = source.clone(); clone.setActivater(user);
        clone.getEffectedPlayers().clear(); clone.getEffectedPlayers().add(key.owner().toString()); clone.setActive(true);
        attached.put(key, clone); plugin.getPerkHandler().getActivePerks().add(clone);
        // Only reversible persistent effects are eligible; command/reward effects are rejected at load/grant.
        // Supported effects are read by gameplay listeners; there is no activation hook to replay.
    }
    private void suspend(BankKey key) {
        checkpoint(key);
        Perk perk = attached.remove(key); if (perk == null) return;
        perk.setActive(false);
        plugin.getPerkHandler().getActivePerks().remove(perk); plugin.getQueuePlanner().check();
    }
    private CompletableFuture<?> checkpoint(BankKey key) {
        ClockDebit checkpoint = reserve(key, false, false);
        if (checkpoint == null || checkpoint.debit().milliseconds() == 0) return CompletableFuture.completedFuture(null);
        return debitElapsed(key, checkpoint.clock(), checkpoint.debit());
    }
    private record ClockDebit(BankedClock clock, BankedClock.Debit debit) {}
    private ClockDebit reserve(BankKey key, boolean eligible, boolean create) {
        return state.bankCheckpoint(() -> {
            BankedClock clock = create ? clocks.computeIfAbsent(key, k -> new BankedClock(() -> state.bankReplacementVersion(k))) : clocks.get(key);
            if (clock == null) return null;
            var debit = clock.reserveCheckpoint(monotonic.getAsLong(), eligible, () -> {
                var current = state.banks().get(key); return current == null ? 0 : current.milliseconds();
            });
            return new ClockDebit(clock, debit);
        });
    }
    private CompletableFuture<?> debitElapsed(BankKey key, BankedClock clock, BankedClock.Debit debit) {
        return state.elapsed(key, debit.milliseconds(), () -> clock.owns(debit)).thenRun(() -> clock.acknowledge(debit));
    }
    public synchronized CompletableFuture<FeatureState.Bank> pause(BankKey key, boolean paused) {
        pausing.add(key); suspend(key);
        return state.pauseBank(key, paused).whenComplete((bank, error) -> { pausing.remove(key); if (error == null) refresh(key.owner()); });
    }
    public synchronized boolean deactivate(Perk perk) {
        BankKey key = attached.entrySet().stream().filter(e -> e.getValue() == perk).map(Map.Entry::getKey).findFirst().orElse(null);
        if (key == null) return false;
        pause(key, true); return true;
    }
    private synchronized void stopSession(Player player) { if (sessions.get(player.getUniqueId()) == player) stop(player.getUniqueId()); }
    private synchronized void stopIfCurrent(UUID owner, long token) { if (Objects.equals(ticking.get(owner), token)) stop(owner); }
    private synchronized void stop(UUID owner) {
        ticking.remove(owner); sessions.remove(owner); for (BankKey key : List.copyOf(clocks.keySet())) if (key.owner().equals(owner)) suspend(key);
        clocks.keySet().removeIf(key -> key.owner().equals(owner));
    }
    public synchronized void reload() {
        Set<UUID> owners = new HashSet<>(ticking.keySet());
        for (BankKey key : clocks.keySet()) owners.add(key.owner());
        generation++; ticking.clear(); sessions.clear();
        for (BankKey key : List.copyOf(clocks.keySet())) suspend(key);
        clocks.clear();
        for (UUID owner : owners) {
            Player player = Bukkit.getPlayer(owner);
            if (player != null) OwnerTasks.run(plugin, player, () -> start(player), () -> stopSession(player));
        }
    }
    public synchronized void retire() {
        retired = true; generation++; ticking.clear(); sessions.clear();
        for (BankKey key : List.copyOf(clocks.keySet())) suspend(key);
        clocks.clear(); attached.clear();
    }
    public boolean available(Perk perk) {
        if (!attached.containsValue(perk)) return true;
        UUID owner = UUID.fromString(perk.getActivater().getUUID());
        if (!state.available() || !ticking.containsKey(owner)) return false;
        BankKey key = new BankKey(owner, perk.getPerk());
        BankedClock clock = clocks.get(key);
        return clock != null && clock.remaining(() -> {
            var bank = state.banks().get(key);
            return bank == null || bank.paused() ? 0 : bank.milliseconds();
        }, monotonic.getAsLong()) > 0;
    }
    public boolean attached(Perk perk) { return attached.containsValue(perk); }
}
