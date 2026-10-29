/*
 *
 */
package com.bencodez.mcperks.perk;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.security.CodeSource;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.messages.PlaceholderUtils;
import com.bencodez.advancedcore.api.misc.effects.BossBar;
import com.bencodez.advancedcore.api.rewards.RewardBuilder;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.advancedcore.api.user.UserDataFetchMode;
import com.bencodez.advancedcore.logger.Logger;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.configs.ConfigPerks;
import com.bencodez.mcperks.configs.Lang;
import com.bencodez.mcperks.effects.RepairEffect;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.bencodez.simpleapi.array.ArrayUtils;
import com.bencodez.simpleapi.messages.MessageAPI;
import com.massivecraft.factions.entity.MPlayer;
import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.object.Resident;

import lombok.Getter;

// TODO: Auto-generated Javadoc
/**
 * The Class ModuleHandler.
 */
public class PerkHandler {

	/** The loaded perks. */
	private Map<String, Perk> loadedPerks = new HashMap<String, Perk>();

	/** The main. */
	private MCPerksMain plugin;

	private Timer timer = new Timer();
    private volatile java.util.concurrent.CompletableFuture<Void> restoration = new java.util.concurrent.CompletableFuture<>();
    public boolean isRestorationReady() { var current = restoration; return current.isDone() && !current.isCompletedExceptionally(); }

	public Map<Integer, Perk> invPerks = new HashMap<Integer, Perk>();

	public HashMap<MCPerksUser, Perk> que = new HashMap<MCPerksUser, Perk>();

	@Getter
	private CopyOnWriteArrayList<Perk> activePerks = new CopyOnWriteArrayList<Perk>();

	private Logger activationLog;

	public PerkHandler(MCPerksMain plugin) {
		this.plugin = plugin;
		if (plugin.getConfigFile().getLoadDefaultPerks()) {
			try {
				copyPerks();
			} catch (IOException e) {
				plugin.getLogger().info("Failed to copy default perks");
				e.printStackTrace();
			}
		}
		loadEnabledPerks(false);
		if (plugin.getConfigFile().getLogActivation()) {
			activationLog = new Logger(plugin, new File(plugin.getDataFolder(), "ActivationLog.txt"));
		}
	}

    PerkHandler(MCPerksMain plugin, List<Perk> initial) {
        this.plugin = plugin; activePerks.addAll(initial); restoration.complete(null);
    }

	public void activePerk(Perk perk, MCPerksUser user, int length, Timestamp timestamp) {
		activePerk(perk, user, length, timestamp, true);
	}

	/** Restores the timer/effects for a persisted grant without replaying activation-only effects/hooks. */
	public void restoreActivePerk(Perk perk, MCPerksUser user, Timestamp expiration) {
		int remaining = expiration == null ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.max(0L,
				(expiration.getTime() - System.currentTimeMillis()) / 1000L));
		activePerk(perk, user, remaining, expiration, false);
	}

	private void activePerk(Perk perk, MCPerksUser user, int length, Timestamp timestamp, boolean runActivationHooks) {
		final Perk clone = perk.clone();
        if (!runActivationHooks) clone.setTime(timestamp == null ? -1 : Math.max(1, length));
		clone.setActivater(user);
		clone.getEffectedPlayers().clear();
		ArrayList<Integer> times = new ArrayList<Integer>();
		if (runActivationHooks && perk.getTime() > 0) {
			for (String str : ConfigPerks.getInstance().getCountDownTimes(perk.getPerk())) {
				if (MessageAPI.isInt(str)) {
					int num = Integer.parseInt(str);
					if (num > 0) {
						times.add(length - num);
					}
				}

			}
		}
		HashMap<String, String> placeholders = new HashMap<String, String>();
		placeholders.put("perk", perk.getName());
		String msg = Lang.getInstance().getCountDownTimer();

		PerkSystemType perkType = clone.getPerkType();

		plugin.debug("Activating perk " + clone.getPerk() + " using " + perkType.toString());

		if (perkType.equals(PerkSystemType.PLAYER)) {
			clone.addEffectedPlayer(user.getUUID());
		} else if (perkType.equals(PerkSystemType.ALL)) {
			for (Player player : Bukkit.getOnlinePlayers()) {
				clone.addEffectedPlayer(player.getUniqueId().toString());
			}

		} else if (perkType.equals(PerkSystemType.TOWNY)) {
			try {
				@SuppressWarnings("deprecation")
				Resident res = Towny.plugin.getTownyUniverse().getResident(user.getPlayerName());
				if (res.hasTown()) {
					for (Resident r : res.getTown().getResidents()) {
						clone.addEffectedPlayer(plugin.getUserManager().getUser(r.getName()).getUUID());
					}
				} else {
					clone.addEffectedPlayer(user.getUUID());

				}
			} catch (Exception e) {
				e.printStackTrace();
			}

		} else if (perkType.equals(PerkSystemType.FACTIONS)) {
			try {
				MPlayer mplayer = MPlayer.get(user.getUUID());
				if (mplayer.hasFaction()) {
					for (MPlayer p : mplayer.getFaction().getMPlayers()) {
						clone.addEffectedPlayer(p.getUuid().toString());
					}

				} else {
					clone.addEffectedPlayer(user.getUUID());

				}
			} catch (Exception e) {
				e.printStackTrace();
			}

		} else if (perkType.equals(PerkSystemType.PERMISSION)) {
			for (Player player : Bukkit.getOnlinePlayers()) {
				if (player.hasPermission(perkType.getPermissionRequired())) {
					clone.addEffectedPlayer(player.getUniqueId().toString());
				}
			}
		}

		// countdown effects
		if (perk.getTime() > 0) {
			for (Integer time1 : times) {
				if (time1 < 0) {
					time1 = time1 * -1;
				}
				final Integer time = time1;
				timer.schedule(new TimerTask() {

					@Override
					public void run() {
						if (!clone.isActive()) {
							cancel();
							return;
						}
						placeholders.put("countdown", "" + (length - time.intValue()));
						HashMap<String, String> phs = new HashMap<String, String>();
						phs.putAll(placeholders);
						for (String uuid : clone.getEffectedPlayers()) {
							AdvancedCoreUser user = plugin.getUserManager().getUser(UUID.fromString(uuid));
							user.sendMessage(msg, phs);

							if (plugin.getRewardHandler().hasRewards(ConfigPerks.getInstance().getData(perk.getPerk()),
									ConfigPerks.getInstance().getCountDownEffectPath())) {
								new RewardBuilder(ConfigPerks.getInstance().getData(perk.getPerk()),
										ConfigPerks.getInstance().getCountDownEffectPath()).setGiveOffline(false)
										.setGiveOffline(false).send(user);
							} else {
								new RewardBuilder(plugin.getConfigFile().getData(),
										plugin.getConfigFile().getCountDownEffectPath()).setGiveOffline(false)
										.setGiveOffline(false).send(user);
							}
						}
					}
				}, time * 1000);
			}
		}

        NativeActivation currentActivation = publishingActivation.get();
        if (currentActivation != null) currentActivation.gameplayStarted = true;
		plugin.getEffectHandler().activate(clone, user, runActivationHooks);
        Timestamp exp = null;
		if (clone.isTimed()) {

			clone.setActive(true);
			if (!clone.isLastForever()) {
				exp = new Timestamp(Calendar.getInstance().getTime().getTime() + 1000 * length);
				if (timestamp != null) {
					exp = timestamp;
				}

				if (publishingActivation.get() != null) clone.setKnownExpiration(exp.getTime());
				else clone.setExperation(user, exp.getTime());

			} else {
				if (publishingActivation.get() != null) clone.setKnownExpiration(-1);
				else clone.setExperation(user, -1);
			}
            try {
                synchronized (this) {
                    activePerks.add(clone);
                    // Publish capacity before reentrant hooks, without releasing the storage drain.
                    releaseNativeCapacity(publishingActivation.get());
                }
            } catch (RuntimeException failure) {
                synchronized (this) { activePerks.remove(clone); }
                clone.setActive(false);
                // Activation hooks may already have run. Retire applied persistent effects
                // without replaying deactivation rewards or pretending hooks were undone.
                for (Effect effect : clone.getEffects()) {
                    try { effect.removeEffect(plugin, clone, clone.getEffectedPlayers()); }
                    catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                }
                throw failure;
            }
		}

        try {
		if (clone.getReActivateEffectsTimer() > 0) {
			final Perk clonedFinal = clone;
			timer.scheduleAtFixedRate(new TimerTask() {

				@Override
				public void run() {
					if (clonedFinal.isActive()) {
						plugin.getEffectHandler().activate(clonedFinal, user);
					} else {
						cancel();
					}

				}
			}, clone.getReActivateEffectsTimer() * 1000, clone.getReActivateEffectsTimer() * 1000);
		}

		if (perk.getEffects().contains(Effect.RandomToolRepair)) {
			final Perk clonedFinal = clone;
			double timing = clonedFinal.getIncreasePercent(Effect.RandomToolRepair);
			timer.scheduleAtFixedRate(new TimerTask() {

				@Override
				public void run() {
					RepairEffect repairEffect = new RepairEffect();
					if (clonedFinal.isActive() && clonedFinal.getEffects().contains(Effect.RandomToolRepair)) {
						for (String uuid : clonedFinal.getEffectedPlayers()) {
							repairEffect.repairRandomTool(Bukkit.getPlayer(UUID.fromString(uuid)));
						}
					} else {
						cancel();
					}

				}
			}, 1000, (long) timing);
		}

		if (perk.getEffects().contains(Effect.ToolRepair)) {
			final Perk clonedFinal = clone;
			double timing = clonedFinal.getIncreasePercent(Effect.ToolRepair);
			timer.scheduleAtFixedRate(new TimerTask() {

				@Override
				public void run() {
					RepairEffect repairEffect = new RepairEffect();
					if (clonedFinal.isActive() && clonedFinal.getEffects().contains(Effect.ToolRepair)) {
						for (String uuid : clonedFinal.getEffectedPlayers()) {
							repairEffect.repairTools(Bukkit.getPlayer(UUID.fromString(uuid)));
						}
					} else {
						cancel();
					}

				}
			}, 1000, (long) timing);
		}

		if (perk.getEffects().contains(Effect.RandomArmorRepair)) {
			final Perk clonedFinal = clone;
			double timing = clonedFinal.getIncreasePercent(Effect.RandomArmorRepair);
			timer.scheduleAtFixedRate(new TimerTask() {

				@Override
				public void run() {
					RepairEffect repairEffect = new RepairEffect();
					if (clonedFinal.isActive() && clonedFinal.getEffects().contains(Effect.RandomArmorRepair)) {
						for (String uuid : clonedFinal.getEffectedPlayers()) {
							repairEffect.repairRandomArmour(Bukkit.getPlayer(UUID.fromString(uuid)));
						}
					} else {
						cancel();
					}

				}
			}, 1000, (long) timing);
		}

		if (perk.getEffects().contains(Effect.ArmorRepair)) {
			final Perk clonedFinal = clone;
			double timing = clonedFinal.getIncreasePercent(Effect.ArmorRepair);
			timer.scheduleAtFixedRate(new TimerTask() {

				@Override
				public void run() {
					RepairEffect repairEffect = new RepairEffect();
					if (clonedFinal.isActive() && clonedFinal.getEffects().contains(Effect.ArmorRepair)) {
						for (String uuid : clonedFinal.getEffectedPlayers()) {
							repairEffect.repairArmour(Bukkit.getPlayer(UUID.fromString(uuid)));
						}
					} else {
						cancel();
					}

				}
			}, 1000, (long) timing);
		}

		if (perk.getEffects().contains(Effect.RandomAllRepair)) {
			final Perk clonedFinal = clone;
			double timing = clonedFinal.getIncreasePercent(Effect.RandomAllRepair);
			timer.scheduleAtFixedRate(new TimerTask() {

				@Override
				public void run() {
					RepairEffect repairEffect = new RepairEffect();
					if (clonedFinal.isActive() && clonedFinal.getEffects().contains(Effect.RandomAllRepair)) {
						for (String uuid : clonedFinal.getEffectedPlayers()) {
							repairEffect.repairRandomItem(Bukkit.getPlayer(UUID.fromString(uuid)));
						}
					} else {
						cancel();
					}

				}
			}, 1000, (long) timing);
		}

		if (perk.getEffects().contains(Effect.AllRepair)) {
			final Perk clonedFinal = clone;
			double timing = clonedFinal.getIncreasePercent(Effect.AllRepair);
			timer.scheduleAtFixedRate(new TimerTask() {

				@Override
				public void run() {
					RepairEffect repairEffect = new RepairEffect();
					if (clonedFinal.isActive() && clonedFinal.getEffects().contains(Effect.AllRepair)) {
						for (String uuid : clonedFinal.getEffectedPlayers()) {
							repairEffect.repairArmour(Bukkit.getPlayer(UUID.fromString(uuid)));
							repairEffect.repairTools(Bukkit.getPlayer(UUID.fromString(uuid)));
						}
					} else {
						cancel();
					}

				}
			}, 1000, (long) timing);
		}

            if (clone.isTimed() && !clone.isLastForever()) {
                synchronized (this) { clone.scheduleDeactivation(user, exp.getTime()); }
            }
        } catch (RuntimeException failure) {
            synchronized (this) { activePerks.remove(clone); }
            clone.setActive(false);
            for (Effect effect : clone.getEffects()) {
                try { effect.removeEffect(plugin, clone, clone.getEffectedPlayers()); }
                catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            throw failure;
        }

		if (runActivationHooks) {
			boolean perkRewards = plugin.getRewardHandler().hasRewards(ConfigPerks.getInstance().getData(perk.getPerk()),
					ConfigPerks.getInstance().getActivationEffectPath());
			for (String uuid : clone.getEffectedPlayers()) {
				if (perkRewards) {
					new RewardBuilder(ConfigPerks.getInstance().getData(perk.getPerk()),
							ConfigPerks.getInstance().getActivationEffectPath()).setGiveOffline(false)
							.send(plugin.getUserManager().getUser(UUID.fromString(uuid)));
				} else {
					new RewardBuilder(plugin.getConfigFile().getData(), plugin.getConfigFile().getActivationEffectPath())
							.setGiveOffline(false).send(plugin.getUserManager().getUser(UUID.fromString(uuid)));
				}
			}
		}

		if (plugin.getConfigFile().getBossBarEnabled() && clone.isTimed() && !clone.isLastForever()) {
			final BossBar bossBar = new BossBar(
					PlaceholderUtils.replacePlaceHolder(PlaceholderUtils
							.replacePlaceHolder(plugin.getConfigFile().getBossBarMessage(), "perk", clone.getName()),
							"seconds", "" + (length)),
					plugin.getConfigFile().getBossBarColor(), plugin.getConfigFile().getBossBarStyle(), 1);
			clone.setBossBar(bossBar);

			for (String uuid : clone.getEffectedPlayers()) {
				if (plugin.getMcperksUserManager().getMCPerksUser(java.util.UUID.fromString(uuid)).isUseBossBar()) {
					bossBar.addPlayer(Bukkit.getPlayer(java.util.UUID.fromString(uuid)),
							plugin.getConfigFile().getBossBarHideInDelay());
				}
			}

			long delay = 0;
			for (int i = 0; i <= length; i++) {
				final int time = i + 1;
				delay += 1000;
				timer.schedule(new TimerTask() {

					@Override
					public void run() {
						if (!clone.isActive()) {
							cancel();
							return;
						}
						try {
							// set bossbar text
							HashMap<String, String> placeholders = new HashMap<String, String>();
							placeholders.put("perk", clone.getName());
							placeholders.put("seconds", "" + (length - time));
							int minutes = (length - time) / 60;
							placeholders.put("minutes", "" + minutes);
							int hours = minutes / 24;
							placeholders.put("hours", "" + hours);
							bossBar.setTitle(PlaceholderUtils
									.replacePlaceHolder(plugin.getConfigFile().getBossBarMessage(), placeholders));

							// process time for progress bar
							double time1 = time;
							double perkTime = length;
							double prog = 1.0 - time1 / perkTime;
							if (prog < 0) {
								prog = 0;
							}
							if (prog > 1) {
								prog = 1;
							}
							bossBar.setProgress(prog);

							bossBar.setVisible(true);
						} catch (NullPointerException e) {
							AdvancedCorePlugin.getInstance().debug(e);
							plugin.debug("Failed to update bossbar, turn debug on to see stacktrace");
						}
					}
				}, delay);
			}
		}

		if (runActivationHooks) clone.announcePerk(user.getPlayerName(), length);

		if (runActivationHooks) plugin.getMcperksServerData()
				.addPerkHistory(user.getPlayerName() + ":" + ArrayUtils.makeStringList(clone.getEffectedPlayers()) + ":"
						+ perk.getName() + ":" + perk.getPerkType().toString() + ":" + System.currentTimeMillis());

		printActivePerks();

		if (runActivationHooks && plugin.getConfigFile().getLogActivation() && activationLog != null)

		{

			String str = new SimpleDateFormat("EEE, d MMM yyyy HH:mm").format(Calendar.getInstance().getTime());
            String message = str + " Activated perk " + perk.getName() + " by " + clone.getActivater().getPlayerName()
                    + ". Affected users: " + ArrayUtils.makeStringList(clone.getEffectedPlayers())
                    + ". Lasted for " + length + " Perk type: " + clone.getPerkType().toString();
            Logger writer = activationLog;
            plugin.getMcperksServerData().activationLog(() -> writer.logToFile(message));
		}
	}

	public void addQue(MCPerksUser user, Perk perk) {
		que.put(user, perk);
	}

	/**
	 * Adds the to list.
	 *
	 * @param key the key
	 * @param p   the p
	 */
	private void addToList(String key, Perk p) {
		loadedPerks.put(key, p);
	}

    private final java.util.concurrent.atomic.AtomicInteger pendingNativeActivations = new java.util.concurrent.atomic.AtomicInteger();
    private boolean nativeReloadPending;
    private boolean nativeActivationsRetired;
    static final class NativeActivation {
        private final String owner;
        private boolean capacityPending = true;
        private Player session;
        private final java.util.Set<Player> earlyQuits = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private boolean earlyQuitOverflow;
        volatile boolean sessionRetired;
        volatile boolean gameplayStarted;
        private NativeActivation(String owner) { this.owner = owner; }
    }
    private final java.util.Set<NativeActivation> nativeSessions = new java.util.HashSet<>();
    synchronized void bindNativeSession(NativeActivation activation, Player session) {
        if (activation == null) return;
        activation.session = session;
        if (session != null && (activation.earlyQuitOverflow || activation.earlyQuits.contains(session)))
            activation.sessionRetired = true;
        activation.earlyQuits.clear();
    }
    /** Called by the quitting player owner; only the exact captured session is retired. */
    public synchronized void retirePendingPlayerSession(Player session) {
        var id = session.getUniqueId();
        for (var activation : nativeSessions) {
            if (activation.session == session) activation.sessionRetired = true;
            else if (activation.session == null && id != null && id.toString().equals(activation.owner)) {
                if (activation.earlyQuits.size() < 8) activation.earlyQuits.add(session);
                else activation.earlyQuitOverflow = true; // Fail closed if identity history is uncertain.
            }
        }
    }
    /** Unknown overrides may run side effects before the standard effect boundary. */
    boolean usesManagedGameplayBoundary() {
        try {
            return getClass().getMethod("activePerk", Perk.class, MCPerksUser.class, int.class, Timestamp.class)
                    .getDeclaringClass() == PerkHandler.class;
        } catch (ReflectiveOperationException | SecurityException unavailable) { return false; }
    }
    private final ThreadLocal<NativeActivation> publishingActivation = new ThreadLocal<>();
    NativeActivation enterNativeGameplay(NativeActivation activation) {
        NativeActivation previous = publishingActivation.get();
        if (activation == null) publishingActivation.remove(); else publishingActivation.set(activation);
        return previous;
    }
    void leaveNativeGameplay(NativeActivation previous) {
        if (previous == null) publishingActivation.remove(); else publishingActivation.set(previous);
    }
    synchronized void releaseNativeCapacity(NativeActivation activation) {
        if (activation == null || !activation.capacityPending) return;
        activation.capacityPending = false;
        pendingPlayerActivations.computeIfPresent(activation.owner, (key, count) -> count == 1 ? null : count - 1);
    }
    private final Map<String, Integer> pendingPlayerActivations = new HashMap<>();
    private java.util.concurrent.CompletableFuture<Void> nativeActivationDrain = java.util.concurrent.CompletableFuture.completedFuture(null);
    /** Close new admissions; the private drain includes accepted storage and compensation. */
    public synchronized java.util.concurrent.CompletionStage<Void> retireNativeActivations() {
        nativeActivationsRetired = true;
        return nativeActivationDrain.minimalCompletionStage();
    }
    boolean hasPendingNativeActivations() { return pendingNativeActivations.get() != 0; }
    /** A reload may retire definitions only when accepted work and startup restoration are settled. */
    public synchronized void pauseNativeActivationsForReload() {
        if (!isRestorationReady() || hasPendingNativeActivations())
            throw new IllegalStateException("Wait for perk activation or startup restoration to finish before reloading");
        nativeReloadPending = true;
    }
    public synchronized void resumeNativeActivationsAfterReload() { nativeReloadPending = false; }
    synchronized NativeActivation beginNativeActivation(MCPerksUser user, boolean enforcePlayerLimit) {
        if (nativeReloadPending || nativeActivationsRetired || !isRestorationReady())
            throw new IllegalStateException("Perk lifecycle is unavailable");
        if (pendingNativeActivations.get() >= 256) throw new IllegalStateException("Perk admissions are full");
        String owner = user == null ? null : user.getUUID();
        int pending = pendingPlayerActivations.getOrDefault(owner, 0);
        if (enforcePlayerLimit && user != null && plugin.getConfigFile().getLimitPermission()) {
            int limit = user.getPerkLimit();
            if (limit != -1 && user.getActivePerks() + pending >= limit)
                throw new IllegalStateException("Player perk capacity is reserved");
        }
        pendingPlayerActivations.put(owner, pending + 1);
        if (pendingNativeActivations.getAndIncrement() == 0) nativeActivationDrain = new java.util.concurrent.CompletableFuture<>();
        var activation = new NativeActivation(owner);
        nativeSessions.add(activation);
        return activation;
    }
    void finishNativeActivation(NativeActivation activation) {
        java.util.concurrent.CompletableFuture<Void> drained;
        synchronized (this) {
            nativeSessions.remove(activation);
            releaseNativeCapacity(activation);
            if (pendingNativeActivations.decrementAndGet() != 0) return;
            drained = nativeActivationDrain;
        }
        // Dependent shutdown callbacks must not run while holding the admission monitor.
        drained.complete(null);
        if (!plugin.isEnabled()
                || plugin.getPerkHandler() != this || !plugin.getConfigFile().getPerkQue()) return;
        try { plugin.getBukkitScheduler().runTask(plugin, () -> {
            if (plugin.isEnabled() && plugin.getPerkHandler() == this) checkQue();
        }); }
        catch (RuntimeException failure) { plugin.getLogger().warning("Legacy perk queue owner dispatch failed"); }
    }

	public void checkQue() {
		if (!hasPendingNativeActivations() && !que.isEmpty() && activePerks.size() == 0) {
			for (Entry<MCPerksUser, Perk> entry : que.entrySet()) {
				entry.getValue().runPerk(entry.getKey());
				que.remove(entry.getKey());
				return;
			}

		}
	}

	public void clearQueue() {
		que.clear();
	}

	public void copyPerks() throws IOException {
		File file = new File(plugin.getDataFolder(), "Perks");
		if (!file.exists()) {
			CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
			if (src != null) {
				URL jar = src.getLocation();
				ZipInputStream zip = null;
				zip = new ZipInputStream(jar.openStream());
				while (true) {
					ZipEntry e;
					e = zip.getNextEntry();
					if (e == null) {
						break;
					}
					String name = e.getName();
					if (name.startsWith("Perks")) {
						// plugin.debug(name);
						String[] list = name.split("/");
						if (list.length > 1) {
							if (!list[1].equals("")) {
								if (!new File(plugin.getDataFolder(), "Perks" + File.separator + list[1]).exists()) {
									plugin.saveResource(name, false);
								}
							}
						}
					}
				}
			} else {
				plugin.debug("Failed to copy perks");
			}
		} else {
			plugin.debug("Perk folder already exists, not generating perks");
		}
	}

	public boolean effectActive(Effect effect, String uuid, String world) {
		ListIterator<Perk> it = activePerks.listIterator();
		while (it.hasNext()) {
			Perk perk = it.next();
			if (perk.getEffects().contains(effect)) {
				if (uuid == null || perk.getEffectedPlayers().contains(uuid)) {
					// plugin.debug("Effect active, " + effect.toString() + " for " + uuid);
					if (perk.isNotInDisabledWorld(world)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public double effectActiveModifier(Effect effect, String uuid, int defaultValue, String world) {
		ListIterator<Perk> it = activePerks.listIterator();
		while (it.hasNext()) {
			Perk perk = it.next();
			if (perk.getEffects().contains(effect)) {
				if (uuid == null || perk.getEffectedPlayers().contains(uuid)) {
					// plugin.debug("Effect active, " + effect.toString() + " for " + uuid);

					for (Effect e : perk.getEffects()) {
						if (e.equals(effect)) {
							if (perk.isNotInDisabledWorld(world)) {
								return e.getModifier();
							}
						}
					}
				}
			}
		}
		return defaultValue;
	}

	/**
	 * Gets the loaded perks.
	 *
	 * @return the loaded perks
	 */
	public Map<String, Perk> getLoadedPerks() {
		return loadedPerks;
	}

	/**
	 * Gets the perk.
	 *
	 * @param key the key
	 * @return the perk
	 */
	public Perk getPerk(String key) {
		try {
			return loadedPerks.get(key);
		} catch (ClassCastException | NullPointerException ex) {
			return null;
		}
	}

	public Perk getActivePerk(MCPerksUser user, Perk p) {
		for (Perk perk : MCPerksMain.plugin.getPerkHandler().getActivePerks()) {
			if (perk.getPerk().equals(p.getPerk())) {
				if (perk.getActivater().getPlayerName().equals(user.getPlayerName())) {
					return perk;
				}
				if (perk.getPerkType().equals(PerkSystemType.ALL)) {
					return perk;
				}
			}

		}
		return p;
	}

    public void loadActivePerks() { loadActivePerksConfirmed(() -> {}); }

    /** Completes only after legacy active state and the caller's shutdown records are restored. */
    public synchronized java.util.concurrent.CompletableFuture<Void> loadActivePerksConfirmed(Runnable shutdownRecords) {
        var completed = new java.util.concurrent.CompletableFuture<Void>();
        restoration = completed;
        try {
            plugin.getBukkitScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    requireCurrentRestoration(completed);
                    var users = new ArrayList<MCPerksUser>();
                    for (String uuid : plugin.getUserManager().getAllUUIDs()) {
                        requireCurrentRestoration(completed);
                        MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(UUID.fromString(uuid));
                        user.userDataFetechMode(UserDataFetchMode.NO_CACHE); users.add(user);
                    }
                    var nativeRecords = new ArrayList<NativeRestoration>();
                    var shutdownRows = new java.util.HashSet<String>();
                    var shutdownNames = new java.util.HashSet<String>();
                    var restorationOwners = new java.util.HashSet<UUID>();
                    var runtime = plugin.getMcperksServerData();
                    synchronized (runtime) {
                        var shutdown = runtime.getData().getConfigurationSection("OfflinePerk");
                        if (shutdown != null) for (String owner : shutdown.getKeys(false)) {
                            var records = shutdown.getConfigurationSection(owner);
                            if (records != null) for (String name : records.getKeys(false)) {
                                shutdownRows.add(owner + ":" + name); shutdownNames.add(name);
                                var definition = getPerk(name);
                                long expiration = records.getLong(name + ".Experiaton");
                                if (definition != null && definition.isEnabled()
                                        && (expiration <= 0 || expiration > System.currentTimeMillis()))
                                    restorationOwners.add(UUID.fromString(owner));
                            }
                        }
                    }
                    var restoredGlobals = new java.util.HashSet<String>();
                    for (Perk perk : List.copyOf(getLoadedPerks().values())) {
                        if (!perk.isEnabled()) continue;
                        for (MCPerksUser user : users) {
                            requireCurrentRestoration(completed);
                            user.userDataFetechMode(UserDataFetchMode.NO_CACHE);
                            boolean global = perk.getPerkType() == PerkSystemType.ALL;
                            if (global ? shutdownNames.contains(perk.getPerk())
                                    : shutdownRows.contains(user.getUUID() + ":" + perk.getPerk())) continue;
                            long expiration = perk.getExperation(user);
                            if (expiration < 0 || expiration > System.currentTimeMillis()) {
                                if (!global || restoredGlobals.add(perk.getPerk())) {
                                    nativeRecords.add(new NativeRestoration(perk, user, expiration));
                                    if (perk.getPerkType() == PerkSystemType.PLAYER)
                                        restorationOwners.add(UUID.fromString(user.getUUID()));
                                }
                            }
                        }
                    }
                    // New wrappers used during restoration need the canonical shared snapshot.
                    // Populate it here on the storage-safe worker, not from the owner callback.
                    var manager = plugin.getUserManager().getDataManager();
                    if (manager.usesSharedSqlStorage(plugin.getStorageType())) for (UUID owner : restorationOwners) {
                        requireCurrentRestoration(completed);
                        if (manager.getPublishedCache(owner) == null) manager.cacheUser(owner);
                        if (manager.getPublishedCache(owner) == null)
                            throw new IllegalStateException("Restoration user cache was not published");
                    }
                    var invoked = new java.util.concurrent.atomic.AtomicBoolean();
                    plugin.getBukkitScheduler().runTask(plugin, () -> {
                        if (!invoked.compareAndSet(false, true)) return;
                        try {
                            requireCurrentRestoration(completed);
                            for (var record : nativeRecords) {
                                requireCurrentRestoration(completed);
                                if (record.expiration() < 0 || record.expiration() > System.currentTimeMillis())
                                    restoreActivePerk(record.perk(), record.user(), record.expiration() < 0
                                            ? null : new Timestamp(record.expiration()));
                            }
                            shutdownRecords.run();
                            completed.complete(null);
                        } catch (Throwable failure) { completed.completeExceptionally(failure); }
                    });
                    java.util.concurrent.CompletableFuture.delayedExecutor(30, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                        if (invoked.compareAndSet(false, true)) completed.completeExceptionally(
                                new java.util.concurrent.TimeoutException("Perk restoration owner dispatch timed out"));
                    });
                } catch (Throwable error) { completed.completeExceptionally(error); }
            });
        } catch (RuntimeException error) { completed.completeExceptionally(error); }
        return completed.copy();
    }
    private record NativeRestoration(Perk perk, MCPerksUser user, long expiration) {}
    private void requireCurrentRestoration(java.util.concurrent.CompletableFuture<Void> completed) {
        if (restoration != completed || plugin.getPerkHandler() != this || !plugin.isEnabled())
            throw new IllegalStateException("Legacy restoration lifecycle retired");
    }

	/**
	 * Load enabled perks.
	 * 
	 * @param loadperks loadperks
	 */
	public void loadEnabledPerks(boolean loadperks) {
		for (String name : ConfigPerks.getInstance().getPerksNames()) {
			loadPerk(name);
		}

		loadInvPerks();

		if (loadperks) {
			loadActivePerks();
		}

	}

	public void loadInvPerks() {
		HashMap<Perk, Integer> perksSorted = new LinkedHashMap<Perk, Integer>();
		for (Perk perk : getLoadedPerks().values()) {
			perksSorted.put(perk, perk.getPriority());
		}

		boolean usePriority = false;
		for (Integer num : perksSorted.values()) {
			if (num.intValue() > 0) {
				usePriority = true;
			}
		}

		if (usePriority) {
			perksSorted = sortByValues(perksSorted, false);

			int count = 0;
			for (Perk perk : perksSorted.keySet()) {
				invPerks.put(count, perk);
				count++;
			}
		} else {
			Map<String, Perk> sortedMap = new TreeMap<String, Perk>();
			for (Perk perk : perksSorted.keySet()) {
				sortedMap.put(perk.getName(), perk);
			}

			int count = 0;
			for (Perk perk : sortedMap.values()) {
				invPerks.put(count, perk);
				count++;
			}
		}

	}

	public void loadPerk(String perkName) {
		try {
			Perk perk = new Perk(perkName);
			if (perk.isEnabled()) {
				plugin.debug("Loading perk: " + perk.getName() + " : " + perk.getPerkType().toString() + " effects: "
						+ perk.listEffects());
				addToList(perkName, perk);
			} else {
				plugin.debug("Perk " + perk.getName() + " not enabled");
			}
		} catch (Exception e) {
			plugin.getLogger().warning("Failed to load perk: " + perkName);
			e.printStackTrace();
		}

	}

	private void printActivePerks() {
		ArrayList<String> str = new ArrayList<String>();
		for (Perk active : activePerks) {
			str.add(active.getPerk() + ":" + ArrayUtils.makeStringList(active.getEffectedPlayers()));
		}
		plugin.debug("Active perks: " + ArrayUtils.makeStringList(str));
	}

	public void reload() {
		loadedPerks.clear();
		try {
			copyPerks();
		} catch (IOException e) {
			plugin.getLogger().info("Failed to copy default perks");
			e.printStackTrace();
		}
		loadEnabledPerks(false);

	}

    public synchronized void retireTimers() { timer.cancel(); }

	public synchronized void remove(Perk pe, MCPerksUser user) {
		getActivePerks().remove(pe);
		checkQue();
		printActivePerks();
	}

	/**
	 * Sets the perk.
	 *
	 * @param key  the key
	 * @param perk the perk
	 */
	public void setPerk(String key, Perk perk) {
		loadedPerks.put(key, perk);
	}

	public HashMap<Perk, Integer> sortByValues(HashMap<Perk, Integer> unsortMap, final boolean order) {

		List<Entry<Perk, Integer>> list = new LinkedList<Entry<Perk, Integer>>(unsortMap.entrySet());

		// Sorting the list based on values
		Collections.sort(list, new Comparator<Entry<Perk, Integer>>() {
			@Override
			public int compare(Entry<Perk, Integer> o1, Entry<Perk, Integer> o2) {
				if (order) {
					return o1.getValue().compareTo(o2.getValue());
				} else {
					return o2.getValue().compareTo(o1.getValue());

				}
			}
		});

		// Maintaining insertion order with the help of LinkedList
		HashMap<Perk, Integer> sortedMap = new LinkedHashMap<Perk, Integer>();
		for (Entry<Perk, Integer> entry : list) {
			sortedMap.put(entry.getKey(), entry.getValue());
		}

		return sortedMap;
	}
}
