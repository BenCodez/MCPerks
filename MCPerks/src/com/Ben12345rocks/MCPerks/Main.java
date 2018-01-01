/*
 *
 */
package com.Ben12345rocks.MCPerks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.Ben12345rocks.AdvancedCore.AdvancedCoreHook;
import com.Ben12345rocks.AdvancedCore.Objects.CommandHandler;
import com.Ben12345rocks.AdvancedCore.Objects.UserStorage;
import com.Ben12345rocks.AdvancedCore.Thread.Thread;
import com.Ben12345rocks.AdvancedCore.Util.Metrics.BStatsMetrics;
import com.Ben12345rocks.AdvancedCore.Util.Metrics.MCStatsMetrics;
import com.Ben12345rocks.AdvancedCore.Util.Misc.MiscUtils;
import com.Ben12345rocks.AdvancedCore.Util.Updater.Updater;
import com.Ben12345rocks.AdvancedCore.mysql.MySQL;
import com.Ben12345rocks.MCPerks.Commands.CommandLoader;
import com.Ben12345rocks.MCPerks.Commands.Executers.CommandMCPerks;
import com.Ben12345rocks.MCPerks.Commands.TabCompleter.MCPerksTabCompleter;
import com.Ben12345rocks.MCPerks.Configs.Config;
import com.Ben12345rocks.MCPerks.Configs.Lang;
import com.Ben12345rocks.MCPerks.Data.ServerData;
import com.Ben12345rocks.MCPerks.Listeners.EffectListeners;
import com.Ben12345rocks.MCPerks.Listeners.Compatability.McMMOEvents;
import com.Ben12345rocks.MCPerks.Objects.EffectHandler;
import com.Ben12345rocks.MCPerks.Objects.PerkHandler;
import com.Ben12345rocks.MCPerks.Objects.PerkSystemType;

// TODO: Auto-generated Javadoc
/**
 * The Class Main.
 */
public class Main extends JavaPlugin {

	/** The plugin. */
	public static Main plugin;

	/** The mh. */
	private PerkHandler perks;

	private EffectHandler effects;

	public ArrayList<CommandHandler> commands;

	public HashMap<String, Boolean> flyingUUIDs;

	private PerkSystemType perkSystemType;

	public void broadcast(String msg) {
		MiscUtils.getInstance().broadcast(msg);
	}

	public void debug(String msg) {
		if (Config.getInstance().getDebugEnabled()) {
			plugin.getLogger().info("Debug: " + msg);
		}
	}

	public EffectHandler getEffectHandler() {
		return effects;
	}

	/**
	 * Gets the main.
	 *
	 * @return the main
	 */
	public Main getMain() {
		return this;
	}

	/**
	 * Gets the perk handler.
	 *
	 * @return the perk handler
	 */
	public PerkHandler getPerkHandler() {
		return perks;
	}

	public void loadEvents() {
		PluginManager pm = getServer().getPluginManager();

		pm.registerEvents(new EffectListeners(this), this);
		if (Bukkit.getPluginManager().getPlugin("mcMMO") != null) {
			pm.registerEvents(new McMMOEvents(this), this);
		}

	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.bukkit.plugin.java.JavaPlugin#onDisable()
	 */
	@Override
	public void onDisable() {
		HandlerList.unregisterAll(plugin);
		plugin = null;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.bukkit.plugin.java.JavaPlugin#onEnable()
	 */
	@Override
	public void onEnable() {
		plugin = this;
		flyingUUIDs = new HashMap<String, Boolean>();
		loadEvents();
		setupFiles();
		updateHook();
		AdvancedCoreHook.getInstance().loadHook(this);
		perks = new PerkHandler();
		effects = new EffectHandler();

		CommandLoader.getInstance().loadCommands();

		getCommand("mcperks").setExecutor(new CommandMCPerks());
		getCommand("mcperks").setTabCompleter(new MCPerksTabCompleter());

		try {
			MCStatsMetrics metrics = new MCStatsMetrics(this);
			metrics.start();
		} catch (IOException e) {
			debug("Failed to load metrics");
		}

		new BStatsMetrics(this);

		Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, new Runnable() {

			@Override
			public void run() {
				Thread.getInstance().run(new Runnable() {

					@Override
					public void run() {
						Updater updater = new Updater(plugin, 27898, false);
						if (updater.getResult().equals(Updater.UpdateResult.UPDATE_AVAILABLE)) {
							plugin.getLogger().info("Found an update available! Your version: "
									+ plugin.getDescription().getVersion() + " New Version: " + updater.getVersion());
						}
					}
				});
			}
		}, 10l);

	}

	public void reload() {
		Config.getInstance().reloadData();
		perkSystemType = PerkSystemType.valueOf(Config.getInstance().getPerkSystemType());
		Lang.getInstance().reloadData();
		ServerData.getInstance().reloadData();
		getPerkHandler().reload();
		CommandLoader.getInstance().loadTabComplete();
		AdvancedCoreHook.getInstance().reload();
		updateHook();
	}

	/**
	 * Setup files.
	 */
	private void setupFiles() {
		Config.getInstance().setup();
		Lang.getInstance().setup(this);
		ServerData.getInstance().setup(this);
		perkSystemType = PerkSystemType.valueOf(Config.getInstance().getPerkSystemType());
	}

	public void updateHook() {
		AdvancedCoreHook.getInstance().setExtraDebug(Config.getInstance().getExtraDebug());
		AdvancedCoreHook.getInstance().setStorageType(UserStorage.valueOf(Config.getInstance().getDataStorage()));
		if (AdvancedCoreHook.getInstance().getStorageType().equals(UserStorage.MYSQL)) {
			Thread.getInstance().run(new Runnable() {

				@Override
				public void run() {
					AdvancedCoreHook.getInstance()
							.setMysql(new MySQL("MCPerks_Users", Config.getInstance().getMysql()));

				}
			});

		}
		AdvancedCoreHook.getInstance().setDebug(Config.getInstance().getDebugEnabled());
		AdvancedCoreHook.getInstance().setHelpLine(Lang.getInstance().getHelpLine());
		AdvancedCoreHook.getInstance().setFormatNoPerms(Lang.getInstance().getNoPermission());
		AdvancedCoreHook.getInstance().setFormatNotNumber(Lang.getInstance().getIncorrectCommandReply());
	}

	public PerkSystemType getPerkSystemType() {
		return perkSystemType;
	}
}
