package com.bencodez.mcperks.listeners;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import com.bencodez.mcperks.listeners.UsageAdmissions.AdmissionScope;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.mcperks.perk.PerkSystemType;
import com.bencodez.mcperks.scheduling.OwnerTasks;
import com.bencodez.mcperks.userapi.UsageCharges;

/** Owner callbacks for optional interaction effects; one generation owns the ledger. */
public final class UsageEffectListener implements Listener {
    private final MCPerksMain plugin;
    private final UsageCharges ledger;
    private final UsageAdmissions admissions = new UsageAdmissions();
    private volatile NativeBreakSessions nativeBreakSessions = new NativeBreakSessions(4096);
    private static final ThreadLocal<java.util.Set<Player>> activeNativePlayers = ThreadLocal.withInitial(
            () -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    public UsageEffectListener(MCPerksMain plugin) {
        this.plugin = plugin;
        this.ledger = plugin.getUsageCharges();
    }
    public void invalidate() { admissions.invalidate(); NativeBreakSessions old = nativeBreakSessions; nativeBreakSessions = new NativeBreakSessions(4096); old.close(); }
    public CompletableFuture<Void> retire() { nativeBreakSessions.close(); return admissions.retire(); }
    public CompletableFuture<Void> retireAndClose() { return retire().whenComplete((ignored, error) -> ledger.close()); }

    /** Owner-only capture. A current server Player identity, not a matching UUID, proves the session. */
    AdmissionScope capture(Player player) {
        return admissions.capture(player.getUniqueId(), player, currentPlayer(player));
    }
    private Player currentPlayer(Player player) {
        if (!plugin.isEnabled() || !player.isOnline() || !player.isValid()) return null;
        return plugin.getServer().getPlayer(player.getUniqueId());
    }
    private boolean current(AdmissionScope scope, Player player) {
        return admissions.current(scope, player) && plugin.isEnabled() && player.isOnline() && player.isValid()
                && plugin.getServer().getPlayer(scope.owner) == player;
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        admissions.join(player.getUniqueId(), player, currentPlayer(player));
        if (currentPlayer(player) == player) nativeBreakSessions.join(player.getUniqueId(), player);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        admissions.quit(player.getUniqueId(), player);
        NativeBreakSessions sessions = nativeBreakSessions;
        NativeBreakSessions.Session session = sessions.capture(player.getUniqueId(), player);
        if (session != null) sessions.retire(session);
    }

    private Perk eligible(Player player, Perk expected, UsageCharges.Kind kind) {
        return eligible(player, expected, kind, ignored -> true);
    }
    private Perk eligible(Player player, Perk expected, UsageCharges.Kind kind, java.util.function.Predicate<Perk> context) {
        if (!plugin.isEnabled()) return null;
        Effect effect;
        try { effect = Effect.valueOf(kind.name()); }
        catch (IllegalArgumentException absentFeature) { return null; }
        String owner = player.getUniqueId().toString();
        String world = player.getWorld().getName();
        Perk charged = null;
        for (Perk perk : plugin.getPerkHandler().getActivePerks()) {
            if (expected != null && expected != perk) continue;
            if (!perk.getEffects().contains(effect)
                    || !perk.getEffectedPlayers().contains(owner) || !perk.isNotInDisabledWorld(world)
                    || !context.test(perk)) continue;
            if (perk.isUsageChargesEnabled() && perk.getPerkType() != PerkSystemType.PLAYER) continue;
            if (!perk.isUsageChargesEnabled()) return perk;
            if (charged == null) charged = perk;
        }
        return charged;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void refill(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Perk perk = eligible(player, null, UsageCharges.Kind.AutoBlockRefill);
        if (perk == null || player.getGameMode() != GameMode.SURVIVAL || event.getItemInHand().getAmount() != 1) return;
        RefillPlan plan = RefillPlan.capture(player.getInventory(), event.getHand(), event.getItemInHand());
        if (plan == null) return;
        AdmissionScope scope = capture(player);
        if (scope == null) return;
        OwnerTasks.run(plugin, player, () -> submit(player, perk, UsageCharges.Kind.AutoBlockRefill, scope,
                List.of(() -> !event.isCancelled() && plan.apply())), () -> {});
    }

    /** Owner-lane lookup shared with other native-break consumers. */
    public boolean isNativeBreak(Player player) {
        var active = activeNativePlayers.get();
        boolean inFlight = active.contains(player);
        if (active.isEmpty()) activeNativePlayers.remove();
        if (inFlight) return true;
        NativeBreakSessions sessions = nativeBreakSessions;
        NativeBreakSessions.Session session = sessions.capture(player.getUniqueId(), player);
        return session != null && sessions.isCurrent(session) && sessions.guard().isActive(session.scope);
    }

    /** Capture both admission and native-break session identities on the originating owner. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void vein(org.bukkit.event.block.BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (isNativeBreak(player)) return;
        AdmissionScope admission = capture(player);
        if (admission == null || player.getGameMode() != GameMode.SURVIVAL) return;
        NativeBreakSessions sessions = nativeBreakSessions;
        NativeBreakSessions.Session session = sessions.capture(player.getUniqueId(), player);
        if (session == null) session = sessions.join(player.getUniqueId(), player);
        if (session == null || sessions.guard().isActive(session.scope)) return;
        org.bukkit.block.Block origin = event.getBlock();
        org.bukkit.Material ore = origin.getType();
        if (!(ore.name().endsWith("_ORE") || ore == org.bukkit.Material.ANCIENT_DEBRIS)
                || origin.hasMetadata("Placed") || !owns(player, origin)) return;
        var tool = player.getInventory().getItemInMainHand().clone();
        Perk perk = eligible(player, null, UsageCharges.Kind.VeinMining, candidate -> {
            var currentOptions = candidate.getVeinOptions();
            return allowed(currentOptions.materials(), ore) && (currentOptions.tools().isEmpty()
                    ? tool.getType().name().endsWith("_PICKAXE") : allowed(currentOptions.tools(), tool.getType()));
        });
        if (perk == null) return;
        var options = perk.getVeinOptions();
        int slot = player.getInventory().getHeldItemSlot();
        if (!allowed(options.materials(), ore) || (options.tools().isEmpty()
                ? !tool.getType().name().endsWith("_PICKAXE") : !allowed(options.tools(), tool.getType()))) return;
        var blocks = VeinTraversal.connected(origin, ore, options.blocks(), options.distance(),
                () -> plugin.getBukkitScheduler().getFoliaLib().isFolia(), block -> owns(player, block));
        if (blocks.isEmpty()) return;
        NativeBreakSessions.Session captured = session;
        java.util.List<EffectBatch.Unit> units = new java.util.ArrayList<>();
        for (var block : blocks) units.add(() -> {
            if (!sessions.isCurrent(captured) || event.isCancelled() || !owns(player, origin)
                    || origin.getType() == ore || !VeinToolIdentity.matches(player, tool, slot)
                    || !owns(player, block) || block.getType() != ore || block.hasMetadata("Placed")
                    || player.getLocation().distanceSquared(block.getLocation()) > 256) return false;
            boolean broken = sessions.guard().run(captured.scope, () -> {
                var active = activeNativePlayers.get();
                if (!active.add(player)) return false;
                try { return player.breakBlock(block); }
                finally {
                    active.remove(player);
                    if (active.isEmpty()) activeNativePlayers.remove();
                }
            });
            // A false result after invoking the native API does not prove no side effect.
            if (!broken) throw new IllegalStateException("Native break outcome is uncertain");
            return true;
        });
        OwnerTasks.later(plugin, player, () -> {
            if (sessions.isCurrent(captured)) submit(player, perk, UsageCharges.Kind.VeinMining, admission, units);
        }, () -> {}, 1);
    }

    /** Main-hand admission retains final interaction permission separately from native protection. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void harvest(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND
                || event.getClickedBlock() == null || !interactionAllowed(event)) return;
        Player player = event.getPlayer();
        if (isNativeBreak(player) || player.getGameMode() != GameMode.SURVIVAL) return;
        AdmissionScope admission = capture(player);
        if (admission == null) return;
        var block = event.getClickedBlock();
        var crop = block.getType();
        var seed = com.bencodez.mcperks.effects.AutoPlant.seedFor(crop);
        if (seed == null || !owns(player, block)
                || !(block.getBlockData() instanceof org.bukkit.block.data.Ageable age)
                || age.getAge() != age.getMaximumAge()) return;
        var tool = player.getInventory().getItemInMainHand().clone();
        Perk perk = eligible(player, null, UsageCharges.Kind.RightClickHarvest,
                candidate -> allowed(candidate.getHarvestCrops(), crop)
                        && com.bencodez.mcperks.effects.AutoPlant.allows(candidate, tool, crop));
        if (perk == null) return;
        int seedSlot = -1;
        for (int i = 0; i < 36; i++) {
            var item = player.getInventory().getItem(i);
            if (item != null && item.getType() == seed && item.getAmount() > 0) { seedSlot = i; break; }
        }
        if (seedSlot < 0) return;
        int slot = seedSlot;
        var seedBefore = player.getInventory().getItem(slot).clone();
        int toolSlot = player.getInventory().getHeldItemSlot();
        var before = block.getBlockData().clone();
        NativeBreakSessions sessions = nativeBreakSessions;
        var session = sessions.capture(player.getUniqueId(), player);
        if (session == null) session = sessions.join(player.getUniqueId(), player);
        if (session == null) return;
        NativeBreakSessions.Session captured = session;
        java.util.function.BooleanSupplier ready = () -> current(admission, player) && sessions.isCurrent(captured)
                && !isNativeBreak(player) && player.getGameMode() == GameMode.SURVIVAL && interactionAllowed(event)
                && eligible(player, perk, UsageCharges.Kind.RightClickHarvest,
                        candidate -> allowed(candidate.getHarvestCrops(), crop)
                                && com.bencodez.mcperks.effects.AutoPlant.allows(candidate, tool, crop)) == perk
                && owns(player, block) && block.getType() == crop && before.equals(block.getBlockData())
                && player.getLocation().distanceSquared(block.getLocation()) <= 36
                && player.getInventory().getHeldItemSlot() == toolSlot
                && tool.equals(player.getInventory().getItemInMainHand())
                && seedBefore.equals(player.getInventory().getItem(slot));
        OwnerTasks.run(plugin, player, () -> {
            if (!ready.getAsBoolean()) return;
            submit(player, perk, UsageCharges.Kind.RightClickHarvest, admission, List.of(() -> {
                if (!ready.getAsBoolean()) return false;
                return sessions.guard().run(captured.scope, () -> {
                    var active = activeNativePlayers.get();
                    if (!active.add(player)) return false;
                    try {
                        // P3 has confirmed its durable reservation; reserve the exact seed
                        // before any native break callback can mutate this inventory.
                        var remainder = seedBefore.clone();
                        remainder.setAmount(remainder.getAmount() - 1);
                        player.getInventory().setItem(slot, remainder.getAmount() == 0 ? null : remainder);
                        if (!player.breakBlock(block)) throw new IllegalStateException("Native harvest outcome is uncertain");
                        if (!current(admission, player) || !sessions.isCurrent(captured) || !owns(player, block)
                                || block.getType() != org.bukkit.Material.AIR)
                            throw new IllegalStateException("Harvest replacement context changed");
                        var replanted = (org.bukkit.block.data.Ageable) before.clone();
                        replanted.setAge(0);
                        block.setBlockData(replanted);
                        return true;
                    } finally {
                        active.remove(player);
                        if (active.isEmpty()) activeNativePlayers.remove();
                    }
                });
            }));
        }, () -> {});
    }

    @SuppressWarnings("deprecation")
    private static boolean interactionAllowed(org.bukkit.event.player.PlayerInteractEvent event) {
        return !event.isCancelled() && event.useInteractedBlock() != org.bukkit.event.Event.Result.DENY
                && event.useItemInHand() != org.bukkit.event.Event.Result.DENY;
    }

    private static boolean allowed(java.util.List<String> values, org.bukkit.Material material) {
        return values.isEmpty() || values.stream().anyMatch(value -> value.equalsIgnoreCase(material.name()));
    }

    private boolean owns(Player player, org.bukkit.block.Block block) {
        if (!player.getWorld().equals(block.getWorld())
                || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return false;
        if (!plugin.getBukkitScheduler().getFoliaLib().isFolia()) return true;
        try {
            var method = org.bukkit.Bukkit.class.getMethod("isOwnedByCurrentRegion", org.bukkit.Location.class);
            return Boolean.TRUE.equals(method.invoke(null, block.getLocation()));
        } catch (ReflectiveOperationException failure) { return false; }
    }

    /** P3 owns admission and terminal settlement; callers retain their per-unit event/guard checks. */
    void submit(Player player, Perk perk, UsageCharges.Kind kind, AdmissionScope scope, List<EffectBatch.Unit> units) {
        List<EffectBatch.Unit> ordered = List.copyOf(units);
        int maximum = kind == UsageCharges.Kind.AutoBlockRefill ? 1 : kind == UsageCharges.Kind.VeinMining ? 63 : 64;
        if (ordered.size() > maximum) throw new IllegalArgumentException("Invalid effect batch size");
        if (ordered.isEmpty() || !current(scope, player) || eligible(player, perk, kind) != perk
                || player.getGameMode() != GameMode.SURVIVAL) return;
        UsageAdmissions.Batch batch = admissions.admit(scope, player, kind);
        if (batch == null) return;
        boolean charged = perk.isUsageChargesEnabled();
        CompletableFuture<UsageCharges.Reservation> admission = charged
                ? ledger.reserve(scope.owner, kind, ordered.size()) : CompletableFuture.completedFuture(null);
        admission.whenComplete((reservation, error) -> {
            if (error != null || (charged && reservation == null)) { batch.complete(); return; }
            batch.onCancellation(() -> {
                try {
                    CompletableFuture<Void> completion = charged ? ledger.finish(reservation, 0, reservation.units())
                            : CompletableFuture.completedFuture(null);
                    completion.whenComplete((ignored, failure) -> {
                        if (failure != null) plugin.getLogger().warning("Unused charge settlement could not be persisted");
                        batch.complete();
                    });
                } catch (Throwable failure) {
                    batch.complete();
                    plugin.getLogger().warning("Unused charge settlement failed; reserved charges remain spent");
                }
            });
            if (!batch.waiting()) return;
            Runnable execute = () -> {
                if (!batch.execute(player)) { batch.cancel(); return; }
                try {
                    int budget = charged ? reservation.units() : ordered.size();
                    List<EffectBatch.Unit> checked = ordered.stream().<EffectBatch.Unit>map(unit -> () ->
                            current(scope, player) && player.getGameMode() == GameMode.SURVIVAL
                                    && eligible(player, perk, kind) == perk && unit.apply()).toList();
                    EffectBatch.Result result = EffectBatch.execute(checked, budget);
                    CompletableFuture<Void> completion = charged ? ledger.finish(reservation, result.successful(), result.unused())
                            : ledger.record(scope.owner, kind, result.successful());
                    completion.whenComplete((ignored, failure) -> {
                        if (failure != null) plugin.getLogger().warning("Effect completion could not be persisted");
                        if (charged) batch.complete();
                    });
                    if (!charged) batch.complete();
                } catch (Throwable failure) {
                    batch.complete();
                    plugin.getLogger().warning("Effect callback failed; any reserved charge remains spent");
                }
            };
            // No Bukkit/perk/session lookup on this storage-completion lane.
            OwnerTasks.run(plugin, player, execute, batch::cancel);
        });
    }
}
