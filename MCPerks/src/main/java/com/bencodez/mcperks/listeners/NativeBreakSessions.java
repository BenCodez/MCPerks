package com.bencodez.mcperks.listeners;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Session identities for a single listener generation's shared native-break guard. */
final class NativeBreakSessions {
    private final NativeBreakGuard guard = new NativeBreakGuard();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final int capacity;
    private boolean closed;

    NativeBreakSessions(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    static final class Session {
        private final UUID playerId;
        private final Object player;
        final NativeBreakGuard.Scope scope = NativeBreakGuard.newScope();

        private Session(UUID playerId, Object player) {
            this.playerId = playerId;
            this.player = player;
        }
    }

    /** Each actual join mints a fresh identity, even if a player object is reused. */
    synchronized Session join(UUID playerId, Object player) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(player, "player");
        if (closed || (!sessions.containsKey(playerId) && sessions.size() >= capacity)) return null;
        Session session = new Session(playerId, player);
        sessions.put(playerId, session);
        return session;
    }

    /** Capture only at the originating event; retain the returned identity across deferral. */
    synchronized Session capture(UUID playerId, Object player) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(player, "player");
        Session session = sessions.get(playerId);
        return !closed && session != null && session.player == player ? session : null;
    }

    synchronized boolean isCurrent(Session session) {
        return !closed && session != null && sessions.get(session.playerId) == session;
    }

    /** A late predecessor may never retire a successor. */
    synchronized boolean retire(Session session) {
        if (!isCurrent(session)) return false;
        sessions.remove(session.playerId);
        return true;
    }

    synchronized void close() {
        closed = true;
        sessions.clear();
    }

    NativeBreakGuard guard() {
        return guard;
    }
}
