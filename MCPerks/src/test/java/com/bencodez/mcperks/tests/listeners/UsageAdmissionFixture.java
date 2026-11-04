package com.bencodez.mcperks.tests.listeners;

import java.lang.reflect.InvocationTargetException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.bencodez.mcperks.userapi.UsageCharges.Kind;

/** Reflection access to the package-local P3 seam, without widening its production visibility. */
final class UsageAdmissionFixture {
    private static final Class<?> TYPE;
    private static final Class<?> SCOPE;
    private final Object state;
    static {
        try {
            TYPE = Class.forName("com.bencodez.mcperks.listeners.UsageAdmissions");
            SCOPE = Class.forName("com.bencodez.mcperks.listeners.UsageAdmissions$AdmissionScope");
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    UsageAdmissionFixture() {
        try { var ctor = TYPE.getDeclaredConstructor(); ctor.setAccessible(true); state = ctor.newInstance(); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    Object capture(UUID owner, Object player, Object current) {
        return call(state, "capture", new Class<?>[]{UUID.class, Object.class, Object.class}, owner, player, current);
    }
    Object join(UUID owner, Object player, Object current) {
        return call(state, "join", new Class<?>[]{UUID.class, Object.class, Object.class}, owner, player, current);
    }
    void retireScope(Object scope) { call(state, "retire", new Class<?>[]{SCOPE}, scope); }
    boolean current(Object scope, Object player) {
        return (boolean) call(state, "current", new Class<?>[]{SCOPE, Object.class}, scope, player);
    }
    Object admit(Object scope, Object player, Kind kind) {
        return call(state, "admit", new Class<?>[]{SCOPE, Object.class, Kind.class}, scope, player, kind);
    }
    void quit(UUID owner, Object player) { call(state, "quit", new Class<?>[]{UUID.class, Object.class}, owner, player); }
    void invalidate() { call(state, "invalidate", new Class<?>[0]); }
    @SuppressWarnings("unchecked") CompletableFuture<Void> retire() {
        return (CompletableFuture<Void>) call(state, "retire", new Class<?>[0]);
    }
    static boolean execute(Object batch, Object player) { return (boolean) call(batch, "execute", new Class<?>[]{Object.class}, player); }
    static void cancel(Object batch) { call(batch, "cancel", new Class<?>[0]); }
    static void onCancel(Object batch, Runnable action) { call(batch, "onCancellation", new Class<?>[]{Runnable.class}, action); }
    static void complete(Object batch) { call(batch, "complete", new Class<?>[0]); }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try {
            var method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error r) throw r;
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
