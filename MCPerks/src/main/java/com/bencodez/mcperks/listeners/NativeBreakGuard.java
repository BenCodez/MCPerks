package com.bencodez.mcperks.listeners;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Guards only synchronous recursive native-break callbacks on the current owner thread. */
final class NativeBreakGuard {
    /** A caller must keep one distinct token for each actual player session and generation. */
    static final class Scope {
        private Scope() {}
    }

    private final ThreadLocal<Set<Scope>> active = ThreadLocal.withInitial(
            () -> Collections.newSetFromMap(new IdentityHashMap<>()));

    static Scope newScope() {
        return new Scope();
    }

    boolean isActive(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        Set<Scope> scopes = active.get();
        boolean found = scopes.contains(scope);
        if (scopes.isEmpty()) active.remove();
        return found;
    }

    /** Returns false without invoking action for a recursive call in the same scope. */
    boolean run(Scope scope, BooleanSupplier action) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(action, "action");
        Set<Scope> scopes = active.get();
        if (!scopes.add(scope)) return false;
        try {
            return action.getAsBoolean();
        } finally {
            scopes.remove(scope);
            if (scopes.isEmpty()) active.remove();
        }
    }
}
