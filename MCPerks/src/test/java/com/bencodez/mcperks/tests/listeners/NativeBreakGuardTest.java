package com.bencodez.mcperks.tests.listeners;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NativeBreakGuardTest {
    private Object guard;
    private Method newScope;
    private Method isActive;
    private Method run;

    @BeforeEach void setup() throws Exception {
        Class<?> type = Class.forName("com.bencodez.mcperks.listeners.NativeBreakGuard");
        Class<?> scope = Class.forName("com.bencodez.mcperks.listeners.NativeBreakGuard$Scope");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        guard = constructor.newInstance();
        newScope = type.getDeclaredMethod("newScope");
        isActive = type.getDeclaredMethod("isActive", scope);
        run = type.getDeclaredMethod("run", scope, BooleanSupplier.class);
        newScope.setAccessible(true);
        isActive.setAccessible(true);
        run.setAccessible(true);
    }

    private Object scope() throws Exception { return newScope.invoke(null); }
    private boolean active(Object scope) throws Exception { return (boolean) isActive.invoke(guard, scope); }
    private boolean run(Object scope, BooleanSupplier action) throws Exception {
        return (boolean) run.invoke(guard, scope, action);
    }

    @Test void activeOnlyDuringNativeCallAndFalseResultIsPreserved() throws Exception {
        Object scope = scope();
        assertFalse(active(scope));
        assertFalse(run(scope, () -> {
            try { assertTrue(active(scope)); } catch (Exception e) { throw new AssertionError(e); }
            return false;
        }));
        assertFalse(active(scope));
    }

    @Test void recursiveSameScopeDoesNotRunAndSuccessorScopeIsIndependent() throws Exception {
        Object oldScope = scope(), successorScope = scope();
        AtomicBoolean recursiveAction = new AtomicBoolean();
        assertTrue(run(oldScope, () -> {
            try {
                assertTrue(active(oldScope));
                assertFalse(active(successorScope));
                assertFalse(run(oldScope, () -> { recursiveAction.set(true); return true; }));
                assertTrue(run(successorScope, () -> true));
                assertTrue(active(oldScope));
                assertFalse(active(successorScope));
                return true;
            } catch (Exception e) { throw new AssertionError(e); }
        }));
        assertFalse(recursiveAction.get());
        assertFalse(active(oldScope));
        assertFalse(active(successorScope));
    }

    @Test void exceptionAndErrorBothClearScope() throws Exception {
        Object scope = scope();
        for (Throwable failure : new Throwable[] {new IllegalStateException("synthetic"), new AssertionError("synthetic")}) {
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> run(scope, () -> {
                if (failure instanceof Error error) throw error;
                throw (RuntimeException) failure;
            }));
            assertSame(failure, thrown.getCause());
            assertFalse(active(scope));
        }
    }

    @Test void synchronousScopeIsConfinedToItsOwnerThread() throws Exception {
        Object scope = scope();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> run(scope, () -> {
                entered.countDown();
                try { return release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(active(scope));
            release.countDown();
            assertTrue(result.get(2, TimeUnit.SECONDS));
            assertFalse(active(scope));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
