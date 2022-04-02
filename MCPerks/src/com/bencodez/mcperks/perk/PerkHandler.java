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
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.messages.StringParser;
import com.bencodez.advancedcore.api.misc.ArrayUtils;
import com.bencodez.advancedcore.api.misc.effects.BossBar;
import com.bencodez.advancedcore.api.rewards.RewardBuilder;
import com.bencodez.advancedcore.api.rewards.RewardHandler;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.advancedcore.logger.Logger;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.configs.ConfigPerks;
import com.bencodez.mcperks.configs.Lang;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.massivecraft.factions.entity.MPlayer;
import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.object.Resident;

// TODO: Auto-generated Javadoc
/**
 * The Class ModuleHandler.
 */
public class PerkHandler {

	/** The loaded perks. */
	private Map<String, Perk> loadedPerks = new HashMap<String, Perk>();

	/** The main. */
	private MCPerksMain plugin = MCPerksMain.plugin;

	public Map<Integer, Perk> invPerks = new HashMap<Integer, Perk>();

	public HashMap<MCPerksUser, Perk> que = new HashMap<MCPerksUser, Perk>();

	private List<Perk> activePerks = Collections.synchronizedList(new ArrayList<Perk>());

	private Timer timer = new Timer();

	private Logger activationLog;

	public PerkHandler() {
		if (plugin.getConfigFile().getLoadDefaultPerks()) {
			try {
				copyPerks();
			} catch (IOException e) {
				plugin.getLogger().info("Failed to copy default perks");
				e.printStackTrace();
			}
		}
		loadEnabledPerks(true);

		if (plugin.getConfigFile().getLogActivation()) {
			activationLog = new Logger(plugin, new File(plugin.getDataFolder(), "ActivationLog.txt"));
		}
	}

	public void activePerk(Perk perk, MCPerksUser user, int length) {
		final Perk clone = perk.clone();
		clone.setActivater(user);
		clone.getEffectedPlayers().clear();
		ArrayList<Integer> times = new ArrayList<Integer>();
		if (perk.getTime() > 0) {
			for (String str : ConfigPerks.getInstance().getCountDownTimes(perk.getPerk())) {
				if (StringParser.getInstance().isInt(str)) {
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

							if (RewardHandler.getInstance().hasRewards(
									ConfigPerks.getInstance().getData(perk.getPerk()),
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

		MCPerksMain.plugin.getEffectHandler().activate(clone, user);
		if (clone.isTimed()) {
			clone.setActive(true);
			if (!clone.isLastForever()) {

				Timestamp exp = new Timestamp(Calendar.getInstance().getTime().getTime() + 1000 * length);
				clone.setExperation(user, exp.getTime());

				clone.scheduleDeactivation(user);
			} else {
				clone.setExperation(user, -1);
			}
			activePerks.add(clone);
		}

		boolean perkRewards = RewardHandler.getInstance().hasRewards(ConfigPerks.getInstance().getData(perk.getPerk()),
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

		if (plugin.getConfigFile().getBossBarEnabled() && clone.isTimed() && !clone.isLastForever()) {
			final BossBar bossBar = new BossBar(
					StringParser.getInstance()
							.replacePlaceHolder(
									StringParser.getInstance().replacePlaceHolder(
											plugin.getConfigFile().getBossBarMessage(), "perk", clone.getName()),
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
							bossBar.setTitle(StringParser.getInstance()
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

		clone.announcePerk(user.getPlayerName(), length);

		plugin.getMcperksServerData()
				.addPerkHistory(user.getPlayerName() + ":"
						+ ArrayUtils.getInstance().makeStringList(clone.getEffectedPlayers()) + ":" + perk.getName()
						+ ":" + perk.getPerkType().toString() + ":" + System.currentTimeMillis());

		printActivePerks();

		if (plugin.getConfigFile().getLogActivation() && activationLog != null) {

			String str = new SimpleDateFormat("EEE, d MMM yyyy HH:mm").format(Calendar.getInstance().getTime());
			activationLog
					.logToFile(str + " Activated perk " + perk.getName() + " by " + clone.getActivater().getPlayerName()
							+ ". Affected users: " + ArrayUtils.getInstance().makeStringList(clone.getEffectedPlayers())
							+ ". Lasted for " + length + " Perk type: " + clone.getPerkType().toString());
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

	public void checkQue() {
		if (!que.isEmpty() && activePerks.size() == 0) {
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
	 * @return the activePerks
	 */
	public List<Perk> getActivePerks() {
		return activePerks;
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

	public void loadActivePerks() {
		Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {

			@Override
			public void run() {
				ArrayList<MCPerksUser> users = new ArrayList<MCPerksUser>();
				for (String uuid : plugin.getUserManager().getAllUUIDs()) {
					MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(UUID.fromString(uuid));
					user.dontCache();
					users.add(user);
				}
				for (Perk perk : getLoadedPerks().values()) {
					for (MCPerksUser user : users) {
						user.dontCache();
						if (perk.getExperation(user) < 0) {
							perk.activatePerk(user, perk.getTime());
						} else {
							perk.scheduleDeactivation(user);
						}

					}
				}
			}
		});
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
		HashMap<Perk, Integer> perksSorted = new HashMap<Perk, Integer>();
		for (Perk perk : getLoadedPerks().values()) {
			perksSorted.put(perk, perk.getPriority());
		}

		perksSorted = sortByValues(perksSorted, false);

		int count = 0;
		for (Perk perk : perksSorted.keySet()) {
			invPerks.put(count, perk);
			count++;
		}
	}

	public void loadPerk(String perkName) {
		try {
			Perk perk = new Perk(perkName);
			if (perk.isEnabled()) {
				plugin.debug("Loading perk: " + perk.getName() + " : " + perk.getPerkType().toString());
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
			str.add(active.getPerk() + ":" + ArrayUtils.getInstance().makeStringList(active.getEffectedPlayers()));
		}
		plugin.debug("Active perks: " + ArrayUtils.getInstance().makeStringList(str));
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

	public void remove(Perk pe, MCPerksUser user) {
		Perk perk = pe.clone();
		perk.setActivater(user);

		for (int i = getActivePerks().size() - 1; i >= 0; i--) {
			if (i >= getActivePerks().size() - 1) {
				Perk p = getActivePerks().get(i);
				if (p.getPerk().equals(perk.getPerk())
						&& p.getActivater().getPlayerName().equals(perk.getActivater().getPlayerName())) {
					getActivePerks().remove(i);
				}
			}
		}
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
