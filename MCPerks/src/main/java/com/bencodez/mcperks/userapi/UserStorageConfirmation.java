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
            var cache = plugin.getUserManager().getDataManager().getCache(UUID.fromString(user.getUUID()));
            if (cache == null) throw new IllegalStateException("Activation user cache unavailable");
            // The callback follows the actual exclusive flush, not its scheduling or a timed guess.
            cache.flushChangesAndRun(() -> confirmed.complete(null));
            return null;
        }).whenComplete((ignored, error) -> { if (error != null) confirmed.completeExceptionally(error); });
        // A deferred backend that cannot report failure must retain the uncertain start fence.
        return confirmed.orTimeout(30, TimeUnit.SECONDS);
    }
}
