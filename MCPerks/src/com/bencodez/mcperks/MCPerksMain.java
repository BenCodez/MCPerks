package com.bencodez.mcperks;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.advancedcore.api.inventory.BInventory.ClickEvent;
import com.bencodez.advancedcore.api.inventory.editgui.EditGUIButton;
import com.bencodez.advancedcore.api.inventory.editgui.valuetypes.EditGUIValueInventory;
import com.bencodez.advancedcore.api.javascript.JavascriptPlaceholderRequest;
import com.bencodez.advancedcore.api.messages.StringParser;
import com.bencodez.advancedcore.api.metrics.BStatsMetrics;
import com.bencodez.advancedcore.api.misc.ArrayUtils;
import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.advancedcore.api.placeholder.PlaceHolder;
import com.bencodez.advancedcore.api.rewards.Reward;
import com.bencodez.advancedcore.api.rewards.RewardEditData;
import com.bencodez.advancedcore.api.rewards.RewardHandler;
import com.bencodez.advancedcore.api.rewards.injected.RewardInjectInt;
import com.bencodez.advancedcore.api.updater.Updater;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.advancedcore.thread.Thread;
import com.bencodez.mcperks.commands.CommandLoader;
import com.bencodez.mcperks.commands.executers.CommandMCPerks;
import com.bencodez.mcperks.commands.tabcompleter.MCPerksTabCompleter;
import com.bencodez.mcperks.configs.Config;
import com.bencodez.mcperks.configs.Lang;
import com.bencodez.mcperks.data.ServerData;
import com.bencodez.mcperks.listeners.EffectListeners;
import com.bencodez.mcperks.listeners.compatability.McMMOEvents;
import com.bencodez.mcperks.perk.EffectHandler;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.mcperks.perk.PerkHandler;
import com.bencodez.mcperks.perk.PerkSystemType;
import com.bencodez.mcperks.rewardedit.MCPerksRewardEditActivations;
import com.bencodez.mcperks.userapi.UserManager;

import lombok.Getter;
import lombok.Setter;

// TODO: Auto-generated Javadoc
/**
 * The Class Main.
 */
public class MCPerksMain extends AdvancedCorePlugin {

	/** The plugin. */
	public static MCPerksMain plugin;

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
	private ArrayList<PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser>> placeholders = new ArrayList<PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser>>();

	@Getter
	private ServerData mcperksServerData;

	@Getter
	private Config configFile;

	@Getter
	private UserManager mcperksUserManager;

	public void addPlacehlder(PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser> placeholder) {
		placeholders.add(placeholder);
	}

	@Getter
	private PerkSystemType perkSystemType;

	public void broadcast(String msg) {
		MiscUtils.getInstance().broadcast(msg);
	}

	/**
	 * Gets the main.
	 *
	 * @return the main
	 */
	public MCPerksMain getMain() {
		return this;
	}

	public void loadListeners() {
		PluginManager pm = getServer().getPluginManager();

		pm.registerEvents(new EffectListeners(this), this);
		if (Bukkit.getPluginManager().getPlugin("mcMMO") != null) {
			plugin.debug("Loading mcMMO support");
			pm.registerEvents(new McMMOEvents(this), this);
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.bukkit.plugin.java.JavaPlugin#onDisable()
	 */
	@Override
	public void onUnLoad() {
		HandlerList.unregisterAll(plugin);
		plugin = null;
	}

	@Override
	public void onPostLoad() {
		loadListeners();
		perkHandler = new PerkHandler();
		effectHandler = new EffectHandler();

		CommandLoader.getInstance().loadCommands();

		getCommand("mcperks").setExecutor(new CommandMCPerks());
		getCommand("mcperks").setTabCompleter(new MCPerksTabCompleter());

		loadPlaceholders();

		new BStatsMetrics(this, 40);

		loadInjectedRewards();

		getJavascriptEngineRequests().add(new JavascriptPlaceholderRequest("MCPerks") {

			@Override
			public Object getObject(OfflinePlayer player) {
				return this;
			}
		});

		getJavascriptEngineRequests().add(new JavascriptPlaceholderRequest("MCPerksUser") {

			@Override
			public Object getObject(OfflinePlayer player) {
				return getMcperksUserManager().getMCPerksUser(player);
			}
		});

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

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.bukkit.plugin.java.JavaPlugin#onEnable()
	 */
	@Override
	public void onPreLoad() {
		plugin = this;
		flyingUUIDs = new HashMap<String, Boolean>();
		setupFiles();

		mcperksUserManager = new UserManager(this);
		mcperksUserManager.addCachingKeys();
		updateHook();
	}

	public void loadInjectedRewards() {
		if (Bukkit.getPluginManager().getPlugin("VotingPlugin") != null) {
			// add failsafe
			try {
				Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {

					@Override
					public void run() {
						VotingPluginHook.getInstance().loadRewards();
						getLogger().info("VotingPlugin hook loaded");
					}
				});
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		RewardHandler.getInstance().addInjectedReward(new RewardInjectInt("Activations") {

			@Override
			public String onRewardRequest(Reward reward, AdvancedCoreUser user, int value,
					HashMap<String, String> placeholders) {
				getMcperksUserManager().getMCPerksUser(user.getPlayerName()).addActivation(value);
				return null;
			}
		}.addEditButton(new EditGUIButton(new EditGUIValueInventory("Activations") {

			@Override
			public void openInventory(ClickEvent event) {
				RewardEditData reward = (RewardEditData) getInv().getData("Reward");
				new MCPerksRewardEditActivations() {

					@Override
					public void setVal(String key, Object value) {
						RewardEditData reward = (RewardEditData) getInv().getData("Reward");
						reward.setValue(getKey(), value);
						plugin.reloadAdvancedCore(false);
					}
				}.open(event.getPlayer(), reward);
			}
		})));

		for (final String perk : MCPerksMain.plugin.getPerkHandler().getLoadedPerks().keySet()) {
			RewardHandler.getInstance().addInjectedReward(new RewardInjectInt("PerkActivations." + perk) {

				@Override
				public String onRewardRequest(Reward reward, AdvancedCoreUser user, int value,
						HashMap<String, String> placeholders) {
					getMcperksUserManager().getMCPerksUser(user.getPlayerName()).addActivation(perk, value);
					return null;
				}
			});
		}
	}

	public void loadPlaceholders() {

		addPlacehlder(new PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser>("ActivePerks") {

			@Override
			public String placeholderRequest(com.bencodez.mcperks.userapi.MCPerksUser user, String identifier) {
				ArrayList<String> list = new ArrayList<String>();
				for (Perk p : plugin.getPerkHandler().getActivePerks()) {
					if (p.getActivater().getUUID().equalsIgnoreCase(user.getUUID())) {
						list.add(p.getName());
					} else if (p.getEffectedPlayers().contains(user.getUUID())) {
						list.add(p.getName());
					}

				}
				return ArrayUtils.getInstance().makeStringList(list);
			}
		});

		addPlacehlder(new PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser>("CurrentActivePerks") {

			@Override
			public String placeholderRequest(com.bencodez.mcperks.userapi.MCPerksUser user, String identifier) {
				return "" + user.getActivePerks();
			}
		});

		addPlacehlder(new PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser>("activations") {

			@Override
			public String placeholderRequest(com.bencodez.mcperks.userapi.MCPerksUser user, String identifier) {
				return "" + user.getActivations();
			}
		});

		addPlacehlder(new PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser>("perklimit") {

			@Override
			public String placeholderRequest(com.bencodez.mcperks.userapi.MCPerksUser user, String identifier) {
				return "" + user.getPerkLimit();
			}
		});

		addPlacehlder(new PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser>("usebossbar") {

			@Override
			public String placeholderRequest(com.bencodez.mcperks.userapi.MCPerksUser user, String identifier) {
				return "" + user.isUseBossBar();
			}
		});

		for (String perk : MCPerksMain.plugin.getPerkHandler().getLoadedPerks().keySet()) {
			addPlacehlder(new PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser>("activations_" + perk) {

				@Override
				public String placeholderRequest(com.bencodez.mcperks.userapi.MCPerksUser user, String identifier) {
					return "" + user.getActivations(perk);
				}
			});
			addPlacehlder(new PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser>("cooldown_" + perk) {

				@Override
				public String placeholderRequest(com.bencodez.mcperks.userapi.MCPerksUser user, String identifier) {
					long coolDownTime = user.getPerkCoolDown(plugin.getPerkHandler().getPerk(perk));
					if (coolDownTime < mcperksServerData.getData().getLong(perk + ".CoolDown", 0)) {
						coolDownTime = mcperksServerData.getData().getLong(perk + ".CoolDown");
					}
					long cooldown = coolDownTime - Calendar.getInstance().getTime().getTime();
					Duration dur = Duration.of(cooldown, ChronoUnit.MILLIS);
					int coolDownHours = (int) dur.toHours();
					int coolDownMin = (int) dur.toMinutes() - coolDownHours * 60;
					if (cooldown < 0) {
						return "Cooldown ended";
					}
					return "" + coolDownHours + " hours " + coolDownMin + " minutes";
				}
			});

			addPlacehlder(new PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser>("perkcooldown_" + perk) {

				@Override
				public String placeholderRequest(com.bencodez.mcperks.userapi.MCPerksUser user, String identifier) {
					int CooldownMin = plugin.getPerkHandler().getPerk(perk).getCoolDown() / 60;
					int CooldownHour = CooldownMin / 60;
					CooldownMin = CooldownHour * 60 - CooldownMin;

					return CooldownHour + " Hours " + CooldownMin + " Minutes";
				}
			});

			addPlacehlder(new PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser>("timeleft_" + perk) {

				@Override
				public String placeholderRequest(com.bencodez.mcperks.userapi.MCPerksUser user, String identifier) {
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

			addPlacehlder(new PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser>("status_" + perk) {

				@Override
				public String placeholderRequest(com.bencodez.mcperks.userapi.MCPerksUser user, String identifier) {
					String perkName = perk;
					for (Perk perk : getPerkHandler().getActivePerks()) {
						if (perk.getPerk().equalsIgnoreCase(perkName)) {
							if (perk.getActivater().getPlayerName().equalsIgnoreCase(user.getPlayerName())
									|| perk.getEffectedPlayers().contains(user.getUUID())) {
								return Lang.getInstance().getPerkActivePlaceholder();
							}
						}
					}
					return Lang.getInstance().getPerkInactivePlaceholder();
				}
			});

			addPlacehlder(new PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser>("activator_" + perk) {

				@Override
				public String placeholderRequest(com.bencodez.mcperks.userapi.MCPerksUser user, String identifier) {
					String perkName = perk;
					for (Perk perk : getPerkHandler().getActivePerks()) {
						if (perk.getPerk().equalsIgnoreCase(perkName)) {
							return perk.getActivater().getPlayerName();
						}
					}
					return "";
				}
			});
		}
	}

	public String placeHolder(Player p, String identifier) {
		return placeHolder(p, identifier);

	}

	public String placeHolder(OfflinePlayer p, String identifier) {
		identifier = StringParser.getInstance().replaceJavascript(p, identifier);
		com.bencodez.mcperks.userapi.MCPerksUser user = getMcperksUserManager().getMCPerksUser(p);

		for (PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser> placeholder : placeholders) {
			if (placeholder.matches(identifier)) {
				return placeholder.placeholderRequest(user, identifier);
			}
		}

		return identifier;

	}

	@Override
	public void reload() {
		configFile.reloadData();
		perkSystemType = PerkSystemType.valueOf(configFile.getPerkSystemType());
		Lang.getInstance().reloadData();
		mcperksServerData.reloadData();
		getPerkHandler().reload();
		CommandLoader.getInstance().loadTabComplete();
		updateHook();
		reloadAdvancedCore(true);
	}

	/**
	 * Setup files.
	 */
	private void setupFiles() {
		configFile = new Config(this);
		configFile.setup();
		Lang.getInstance().setup(this);
		perkSystemType = PerkSystemType.valueOf(configFile.getPerkSystemType());
		mcperksServerData = new ServerData(this);
	}

	public void updateHook() {
		setConfigData(configFile.getData());
	}
}
