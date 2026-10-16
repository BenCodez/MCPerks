package com.bencodez.mcperks.userapi;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import com.bencodez.mcperks.MCPerksMain;

/** Confirms the canonical user cache on storage, including writes staged by owner-thread setters. */
public final class UserStorageConfirmation {
    private UserStorageConfirmation() {}
    public static CompletableFuture<Void> confirm(MCPerksMain plugin, MCPerksUser user) {
        var confirmed = new CompletableFuture<Void>();
        plugin.getFeatureState().runtimeStorage(() -> {
            var manager = plugin.getUserManager().getDataManager();
            UUID owner = UUID.fromString(user.getUUID());
            // Owner-thread setters can defer the entire mutation while their canonical
            // cache is still a placeholder. Join that same ordered worker before flushing;
            // a flush on our independent file worker can otherwise confirm an empty cache.
            manager.getTimer().execute(() -> {
                try {
                    var cache = manager.getCache(owner);
                    if (cache == null) throw new IllegalStateException("Activation user cache unavailable");
                    cache.flushChangesAndRun(() -> confirmed.complete(null));
                } catch (RuntimeException | Error failure) {
                    confirmed.completeExceptionally(failure);
                }
            });
            return null;
        }).whenComplete((ignored, error) -> { if (error != null) confirmed.completeExceptionally(error); });
        // A deferred backend that cannot report failure must retain the uncertain start fence.
        return confirmed.orTimeout(30, TimeUnit.SECONDS);
    }
}
