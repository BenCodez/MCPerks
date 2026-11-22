package com.bencodez.mcperks.scheduling;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.entity.Player;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.simpleapi.folialib.enums.EntityTaskResult;

/** Retired/rejected entity tasks complete their admission without touching game state. */
public final class OwnerTasks {
    private static final Map<MCPerksMain, Scope> OWNERS = new ConcurrentHashMap<>();

    private OwnerTasks() {}

    /** Initialize during plugin startup, before admitting any owner callbacks. */
    public static void initialize(MCPerksMain plugin) {
        synchronized (OWNERS) {
            Scope previous = OWNERS.get(plugin);
            if (previous != null && !previous.isClosed()) return;
            if (!plugin.isEnabled()) return;
            Scope scope = new Scope(plugin);
            try {
                plugin.getServer().getPluginManager().registerEvent(PluginDisableEvent.class, scope,
                        EventPriority.LOWEST, (listener, event) -> {
                            if (((PluginDisableEvent) event).getPlugin() == plugin) scope.stop();
                        }, plugin);
                if (scope.isClosed() || !plugin.isEnabled()) {
                    scope.stop();
                    return;
                }
                OWNERS.put(plugin, scope);
            } catch (RuntimeException failure) {
                scope.stop();
                throw failure;
            }
        }
    }

    /** Close admission before teardown, including unloads without a native disable event. */
    public static void shutdown(MCPerksMain plugin) {
        Scope scope = OWNERS.get(plugin);
        if (scope != null) scope.stop();
    }

    private static Admission admit(MCPerksMain plugin, Player player, Runnable retired) {
        Scope scope = OWNERS.get(plugin);
        Admission admission = player == null || !plugin.isEnabled() || scope == null
                ? null : scope.admit(retired);
        if (admission == null) retired.run();
        return admission;
    }

    public static void run(MCPerksMain plugin, Player player, Runnable action, Runnable retired) {
        Admission admission = admit(plugin, player, retired);
        if (admission == null) return;
        try {
            plugin.getBukkitScheduler().getFoliaLib().getImpl().runAtEntityWithFallback(player,
                    task -> admission.onOwner(player, action), admission::retire)
                    .whenComplete((result, error) -> {
                        if (error != null || result != EntityTaskResult.SUCCESS) admission.retire();
                    });
        } catch (RuntimeException failure) { admission.retire(); }
    }

    public static void later(MCPerksMain plugin, Player player, Runnable action, Runnable retired, long ticks) {
        Admission admission = admit(plugin, player, retired);
        if (admission == null) return;
        try {
            var folia = plugin.getBukkitScheduler().getFoliaLib();
            Runnable onOwner = () -> admission.onOwner(player, action);
            // The bundled Spigot implementation's entity-delay overloads recurse (Paper inherits
            // them). Its synchronous runLater uses Bukkit runTaskLater; check retirement there,
            // on the server owner. Folia must retain the moving entity's scheduler and fallback.
            var task = folia.isFolia()
                    ? folia.getImpl().runAtEntityLater(player, onOwner, admission::retire, ticks)
                    : folia.getImpl().runLater(onOwner, ticks);
            if (task == null) admission.retire();
        } catch (RuntimeException failure) { admission.retire(); }
    }

    private static final class Scope implements Listener {
        private final MCPerksMain plugin;
        private final Set<Admission> pending = new HashSet<>();
        private boolean closed;

        private Scope(MCPerksMain plugin) { this.plugin = plugin; }

        private synchronized boolean isClosed() { return closed; }

        private synchronized Admission admit(Runnable retired) {
            if (closed || !plugin.isEnabled()) return null;
            Admission admission = new Admission(this, retired);
            pending.add(admission);
            return admission;
        }

        private void stop() {
            ArrayList<Admission> retiring;
            synchronized (this) {
                if (closed) return;
                closed = true;
                retiring = new ArrayList<>(pending);
                pending.clear();
            }
            Throwable retirementFailure = null;
            try {
                HandlerList.unregisterAll(this);
            } finally {
                for (Admission admission : retiring) {
                    try {
                        admission.retire();
                    } catch (Throwable failure) {
                        if (retirementFailure == null) retirementFailure = failure;
                        else if (retirementFailure != failure) retirementFailure.addSuppressed(failure);
                    }
                }
            }
            if (retirementFailure != null && plugin.getLogger() != null) {
                plugin.getLogger().log(Level.WARNING, "Owner-task retirement callback failed", retirementFailure);
            }
        }
    }

    private static final class Admission {
        private final Scope scope;
        private final Runnable retired;
        private boolean decided;

        private Admission(Scope scope, Runnable retired) {
            this.scope = scope;
            this.retired = retired;
        }

        private void onOwner(Player player, Runnable action) {
            boolean active;
            synchronized (scope) {
                if (decided) return;
                active = !scope.closed;
            }
            // Player access remains on the scheduled native owner. The final
            // decision rechecks the epoch atomically, then invokes outside its lock.
            if (active && scope.plugin.isEnabled() && player.isValid()) finish(action);
            else retire();
        }

        private void finish(Runnable action) {
            Runnable completion;
            synchronized (scope) {
                if (decided) return;
                decided = true;
                scope.pending.remove(this);
                completion = action != null && !scope.closed ? action : retired;
            }
            completion.run();
        }

        private void retire() { finish(null); }
    }
}
