package com.bencodez.mcperks.listeners;
import java.util.List;
/** Production execution seam: each true result is one successful gameplay unit. */
public final class EffectBatch {
    @FunctionalInterface public interface Unit { boolean apply() throws Exception; }
    public record Result(int successful, int unused) {}
    private EffectBatch() {}
    public static Result execute(List<Unit> units, int budget) {
        if (budget < 0 || budget > units.size()) throw new IllegalArgumentException("Invalid budget");
        int successful = 0;
        for (Unit unit : units) {
            if (successful == budget) break;
            try { if (unit.apply()) successful++; }
            catch (Exception uncertain) { return new Result(successful, budget - successful - 1); }
        }
        return new Result(successful, budget - successful);
    }
}
