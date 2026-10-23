package com.bencodez.mcperks.listeners;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.scheduling.OwnerTasks;
import com.bencodez.mcperks.data.LocalStatistics.Skip;
import com.bencodez.mcperks.effects.AutoPlant;
import com.bencodez.mcperks.perk.*;
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.userapi.UsageCharges;
import com.bencodez.mcperks.userapi.UsageCharges.Kind;

/** New automation effects. World/inventory access always runs on the player's owner. */
public final class UsageEffectListener implements Listener {
    private final MCPerksMain plugin;
    private final UsageCharges ledger;
    private final Set<UUID> nativeBreaks = ConcurrentHashMap.newKeySet();
    private final Map<String, Batch> pending = new ConcurrentHashMap<>();
    private static final class Batch {
        final AtomicBoolean invoked = new AtomicBoolean();
        final CompletableFuture<Void> settled = new CompletableFuture<>();
        volatile Runnable cancel = () -> invoked.compareAndSet(false, true);
    }
    public record RecentSkip(Effect effect, Skip reason, long time) {}
    private final Map<UUID, RecentSkip> recentSkips = new ConcurrentHashMap<>();
    public RecentSkip recentSkip(UUID owner) { return recentSkips.get(owner); }
    private void skip(Player player, Effect effect, Skip reason) {
        noteSkip(player.getUniqueId(), effect, reason);
    }
    private synchronized void noteSkip(UUID owner, Effect effect, Skip reason) {
        if (recentSkips.size() < 256 || recentSkips.containsKey(owner)) recentSkips.put(owner, new RecentSkip(effect, reason, System.currentTimeMillis()));
        plugin.getLocalStatistics().skip(reason);
    }
    private volatile int generation;
    private volatile boolean retired;
    private record SneakingSession(long token, Player player) {}
    private final ConcurrentMap<UUID, SneakingSession> sneakingSessions = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong nextSneakingSession = new java.util.concurrent.atomic.AtomicLong();
    private final Object sneakingLock = new Object();
    private final Method ownedRegion;
    public UsageEffectListener(MCPerksMain plugin) {
        this.plugin = plugin;
        this.ledger = plugin.getUsageCharges();
        Method method = null;
        try { method = Bukkit.class.getMethod("isOwnedByCurrentRegion", Location.class); } catch (ReflectiveOperationException ignored) {}
        ownedRegion = method;
    }
    public void invalidate() { synchronized (sneakingLock) { generation++; } }
    /** Re-sample a connected player on that player's Bukkit/Folia owner. */
    public void synchronizeSneaking(Player player) {
        if (player == null || retired || !plugin.isEnabled()) return;
        UUID owner = player.getUniqueId();
        long session;
        int version;
        synchronized (sneakingLock) {
            if (retired) return;
            session = nextSneakingSession.incrementAndGet();
            sneakingSessions.put(owner, new SneakingSession(session, player));
            version = generation;
        }
        OwnerTasks.run(plugin, player, () -> {
            boolean sneaking = player.isSneaking();
            synchronized (sneakingLock) {
                if (!retired && version == generation && isCurrent(owner, player, session)
                        && plugin.isEnabled() && player.isOnline()) {
                    plugin.getPerkHandler().setPrecisionSneaking(owner, sneaking);
                }
            }
        }, () -> clearSneaking(owner, player, version, session));
    }
    /** Clear only the session that requested cleanup; a rejoined player remains authoritative. */
    public void clearSneaking(Player player) {
        if (player != null) {
            synchronized (sneakingLock) {
                SneakingSession current = sneakingSessions.get(player.getUniqueId());
                if (current != null && current.player() == player) clearSneaking(player.getUniqueId(), player, generation, current.token());
            }
        }
    }
    private boolean isCurrent(UUID owner, Player player, long session) {
        SneakingSession current = sneakingSessions.get(owner);
        return current != null && current.token() == session && current.player() == player;
    }
    private void clearSneaking(UUID owner, Player player, int version, long session) {
        synchronized (sneakingLock) {
            SneakingSession current = sneakingSessions.get(owner);
            if (version == generation && current != null && current.token() == session && current.player() == player
                    && sneakingSessions.remove(owner, current)) {
                plugin.getPerkHandler().setPrecisionSneaking(owner, false);
            }
        }
    }
    /** Resample all players present at enable/reload without reading player state off-owner. */
    public void synchronizeOnlinePlayers() {
        if (retired || !plugin.isEnabled()) return;
        for (Player player : Bukkit.getOnlinePlayers()) synchronizeSneaking(player);
    }
    public synchronized CompletableFuture<Void> retire() {
        retired = true; invalidate();
        synchronized (sneakingLock) { sneakingSessions.clear(); }
        List<Batch> batches = List.copyOf(pending.values());
        batches.forEach(batch -> batch.cancel.run());
        return CompletableFuture.allOf(batches.stream().map(batch -> batch.settled).toArray(CompletableFuture[]::new));
    }
    /** Seal admissions, release callbacks proved unused, then close their original ledger without owner waits. */
    public CompletableFuture<Void> retireAndClose() {
        return retire().whenComplete((ignored, error) -> ledger.close());
    }
    public boolean isNativeBreak(UUID player) { return nativeBreaks.contains(player); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void sneak(PlayerToggleSneakEvent event) {
        if (event.isCancelled()) return;
        synchronizeSneaking(event.getPlayer());
    }
    @EventHandler public void join(PlayerJoinEvent event) { synchronizeSneaking(event.getPlayer()); plugin.getQueuePlanner().check(); }
    @EventHandler public void quit(PlayerQuitEvent event) { clearSneaking(event.getPlayer()); recentSkips.remove(event.getPlayer().getUniqueId()); }

    private Perk eligible(Player player, Effect effect) {
        return eligible(player, effect, candidate -> true);
    }
    private Perk eligible(Player player, Effect effect, java.util.function.Predicate<Perk> context) {
        if (retired || !plugin.isEnabled()) return null;
        plugin.getPerkHandler().setPrecisionSneaking(player.getUniqueId(), player.isSneaking());
        var decision = plugin.getPerkHandler().effectDecision(effect, player.getUniqueId().toString(), player.getWorld().getName());
        if (decision != PerkHandler.Decision.ACTIVE) {
            skip(player, effect, switch (decision) {
                case OUTSIDE_AUDIENCE -> Skip.AUDIENCE; case DISABLED_WORLD -> Skip.WORLD; case PRECISION_SUPPRESSED -> Skip.PRECISION;
                case BANKED_PAUSED -> Skip.BANKED; default -> Skip.INACTIVE;
            }); return null;
        }
        Perk chargedCandidate = null;
        for (Perk perk : plugin.getPerkHandler().getActivePerks()) {
            if (!perk.getEffects().contains(effect) || !perk.getEffectedPlayers().contains(player.getUniqueId().toString())
                    || !perk.isNotInDisabledWorld(player.getWorld().getName())
                    || (plugin.getBankedPerks() != null && !plugin.getBankedPerks().available(perk)) || !context.test(perk)) continue;
            if (charged(perk) && perk.getPerkType() != PerkSystemType.PLAYER) { skip(player, effect, Skip.SHARED_CHARGES); continue; } // No stable shared charge owner.
            // Prefer a matching unlimited grant. Its gameplay does not depend on optional
            // charge storage, and a reservation on another grant cannot hide it later.
            if (!charged(perk)) return perk;
            if (chargedCandidate == null) chargedCandidate = perk;
        }
        return chargedCandidate;
    }
    private boolean charged(Perk perk) {
        return perk.getInteractionOptions().charged();
    }
    private boolean owns(Player player, Block block) {
        if (!player.getWorld().equals(block.getWorld()) || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return false;
        if (!plugin.getBukkitScheduler().getFoliaLib().isFolia()) return true;
        if (ownedRegion == null) return false;
        try { return Boolean.TRUE.equals(ownedRegion.invoke(null, block.getLocation())); }
        catch (ReflectiveOperationException e) { return false; }
    }
    private boolean matchesTool(Player player, ItemStack expected) { return player.getInventory().getItemInMainHand().equals(expected); }
    private void later(Player player, Runnable action) {
        int version = generation;
        if (!retired) OwnerTasks.run(plugin, player, () -> {
            if (!retired && version == generation && plugin.isEnabled()) action.run();
        }, () -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void refill(BlockPlaceEvent event) {
        Player player = event.getPlayer(); Perk perk = eligible(player, Effect.AutoBlockRefill);
        if (perk == null || player.getGameMode() != GameMode.SURVIVAL || event.getItemInHand().getAmount() != 1) return;
        RefillPlan plan = RefillPlan.capture(player.getInventory(), event.getHand(), event.getItemInHand());
        if (plan == null) return;
        later(player, () -> run(player, perk, Kind.AutoBlockRefill, List.of(() -> {
            if (event.isCancelled() || eligible(player, Effect.AutoBlockRefill, candidate -> candidate == perk) != perk || player.getGameMode() != GameMode.SURVIVAL) return false;
            boolean applied = plan.apply(); if (!applied) skip(player, Effect.AutoBlockRefill, Skip.INVENTORY_CHANGED); return applied;
        })));
    }

    private record Point(int x, int y, int z) {}
    private static final int[][] NEIGHBORS = { {1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1} };
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void vein(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (nativeBreaks.contains(player.getUniqueId()) || player.getGameMode() != GameMode.SURVIVAL) return;
        Block origin = event.getBlock(); Material ore = origin.getType();
        Material selectedTool = player.getInventory().getItemInMainHand().getType();
        Perk perk = eligible(player, Effect.VeinMining, candidate -> {
            var options = candidate.getInteractionOptions();
            return allowed(options.veinMaterials(), ore) && (options.veinTools().isEmpty()
                    ? selectedTool.name().endsWith("_PICKAXE") : allowed(options.veinTools(), selectedTool));
        });
        if (perk == null) return;
        if (!(ore.name().endsWith("_ORE") || ore == Material.ANCIENT_DEBRIS) || origin.hasMetadata("Placed")) return;
        var data = perk.getInteractionOptions();
        if (!allowed(data.veinMaterials(), ore)) { skip(player, Effect.VeinMining, Skip.MATERIAL); return; }
        ItemStack tool = player.getInventory().getItemInMainHand().clone();
        int toolSlot = player.getInventory().getHeldItemSlot();
        List<String> tools = data.veinTools();
        if (tools.isEmpty() ? !tool.getType().name().endsWith("_PICKAXE") : !allowed(tools, tool.getType())) { skip(player, Effect.VeinMining, Skip.TOOL); return; }
        int maximum = data.veinBlocks();
        int distance = data.veinDistance();
        if (!owns(player, origin)) return;
        List<Block> blocks = connected(player, origin, ore, maximum, distance);
        if (blocks.isEmpty()) return;
        List<EffectBatch.Unit> units = new ArrayList<>();
        for (Block block : blocks) units.add(() -> {
            if (player.getGameMode() != GameMode.SURVIVAL || event.isCancelled() || !owns(player, origin) || origin.getType() == ore || eligible(player, Effect.VeinMining, candidate -> candidate == perk) != perk
                    || !matchesTool(player, tool, toolSlot) || !owns(player, block) || block.getType() != ore || block.hasMetadata("Placed")
                    || player.getLocation().distanceSquared(block.getLocation()) > 256) return false;
            boolean broken = nativeBreak(player, block); if (!broken) skip(player, Effect.VeinMining, Skip.NATIVE_BREAK_DENIED); return broken;
        });
        later(player, () -> run(player, perk, Kind.VeinMining, units));
    }
    private boolean matchesTool(Player player, ItemStack expected, int selectedSlot) {
        if (player.getInventory().getHeldItemSlot() != selectedSlot) return false;
        ItemStack actual = player.getInventory().getItemInMainHand();
        if (actual == null || actual.getType() != expected.getType()) return false;
        var actualMeta = actual.getItemMeta(); var expectedMeta = expected.getItemMeta();
        // Native breaks change only durability. All other metadata identifies the admitted tool.
        if (actualMeta instanceof org.bukkit.inventory.meta.Damageable damage) damage.setDamage(0);
        if (expectedMeta instanceof org.bukkit.inventory.meta.Damageable damage) damage.setDamage(0);
        return Objects.equals(actualMeta, expectedMeta);
    }
    private List<Block> connected(Player player, Block origin, Material ore, int maximum, int distance) {
        List<Block> found = new ArrayList<>(); Set<Point> visited = new HashSet<>(); ArrayDeque<Point> queue = new ArrayDeque<>();
        Point start = new Point(origin.getX(), origin.getY(), origin.getZ()); queue.add(start);
        while (!queue.isEmpty() && found.size() < maximum - 1 && visited.size() < 512) {
            Point p = queue.remove(); if (!visited.add(p)) continue;
            if (Math.abs(p.x-start.x) > distance || Math.abs(p.y-start.y) > distance || Math.abs(p.z-start.z) > distance
                    || p.y < origin.getWorld().getMinHeight() || p.y >= origin.getWorld().getMaxHeight()) continue;
            // Check coordinates before obtaining a Block; never load/access an adjacent unloaded chunk.
            if (!origin.getWorld().isChunkLoaded(p.x >> 4, p.z >> 4)) continue;
            if (plugin.getBukkitScheduler().getFoliaLib().isFolia() && ((p.x >> 4) != (start.x >> 4) || (p.z >> 4) != (start.z >> 4))) continue;
            Block b = origin.getWorld().getBlockAt(p.x, p.y, p.z);
            if (!owns(player, b) || b.getType() != ore || b.hasMetadata("Placed")) continue;
            if (!p.equals(start)) found.add(b);
            for (int[] offset : NEIGHBORS) queue.add(new Point(p.x+offset[0], p.y+offset[1], p.z+offset[2]));
        }
        return found;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void harvest(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null
                || !interactionAllowed(event)) return;
        Player player = event.getPlayer(); Block block = event.getClickedBlock();
        Perk perk = eligible(player, Effect.RightClickHarvest, candidate -> allowed(candidate.getInteractionOptions().crops(), block.getType())
                && AutoPlant.allows(candidate, player.getInventory().getItemInMainHand(), block.getType()));
        if (perk == null || player.getGameMode() != GameMode.SURVIVAL || !owns(player, block)) return;
        Material crop = block.getType(); Material seed = AutoPlant.seedFor(crop);
        if (seed == null || !(block.getBlockData() instanceof Ageable age) || age.getAge() != age.getMaximumAge()
                || !allowed(perk.getInteractionOptions().crops(), crop)
                || !AutoPlant.allows(perk, player.getInventory().getItemInMainHand(), crop)) return;
        int seedSlot = -1;
        for (int i = 0; i < 36; i++) { ItemStack item = player.getInventory().getItem(i); if (item != null && item.getType() == seed && item.getAmount() > 0) { seedSlot = i; break; } }
        if (seedSlot < 0) { skip(player, Effect.RightClickHarvest, Skip.SEED); return; }
        int slot = seedSlot; ItemStack seedBefore = player.getInventory().getItem(slot).clone();
        ItemStack tool = player.getInventory().getItemInMainHand().clone();
        int toolSlot = player.getInventory().getHeldItemSlot(); BlockData before = block.getBlockData().clone();
        later(player, () -> {
            // Final interaction protection is distinct from native block-break protection.
            if (!interactionAllowed(event)) { skip(player, Effect.RightClickHarvest, Skip.INTERACTION_DENIED); return; }
            run(player, perk, Kind.RightClickHarvest, List.of(() -> {
                if (player.getGameMode() != GameMode.SURVIVAL || !interactionAllowed(event) || eligible(player, Effect.RightClickHarvest, candidate -> candidate == perk) != perk || !owns(player, block) || !before.equals(block.getBlockData())
                        || player.getLocation().distanceSquared(block.getLocation()) > 36
                        || player.getInventory().getHeldItemSlot() != toolSlot || !matchesTool(player, tool) || !seedBefore.equals(player.getInventory().getItem(slot))) return false;
                // Reserve the replant seed before native break callbacks can change inventory.
                ItemStack remainder = seedBefore.clone(); remainder.setAmount(remainder.getAmount() - 1);
                player.getInventory().setItem(slot, remainder.getAmount() == 0 ? null : remainder);
                boolean broken = nativeBreak(player, block);
                if (!broken) {
                    skip(player, Effect.RightClickHarvest, Skip.NATIVE_BREAK_DENIED);
                    // Restore only into the unchanged slot; otherwise give the exact seed through normal inventory/drop handling.
                    ItemStack current = player.getInventory().getItem(slot);
                    if ((remainder.getAmount() == 0 && (current == null || current.getType() == Material.AIR)) || remainder.equals(current)) player.getInventory().setItem(slot, seedBefore);
                    else { ItemStack returned = seedBefore.clone(); returned.setAmount(1);
                        for (ItemStack overflow : player.getInventory().addItem(returned).values()) player.getWorld().dropItemNaturally(player.getLocation(), overflow); }
                    return false;
                }
                if (block.getType() != Material.AIR) throw new IllegalStateException("Harvest replacement changed by another listener");
                Ageable replanted = (Ageable) before.clone(); replanted.setAge(0); block.setBlockData(replanted);
                return true;
        }));
        });
    }
    @SuppressWarnings("deprecation")
    private static boolean interactionAllowed(PlayerInteractEvent event) {
        return !event.isCancelled() && event.useInteractedBlock() != Event.Result.DENY && event.useItemInHand() != Event.Result.DENY;
    }
    private boolean nativeBreak(Player player, Block block) {
        nativeBreaks.add(player.getUniqueId());
        try { return player.breakBlock(block); } finally { nativeBreaks.remove(player.getUniqueId()); }
    }
    private static boolean allowed(List<String> values, Material material) { return values.isEmpty() || values.stream().anyMatch(v -> v.equalsIgnoreCase(material.name())); }
    private synchronized boolean admitPending(String key, Batch batch) {
        return !retired && pending.size() < 256 && pending.putIfAbsent(key, batch) == null;
    }
    private void settle(String key, Batch batch) {
        pending.remove(key, batch); batch.settled.complete(null);
    }
    private void run(Player player, Perk perk, Kind kind, List<EffectBatch.Unit> units) {
        if (player.getGameMode() != GameMode.SURVIVAL) return;
        String key = player.getUniqueId() + ":" + kind;
        Batch batch = new Batch();
        if (!admitPending(key, batch)) { skip(player, Effect.valueOf(kind.name()), Skip.BUSY); return; }
        int version = generation;
        boolean charge = charged(perk);
        CompletableFuture<UsageCharges.Reservation> admission = charge
                ? ledger.reserve(player.getUniqueId(), kind, units.size()) : CompletableFuture.completedFuture(null);
        admission.whenComplete((reservation, error) -> {
            if (error != null || (charge && reservation == null)) {
                noteSkip(player.getUniqueId(), Effect.valueOf(kind.name()), error == null ? Skip.DEPLETED : Skip.STORAGE);
                settle(key, batch); return;
            }
            Runnable release = () -> {
                CompletableFuture<Void> completion = charge ? ledger.finish(reservation, 0, reservation.units()) : CompletableFuture.completedFuture(null);
                completion.whenComplete((ignored, failure) -> settle(key, batch));
            };
            Runnable cancel = () -> { if (batch.invoked.compareAndSet(false, true)) release.run(); };
            batch.cancel = cancel;
            // Retirement may have claimed cancellation before the reservation finished publishing.
            if (batch.invoked.get()) { release.run(); return; }
            Runnable execute = () -> {
                if (!batch.invoked.compareAndSet(false, true)) return;
                try {
                    int budget = charge ? reservation.units() : units.size();
                    EffectBatch.Result result = retired || version != generation || !player.isOnline() || player.getGameMode() != GameMode.SURVIVAL
                            ? new EffectBatch.Result(0, budget) : EffectBatch.execute(units.stream().<EffectBatch.Unit>map(unit -> () -> retired || version != generation || player.getGameMode() != GameMode.SURVIVAL ? false : unit.apply()).toList(), budget);
                    CompletableFuture<Void> completion = charge ? ledger.finish(reservation, result.successful(), result.unused())
                            : ledger.record(player.getUniqueId(), kind, result.successful());
                    if (result.successful() + result.unused() < budget) skip(player, Effect.valueOf(kind.name()), Skip.UNCERTAIN);
                    completion.whenComplete((ignored, failure) -> {
                        if (failure != null) plugin.getLogger().warning(charge
                                ? "Usage completion could not be persisted; reserved charges remain spent"
                                : "Usage statistics could not be persisted; gameplay completed");
                        if (charge) settle(key, batch);
                    });
                    // Best-effort statistics must never retain an unlimited gameplay admission.
                    if (!charge) settle(key, batch);
                } catch (Throwable failure) { settle(key, batch); plugin.getLogger().warning("Usage callback failed; reservation remains spent"); }
            };
            if (retired || !plugin.isEnabled()) cancel.run();
            else OwnerTasks.run(plugin, player, execute, cancel);
        });
    }
}
