/*
 *
 */
package com.Ben12345rocks.MCPerks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
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
import com.Ben12345rocks.AdvancedCore.Util.Placeholder.PlaceHolder;
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
import com.Ben12345rocks.MCPerks.Perk.Perk;
import com.Ben12345rocks.MCPerks.Perk.PerkHandler;
import com.Ben12345rocks.MCPerks.Perk.PerkSystemType;
import com.Ben12345rocks.MCPerks.UserAPI.UserManager;
import com.Ben12345rocks.VotingPlugin.VotingPluginHooks;

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
	private ArrayList<PlaceHolder<com.Ben12345rocks.MCPerks.UserAPI.User>> placeholders = new ArrayList<PlaceHolder<com.Ben12345rocks.MCPerks.UserAPI.User>>();

	public void addPlacehlder(PlaceHolder<com.Ben12345rocks.MCPerks.UserAPI.User> placeholder) {
		placeholders.add(placeholder);
	}

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
		
		loadPlaceholders();

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

		RewardHandler.getInstance().addInjectedReward(new RewardInjectInt("Activations") {

			@Override
			public String onRewardRequest(Reward reward, User user, int value, HashMap<String, String> placeholders) {
				UserManager.getInstance().getMCPerksUser(user.getPlayerName()).addActivation(value);
				return null;
			}
		}.addEditButton(new EditGUIButton(new EditGUIValueNumber("Activations", null) {

			@Override
			public void setValue(Player player, Number num) {
				Reward reward = (Reward) getInv().getData("Reward");
				reward.getConfig().set(getKey(), num.intValue());
				VotingPluginHooks.getInstance().getMainClass().reload();
			}
		})));

		for (final String perk : Main.plugin.getPerkHandler().getLoadedPerks().keySet()) {
			RewardHandler.getInstance().addInjectedReward(new RewardInjectInt("PerkActivations." + perk) {

				@Override
				public String onRewardRequest(Reward reward, User user, int value,
						HashMap<String, String> placeholders) {
					UserManager.getInstance().getMCPerksUser(user.getPlayerName()).addActivation(perk, value);
					return null;
				}
			}.addEditButton(new EditGUIButton(new EditGUIValueNumber("PerkActivations." + perk, null) {

				@Override
				public void setValue(Player player, Number num) {
					Reward reward = (Reward) getInv().getData("Reward");
					reward.getConfig().set(getKey(), num.intValue());
					VotingPluginHooks.getInstance().getMainClass().reload();
				}
			})));
		}
	}

	public void loadPlaceholders() {
		addPlacehlder(new PlaceHolder<com.Ben12345rocks.MCPerks.UserAPI.User>("activations") {

			@Override
			public String placeholderRequest(OfflinePlayer p, com.Ben12345rocks.MCPerks.UserAPI.User user,
					String identifier) {
				return "" + user.getActivations();
			}
		});

		addPlacehlder(new PlaceHolder<com.Ben12345rocks.MCPerks.UserAPI.User>("perklimit") {

			@Override
			public String placeholderRequest(OfflinePlayer p, com.Ben12345rocks.MCPerks.UserAPI.User user,
					String identifier) {
				return "" + user.getPerkLimit();
			}
		});

		addPlacehlder(new PlaceHolder<com.Ben12345rocks.MCPerks.UserAPI.User>("usebossbar") {

			@Override
			public String placeholderRequest(OfflinePlayer p, com.Ben12345rocks.MCPerks.UserAPI.User user,
					String identifier) {
				return "" + user.isUseBossBar();
			}
		});

		for (String perk : Main.plugin.getPerkHandler().getLoadedPerks().keySet()) {
			addPlacehlder(new PlaceHolder<com.Ben12345rocks.MCPerks.UserAPI.User>("activations_" + perk) {

				@Override
				public String placeholderRequest(OfflinePlayer p, com.Ben12345rocks.MCPerks.UserAPI.User user,
						String identifier) {
					return "" + user.getActivations(perk);
				}
			});
			addPlacehlder(new PlaceHolder<com.Ben12345rocks.MCPerks.UserAPI.User>("cooldown_" + perk) {

				@Override
				public String placeholderRequest(OfflinePlayer p, com.Ben12345rocks.MCPerks.UserAPI.User user,
						String identifier) {
					long coolDownTime = user.getPerkCoolDown(plugin.getPerkHandler().getPerk(perk));
					if (coolDownTime < ServerData.getInstance().getData().getLong(perk + ".CoolDown", 0)) {
						coolDownTime = ServerData.getInstance().getData().getLong(perk + ".CoolDown");
					}
					long cooldown = coolDownTime - Calendar.getInstance().getTime().getTime();
					long coolDownMins = cooldown / (1000 * 64);
					int coolDownHours = (int) (coolDownMins / 60);
					int coolDownMin = (int) (coolDownHours * 60 - coolDownMins);
					if (coolDownHours <= 0 || coolDownMin <= 0) {
						return "Cooldown ended";
					}
					return "" + coolDownHours + " hours " + coolDownMin + " minutes";
				}
			});

			addPlacehlder(new PlaceHolder<com.Ben12345rocks.MCPerks.UserAPI.User>("perkcooldown_" + perk) {

				@Override
				public String placeholderRequest(OfflinePlayer p, com.Ben12345rocks.MCPerks.UserAPI.User user,
						String identifier) {
					int CooldownMin = plugin.getPerkHandler().getPerk(perk).getCoolDown() / 60;
					int CooldownHour = CooldownMin / 60;
					CooldownMin = CooldownHour * 60 - CooldownMin;

					return CooldownHour + " Hours " + CooldownMin + " Minutes";
				}
			});

			addPlacehlder(new PlaceHolder<com.Ben12345rocks.MCPerks.UserAPI.User>("timeleft_" + perk) {

				@Override
				public String placeholderRequest(OfflinePlayer p, com.Ben12345rocks.MCPerks.UserAPI.User user,
						String identifier) {
					Perk pe = plugin.getPerkHandler().getPerk(perk);
					long left = pe.getExperation(user) - Calendar.getInstance().getTime().getTime();
					int min = (int) (left / (1000 * 64));
					long sec = left / 1000 - min * 1000 * 64;
					if (!pe.isLastForever()) {
						return "" + left / (1000 * 64) + " minutes " + sec + " seconds";
					} else {
						return "Forever";
					}
				}
			});
		}
	}

	public String placeHolder(OfflinePlayer p, String identifier) {
		identifier = StringUtils.getInstance().replaceJavascript(p, identifier);
		com.Ben12345rocks.MCPerks.UserAPI.User user = UserManager.getInstance().getMCPerksUser(p);

		for (PlaceHolder<com.Ben12345rocks.MCPerks.UserAPI.User> placeholder : placeholders) {
			if (placeholder.matches(identifier)) {
				return placeholder.placeholderRequest(p, user, identifier);
			}
		}

		return identifier;

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
