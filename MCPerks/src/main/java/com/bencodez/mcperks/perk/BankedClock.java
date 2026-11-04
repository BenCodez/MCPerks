package com.bencodez.mcperks.perk;
/** Explicit lifecycle clock; queries do not checkpoint or consume time. */
public final class BankedClock {
    private long previous;
    private boolean eligible;
    private boolean grantInProgress;
    private long pendingMilliseconds;
    private long replacement;
    private final java.util.function.LongSupplier publishedReplacement;
    public BankedClock() { this(() -> 0); }
    BankedClock(java.util.function.LongSupplier publishedReplacement) { this.publishedReplacement = publishedReplacement; }
    public record Debit(long milliseconds, long replacement, long publication) {}
    /** Capture debit ownership together with its elapsed-time reservation. */
    public synchronized Debit reserveCheckpoint(long monotonicNanos, boolean nowEligible,
            java.util.function.LongSupplier confirmedMilliseconds) {
        return new Debit(checkpoint(monotonicNanos, nowEligible, confirmedMilliseconds), replacement, publishedReplacement.getAsLong());
    }
    private long elapsed(long monotonicNanos) {
        return eligible && previous != 0 ? Math.max(0, (monotonicNanos - previous) / 1_000_000) : 0;
    }
    public synchronized long checkpoint(long monotonicNanos, boolean nowEligible) {
        return checkpoint(monotonicNanos, nowEligible, () -> Long.MAX_VALUE);
    }
    public synchronized long checkpoint(long monotonicNanos, boolean nowEligible,
            java.util.function.LongSupplier confirmedMilliseconds) {
        if (grantInProgress) { eligible = nowEligible; return 0; }
        long elapsed = Math.min(elapsed(monotonicNanos), Math.max(0, confirmedMilliseconds.getAsLong() - pendingMilliseconds));
        pendingMilliseconds = Math.min(31_536_000_000L, pendingMilliseconds + elapsed);
        previous = monotonicNanos; eligible = nowEligible; return elapsed;
    }
    /**
     * Move the elapsed-time boundary to a grant publication.  The caller
     * persists the returned debit as part of the same bank publication.
     */
    public synchronized com.bencodez.mcperks.data.FeatureState.BankGrant prepareGrant(long monotonicNanos, long oldBalance,
            java.util.function.LongSupplier committedTime) {
        // A prior checkpoint may already be awaiting its worker debit. That
        // debit is still charged against the published balance, so only the
        // unreserved portion of the old balance can cover this interval.
        long elapsed = Math.min(elapsed(monotonicNanos),
                Math.max(0, oldBalance - pendingMilliseconds));
        grantInProgress = true;
        return new com.bencodez.mcperks.data.FeatureState.BankGrant(elapsed,
                () -> commitGrant(committedTime.getAsLong()), this::rollbackGrant);
    }
    private synchronized void commitGrant(long monotonicNanos) { previous = monotonicNanos; grantInProgress = false; }
    /** A clock first attached during publication can spend only the pre-grant balance. */
    public synchronized Debit rebaseLateGrant(long monotonicNanos, long oldBalance) {
        long elapsed = Math.min(elapsed(monotonicNanos), Math.max(0, oldBalance - pendingMilliseconds));
        pendingMilliseconds += elapsed;
        previous = monotonicNanos;
        return new Debit(elapsed, replacement, publishedReplacement.getAsLong());
    }
    private synchronized void rollbackGrant() { grantInProgress = false; }
    /** Replacement discards earlier elapsed reservations only once its new balance is published. */
    public synchronized com.bencodez.mcperks.data.FeatureState.BankGrant prepareReplacement(
            java.util.function.LongSupplier committedTime) {
        grantInProgress = true;
        return new com.bencodez.mcperks.data.FeatureState.BankGrant(0,
                () -> commitReplacement(committedTime.getAsLong()), this::rollbackGrant);
    }
    private synchronized void commitReplacement(long monotonicNanos) {
        replacement++; pendingMilliseconds = 0; commitGrant(monotonicNanos);
    }
    public synchronized boolean owns(Debit debit) {
        return debit.replacement() == replacement && debit.publication() == publishedReplacement.getAsLong();
    }
    public synchronized void acknowledge(Debit debit) {
        if (owns(debit)) acknowledge(debit.milliseconds());
    }
    /** Called only after the matching debit has been published by the storage worker. */
    public synchronized void acknowledge(long milliseconds) {
        pendingMilliseconds = Math.max(0, pendingMilliseconds - milliseconds);
    }
    /** Read the confirmed balance under the same monitor as acknowledgement, without mutation. */
    public synchronized long remaining(java.util.function.LongSupplier confirmedMilliseconds, long now) {
        if (grantInProgress) return 0;
        return Math.max(0, confirmedMilliseconds.getAsLong() - pendingMilliseconds - elapsed(now));
    }
    public synchronized void pause() { previous = 0; eligible = false; }
}
