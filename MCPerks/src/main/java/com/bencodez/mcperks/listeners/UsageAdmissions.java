package com.bencodez.mcperks.listeners;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import com.bencodez.mcperks.userapi.UsageCharges.Kind;

/** P3 admission state only. Identity inputs are captured on the player owner; no Bukkit reads occur here. */
final class UsageAdmissions {
    private static final int LIMIT = 256;
    private static final int SESSION_LIMIT = 10000;
    private final Map<UUID, AdmissionScope> sessions = new HashMap<>();
    private final Map<Key, Batch> pending = new HashMap<>();
    private final Set<Batch> outstanding = new HashSet<>();
    private Object epoch = new Object();
    private boolean retired;

    static final class AdmissionScope {
        final UUID owner;
        private final Object session;
        private final Object epoch;
        private AdmissionScope(UUID owner, Object session, Object epoch) {
            this.owner = owner; this.session = session; this.epoch = epoch;
        }
    }

    private record Key(UUID owner, Kind kind) {}

    AdmissionScope join(UUID owner, Object player, Object currentPlayer) {
        return capture(owner, player, currentPlayer, true);
    }

    void retire(AdmissionScope scope) {
        if (scope == null) return;
        List<Batch> cancelling;
        synchronized (this) { cancelling = detach(scope); }
        cancelling.forEach(Batch::cancel);
    }

    /** The caller supplies the actual current server lookup, never a UUID-only claim. */
    AdmissionScope capture(UUID owner, Object player, Object currentPlayer) {
        return capture(owner, player, currentPlayer, false);
    }

    private AdmissionScope capture(UUID owner, Object player, Object currentPlayer, boolean joined) {
        Objects.requireNonNull(owner);
        if (player == null || player != currentPlayer) return null;
        List<Batch> cancelling = List.of();
        AdmissionScope result;
        synchronized (this) {
            if (retired) return null;
            AdmissionScope old = sessions.get(owner);
            if (old != null && old.session == player && !joined) return old;
            if (old == null && sessions.size() >= SESSION_LIMIT) return null;
            if (old != null) cancelling = detach(old);
            result = new AdmissionScope(owner, player, epoch);
            sessions.put(owner, result);
        }
        cancelling.forEach(Batch::cancel);
        return result;
    }

    synchronized boolean current(AdmissionScope scope, Object player) {
        return scope != null && !retired && scope.epoch == epoch && scope.session == player
                && sessions.get(scope.owner) == scope;
    }

    void quit(UUID owner, Object player) {
        List<Batch> cancelling;
        synchronized (this) {
            AdmissionScope scope = sessions.get(owner);
            if (scope == null || scope.session != player) return;
            cancelling = detach(scope);
        }
        cancelling.forEach(Batch::cancel);
    }

    private List<Batch> detach(AdmissionScope scope) {
        sessions.remove(scope.owner, scope);
        List<Batch> result = new ArrayList<>();
        for (Batch batch : outstanding) if (batch.scope == scope) {
            pending.remove(batch.key, batch);
            result.add(batch);
        }
        return result;
    }

    void invalidate() { end(false); }
    CompletableFuture<Void> retire() { return end(true); }

    private CompletableFuture<Void> end(boolean close) {
        List<Batch> cancelling;
        synchronized (this) {
            retired |= close;
            epoch = new Object();
            sessions.clear(); pending.clear();
            cancelling = List.copyOf(outstanding);
        }
        cancelling.forEach(Batch::cancel);
        return CompletableFuture.allOf(cancelling.stream().map(b -> b.settled).toArray(CompletableFuture[]::new));
    }

    synchronized Batch admit(AdmissionScope scope, Object player, Kind kind) {
        Objects.requireNonNull(kind);
        if (!current(scope, player) || outstanding.size() >= LIMIT) return null;
        Key key = new Key(scope.owner, kind);
        if (pending.containsKey(key)) return null;
        Batch batch = new Batch(scope, key);
        pending.put(key, batch); outstanding.add(batch);
        return batch;
    }

    final class Batch {
        private final AdmissionScope scope;
        private final Key key;
        private final CompletableFuture<Void> settled = new CompletableFuture<>();
        private final AtomicBoolean cancellationDelivered = new AtomicBoolean();
        private Runnable cancellation;
        private boolean decided;
        private boolean cancelled;

        private Batch(AdmissionScope scope, Key key) { this.scope = scope; this.key = key; }

        boolean waiting() { synchronized (UsageAdmissions.this) { return !decided; } }

        boolean execute(Object player) {
            synchronized (UsageAdmissions.this) {
                if (decided || !current(scope, player) || pending.get(key) != this) return false;
                decided = true;
                return true;
            }
        }

        void onCancellation(Runnable handler) {
            synchronized (this) {
                if (cancellation != null) throw new IllegalStateException("Cancellation already installed");
                cancellation = Objects.requireNonNull(handler);
            }
            deliverCancellation();
        }

        void cancel() {
            synchronized (UsageAdmissions.this) {
                if (decided) return;
                decided = true; cancelled = true;
            }
            deliverCancellation();
        }

        private void deliverCancellation() {
            Runnable handler;
            synchronized (this) { handler = cancellation; }
            boolean shouldCancel;
            synchronized (UsageAdmissions.this) { shouldCancel = cancelled; }
            if (shouldCancel && handler != null && cancellationDelivered.compareAndSet(false, true)) handler.run();
        }

        void complete() {
            synchronized (UsageAdmissions.this) {
                decided = true;
                pending.remove(key, this); outstanding.remove(this);
            }
            settled.complete(null);
        }
    }
}
