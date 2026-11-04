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

/** Refill-only owner callbacks; the ledger remains owned by this listener generation. */
public final class UsageEffectListener implements Listener {
    private final MCPerksMain plugin;
    private final UsageCharges ledger;
    private final UsageAdmissions admissions = new UsageAdmissions();
    public UsageEffectListener(MCPerksMain plugin) {
        this.plugin = plugin;
        this.ledger = plugin.getUsageCharges();
    }
    public void invalidate() { admissions.invalidate(); }
    public CompletableFuture<Void> retire() { return admissions.retire(); }
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
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        admissions.quit(player.getUniqueId(), player);
    }

    private Perk eligible(Player player, Perk expected, UsageCharges.Kind kind) {
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
                    || !perk.getEffectedPlayers().contains(owner) || !perk.isNotInDisabledWorld(world)) continue;
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
