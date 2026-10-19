package com.bencodez.mcperks.tests.data;
import static com.bencodez.mcperks.tests.TestAccess.*;
import com.bencodez.mcperks.data.*;
import java.nio.file.Path;
public final class FeatureStateFixture {
    private FeatureStateFixture() {}
    public static FeatureState create(Path file, DurableFeatureFiles.Publisher publisher) { return newFeatureState(file, publisher); }
    /** Delay the real controller commit, retaining its original elapsed/rollback behavior. */
    @SuppressWarnings("unchecked")
    public static void beforeBankCommit(FeatureState state, Runnable before) throws ReflectiveOperationException {
        var grantField = FeatureState.class.getDeclaredField("bankGrantBoundary"); grantField.setAccessible(true);
        var replacementField = FeatureState.class.getDeclaredField("bankReplacementBoundary"); replacementField.setAccessible(true);
        var grants = (java.util.function.BiFunction<FeatureState.BankKey, Long, FeatureState.BankGrant>) grantField.get(state);
        var replacements = (java.util.function.Function<FeatureState.BankKey, FeatureState.BankGrant>) replacementField.get(state);
        state.onBankGrant((key, balance) -> wrap(grants.apply(key, balance), before));
        state.onBankReplacement(key -> wrap(replacements.apply(key), before));
    }
    private static FeatureState.BankGrant wrap(FeatureState.BankGrant actual, Runnable before) {
        return new FeatureState.BankGrant(actual.elapsed(), () -> { before.run(); actual.commit().run(); }, actual.rollback());
    }
    public static boolean ownsBankBoundary(FeatureState state) throws ReflectiveOperationException {
        var lock = FeatureState.class.getDeclaredField("bankBoundaryLock"); lock.setAccessible(true);
        return Thread.holdsLock(lock.get(state));
    }
}
