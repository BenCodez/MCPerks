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
    private com.bencodez.mcperks.data.OrderedStorage storage;
    public com.bencodez.mcperks.data.OrderedStorage getStorage() { return storage; }

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
        java.util.concurrent.CompletableFuture<Void> activations = java.util.concurrent.CompletableFuture.completedFuture(null);
        java.util.concurrent.CompletableFuture<Void> saved;
        try {
            featureLifecycle++;
            if (getPerkHandler() != null) activations = getPerkHandler().retireNativeActivations().toCompletableFuture();
            if (getMcperksServerData() != null) getMcperksServerData().retireActivationCallbacks();
            var legacy = new ArrayList<Perk>();
            if (getPerkHandler() != null) for (Perk perk : getPerkHandler().getActivePerks()) legacy.add(perk);
            if (getPerkHandler() != null) getPerkHandler().retireTimers();
            legacy.removeIf(perk -> !perk.detachForShutdown());
            if (legacy.isEmpty()) saved = java.util.concurrent.CompletableFuture.completedFuture(null);
            else {
                if (storage == null) throw new IllegalStateException("Shutdown persistence owner unavailable");
                saved = storage.runtimeStorage(() -> legacy.stream().map(Perk::persistForShutdown).toList())
                        .thenCompose(stages -> java.util.concurrent.CompletableFuture.allOf(
                                stages.toArray(java.util.concurrent.CompletableFuture[]::new)));
            }
        } catch (Throwable failure) { saved = java.util.concurrent.CompletableFuture.failedFuture(failure); }
        java.util.concurrent.CompletableFuture.allOf(activations, saved).whenComplete((ignored, failure) -> {
            if (failure == null) shutdownPreparation.complete(null);
            else shutdownPreparation.completeExceptionally(failure);
        });
    }

    @Override public java.util.concurrent.CompletionStage<Void> onBeforeStorageShutdown() {
        var preparation = shutdownPreparation;
        return preparation == null ? java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("Shutdown owner preparation was not admitted")) : preparation.minimalCompletionStage();
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
        var owner = storage;
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
        com.bencodez.mcperks.scheduling.OwnerTasks.shutdown(this);
        // Native user storage is retired here; all user work belongs to the earlier stage.
        if (getMcperksServerData() != null) getMcperksServerData().saveData();
		HandlerList.unregisterAll(plugin);
		plugin = null;
	}

	@Override
	public void onPostLoad() {
        com.bencodez.mcperks.scheduling.OwnerTasks.initialize(this);
        shutdownPreparation = null;
        shutdownFinalFiles = null;
        shutdownCompletionHookDispatched = false;
        int featureEpoch = ++featureLifecycle;
        storage = new com.bencodez.mcperks.data.OrderedStorage(getDataFolder().toPath().resolve("perk-features.dat"));
        var runtimeReady = mcperksServerData.initialize(storage);
        runtimeReady.exceptionally(error -> { getLogger().severe("ServerData startup reconciliation failed; activations remain unavailable"); return null; });
		loadListeners();
		perkHandler = new PerkHandler(this);
		effectHandler = new EffectHandler(this);

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

        runtimeReady.thenRun(() -> {
            if (featureEpoch != featureLifecycle || !isEnabled()) return;
            getBukkitScheduler().runTask(this, () -> {
                if (featureEpoch != featureLifecycle || !isEnabled()) return;
                perkHandler.loadActivePerksConfirmed(this::restoreOfflinePerks).exceptionally(error -> {
                    getLogger().severe("Legacy perk restoration failed; activations remain unavailable: " + error.getMessage());
                    return null;
                });
            });
        });

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
            @Override public boolean supportsAsyncRequest() { return true; }
            @Override public boolean requiresConfiguredDataForAsync() { return true; }
            @Override public java.util.concurrent.CompletionStage<Void> onReplayCheckpointPersisted(Reward reward, AdvancedCoreUser user,
                    String occurrenceId, String injectionKey, boolean selectionPresent) {
                if (!selectionPresent) return java.util.concurrent.CompletableFuture.completedFuture(null);
                return onReplayCheckpointPersisted(reward, user, occurrenceId, injectionKey);
            }
            @Override public boolean hasPendingReplayWork(HashMap<String, String> placeholders) {
                return Reward.hasReplaySelection(placeholders);
            }
            @Override public java.util.concurrent.CompletionStage<Object> onRewardRequestAsync(Reward reward, AdvancedCoreUser user,
                    org.bukkit.configuration.ConfigurationSection data, HashMap<String, String> placeholders) {
                if (data.isInt(getPath()) || (isAlwaysForce() && data.contains(getPath(), true)) || isAlwaysForceNoData())
                    return super.onRewardRequestAsync(reward, user, data, placeholders);
                if (!hasPendingReplayWork(placeholders)) return java.util.concurrent.CompletableFuture.completedFuture(null);
                return onRewardRequestAsync(reward, user, 0, placeholders).thenApply(result -> (Object) result);
            }

			@Override
			public java.util.concurrent.CompletionStage<String> onRewardRequestAsync(Reward reward, AdvancedCoreUser user, int value,
					HashMap<String, String> placeholders) {
				String identity = Reward.currentReplaySideEffectOccurrenceId();
				int selected = Integer.parseInt(Reward.replaySelection(placeholders, () -> Integer.toString(value)));
				var target = getMcperksUserManager().getMCPerksUser(user.getPlayerName());
				boolean shared = getUserManager().getDataManager().usesSharedSqlStorage(getStorageType());
				return Reward.persistReplayMetadataAsync(plugin, placeholders).thenCompose(ignored ->
						shared && identity != null ? target.addReplayActivationAsync(null, selected, "mcperks:" + identity)
								: target.addActivationAsync(selected)).thenApply(ignored -> null);
			}
			@Override
			public java.util.concurrent.CompletionStage<Void> onReplayCheckpointPersisted(Reward reward, AdvancedCoreUser user,
					String occurrenceId, String injectionKey) {
				if (!getUserManager().getDataManager().usesSharedSqlStorage(getStorageType()))
					return super.onReplayCheckpointPersisted(reward, user, occurrenceId, injectionKey);
				String identity = Reward.replaySideEffectOccurrenceId(occurrenceId, injectionKey);
				if (identity == null) return super.onReplayCheckpointPersisted(reward, user, occurrenceId, injectionKey);
				return getMcperksUserManager().getMCPerksUser(user.getPlayerName()).checkpointReplayActivationAsync("mcperks:" + identity);
			}

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
            @Override public boolean supportsAsyncRequest() { return true; }
            @Override public boolean requiresConfiguredDataForAsync() { return true; }
            @Override public java.util.concurrent.CompletionStage<Void> onReplayCheckpointPersisted(Reward reward, AdvancedCoreUser user,
                    String occurrenceId, String injectionKey, boolean selectionPresent) {
                if (!selectionPresent) return java.util.concurrent.CompletableFuture.completedFuture(null);
                return onReplayCheckpointPersisted(reward, user, occurrenceId, injectionKey);
            }
            @Override public boolean hasPendingReplayWork(HashMap<String, String> placeholders) {
                return Reward.hasReplaySelection(placeholders);
            }
            @Override public java.util.concurrent.CompletionStage<Object> onRewardRequestAsync(Reward reward, AdvancedCoreUser user,
                    org.bukkit.configuration.ConfigurationSection data, HashMap<String, String> placeholders) {
                if (data.isInt(getPath()) || (isAlwaysForce() && data.contains(getPath(), true)) || isAlwaysForceNoData())
                    return super.onRewardRequestAsync(reward, user, data, placeholders);
                if (!hasPendingReplayWork(placeholders)) return java.util.concurrent.CompletableFuture.completedFuture(null);
                return onRewardRequestAsync(reward, user, 0, placeholders).thenApply(result -> (Object) result);
            }

				@Override
				public java.util.concurrent.CompletionStage<String> onRewardRequestAsync(Reward reward, AdvancedCoreUser user, int value,
						HashMap<String, String> placeholders) {
					String identity = Reward.currentReplaySideEffectOccurrenceId();
					int selected = Integer.parseInt(Reward.replaySelection(placeholders, () -> Integer.toString(value)));
					var target = getMcperksUserManager().getMCPerksUser(user.getPlayerName());
					boolean shared = getUserManager().getDataManager().usesSharedSqlStorage(getStorageType());
					return Reward.persistReplayMetadataAsync(plugin, placeholders).thenCompose(ignored ->
							shared && identity != null ? target.addReplayActivationAsync(perk, selected, "mcperks:" + identity)
									: target.addActivationAsync(perk, selected)).thenApply(ignored -> null);
				}
				@Override
				public java.util.concurrent.CompletionStage<Void> onReplayCheckpointPersisted(Reward reward, AdvancedCoreUser user,
						String occurrenceId, String injectionKey) {
					if (!getUserManager().getDataManager().usesSharedSqlStorage(getStorageType()))
						return super.onReplayCheckpointPersisted(reward, user, occurrenceId, injectionKey);
					String identity = Reward.replaySideEffectOccurrenceId(occurrenceId, injectionKey);
					if (identity == null) return super.onReplayCheckpointPersisted(reward, user, occurrenceId, injectionKey);
					return getMcperksUserManager().getMCPerksUser(user.getPlayerName()).checkpointReplayActivationAsync("mcperks:" + identity);
				}

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
        if (storage == null && mcperksServerData != null && original != null)
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
    private volatile java.util.concurrent.CompletableFuture<Void> reloadObservation;
    @Override
    public void reload() {
        reloadAsync();
    }
    /** Starts (or joins) the asynchronous reload and completes after the new lifecycle is installed. */
    public synchronized java.util.concurrent.CompletableFuture<Void> reloadAsync() {
        java.util.concurrent.CompletableFuture<Void> existing = reloadOperation;
        if (existing != null && !existing.isDone()) return reloadObservation;
        if (!reloadPending.compareAndSet(false, true)) return reloadObservation;
        var operation = new java.util.concurrent.CompletableFuture<Void>();
        reloadOperation = operation;
        var observation = operation.copy();
        reloadObservation = observation;
        try {
            if (perkHandler != null) perkHandler.pauseNativeActivationsForReload();
        } catch (RuntimeException unavailable) {
            finishReload(operation, unavailable);
            return observation;
        }
        // ServerData snapshots accepted before reload must publish before AdvancedCore reads its file.
        var barrier = storage == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : storage.runtimeStorage(() -> null);
        var nativeBarrier = barrier.thenCompose(ignored -> mcperksServerData == null
                ? java.util.concurrent.CompletableFuture.completedFuture(null) : mcperksServerData.reloadDataAsync());
        var ownerStarted = new java.util.concurrent.atomic.AtomicBoolean();
        nativeBarrier.whenComplete((ignored, error) -> {
            if (error != null || !isEnabled()) {
                if (error != null) getLogger().warning("Reload postponed: pending ServerData publication could not drain");
                finishReload(operation, error == null ? new IllegalStateException("Plugin is disabled") : error);
                return;
            }
            try {
                var dispatch = getBukkitScheduler().getFoliaLib().getImpl().runNextTick(scheduledTask -> {
                    if (operation.isDone() || !ownerStarted.compareAndSet(false, true)) return;
                    if (!isEnabled()) { finishReload(operation, new IllegalStateException("Plugin is disabled")); return; }
                    try {
                        reloadAfterDrain();
                        reloadAdvancedCoreAsync(true).whenComplete((value, nativeFailure) -> {
                            if (nativeFailure != null) { finishReload(operation, nativeFailure); return; }
                            var files = mcperksServerData == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null)
                                    : mcperksServerData.reloadCompletion();
                            files.whenComplete((done, fileFailure) -> finishReload(operation, fileFailure));
                        });
                    }
                    catch (Throwable failure) { finishReload(operation, failure); }
                });
                dispatch.whenComplete((dispatchResult, dispatchFailure) -> {
                    if (dispatchFailure != null && ownerStarted.compareAndSet(false, true)) finishReload(operation, dispatchFailure);
                });
            } catch (Throwable failure) {
                finishReload(operation, failure);
            }
        });
        return observation;
    }
    private synchronized void finishReload(java.util.concurrent.CompletableFuture<Void> operation, Throwable failure) {
        if (operation.isDone()) return;
        if (perkHandler != null) perkHandler.resumeNativeActivationsAfterReload();
        reloadPending.set(false);
        if (failure == null) operation.complete(null); else {
            getLogger().log(java.util.logging.Level.WARNING, "Reload failed", failure);
            operation.completeExceptionally(failure);
        }
    }
    private void reloadAfterDrain() {
        // Retire direct activations admitted by the previous configuration generation.
        featureLifecycle++;
		configFile.reloadData();
		perkSystemType = configFile.getPerkSystemType();
		Lang.getInstance().reloadData();
		getPerkHandler().reload();
		commandLoader.loadTabComplete();
		updateHook();
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
