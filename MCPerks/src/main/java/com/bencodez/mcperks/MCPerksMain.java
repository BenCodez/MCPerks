package com.bencodez.mcperks;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.UUID;

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
import com.bencodez.advancedcore.api.messages.PlaceholderUtils;
import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.advancedcore.api.placeholder.PlaceHolder;
import com.bencodez.advancedcore.api.rewards.Reward;
import com.bencodez.advancedcore.api.rewards.RewardEditData;
import com.bencodez.advancedcore.api.rewards.injected.RewardInjectInt;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.mcperks.commands.CommandLoader;
import com.bencodez.mcperks.commands.executers.CommandMCPerks;
import com.bencodez.mcperks.commands.tabcompleter.MCPerksTabCompleter;
import com.bencodez.mcperks.configs.Config;
import com.bencodez.mcperks.configs.Lang;
import com.bencodez.mcperks.data.ServerData;
import com.bencodez.mcperks.listeners.EffectListeners;
import com.bencodez.mcperks.listeners.compatability.McMMOEvents;
import com.bencodez.mcperks.listeners.compatability.RoseStackerListener;
import com.bencodez.mcperks.perk.EffectHandler;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.mcperks.perk.PerkHandler;
import com.bencodez.mcperks.perk.PerkSystemType;
import com.bencodez.mcperks.placeholder.MCPerksExpansion;
import com.bencodez.mcperks.rewardedit.MCPerksRewardEditActivations;
import com.bencodez.mcperks.userapi.UserManager;
import com.bencodez.mcperks.version.Only121VersionHandle;
import com.bencodez.mcperks.version.Post123VersionHandle;
import com.bencodez.mcperks.version.Pre121VersionHandle;
import com.bencodez.mcperks.version.VersionHandle;
import com.bencodez.simpleapi.array.ArrayUtils;
import com.bencodez.simpleapi.metrics.BStatsMetrics;
import com.bencodez.simpleapi.nms.NMSManager;
import com.bencodez.simpleapi.updater.Updater;

import lombok.Getter;
import lombok.Setter;

// TODO: Auto-generated Javadoc
/**
 * The Class Main.
 */
public class MCPerksMain extends AdvancedCorePlugin {
    private volatile int featureLifecycle;
    public int getFeatureLifecycle() { return featureLifecycle; }
    private final com.bencodez.mcperks.data.LocalStatistics localStatistics = new com.bencodez.mcperks.data.LocalStatistics();
    public com.bencodez.mcperks.data.LocalStatistics getLocalStatistics() { return localStatistics; }
    private com.bencodez.mcperks.perk.BankedPerks bankedPerks;
    public com.bencodez.mcperks.perk.BankedPerks getBankedPerks() { return bankedPerks; }
    private com.bencodez.mcperks.data.FeatureState featureState;
    private com.bencodez.mcperks.perk.QueuePlanner queuePlanner;
    public com.bencodez.mcperks.data.FeatureState getFeatureState() { return featureState; }
    public com.bencodez.mcperks.perk.QueuePlanner getQueuePlanner() { return queuePlanner; }
    private com.bencodez.mcperks.userapi.UsageCharges usageCharges;
    private com.bencodez.mcperks.listeners.UsageEffectListener usageEffects;
    public com.bencodez.mcperks.userapi.UsageCharges getUsageCharges() { return usageCharges; }
    public com.bencodez.mcperks.listeners.UsageEffectListener getUsageEffects() { return usageEffects; }

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

	@Getter
	private CommandLoader commandLoader;

	@Getter
	private VersionHandle versionHandle;

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

		if (Bukkit.getPluginManager().getPlugin("RoseStacker") != null) {
			plugin.debug("Loading RoseStacker support");
			pm.registerEvents(new RoseStackerListener(this), this);
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.bukkit.plugin.java.JavaPlugin#onDisable()
	 */
    private volatile java.util.concurrent.CompletableFuture<Void> shutdownPreparation;
    private volatile java.util.concurrent.CompletionStage<Void> shutdownFinalFiles;
    private boolean shutdownCompletionHookDispatched;

    @Override
    public void onDisable() {
        prepareShutdown();
        super.onDisable();
    }

    /** Capture and remove native effects on the disable owner, before any asynchronous retirement. */
    private synchronized void prepareShutdown() {
        if (shutdownPreparation != null) return;
        shutdownPreparation = new java.util.concurrent.CompletableFuture<>();
        try {
            featureLifecycle++;
            var legacy = new ArrayList<Perk>();
            if (getPerkHandler() != null) for (Perk perk : getPerkHandler().getActivePerks()) {
                if (bankedPerks == null || !bankedPerks.attached(perk)) legacy.add(perk);
            }
            if (queuePlanner != null) queuePlanner.retire();
            var bankRetirement = bankedPerks == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null)
                    : bankedPerks.retireAsync();
            if (usageEffects != null) usageEffects.retireAndClose();
            else if (usageCharges != null) usageCharges.close();
            if (getPerkHandler() != null) getPerkHandler().retireTimers();
            legacy.removeIf(perk -> !perk.detachForShutdown());
            if (legacy.isEmpty()) {
                bankRetirement.whenComplete((ignored, failure) -> {
                    if (failure == null) shutdownPreparation.complete(null);
                    else shutdownPreparation.completeExceptionally(failure);
                });
                return;
            }
            if (featureState == null) throw new IllegalStateException("Shutdown persistence owner unavailable");
            featureState.runtimeStorage(() -> legacy.stream().map(Perk::persistForShutdown).toList())
                    .thenCompose(stages -> java.util.concurrent.CompletableFuture.allOf(
                            java.util.stream.Stream.concat(stages.stream(), java.util.stream.Stream.of(bankRetirement))
                                    .toArray(java.util.concurrent.CompletableFuture[]::new)))
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) shutdownPreparation.complete(null);
                        else shutdownPreparation.completeExceptionally(failure);
                    });
        } catch (Throwable failure) { shutdownPreparation.completeExceptionally(failure); }
    }

    @Override public java.util.concurrent.CompletionStage<Void> onBeforeStorageShutdown() {
        var preparation = shutdownPreparation;
        return preparation == null ? java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("Shutdown owner preparation was not admitted")) : preparation;
    }

    @Override public void onShutdownComplete(java.util.concurrent.CompletionStage<Void> finalFilesStage) {
        synchronized (this) {
            // A duplicate/reentrant hook cannot replace the admitted final-file fence.
            if (shutdownFinalFiles == null) shutdownFinalFiles = java.util.Objects.requireNonNull(finalFilesStage, "finalFilesStage");
            if (shutdownCompletionHookDispatched) return;
            shutdownCompletionHookDispatched = true;
        }
        onShutdownComplete(); // Preserve virtual dispatch to existing subclass hooks once.
    }

    @Override public void onShutdownComplete() {
        var owner = featureState;
        var preparation = shutdownPreparation;
        var files = shutdownFinalFiles;
        if (owner != null) {
            var prepared = preparation == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null)
                    : preparation;
            var finalFiles = files == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null)
                    : files.toCompletableFuture();
            // allOf waits for every settlement, including exceptional ones. Termination is
            // deliberately outside these stages: closing the worker must not wait on itself.
            java.util.concurrent.CompletableFuture.allOf(prepared, finalFiles)
                    .whenComplete((ignored, failure) -> owner.close());
        }
    }

	@Override
	public void onUnLoad() {
        // Native user storage is retired here; all user work belongs to the earlier stage.
        if (getMcperksServerData() != null) getMcperksServerData().saveData();
		HandlerList.unregisterAll(plugin);
		plugin = null;
	}

	@Override
	public void onPostLoad() {
        shutdownPreparation = null;
        shutdownFinalFiles = null;
        shutdownCompletionHookDispatched = false;
        int featureEpoch = ++featureLifecycle;
        localStatistics.enabled(getConfigFile().getData().getBoolean("LocalStatistics.Enabled", false));
        usageCharges = new com.bencodez.mcperks.userapi.UsageCharges(getDataFolder().toPath().resolve("usage-charges.dat"));
        usageCharges.ready().exceptionally(error -> { getLogger().severe("Usage charge store unavailable; charged effects fail closed: " + error.getMessage()); return null; });
		featureState = new com.bencodez.mcperks.data.FeatureState(getDataFolder().toPath().resolve("perk-features.dat"));
        featureState.ready().exceptionally(error -> { getLogger().severe("Queue/bank store unavailable: " + error.getMessage()); return null; });
        java.util.concurrent.CompletableFuture<Void> runtimeReady = mcperksServerData.initialize(featureState);
        runtimeReady.exceptionally(error -> { getLogger().severe("ServerData startup reconciliation failed; queued activations remain unavailable"); return null; });
        queuePlanner = new com.bencodez.mcperks.perk.QueuePlanner(this, featureState);
        loadListeners();
		perkHandler = new PerkHandler(this);
		effectHandler = new EffectHandler(this);
        usageEffects = new com.bencodez.mcperks.listeners.UsageEffectListener(this);
        getServer().getPluginManager().registerEvents(usageEffects, this);
        usageEffects.synchronizeOnlinePlayers();
        bankedPerks = new com.bencodez.mcperks.perk.BankedPerks(this, featureState);
        getServer().getPluginManager().registerEvents(bankedPerks, this);
        runtimeReady.thenRun(() -> {
            if (featureEpoch != featureLifecycle || !isEnabled()) return;
            getBukkitScheduler().runTask(this, () -> {
            if (featureEpoch != featureLifecycle || !isEnabled()) return;
            perkHandler.loadActivePerksConfirmed(this::restoreOfflinePerks).whenComplete((ignored, error) -> {
                if (error != null) { getLogger().severe("Legacy perk restoration failed; queued starts and bank attachments remain unavailable: " + error.getMessage()); return; }
                if (featureEpoch != featureLifecycle || !isEnabled()) return;
                getBukkitScheduler().runTask(this, () -> {
                    if (featureEpoch != featureLifecycle || !isEnabled()) return;
                    for (Player player : Bukkit.getOnlinePlayers()) getBukkitScheduler().runTask(this, () -> bankedPerks.start(player), player);
                    queuePlanner.refreshLegacyView(); queuePlanner.check(); queuePlanner.startChecks();
                });
            });
        }); });

		commandLoader = new CommandLoader(this);
		commandLoader.loadCommands();

		getCommand("mcperks").setExecutor(new CommandMCPerks());
		getCommand("mcperks").setTabCompleter(new MCPerksTabCompleter());

		loadPlaceholders();

		new BStatsMetrics(this, 40);

		loadInjectedRewards();
		if (NMSManager.getInstance().isVersion("1.13", "1.14", "1.15", "1.16", "1.17", "1.18", "1.19", "1.20")) {
			versionHandle = new Pre121VersionHandle();
		} else if (NMSManager.getInstance().isVersion("1.21.1")) {
			versionHandle = new Only121VersionHandle();
		} else {
			versionHandle = new Post123VersionHandle();
		}


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
				Updater updater = new Updater(plugin, 27898, false);
				if (updater.getResult().equals(Updater.UpdateResult.UPDATE_AVAILABLE)) {
					plugin.getLogger().info("Found an update available! Your version: "
							+ plugin.getDescription().getVersion() + " New Version: " + updater.getVersion());
				}
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

		plugin.getRewardHandler().addInjectedReward(new RewardInjectInt("Activations") {

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
			plugin.getRewardHandler().addInjectedReward(new RewardInjectInt("PerkActivations." + perk) {

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
		if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
			new MCPerksExpansion(this).register();
			getLogger().info("Loading PlaceholderAPI expansion");
		}
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
				return ArrayUtils.makeStringList(list);
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
		identifier = PlaceholderUtils.replaceJavascript(p, identifier);
		com.bencodez.mcperks.userapi.MCPerksUser user = getMcperksUserManager().getMCPerksUser(p);

		for (PlaceHolder<com.bencodez.mcperks.userapi.MCPerksUser> placeholder : placeholders) {
			if (placeholder.matches(identifier)) {
				return placeholder.placeholderRequest(user, identifier);
			}
		}

		return identifier;

	}

    private volatile com.bencodez.mcperks.data.OrderedServerDataFile orderedServerData;
    @Override public com.bencodez.advancedcore.data.ServerData getServerDataFile() {
        var original = super.getServerDataFile();
        if (featureState == null && mcperksServerData != null && original != null)
            mcperksServerData.observeNativePermissionSource(original.getData());
        if (mcperksServerData == null || original == null || original.getData() == null) return original;
        synchronized (mcperksServerData) {
            if (orderedServerData == null) orderedServerData = new com.bencodez.mcperks.data.OrderedServerDataFile(this, original, mcperksServerData);
            return orderedServerData;
        }
    }
    @Override public com.bencodez.advancedcore.api.permissions.PermissionHandler getPermissionHandler() {
        var handler = super.getPermissionHandler();
        if (mcperksServerData != null && handler != null) mcperksServerData.observeNativePermissionHandler(handler);
        return handler;
    }
    private void restoreOfflinePerks() {
	if (getMcperksServerData().getData().isConfigurationSection("OfflinePerk")) {
		for (String uuid : getMcperksServerData().getData().getConfigurationSection("OfflinePerk")
				.getKeys(false)) {
			if (getMcperksServerData().getData().isConfigurationSection("OfflinePerk." + uuid)) {
				for (String perk : getMcperksServerData().getData()
						.getConfigurationSection("OfflinePerk." + uuid).getKeys(false)) {

					long timeLast = getMcperksServerData().getData()
							.getLong("OfflinePerk." + uuid + "." + perk + ".Experiaton");
					long persistedExpiration = timeLast;
					debug(uuid + "/" + perk + "/" + timeLast);
					Timestamp timestamp = null;
					if (timeLast > 0) {
						timestamp = new Timestamp(timeLast);
						timeLast = 0;
					}
					boolean activate = timestamp == null || timestamp.after(new Timestamp(Calendar.getInstance().getTime().getTime()));
					var definition = getPerkHandler().getPerk(perk);
                    if (definition == null || !definition.isEnabled()) {
                        getLogger().warning("Skipping shutdown record for unavailable perk: " + perk);
                        continue;
                    }
					if (activate) {
						debug("Activating perk from shutdown " + perk + "/" + uuid + "/" + timeLast);
						getPerkHandler().restoreActivePerk(definition,
							getMcperksUserManager().getMCPerksUser(UUID.fromString(uuid)), timestamp);
					}
					getMcperksServerData().removeOfflinePerkIfMatches(uuid, perk, persistedExpiration);

				}
			}
		}
	}

	getMcperksServerData().saveData();
    }

    private final java.util.concurrent.atomic.AtomicBoolean reloadPending = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile java.util.concurrent.CompletableFuture<Void> reloadOperation;
    @Override
    public void reload() {
        reloadAsync();
    }
    /** Starts (or joins) the asynchronous reload and completes after the new lifecycle is installed. */
    public synchronized java.util.concurrent.CompletableFuture<Void> reloadAsync() {
        java.util.concurrent.CompletableFuture<Void> existing = reloadOperation;
        if (existing != null && !existing.isDone()) return existing;
        if (!reloadPending.compareAndSet(false, true)) return reloadOperation;
        var operation = new java.util.concurrent.CompletableFuture<Void>();
        reloadOperation = operation;
        if (usageEffects != null) usageEffects.invalidate();
        if (queuePlanner != null) queuePlanner.suspend();
        // ServerData snapshots accepted before reload must publish before AdvancedCore reads its file.
        var barrier = featureState == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : featureState.runtimeStorage(() -> null);
        barrier.whenComplete((ignored, error) -> {
            if (error != null || !isEnabled()) {
                if (error != null) getLogger().warning("Reload postponed: pending ServerData publication could not drain");
                finishReload(operation, error == null ? new IllegalStateException("Plugin is disabled") : error);
                return;
            }
            try {
                var dispatch = getBukkitScheduler().getFoliaLib().getImpl().runNextTick(scheduledTask -> {
                    if (operation.isDone()) return;
                    if (!isEnabled()) { finishReload(operation, new IllegalStateException("Plugin is disabled")); return; }
                    try { reloadAfterDrain(); finishReload(operation, null); }
                    catch (Throwable failure) { finishReload(operation, failure); }
                });
                dispatch.whenComplete((dispatchResult, dispatchFailure) -> {
                    if (dispatchFailure != null) finishReload(operation, dispatchFailure);
                });
            } catch (Throwable failure) {
                finishReload(operation, failure);
            }
        });
        return operation;
    }
    private synchronized void finishReload(java.util.concurrent.CompletableFuture<Void> operation, Throwable failure) {
        if (operation.isDone()) return;
        reloadPending.set(false);
        if (queuePlanner != null) queuePlanner.resume();
        if (failure == null) operation.complete(null); else {
            getLogger().log(java.util.logging.Level.WARNING, "Reload failed", failure);
            operation.completeExceptionally(failure);
        }
    }
    private void reloadAfterDrain() {
        // Retire direct activations admitted by the previous configuration generation.
        featureLifecycle++;
        if (usageEffects != null) usageEffects.invalidate();
        if (queuePlanner != null) queuePlanner.invalidate();
		configFile.reloadData();
        localStatistics.enabled(getConfigFile().getData().getBoolean("LocalStatistics.Enabled", false));
		perkSystemType = configFile.getPerkSystemType();
		Lang.getInstance().reloadData();
		mcperksServerData.reloadData();
		getPerkHandler().reload();
        if (usageEffects != null) usageEffects.synchronizeOnlinePlayers();
        if (bankedPerks != null) bankedPerks.reload();
		commandLoader.loadTabComplete();
		updateHook();
		reloadAdvancedCore(true);
	}

	/**
	 * Setup files.
	 */
	private void setupFiles() {
        orderedServerData = null;
		configFile = new Config(this);
		configFile.setup();
		Lang.getInstance().setup(this);
		perkSystemType = configFile.getPerkSystemType();
		mcperksServerData = new ServerData(this);
	}

	@SuppressWarnings("deprecation")
	public void updateHook() {
		setConfigData(configFile.getData());
		getOptions().setFormatNoPerms(Lang.getInstance().getNoPermission());
	}
}
