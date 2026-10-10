/*
 *
 */
package com.bencodez.mcperks.perk;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.sql.Timestamp;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.advancedcore.api.messages.PlaceholderUtils;
import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.advancedcore.api.misc.effects.BossBar;
import com.bencodez.advancedcore.api.rewards.RewardBuilder;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.configs.ConfigPerks;
import com.bencodez.mcperks.configs.Lang;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.bencodez.simpleapi.array.ArrayUtils;
import com.bencodez.simpleapi.messages.MessageAPI;

import lombok.Getter;

// TODO: Auto-generated Javadoc
/**
 * The Class Perk.
 */
public class Perk {
	/** Admission fence for direct starts; prevents duplicate delayed hooks. */
	private final Map<String, Long> activationAdmissions = new LinkedHashMap<>();
	private long activationSequence;

	/** The perk. */
	private String perk;

	/** The name. */
	private String name;

	/** The description. */
	private String description;

	/** The time. */
	private int time;

	/** The cool down. */
	private int coolDown;

	@Getter
	private int serverWideCoolDown;

	/** The increase percent. */
	private double increasePercent;

	/** The limit. */
	private int limit;

	/** The special items. */
	// private ArrayList<String> specialItems;

	/** The is enabled. */
	private boolean isEnabled;

	private ArrayList<Effect> effects = new ArrayList<Effect>();

	private volatile boolean active;
    private boolean shutdownPrepared;

	private volatile long experation;

	private ArrayList<ItemStack> specialDrops;

	@Getter
	private ArrayList<Material> whitelistedTools;

	@Getter
	private HashMap<String, Integer> requiredEnchants;

	@Getter
	private ArrayList<Material> whitelistedBlocks;
	@Getter
	private ArrayList<Material> blacklistedBlocks;

	@Getter
	private ArrayList<EntityType> blackedListedMobs;

	private ArrayList<Material> blocks;

	private ArrayList<String> aliases;

	private ConfigurationSection item;

	private Set<String> potions;

	private HashMap<String, Integer> potionDurations;

	private HashMap<String, Integer> potionAmplifiers;

	private int priority;

	private ArrayList<String> flyWorlds;

	@Getter
	private PerkSystemType perkType;

	private ArrayList<String> commands;

	@Getter
	private ArrayList<String> globalCommands;

	@Getter
	private ArrayList<String> disableCommands;

	private ArrayList<String> mcmmoSkills;

	private BossBar bossBar;

	private ArrayList<String> effectedPlayers = new ArrayList<String>();

	private MCPerksUser activater;

	private ArrayList<String> disabledWorlds;

	@Getter
	private int reActivateEffectsTimer;

	@Getter
	private MCPerksMain plugin = MCPerksMain.plugin;

	@Getter
	private boolean onlyGiveOnce;

	@Getter
	private boolean clearModifiers;

    @Getter private InteractionOptions interactionOptions;

	public Perk(Perk perk) {
		this.perk = perk.perk;
        interactionOptions = perk.interactionOptions;
		name = perk.name;
		description = perk.description;
		time = perk.time;
		coolDown = perk.coolDown;
		increasePercent = perk.increasePercent;
		limit = perk.limit;
		isEnabled = perk.isEnabled;
		effects = perk.effects;
		active = perk.active;
		experation = perk.experation;
		specialDrops = perk.specialDrops;
		blocks = perk.blocks;
		aliases = perk.aliases;
		item = perk.item;
		potions = perk.potions;
		potionDurations = perk.potionDurations;
		potionAmplifiers = perk.potionAmplifiers;
		priority = perk.priority;
		flyWorlds = perk.flyWorlds;
		effectedPlayers = new ArrayList<String>();
		activater = perk.activater;
		commands = perk.commands;
		mcmmoSkills = perk.mcmmoSkills;
		perkType = perk.perkType;
		serverWideCoolDown = perk.serverWideCoolDown;
		blackedListedMobs = perk.blackedListedMobs;
		whitelistedTools = perk.whitelistedTools;
		disabledWorlds = perk.disabledWorlds;
		blacklistedBlocks = perk.blacklistedBlocks;
		whitelistedBlocks = perk.whitelistedBlocks;
		onlyGiveOnce = perk.onlyGiveOnce;
		disableCommands = perk.disableCommands;
		reActivateEffectsTimer = perk.reActivateEffectsTimer;
		clearModifiers = perk.clearModifiers;
		requiredEnchants = perk.requiredEnchants;
		globalCommands = perk.globalCommands;
	}

	/**
	 * Instantiates a new perk.
	 *
	 * @param perk the perk
	 */
	public Perk(String perk) {
		this.perk = perk;
        interactionOptions = InteractionOptions.read(ConfigPerks.getInstance().getData(perk));
		setPriority(ConfigPerks.getInstance().getPerkPriority(perk));
		name = ConfigPerks.getInstance().getPerkName(perk);
		description = ConfigPerks.getInstance().getPerkDescription(perk).replace("%TimeLasts%", "" + getTime());
		isEnabled = ConfigPerks.getInstance().getPerkEnabled(perk);
		time = ConfigPerks.getInstance().getPerkTimeLasts(perk);
		coolDown = ConfigPerks.getInstance().getPerkCoolDown(perk);
		serverWideCoolDown = ConfigPerks.getInstance().getPerkServerWideCoolDown(perk);
		limit = ConfigPerks.getInstance().getPerkLimit(perk);
		specialDrops = ConfigPerks.getInstance().getPerkSpecialDrops(perk);
		whitelistedTools = ConfigPerks.getInstance().getPerkWhiteListedTools(perk);
		blocks = ConfigPerks.getInstance().getPerkSpecialBlocks(perk);
		aliases = ConfigPerks.getInstance().getPerkAliases(perk);
		item = ConfigPerks.getInstance().getPerkItem(perk);
		potions = ConfigPerks.getInstance().getPerkPotions(perk);
		if (potions == null) {
			potions = new HashSet<String>();
		}
		potionDurations = new HashMap<String, Integer>();
		potionAmplifiers = new HashMap<String, Integer>();
		for (String potion : potions) {
			potionDurations.put(potion, ConfigPerks.getInstance().getPerkPotionDuraton(perk, potion));
			potionAmplifiers.put(potion, ConfigPerks.getInstance().getPerkPotionAmplifier(perk, potion));
		}

		flyWorlds = ConfigPerks.getInstance().getFlyWorlds(perk);
		experation = 0;
		commands = ConfigPerks.getInstance().getCommands(perk);
		mcmmoSkills = ConfigPerks.getInstance().getPerkMCMMOSkills(perk);
		perkType = ConfigPerks.getInstance().getPerkType(perk);

		increasePercent = ConfigPerks.getInstance().getPerkPercentIncrease(perk);

		blackedListedMobs = ConfigPerks.getInstance().getPerkBlackListedMobs(perk);

		for (String effectLine : ConfigPerks.getInstance().getPerkEffects(perk)) {
			String[] data = effectLine.split(":");
			Effect effect = Effect.fromString(data[0]);
			if (data.length > 1) {
				if (MessageAPI.isDouble(data[1])) {
					effect.setModifier(Double.valueOf(data[1]));
					if (effect.usesModifier() && effect.usesIncreasePercentage()) {
						if (increasePercent == -1) {
							increasePercent = effect.getModifier();
						}
					}
				} else if (effect.equals(Effect.TempPermission)) {
					effect.setPermission(data[1]);
				}

			} else {
				if (effect.usesModifier() && effect.usesIncreasePercentage()) {
					effect.setModifier(increasePercent);
				}
			}
			effects.add(effect);
		}

		disabledWorlds = ConfigPerks.getInstance().getPerkDisabledWorlds(perk);
		whitelistedBlocks = ConfigPerks.getInstance().getPerkWhiteListedBlocks(perk);
		blacklistedBlocks = ConfigPerks.getInstance().getPerkBlackListedBlocks(perk);
		onlyGiveOnce = ConfigPerks.getInstance().getOnlyGiveOnce(perk);
		disableCommands = ConfigPerks.getInstance().getDisableCommands(perk);

		reActivateEffectsTimer = ConfigPerks.getInstance().getPerkReActivateEffectsTimer(perk);
		clearModifiers = ConfigPerks.getInstance().getPerkClearModifiers(perk);
		requiredEnchants = new HashMap<String, Integer>();
		for (String enchant : ConfigPerks.getInstance().getPerkRequiredEnchants(perk)) {
			requiredEnchants.put(enchant, ConfigPerks.getInstance().getPerkRequiredEnchantsLevel(perk, enchant));
		}
		globalCommands = ConfigPerks.getInstance().getGlobalCommands(perk);
	}

	/**
	 * Activate perk.
	 * 
	 * @param user   user
	 * @param length length
	 */
	public void activatePerk(MCPerksUser user, int length) {
		activatePerk(user, length, null);
	}

	void activatePerk(MCPerksUser user, int length, Timestamp timestamp) {
		activater = user;
		plugin.getPerkHandler().activePerk(this, user, length, timestamp);
	}

	public void addEffectedPlayer(String uuid) {
		if (!this.effectedPlayers.contains(uuid)) {
			MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(UUID.fromString(uuid));
			if (!user.isIgnorePerkEffects()) {
				this.effectedPlayers.add(uuid);
			} else {
				user.sendMessage(Lang.getInstance().getIgnoreEffect());
			}
		}
	}

	/**
	 * Announce perk.
	 * 
	 * @param playerName playername
	 * @param length     length
	 */
	public void announcePerk(String playerName, int length) {
		if (playerName != null) {
			String msg = ConfigPerks.getInstance().getPerkActivated(perk).replace("%Player%", playerName)
					.replace("%Perk%", getName());
			ArrayList<Player> players = new ArrayList<Player>();
			for (String uuid : getEffectedPlayers()) {
				Player p = Bukkit.getPlayer(UUID.fromString(uuid));
				// Main.plugin.debug(uuid);
				if (p != null) {
					players.add(p);
				}
			}

			if (!msg.isEmpty()) {
				for (Player p : players) {
					p.sendMessage(MessageAPI.colorize(msg));
				}
			}

			if (isTimed() && !isLastForever()) {
				HashMap<String, String> placeholders = new HashMap<String, String>();
				placeholders.put("TimeLasts", "" + length);
				placeholders.put("TimeLastsMin", "" + length / 60);
				placeholders.put("TimeLastsHour", "" + length / 60 / 60);
				msg = PlaceholderUtils.replacePlaceHolder(ConfigPerks.getInstance().getPerkActivatedTimed(getPerk()),
						placeholders);
				if (!msg.isEmpty()) {
					for (Player p : players) {
						p.sendMessage(MessageAPI.colorize(msg));
					}
				}
			}
		}

	}

	public boolean checkServerWideCoolDown(MCPerksUser user) {
		Player player = user.getPlayer();
		if (player != null) {
			if (player.hasPermission("MCPerks.Perks.BypassCoolDown")) {
				return true;
			}
		}
		long time = MCPerksMain.plugin.getMcperksServerData().getData().getLong(getPerk() + ".CoolDown", 0);
		if (time > 0) {
			Date date = new Date(time);
			Date now = new Date();
			if (now.before(date)) {
				// in cooldown
				return false;
			}
		}
		return true;
	}

	public boolean checkCoolDown(MCPerksUser user) {
		Player player = user.getPlayer();
		if (player != null) {
			if (player.hasPermission("MCPerks.Perks.BypassCoolDown")) {
				return true;
			}
		}
		long time = user.getPerkCoolDown(this);
		Date date = new Date(time);
		Date now = new Date();
		if (now.before(date)) {
			// in cooldown
			return false;
		}
		return true;
	}

	public boolean checkTimesUsed(MCPerksUser user) {
		if (getLimit() == 0) {
			return true;
		}
		int used = user.getPerkTimesUsed(this);
		if (used >= getLimit()) {

			return false;
		}
		return true;
	}

	@Override
	public Perk clone() {
		return new Perk(this);
	}

    /** Disable-time game cleanup is synchronous on Bukkit's owner and performs no user-data lookup. */
    public synchronized boolean detachForShutdown() {
        if (shutdownPrepared || !active) return false;
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Perk shutdown requires the Bukkit owner");
        shutdownPrepared = true;
        active = false;
        for (Effect effect : getEffects()) {
            try {
                effect.removeEffectForShutdown(plugin, this, getEffectedPlayers());
            } catch (Throwable failure) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Failed to remove shutdown effect " + effect + " for perk " + getPerk(), failure);
            }
        }
        if (getBossBar() != null) {
            try { getBossBar().hide(); }
            catch (Throwable failure) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Failed to hide shutdown boss bar for perk " + getPerk(), failure);
            } finally { setBossBar(null); }
        }
        plugin.getPerkHandler().getActivePerks().remove(this);
        return true;
    }

    /** First acknowledge the legacy restart record; only then clear the native live expiration. */
    public java.util.concurrent.CompletableFuture<Void> persistForShutdown() {
        var state = plugin.getFeatureState();
        var data = plugin.getMcperksServerData();
        return data.confirmedActivation(() -> data.saveOfflinePerk(this))
                .thenCompose(ignored -> state.runtimeStorage(() -> data.confirmedActivation(
                        () -> setExperation(getActivater(), 0))).thenCompose(stage -> stage))
                .thenCompose(ignored -> state.runtimeStorage(() -> {
                    if (getPerkType() == PerkSystemType.ALL) return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
                    var flushed = new java.util.concurrent.CompletableFuture<Void>();
                    plugin.getUserManager().getDataManager().getCache(UUID.fromString(getActivater().getUUID()))
                            .flushChangesAndRun(() -> flushed.complete(null));
                    return flushed;
                }).thenCompose(stage -> stage));
    }

	public synchronized void deactivatePerk(MCPerksUser user) {
        if (shutdownPrepared) return;
        if (plugin.getBankedPerks() != null && plugin.getBankedPerks().deactivate(this)) return;
		setExperation(user, 0);
		setActive(false);
		// Main.plugin.getEffectHandler().deactivate(this);
		MCPerksMain.plugin
				.debug("Perk '" + getPerk() + "' deactivated for " + ArrayUtils.makeStringList(getEffectedPlayers()));
		for (Effect effect : getEffects()) {
			effect.removeEffect(plugin, this, getEffectedPlayers());
		}

		String msg = ConfigPerks.getInstance().getPerkDeactivated(perk).replace("%Perk%", getName());
		// ArrayList<User> users = new ArrayList<User>();
		boolean perkRewards = plugin.getRewardHandler().hasRewards(ConfigPerks.getInstance().getData(perk),
				ConfigPerks.getInstance().getDeactivationEffect());
		for (String uuid : getEffectedPlayers()) {
			MCPerksUser u = plugin.getMcperksUserManager().getMCPerksUser(UUID.fromString(uuid));
			if (!msg.isEmpty()) {
				u.sendMessage(msg);
			}
			// users.add(u);
			if (perkRewards) {
				new RewardBuilder(ConfigPerks.getInstance().getData(perk),
						ConfigPerks.getInstance().getDeactivationEffect()).setGiveOffline(false).send(user);
			} else {
				new RewardBuilder(plugin.getConfigFile().getData(), plugin.getConfigFile().getDeactivationEffect())
						.setGiveOffline(false).send(user);
			}
		}

		if (getBossBar() != null) {
			Bukkit.getScheduler().runTask(MCPerksMain.plugin, new Runnable() {

				@Override
				public void run() {
					try {
						getBossBar().hide();
						setBossBar(null);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});

		}
		Perk p = this;

		MCPerksMain.plugin.getPerkHandler().remove(p, user);

	}

    public void execute(MCPerksUser user, int length) {
        executeConfirmed(user, length).exceptionally(error -> { plugin.getLogger().warning("Perk execution could not be confirmed: " + getPerk()); return null; });
    }
    public java.util.concurrent.CompletableFuture<Void> executeConfirmed(MCPerksUser user, int length) {
        long admission = acquireActivationAdmission(user);
        if (admission == 0) return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("Perk activation already pending or admissions busy"));
        return executeAdmitted(user, length, admission);
    }
    private java.util.concurrent.CompletableFuture<Void> executeAdmitted(MCPerksUser user, int length, long admission) {
        final String admissionKey = activationKey(user);
        try {
            return executeAdmittedWork(user, length, admissionKey, admission).whenComplete((ignored, error) ->
                    releaseActivationAdmission(admissionKey, admission));
        } catch (RuntimeException failure) {
            releaseActivationAdmission(admissionKey, admission);
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }
    private java.util.concurrent.CompletableFuture<Void> executeAdmittedWork(MCPerksUser user, int length, String admissionKey, long admission) {
        return executeAdmittedWork(user, length, admissionKey, admission, null);
    }
    private Runnable activationVerifier(String admissionKey, long admission) {
        final PerkHandler ownerHandler = plugin.getPerkHandler();
        final int lifecycle = plugin.getFeatureLifecycle();
        return () -> {
            synchronized (this) {
                if (!Objects.equals(activationAdmissions.get(admissionKey), admission) || !plugin.isEnabled()
                        || plugin.getFeatureLifecycle() != lifecycle || plugin.getPerkHandler() != ownerHandler)
                    throw new IllegalStateException("Perk activation lifecycle retired");
            }
        };
    }
    private java.util.concurrent.CompletableFuture<Void> executeAdmittedWork(MCPerksUser user, int length, String admissionKey, long admission,
            java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> consume) {
        if (!plugin.getMcperksServerData().isReady()) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("ServerData initialization unavailable"));
        org.bukkit.entity.Player owner = user == null ? null : user.getPlayer();
        if (consume != null) {
            Runnable verifyAdmission = activationVerifier(admissionKey, admission);
            // Before credit commits, confirm only existing state. Publishing a new expiration
            // here could restore a rejected permanent purchase as a free perk after restart.
            return plugin.getMcperksServerData().confirmedActivationBeforeWork(Map.of(), () -> {
                verifyAdmission.run();
                return com.bencodez.mcperks.userapi.UserStorageConfirmation.confirm(plugin, user)
                        .thenCompose(ignored -> consume.get());
            }, () -> {
                verifyAdmission.run();
                return executeMetadataAndGameplay(user, length, admissionKey, admission, true, owner);
            }, owner);
        }
        return executeMetadataAndGameplay(user, length, admissionKey, admission, false, owner);
    }
    private java.util.concurrent.CompletableFuture<Void> executeMetadataAndGameplay(MCPerksUser user, int length,
            String admissionKey, long admission, boolean alreadyConsumed, org.bukkit.entity.Player owner) {
        final boolean banked = getInteractionOptions().banked();
        long admissionExpiration = activationExpiration(length);
        final long personalCooldown = MiscUtils.getInstance().addSeconds(new Date(), getCoolDown()).getTime();
        Map<String, Long> sharedFields = new LinkedHashMap<>();
        if (getServerWideCoolDown() > 0)
            sharedFields.put(getPerk() + ".CoolDown", MiscUtils.getInstance().addSeconds(new Date(), getServerWideCoolDown()).getTime());
        if (isTimed() && getPerkType() == PerkSystemType.ALL)
            sharedFields.put("Experation." + getPerk(), admissionExpiration);
        Runnable verifyAdmission = activationVerifier(admissionKey, admission);
        java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> callerStorage = () -> {
            verifyAdmission.run();
            user.setPerkCoolDown(this, personalCooldown);
            if (isTimed() && !banked) {
                if (getPerkType() != PerkSystemType.ALL) user.setPerkExperation(this, admissionExpiration);
                this.experation = admissionExpiration;
            }
            return com.bencodez.mcperks.userapi.UserStorageConfirmation.confirm(plugin, user);
        };
        // Publish the cooldown/admission boundary before adding bank time. The
        // bank remains durable if the later attachment fails, so a retry must
        // observe the cooldown and duplicate-entitlement policy.
        if (banked && !alreadyConsumed)
            return plugin.getMcperksServerData().confirmedActivationBeforeWork(sharedFields, callerStorage, () -> {
                verifyAdmission.run();
                return plugin.getBankedPerks().grantConfirmed(user, this, length);
            }, owner);
        if (banked) return plugin.getMcperksServerData().confirmedActivationBeforeWork(sharedFields, callerStorage, () -> {
            verifyAdmission.run();
            return attachPublishedBank(user, verifyAdmission);
        }, owner);
        var confirmed = plugin.getMcperksServerData().confirmedActivationBeforeGameplay(sharedFields, callerStorage, () -> {
            verifyAdmission.run();
            activatePerk(user, length, isTimed() && !isLastForever() ? new Timestamp(admissionExpiration) : null);
        }, owner);
        return confirmed;
    }
    private String activationKey(MCPerksUser user) {
        return getPerkType() == PerkSystemType.PLAYER && getServerWideCoolDown() <= 0
                ? "PLAYER:" + user.getUUID() : getPerkType().name() + ":" + getPerk();
    }
    private synchronized long acquireActivationAdmission(MCPerksUser user) {
        String key = activationKey(user);
        if (activationAdmissions.size() >= 256 || activationAdmissions.containsKey(key)) return 0;
        long admission = ++activationSequence;
        activationAdmissions.put(key, admission);
        return admission;
    }
    private synchronized void releaseActivationAdmission(String key, long admission) {
        if (Objects.equals(activationAdmissions.get(key), admission)) activationAdmissions.remove(key);
    }
    private long activationExpiration(int length) {
        if (!isTimed()) return 0;
        if (isLastForever()) return -1;
        return Math.addExact(System.currentTimeMillis(), Math.multiplyExact(1000L, length));
    }
    public java.util.concurrent.CompletableFuture<Void> executePublishedBank(MCPerksUser user) {
        return plugin.getMcperksServerData().confirmedActivation(() -> recordCooldown(user))
                .thenCompose(ignored -> attachPublishedBank(user, () -> {}));
    }
    private java.util.concurrent.CompletableFuture<Void> attachPublishedBank(MCPerksUser user, Runnable verifyAdmission) {
        verifyAdmission.run();
        return plugin.getBankedPerks().grantedConfirmed(user, this)
                .thenCompose(ignored -> {
                    verifyAdmission.run();
                    return com.bencodez.mcperks.userapi.UserStorageConfirmation.confirm(plugin, user);
                });
    }
    private java.util.concurrent.CompletableFuture<Void> confirmCooldown(MCPerksUser user) {
        return plugin.getMcperksServerData().confirmedActivation(() -> recordCooldown(user))
                .thenCompose(ignored -> com.bencodez.mcperks.userapi.UserStorageConfirmation.confirm(plugin, user));
    }
    private void recordCooldown(MCPerksUser user) {
        user.setPerkCoolDown(this, MiscUtils.getInstance().addSeconds(new Date(), getCoolDown()).getTime());
        if (getServerWideCoolDown() > 0) setServerCoolDownTime(MiscUtils.getInstance().addSeconds(new Date(), getServerWideCoolDown()).getTime());
    }

	public void setServerCoolDownTime(long time) {
		MCPerksMain.plugin.getMcperksServerData().setServerCooldown(getPerk(), time);
	}

	public void forcePerk(String name) {
		forcePerk(name, getTime());
	}

	public void forcePerk(String name, int length) {
		MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(name);
		if (isActive()) {
			user.sendMessage(ConfigPerks.getInstance().getPerkAlreayActive(perk));
			return;
		}

		execute(plugin.getMcperksUserManager().getMCPerksUser(name), length);
	}

	/**
	 * @return the activater
	 */
	public MCPerksUser getActivater() {
		return activater;
	}

	public ArrayList<String> getAliases() {
		return aliases;
	}

	public ArrayList<Material> getBlocks() {
		return blocks;
	}

	public BossBar getBossBar() {
		return bossBar;
	}

	public ArrayList<String> getCommands() {
		return commands;
	}

	/**
	 * Gets the cool down.
	 *
	 * @return the cool down
	 */
	public int getCoolDown() {
		return coolDown;
	}

	/**
	 * Gets the description.
	 *
	 * @return the description
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * @return the effectedPlayers
	 */
	public ArrayList<String> getEffectedPlayers() {
		return effectedPlayers;
	}

	public ArrayList<Effect> getEffects() {
		return effects;
	}

    /** Last expiration published with the active grant; reading does not touch user storage. */
    public long getKnownExpiration() { return experation; }

	public long getExperation(MCPerksUser user) {
		if (getPerkType().equals(PerkSystemType.ALL)) {
			experation = plugin.getMcperksServerData().getPerkExperation(this);
		} else {
			experation = user.getPerkExperation(this);
		}
		return experation;
	}

	/**
	 * @return the flyWorlds
	 */
	public ArrayList<String> getFlyWorlds() {
		return flyWorlds;
	}

	/**
	 * Gets the increase percent.
	 * 
	 * @param effect effect
	 *
	 * @return the increase percent
	 */
	public double getIncreasePercent(Effect effect) {
		for (Effect eff : effects) {
			if (eff.isEffect(effect)) {
				return eff.getModifier();
			}
		}
		return increasePercent;
	}

	public ItemBuilder getItem(MCPerksUser user) {
		return new ItemBuilder(item).setName(getItemName()).setLore(getItemLore(user));
	}

	public String[] getItemLore(MCPerksUser user) {
		ArrayList<String> lore = new ArrayList<String>();
		if (isPerkActive(user)) {
			lore.addAll(ConfigPerks.getInstance().getLoreActive(getPerk()));
		} else if (!checkTimesUsed(user)) {
			lore.addAll(ConfigPerks.getInstance().getLoreLimitReached(getPerk()));
		} else if (!checkCoolDown(user) || !checkServerWideCoolDown(user)) {
			lore.addAll(ConfigPerks.getInstance().getLoreInCoolDown(getPerk()));
		} else {
			lore.addAll(ConfigPerks.getInstance().getLoreAvailable(getPerk()));
		}

		boolean hasPerm = false;
		if (user != null) {
			Player player = user.getPlayer();
			if (player != null) {
				hasPerm = player.hasPermission("mcperks." + getPerk());
			}
		} else {
			return new String[0];
		}

		long left = getExperation(user) - Calendar.getInstance().getTime().getTime();
		int seconds = (int) (left / 1000);
		int min = seconds / 60;
		int sec = seconds - min * 60;

		long coolDownTime = user.getPerkCoolDown(this);
		if (coolDownTime < MCPerksMain.plugin.getMcperksServerData().getData().getLong(getPerk() + ".CoolDown", 0)) {
			coolDownTime = MCPerksMain.plugin.getMcperksServerData().getData().getLong(getPerk() + ".CoolDown");
		}
		long cooldown = coolDownTime - Calendar.getInstance().getTime().getTime();

		Duration dur = Duration.of(cooldown, ChronoUnit.MILLIS);
		int coolDownHours = (int) dur.toHours();
		int coolDownMin = (int) dur.toMinutes() - coolDownHours * 60;
		if (coolDownMin < 0) {
			coolDownMin = coolDownMin * -1;
		}

		HashMap<String, String> placeholders = new HashMap<String, String>();
		int timeLasts = getTime();
		placeholders.put("TimeLasts", "" + timeLasts);
		placeholders.put("Activations", "" + user.getActivations());
		placeholders.put("PerkActivations", "" + user.getActivations(perk));
		placeholders.put("TimeLastsMin", "" + (timeLasts / 60));
		placeholders.put("TimeLastsHour", "" + (timeLasts / 60 / 60));
		placeholders.put("Description", getDescription());
		placeholders.put("HasPerm", Boolean.toString(hasPerm));
		placeholders.put("CoolDownLeft", "" + coolDownHours + " hours " + coolDownMin + " minutes");
		if (!isLastForever()) {
			placeholders.put("TimeLeft", "" + min + " minutes " + sec + " seconds");
		} else {
			placeholders.put("TimeLeft", "Forever");
		}
		int cooldownMin1 = getCoolDown() / 60;
		int cooldownHour = cooldownMin1 / 60;
		int cooldownMin = cooldownHour * 60 - cooldownMin1;
		if (cooldownMin < 0) {
			cooldownMin = cooldownMin * -1;
		}
		placeholders.put("CoolDown", "" + cooldownHour + " Hours " + cooldownMin + " Minutes");
		// lore = Utils.getInstance().replacePlaceStringList(lore, "", "");

		lore = PlaceholderUtils.replacePlaceHolder(lore, placeholders);
		return ArrayUtils.convert(lore);
	}

	public String getItemName() {
		return "&c" + getName();
	}

	/**
	 * Gets the limit.
	 *
	 * @return the limit
	 */
	public int getLimit() {
		return limit;
	}

	public ArrayList<String> getMcmmoSkills() {
		return mcmmoSkills;
	}

	/**
	 * Gets the name.
	 *
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * Gets the perk.
	 *
	 * @return the perk
	 */
	public String getPerk() {
		return perk;
	}

	public String getPermission() {
		return "MCPerks." + getPerk();
	}

	public HashMap<String, Integer> getPotionAmplifiers() {
		return potionAmplifiers;
	}

	public HashMap<String, Integer> getPotionDurations() {
		return potionDurations;
	}

	public Set<String> getPotions() {
		return potions;
	}

	public int getPriority() {
		return priority;
	}

	public ArrayList<ItemStack> getSpecialDrops() {
		return specialDrops;
	}

	/**
	 * Gets the time.
	 *
	 * @return the time
	 */
	public int getTime() {
		if (time == 0) {
			time = ConfigPerks.getInstance().getPerkTimeLasts(getPerk());
		}
		return time;
	}

	public void giveEffect(Player player) {
		for (Effect effect : getEffects()) {
			ArrayList<String> uuids = new ArrayList<String>();
			uuids.add(player.getUniqueId().toString());
			effect.runEffect(plugin, this, null, uuids);
		}
	}

	public boolean hasEffect(Effect effect) {
		return getEffects().contains(effect);
	}

	public boolean isActive() {
		return active;
	}

	/**
	 * Checks if is enabled.
	 *
	 * @return true, if is enabled
	 */
	public boolean isEnabled() {
		return isEnabled;
	}

	public boolean isLastForever() {
		if (getTime() < 0) {
			return true;
		}
		return false;
	}

	public boolean isPerkActive(MCPerksUser user) {
		for (Perk perk : MCPerksMain.plugin.getPerkHandler().getActivePerks()) {
			if (perk.getPerk().equals(getPerk())) {
				if (perk.getActivater().getPlayerName().equals(user.getPlayerName())) {
					return true;
				}
				if (perk.getPerkType().equals(PerkSystemType.ALL)) {
					return true;
				}
			}

		}
		return false;
	}

	public boolean isTimed() {
		if (getTime() == 0) {
			return false;
		}
		return true;
	}

	public boolean isNotInDisabledWorld(String world) {
		if (world != null && !world.isEmpty()) {
			for (String str : disabledWorlds) {
				if (str.equalsIgnoreCase(world)) {
					return false;
				}
			}
		}
		return true;
	}

	public void runPerk(MCPerksUser user) {
        boolean banked = getInteractionOptions().banked();
        var controller = banked ? plugin.getBankedPerks() : null;
        if (banked && !controller.beginDirect(user, this)) {
            user.sendMessage("&cA bank grant for this perk is still being confirmed; no activation consumed."); return;
        }
        boolean handedOff = false;
        try { handedOff = runPerkAdmitted(user, controller); }
        finally { if (banked && !handedOff) controller.finishDirect(user, this); }
    }

    private boolean runPerkAdmitted(MCPerksUser user, BankedPerks controller) {
        if (!plugin.getMcperksServerData().isReady() || !plugin.getPerkHandler().isRestorationReady()) {
            user.sendMessage("&cPerk runtime state is unavailable; no activation consumed."); return false;
        }
        if ((plugin.getConfigFile().getPerkQue() || plugin.getConfigFile().getLimitActivations() || getInteractionOptions().banked()) && !plugin.getFeatureState().available()) {
            user.sendMessage("&cPerk entitlement state is unavailable; no activation consumed."); return false;
        }

		if (getInteractionOptions().banked() && (!BankedPerks.supported(this) || getTime() <= 0)) {
            user.sendMessage("&cThis perk is not supported in banked mode; no activation consumed."); return false;
        }
        if (user.isInWorld(disabledWorlds)) {
			user.sendMessage(Lang.getInstance().getDisabledWorlds());
			return false;
		}

		if ((isPerkActive(user) || getInteractionOptions().banked() && plugin.getBankedPerks().hasEntitlement(UUID.fromString(user.getUUID()), this)) && !plugin.getConfigFile().getAllowDuplicatePerksActive()) {
			user.sendMessage(ConfigPerks.getInstance().getPerkAlreayActive(perk));
			return false;
		}

		if (!checkTimesUsed(user)) {
			user.sendMessage(ConfigPerks.getInstance().getPerkLimitReached(perk));
			return false;
		}

		long coolDownTime = user.getPerkCoolDown(plugin.getPerkHandler().getPerk(perk));
		if (coolDownTime < plugin.getMcperksServerData().getData().getLong(perk + ".CoolDown", 0)) {
			coolDownTime = plugin.getMcperksServerData().getData().getLong(perk + ".CoolDown");
		}
		long cooldown = coolDownTime - Calendar.getInstance().getTime().getTime();
		Duration dur = Duration.of(cooldown, ChronoUnit.MILLIS);
		int coolDownHours = (int) dur.toHours();
		int coolDownMin = (int) dur.toMinutes() - coolDownHours * 60;

		String coolDown = coolDownHours + " hours " + coolDownMin + " minutes";

		if (!checkCoolDown(user)) {
			user.sendMessage(ConfigPerks.getInstance().getPerkInCoolDown(perk), "cooldown", coolDown);
			return false;
		}

		if (!checkServerWideCoolDown(user)) {
			user.sendMessage(ConfigPerks.getInstance().getPerkInCoolDown(perk), "cooldown", coolDown);
			return false;
		}

		if (plugin.getConfigFile().getLimitPermission()) {
			if (user.getPerkLimit() != -1) {
				if (user.getActivePerks() + 1 > user.getPerkLimit()) {
					user.sendMessage(ConfigPerks.getInstance().getPerkLimitReached(perk));
					return false;
				}
			}
		}

        if (plugin.getConfigFile().getPerkQue() && (!plugin.getPerkHandler().getActivePerks().isEmpty() || plugin.getFeatureState().paused()
                || !plugin.getFeatureState().queue().isEmpty() || plugin.getQueuePlanner().isStarting() || plugin.getQueuePlanner().hasAdmissions() || plugin.getQueuePlanner().hasDirectStart())) {
            plugin.getPerkHandler().addQue(user, this); return false;
        }
        java.util.UUID directOwner = java.util.UUID.fromString(user.getUUID());
        boolean queueOwned = plugin.getConfigFile().getPerkQue();
        if (queueOwned && !plugin.getQueuePlanner().beginDirect(directOwner, this)) {
            plugin.getPerkHandler().addQue(user, this); return false;
        }
        final long admission = acquireActivationAdmission(user);
        if (admission == 0) {
            if (queueOwned) plugin.getQueuePlanner().finishDirect(directOwner, this);
            user.sendMessage("&cA perk activation is still being confirmed; no activation consumed.");
            return false;
        }
        boolean handedOff = false;
        try {
            if (plugin.getConfigFile().getLimitActivations()) {
                final String key = activationKey(user);
                Runnable verify = activationVerifier(key, admission);
                plugin.getQueuePlanner().startDirect(user, this, verify,
                        consume -> executeAdmittedWork(user, getTime(), key, admission, consume))
                        .whenComplete((ignored, error) -> {
                            releaseActivationAdmission(key, admission);
                            if (controller != null) controller.finishDirect(user, this);
                            if (queueOwned) plugin.getQueuePlanner().finishDirect(directOwner, this);
                            if (error != null) plugin.getLogger().warning("Direct perk start could not be confirmed: " + getPerk());
                        });
                handedOff = true;
                return getInteractionOptions().banked();
            }

            if (getInteractionOptions().banked()) {
                try {
                    executeAdmitted(user, getTime(), admission).whenComplete((ignored, error) -> {
                        controller.finishDirect(user, this);
                        if (queueOwned) plugin.getQueuePlanner().finishDirect(directOwner, this);
                        if (error != null) plugin.getLogger().warning("Bank grant could not be confirmed: " + getPerk());
                    });
                } catch (RuntimeException failure) {
                    if (queueOwned) plugin.getQueuePlanner().finishDirect(directOwner, this);
                    throw failure;
                }
                handedOff = true;
                return true;
            }
            if (queueOwned) {
                try {
                    executeAdmitted(user, getTime(), admission).whenComplete((ignored, error) -> plugin.getQueuePlanner().finishDirect(directOwner, this));
                } catch (RuntimeException failure) { plugin.getQueuePlanner().finishDirect(directOwner, this); throw failure; }
            } else executeAdmitted(user, getTime(), admission).exceptionally(error -> {
                plugin.getLogger().warning("Perk execution could not be confirmed: " + getPerk()); return null;
            });
            handedOff = true;
            return false;
        } finally {
            if (!handedOff) releaseActivationAdmission(activationKey(user), admission);
        }

	}

	public void runPerkPerk(MCPerksUser user, boolean globalActivation) {
		if (MCPerksMain.plugin.getPerkHandler().getActivePerks().size() != 0 && plugin.getConfigFile().getPerkQue()) {
			user.sendMessage(ConfigPerks.getInstance().getPerkAddedToQue(perk));
			MCPerksMain.plugin.getPerkHandler().addQue(user, this);
			return;
		}

		execute(user, getTime());
	}

	public void scheduleDeactivation(final MCPerksUser user) {
		if (getExperation(user) > 0 && !isLastForever()) {
			TimerTask deactivate = new TimerTask() {

				@Override
				public void run() {
					if (isActive()) {
						deactivatePerk(user);
					}
				}
			};
			long timemill = getExperation(user);
			new Timer().schedule(deactivate, new Date(timemill));
		}
	}

	/**
	 * @param activater the activater to set
	 */
	public void setActivater(MCPerksUser activater) {
		this.activater = activater;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public void setAliases(ArrayList<String> aliases) {
		this.aliases = aliases;
	}

	public void setBlocks(ArrayList<Material> blocks) {
		this.blocks = blocks;
	}

	public void setBossBar(BossBar bossBar) {
		this.bossBar = bossBar;
	}

	public void setCommands(ArrayList<String> commands) {
		this.commands = commands;
	}

	/**
	 * Sets the cool down.
	 *
	 * @param coolDown the new cool down
	 */
	public void setCoolDown(int coolDown) {
		this.coolDown = coolDown;
	}

	/**
	 * Sets the description.
	 *
	 * @param description the new description
	 */
	public void setDescription(String description) {
		this.description = description;
	}

	public void setEffects(ArrayList<Effect> effects) {
		this.effects = effects;
	}

	/**
	 * Sets the enabled.
	 *
	 * @param isEnabled the new enabled
	 */
	public void setEnabled(boolean isEnabled) {
		this.isEnabled = isEnabled;
	}

	public void setExperation(MCPerksUser user, long experation) {
		if (getPerkType().equals(PerkSystemType.ALL)) {
			plugin.getMcperksServerData().setPerkExperation(this, experation);
		} else {
			user.setPerkExperation(this, experation);
		}
		this.experation = experation;
	}

	/**
	 * @param flyWorlds the flyWorlds to set
	 */
	public void setFlyWorlds(ArrayList<String> flyWorlds) {
		this.flyWorlds = flyWorlds;
	}

	/**
	 * Sets the increase percent.
	 *
	 * @param increasePercent the new increase percent
	 */
	public void setIncreasePercent(int increasePercent) {
		this.increasePercent = increasePercent;
	}

	/**
	 * @param item the item to set
	 */
	public void setItem(ConfigurationSection item) {
		this.item = item;
	}

	/**
	 * Sets the limit.
	 *
	 * @param limit the new limit
	 */
	public void setLimit(int limit) {
		this.limit = limit;
	}

	/**
	 * Sets the name.
	 *
	 * @param name the new name
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * Sets the perk.
	 *
	 * @param perk the new perk
	 */
	public void setPerk(String perk) {
		this.perk = perk;
	}

	public void setPotionAmplifiers(HashMap<String, Integer> potionAmplifiers) {
		this.potionAmplifiers = potionAmplifiers;
	}

	public void setPotionDurations(HashMap<String, Integer> potionDurations) {
		this.potionDurations = potionDurations;
	}

	public void setPotions(Set<String> potions) {
		this.potions = potions;
	}

	public void setPriority(int priority) {
		this.priority = priority;
	}

	public void setSpecialDrops(ArrayList<ItemStack> specialDrops) {
		this.specialDrops = specialDrops;
	}

	/**
	 * Sets the time.
	 *
	 * @param time the new time
	 */
	public void setTime(int time) {
		this.time = time;
	}

	public void startUp(MCPerksUser user) {
		if (getExperation(user) != 0) {
			if (getExperation(user) != -1) {
				setActive(true);
				scheduleDeactivation(user);
			} else {
				activatePerk(null, getTime());
			}
		}
	}

	public String listEffects() {
		String str = "";
		for (Effect effect : getEffects()) {
			str += effect.toString() + ":" + effect.getModifier() + " ";
		}
		return str;
	}

}
