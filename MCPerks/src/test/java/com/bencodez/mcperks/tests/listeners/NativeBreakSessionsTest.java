package com.bencodez.mcperks.tests.listeners;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NativeBreakSessionsTest {
    private Object sessions;
    private Method join, capture, current, retire, close, guard, active, run;
    private Field scope;
    private final UUID playerId = UUID.randomUUID();
    private final Object player = new Object();

    @BeforeEach void setup() throws Exception {
        Class<?> type = Class.forName("com.bencodez.mcperks.listeners.NativeBreakSessions");
        Class<?> session = Class.forName("com.bencodez.mcperks.listeners.NativeBreakSessions$Session");
        Class<?> guardType = Class.forName("com.bencodez.mcperks.listeners.NativeBreakGuard");
        Class<?> scopeType = Class.forName("com.bencodez.mcperks.listeners.NativeBreakGuard$Scope");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        sessions = constructor.newInstance(2);
        join = method(type, "join", UUID.class, Object.class);
        capture = method(type, "capture", UUID.class, Object.class);
        current = method(type, "isCurrent", session);
        retire = method(type, "retire", session);
        close = method(type, "close");
        guard = method(type, "guard");
        active = method(guardType, "isActive", scopeType);
        run = method(guardType, "run", scopeType, BooleanSupplier.class);
        scope = session.getDeclaredField("scope");
        scope.setAccessible(true);
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }

    @Test void sameSessionSharesOneGuardAndScopeForBothConsumers() throws Exception {
        Object session = join.invoke(sessions, playerId, player);
        Object p4 = capture.invoke(sessions, playerId, player);
        Object p5 = capture.invoke(sessions, playerId, player);
        assertSame(session, p4);
        assertSame(p4, p5);
        Object nativeGuard = guard.invoke(sessions);
        Object sharedScope = scope.get(p4);
        AtomicBoolean recursive = new AtomicBoolean();
        assertTrue((boolean) run.invoke(nativeGuard, sharedScope, (BooleanSupplier) () -> {
            try {
                assertTrue((boolean) active.invoke(nativeGuard, scope.get(p5)));
                assertFalse((boolean) run.invoke(nativeGuard, scope.get(p5),
                        (BooleanSupplier) () -> { recursive.set(true); return true; }));
                return true;
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }));
        assertFalse(recursive.get());
    }

    @Test void sameUuidReconnectInvalidatesOldSessionAndLateRetirementCannotRemoveSuccessor() throws Exception {
        Object old = join.invoke(sessions, playerId, player);
        Object successor = join.invoke(sessions, playerId, new Object());
        assertNotSame(old, successor);
        assertNotSame(scope.get(old), scope.get(successor));
        assertFalse((boolean) current.invoke(sessions, old));
        assertFalse((boolean) retire.invoke(sessions, old));
        assertTrue((boolean) current.invoke(sessions, successor));
        assertNull(capture.invoke(sessions, playerId, player));
    }

    @Test void joinRotatesEvenWhenPlayerObjectIsReused() throws Exception {
        Object old = join.invoke(sessions, playerId, player);
        Object successor = join.invoke(sessions, playerId, player);
        assertNotSame(old, successor);
        assertFalse((boolean) current.invoke(sessions, old));
        assertFalse((boolean) retire.invoke(sessions, old));
        assertSame(successor, capture.invoke(sessions, playerId, player));
    }

    @Test void oldActiveScopeNeitherSuppressesNorRetiresSuccessor() throws Exception {
        Object old = join.invoke(sessions, playerId, player);
        Object nativeGuard = guard.invoke(sessions);
        assertTrue((boolean) run.invoke(nativeGuard, scope.get(old), (BooleanSupplier) () -> {
            try {
                Object successor = join.invoke(sessions, playerId, player);
                assertFalse((boolean) active.invoke(nativeGuard, scope.get(successor)));
                assertTrue((boolean) run.invoke(nativeGuard, scope.get(successor),
                        (BooleanSupplier) () -> true));
                assertFalse((boolean) retire.invoke(sessions, old));
                assertTrue((boolean) current.invoke(sessions, successor));
                return true;
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }));
    }

    @Test void retireAndCloseAreIdempotentAndRetiredGenerationCannotReopen() throws Exception {
        Object session = join.invoke(sessions, playerId, player);
        assertTrue((boolean) retire.invoke(sessions, session));
        assertFalse((boolean) retire.invoke(sessions, session));
        assertNull(capture.invoke(sessions, playerId, player));
        Object next = join.invoke(sessions, playerId, player);
        close.invoke(sessions);
        close.invoke(sessions);
        assertFalse((boolean) current.invoke(sessions, next));
        assertNull(join.invoke(sessions, playerId, player));
    }

    @Test void boundedLookupAllowsReplacementButRefusesAdditionalIdentity() throws Exception {
        Object old = join.invoke(sessions, playerId, player);
        UUID second = UUID.randomUUID();
        assertNotNull(join.invoke(sessions, second, new Object()));
        assertNull(join.invoke(sessions, UUID.randomUUID(), new Object()));
        Object replacement = join.invoke(sessions, playerId, player);
        assertNotNull(replacement);
        assertFalse((boolean) retire.invoke(sessions, old));
        assertTrue((boolean) current.invoke(sessions, replacement));
    }
}
