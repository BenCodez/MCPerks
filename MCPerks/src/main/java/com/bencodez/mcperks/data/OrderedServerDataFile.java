package com.bencodez.mcperks.data;

import java.io.File;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.advancedcore.api.time.TimeType;

/** Shares AdvancedCore's initialized YAML while keeping calendar and perk writes ordered together. */
public final class OrderedServerDataFile extends com.bencodez.advancedcore.data.ServerData {
    private final com.bencodez.advancedcore.data.ServerData delegate;
    private final ServerData runtime;
    private final TransitionFile transitions;
    public OrderedServerDataFile(MCPerksMain plugin, com.bencodez.advancedcore.data.ServerData delegate, ServerData runtime) {
        super(plugin); this.delegate = delegate; this.runtime = runtime; transitions = new TransitionFile(plugin);
        // Native TimeChecker saves LastUpdated before onPostLoad creates OrderedStorage.
        // Keep that mutation in memory until the original disk source is strictly reconciled.
        if (plugin.getStorage() == null) runtime.deferNativeSaves(delegate.getData(), delegate.isJustCreated());
    }
    @Override public FileConfiguration getData() { return delegate.getData(); }
    @Override public File getdFile() { return delegate.getdFile(); }
    @Override public boolean isFailedToRead() { return delegate.isFailedToRead(); }
    @Override public Object publicationMonitor() { return runtime; }
    @Override public void saveData() { runtime.saveData(); }
    @Override public java.util.concurrent.CompletionStage<Void> mutateFinalData(
            java.util.function.Consumer<FileConfiguration> mutation) {
        return runtime.confirmedFinalMutation(mutation);
    }

    // Only calendar callers share this monitor. Inherited mutation/rollback operates on a private
    // view; gameplay never acquires this monitor or waits for calendar file publication.
    private <T> T calendar(java.util.function.Function<TransitionFile, T> operation) {
        runtime.ready().join();
        transitions.view = runtime.timeTransitionView();
        try { return operation.apply(transitions); }
        finally { transitions.view = null; }
    }
    private final class TransitionFile extends com.bencodez.advancedcore.data.ServerData {
        private ServerData.TimeTransitionView view;
        private TransitionFile(MCPerksMain plugin) { super(plugin); }
        @Override public FileConfiguration getData() { return view.data(); }
        @Override protected synchronized void saveTimeChangeTransitionData() {
            // TimeChecker invokes this synchronous durability API off the gameplay owner.
            runtime.confirmedTimeTransition(view).join();
        }
    }
    @Override public synchronized TimeChangeTransitionState beginTimeChangeTransition(TimeType type, String period, String marker) {
        runtime.requireCalendarAdmission();
        return calendar(file -> file.beginTimeChangeTransition(type, period, marker));
    }
    @Override public synchronized TimeChangeTransitionState getPendingTimeChangeTransition(TimeType type) {
        return calendar(file -> file.getPendingTimeChangeTransition(type));
    }
    @Override public synchronized void completeTimeChangeTransition(TimeChangeTransitionState transition) {
        calendar(file -> { file.completeTimeChangeTransition(transition); return null; });
    }
    @Override public synchronized void failTimeChangeTransition(TimeChangeTransitionState transition) {
        calendar(file -> { file.failTimeChangeTransition(transition); return null; });
    }
    @Override public synchronized boolean isPendingTimeChangeTransition(TimeChangeTransitionState transition) {
        return calendar(file -> file.isPendingTimeChangeTransition(transition));
    }
    @Override public void reloadData() { runtime.reloadData(); }
    @Override public void setData(String path, Object value) { synchronized (runtime) { runtime.calendarEdited(path); super.setData(path, value); } }
    @Override public void setData(FileConfiguration data) { synchronized (runtime) { runtime.calendarEdited(""); delegate.setData(data); } }
    @Override public void setValue(String path, Object value) { synchronized (runtime) { runtime.calendarEdited(path); super.setValue(path, value); } }
    @Override public void createSection(String path) { synchronized (runtime) { runtime.calendarEdited(path); super.createSection(path); } }
    @Override public void setIgnoreTime(boolean value) { synchronized (runtime) { super.setIgnoreTime(value); } }
    @Override public void setLastUpdated() { synchronized (runtime) { super.setLastUpdated(); } }
    @Override public void setPluginVersion(Plugin plugin) { synchronized (runtime) { super.setPluginVersion(plugin); } }
    @Override public void setPrevDay(int value) { synchronized (runtime) { runtime.calendarEdited("PrevDay"); super.setPrevDay(value); } }
    @Override public void setPrevMonth(String value) { synchronized (runtime) { runtime.calendarEdited("Month"); super.setPrevMonth(value); } }
    @Override public void setPrevWeekDay(int value) { synchronized (runtime) { runtime.calendarEdited("PrevWeek"); super.setPrevWeekDay(value); } }
    @Override public long getLastUpdated() { synchronized (runtime) { return super.getLastUpdated(); } }
    @Override public String getPluginVersion(Plugin plugin) { synchronized (runtime) { return super.getPluginVersion(plugin); } }
    @Override public int getPrevDay() { synchronized (runtime) { return super.getPrevDay(); } }
    @Override public String getPrevMonth() { synchronized (runtime) { return super.getPrevMonth(); } }
    @Override public int getPrevWeekDay() { synchronized (runtime) { return super.getPrevWeekDay(); } }
    @Override public boolean isIgnoreTime() { synchronized (runtime) { return super.isIgnoreTime(); } }
}
