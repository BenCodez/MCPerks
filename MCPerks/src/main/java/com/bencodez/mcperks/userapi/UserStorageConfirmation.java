package com.bencodez.mcperks.userapi;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.bencodez.mcperks.MCPerksMain;

/**
 * Confirms the current canonical user cache after operations already pending at
 * observation have settled. Callers must separately observe their operation's
 * receipt: a failure that completed before this snapshot cannot be reconstructed.
 */
public final class UserStorageConfirmation {
    private UserStorageConfirmation() {}
    public static CompletableFuture<Void> confirm(MCPerksMain plugin, MCPerksUser user) {
        var confirmed = new CompletableFuture<Void>();
        plugin.getStorage().runtimeStorage(() -> {
            var manager = plugin.getUserManager().getDataManager();
            UUID owner = UUID.fromString(user.getUUID());
            // Owner-thread setters can defer the entire mutation while their canonical
            // cache is still a placeholder. Join that same ordered worker before flushing;
            // a flush on our independent file worker can otherwise confirm an empty cache.
            manager.pendingLegacyIntegerMutations(owner).thenCompose(ignored -> manager.pendingSharedIntegerMutations(owner)).whenComplete((ignored, pendingFailure) -> {
                if (pendingFailure != null) { confirmed.completeExceptionally(pendingFailure); return; }
                try {
                    manager.getTimer().execute(() -> {
                        try {
                            var cache = manager.getCache(owner);
                            if (cache == null) throw new IllegalStateException("Activation user cache unavailable");
                            cache.flushChangesAndRun(() -> confirmed.complete(null));
                        } catch (RuntimeException | Error failure) {
                            confirmed.completeExceptionally(failure);
                        }
                    });
                } catch (RuntimeException | Error failure) { confirmed.completeExceptionally(failure); }
            });
            return null;
        }).whenComplete((ignored, error) -> { if (error != null) confirmed.completeExceptionally(error); });
        // A deferred backend that cannot report failure must retain the uncertain start fence.
        return confirmed.copy();
    }
}
