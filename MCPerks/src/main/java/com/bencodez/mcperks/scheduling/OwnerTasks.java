package com.bencodez.mcperks.scheduling;

import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.simpleapi.folialib.enums.EntityTaskResult;

/** Retired/rejected entity tasks complete their admission without touching game state. */
public final class OwnerTasks {
    private OwnerTasks() {}
    public static void run(MCPerksMain plugin, Player player, Runnable action, Runnable retired) {
        AtomicBoolean decided = new AtomicBoolean();
        Runnable unavailable = () -> { if (decided.compareAndSet(false, true)) retired.run(); };
        if (!plugin.isEnabled() || player == null) { unavailable.run(); return; }
        try {
            plugin.getBukkitScheduler().getFoliaLib().getImpl().runAtEntityWithFallback(player,
                    task -> { if (decided.compareAndSet(false, true)) action.run(); }, unavailable)
                    .whenComplete((result, error) -> { if (error != null || result != EntityTaskResult.SUCCESS) unavailable.run(); });
        } catch (RuntimeException failure) { unavailable.run(); }
    }
    public static void later(MCPerksMain plugin, Player player, Runnable action, Runnable retired, long ticks) {
        AtomicBoolean decided = new AtomicBoolean();
        Runnable unavailable = () -> { if (decided.compareAndSet(false, true)) retired.run(); };
        if (!plugin.isEnabled() || player == null) { unavailable.run(); return; }
        try {
            var folia = plugin.getBukkitScheduler().getFoliaLib();
            Runnable onOwner = () -> {
                if (!plugin.isEnabled() || !player.isValid()) { unavailable.run(); return; }
                if (decided.compareAndSet(false, true)) action.run();
            };
            // The bundled Spigot implementation's entity-delay overloads recurse (Paper inherits
            // them). Its synchronous runLater uses Bukkit runTaskLater; check retirement there,
            // on the server owner. Folia must retain the moving entity's scheduler and fallback.
            var task = folia.isFolia()
                    ? folia.getImpl().runAtEntityLater(player, onOwner, unavailable, ticks)
                    : folia.getImpl().runLater(onOwner, ticks);
            if (task == null) unavailable.run();
        } catch (RuntimeException failure) { unavailable.run(); }
    }

}
