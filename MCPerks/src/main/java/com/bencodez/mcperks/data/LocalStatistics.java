package com.bencodez.mcperks.data;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
/** Local aggregate counts only: no external reporting, players, inventories, commands or locations. */
public final class LocalStatistics {
    private volatile boolean enabled;
    public void enabled(boolean value) { enabled = value; }
    private final LongAdder activations = new LongAdder(), queueStarts = new LongAdder(), queueWaitMillis = new LongAdder();
    private final EnumMap<Skip, LongAdder> skips = new EnumMap<>(Skip.class);
    public enum Skip { INACTIVE, AUDIENCE, WORLD, PRECISION, BANKED, SHARED_CHARGES, DEPLETED, TOOL, MATERIAL, IMMATURE, SEED, INVENTORY_CHANGED, NATIVE_BREAK_DENIED, INTERACTION_DENIED, BUSY, STORAGE, UNCERTAIN }
    public LocalStatistics() { for (Skip skip : Skip.values()) skips.put(skip, new LongAdder()); }
    public void activation() { if (enabled) activations.increment(); }
    public void queueStart(long waitMillis) { if (!enabled) return; queueStarts.increment(); queueWaitMillis.add(Math.max(0, Math.min(waitMillis, 31_536_000_000L))); }
    public void skip(Skip reason) { if (enabled) skips.get(reason).increment(); }
    public String summary() {
        if (!enabled) return "LocalStatistics.Enabled is false; aggregate collection disabled.";
        StringBuilder text = new StringBuilder("activations=").append(activations.sum()).append(", queued starts=").append(queueStarts.sum()).append(", queue wait seconds=").append(queueWaitMillis.sum()/1000);
        for (Skip reason : Skip.values()) { long count = skips.get(reason).sum(); if (count > 0) text.append(", ").append(reason).append('=').append(count); }
        return text.toString();
    }
}
