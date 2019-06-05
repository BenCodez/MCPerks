/*
 *
 */
package com.Ben12345rocks.MCPerks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.Ben12345rocks.AdvancedCore.AdvancedCoreHook;
import com.Ben12345rocks.AdvancedCore.CommandAPI.CommandHandler;
import com.Ben12345rocks.AdvancedCore.Rewards.Reward;
import com.Ben12345rocks.AdvancedCore.Rewards.RewardHandler;
import com.Ben12345rocks.AdvancedCore.Rewards.Injected.RewardInjectInt;
import com.Ben12345rocks.AdvancedCore.Thread.Thread;
import com.Ben12345rocks.AdvancedCore.UserManager.User;
import com.Ben12345rocks.AdvancedCore.Util.EditGUI.EditGUIButton;
import com.Ben12345rocks.AdvancedCore.Util.EditGUI.ValueTypes.EditGUIValueNumber;
import com.Ben12345rocks.AdvancedCore.Util.Metrics.BStatsMetrics;
import com.Ben12345rocks.AdvancedCore.Util.Metrics.MCStatsMetrics;
import com.Ben12345rocks.AdvancedCore.Util.Misc.MiscUtils;
import com.Ben12345rocks.AdvancedCore.Util.Misc.StringUtils;
import com.Ben12345rocks.AdvancedCore.Util.Updater.Updater;
import com.Ben12345rocks.MCPerks.Commands.CommandLoader;
import com.Ben12345rocks.MCPerks.Commands.Executers.CommandMCPerks;
import com.Ben12345rocks.MCPerks.Commands.TabCompleter.MCPerksTabCompleter;
import com.Ben12345rocks.MCPerks.Configs.Config;
import com.Ben12345rocks.MCPerks.Configs.Lang;
import com.Ben12345rocks.MCPerks.Data.ServerData;
import com.Ben12345rocks.MCPerks.Listeners.EffectListeners;
import com.Ben12345rocks.MCPerks.Listeners.Compatability.McMMOEvents;
import com.Ben12345rocks.MCPerks.Perk.EffectHandler;
import com.Ben12345rocks.MCPerks.Perk.PerkHandler;
import com.Ben12345rocks.MCPerks.Perk.PerkSystemType;
import com.Ben12345rocks.MCPerks.UserAPI.UserManager;

import lombok.Getter;
import lombok.Setter;

// TODO: Auto-generated Javadoc
/**
 * The Class Main.
 */
public class Main extends JavaPlugin {

	/** The plugin. */
	public static Main plugin;

	/** The mh. */
	@Getter
	private PerkHandler perkHandler;

	@Getter
	private EffectHandler effectHandler;

	@Getter
	@Setter
	private ArrayList<CommandHandler> commands;

	@Getter
	private HashMap<String, Boolean> flyingUUIDs;

	@Getter
	private PerkSystemType perkSystemType;

	public void broadcast(String msg) {
		MiscUtils.getInstance().broadcast(msg);
	}

	public void debug(String msg) {
		if (Config.getInstance().getDebugEnabled()) {
			plugin.getLogger().info("Debug: " + msg);
		}
	}

	/**
	 * Gets the main.
	 *
	 * @return the main
	 */
	public Main getMain() {
		return this;
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
	 * @see org.bukkit.plugin.java.JavaPlugin#onDisable()
	 */
	@Override
	public void onDisable() {
		HandlerList.unregisterAll(plugin);
		plugin = null;
	}

	/*
	 * (non-Javadoc)
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
		perkHandler = new PerkHandler();
		effectHandler = new EffectHandler();

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

		loadInjectedRewards();

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

	public void loadInjectedRewards() {
		if (Bukkit.getPluginManager().getPlugin("VotingPlugin") != null) {
			VotingPluginHook.getInstance().loadRewards();
		}

		RewardHandler.getInstance().addInjectedReward(new RewardInjectInt("PerkActivations") {

			@Override
			public Integer onRewardRequest(Reward reward, User user, int value, HashMap<String, String> placeholders) {
				UserManager.getInstance().getMCPerksUser(user.getPlayerName()).addActivation(value);
				return null;
			}
		}.addEditButton(new EditGUIButton(new EditGUIValueNumber("PerkActivations", null) {

			@Override
			public void setValue(Player player, Number num) {
				Reward reward = (Reward) getInv().getData("Reward");
				reward.getConfig().set(getKey(), num.intValue());
				reload();
			}
		})));
	}
	
	public String placeHolder(OfflinePlayer p, String identifier) {
		identifier = StringUtils.getInstance().replaceJavascript(p, identifier);
		com.Ben12345rocks.MCPerks.UserAPI.User user = UserManager.getInstance().getMCPerksUser(p);
		if (identifier.equalsIgnoreCase("activations")) {
			return "" + user.getActivations();
		}

		for (String perk : Main.plugin.getPerkHandler().getLoadedPerks().keySet()) {
			if (identifier.equalsIgnoreCase("activations_" + perk)) {
				return "" + user.getActivations(perk);
			}
		}
		return "";

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
		perkSystemType = PerkSystemType.valueOf(Config.getInstance().getPerkSystemType());
	}

	public void updateHook() {
		AdvancedCoreHook.getInstance().setConfigData(Config.getInstance().getData());
		AdvancedCoreHook.getInstance().getOptions().setPreloadSkulls(false);
	}
}
