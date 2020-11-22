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
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import org.apache.commons.lang.time.DateUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.advancedcore.api.messages.StringParser;
import com.bencodez.advancedcore.api.misc.ArrayUtils;
import com.bencodez.advancedcore.api.misc.effects.BossBar;
import com.bencodez.advancedcore.api.rewards.RewardBuilder;
import com.bencodez.advancedcore.api.rewards.RewardHandler;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.configs.ConfigPerks;
import com.bencodez.mcperks.configs.Lang;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.bencodez.mcperks.userapi.UserManager;

import lombok.Getter;

// TODO: Auto-generated Javadoc
/**
 * The Class Perk.
 */
public class Perk {

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

	private boolean active;

	private long experation;

	private ArrayList<ItemStack> specialDrops;

	@Getter
	private ArrayList<Material> whitelistedTools;

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

	private String perkType;

	private ArrayList<String> commands;

	private ArrayList<String> mcmmoSkills;

	private BossBar bossBar;

	private ArrayList<String> effectedPlayers = new ArrayList<String>();

	private MCPerksUser activater;

	private ArrayList<String> disabledWorlds;
	
	private MCPerksMain plugin = MCPerksMain.plugin;

	public Perk(Perk perk) {
		this.perk = perk.perk;
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
	}

	/**
	 * Instantiates a new perk.
	 *
	 * @param perk the perk
	 */
	public Perk(String perk) {
		this.perk = perk;
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
			Effect effect = Effect.valueOf(data[0]);
			if (data.length > 1) {
				if (StringParser.getInstance().isDouble(data[1])) {
					effect.setModifier(Double.valueOf(data[1]));
					if (effect.usesModifier() && effect.usesIncreasePercentage()) {
						if (increasePercent == -1) {
							increasePercent = effect.getModifier();
						}
					}
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

	}

	/**
	 * Activate perk.
	 * 
	 * @param user   user
	 * @param length length
	 */
	public void activatePerk(MCPerksUser user, int length) {
		activater = user;
		MCPerksMain.plugin.getPerkHandler().activePerk(this, user, length);
	}

	public void addEffectedPlayer(String uuid) {
		if (!this.effectedPlayers.contains(uuid)) {
			MCPerksUser user = UserManager.getInstance()
					.getMCPerksUser(new com.bencodez.advancedcore.api.user.UUID(uuid));
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

			for (Player p : players) {
				p.sendMessage(StringParser.getInstance().colorize(msg));
			}

			if (isTimed() && !isLastForever()) {
				HashMap<String, String> placeholders = new HashMap<String, String>();
				placeholders.put("TimeLasts", "" + length);
				placeholders.put("TimeLastsMin", "" + length / 60);
				placeholders.put("TimeLastsHour", "" + length / 60 / 60);
				msg = StringParser.getInstance()
						.replacePlaceHolder(ConfigPerks.getInstance().getPerkActivatedTimed(getPerk()), placeholders);
				for (Player p : players) {
					p.sendMessage(StringParser.getInstance().colorize(msg));
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

	public void deactivatePerk(MCPerksUser user) {
		setExperation(user, 0);
		setActive(false);
		// Main.plugin.getEffectHandler().deactivate(this);
		MCPerksMain.plugin.debug("Perk '" + getPerk() + "' deactivated for "
				+ ArrayUtils.getInstance().makeStringList(getEffectedPlayers()));
		for (Effect effect : getEffects()) {
			effect.removeEffect(this, getEffectedPlayers());
		}

		String msg = ConfigPerks.getInstance().getPerkDeactivated(perk).replace("%Perk%", getName());
		// ArrayList<User> users = new ArrayList<User>();
		boolean perkRewards = RewardHandler.getInstance().hasRewards(ConfigPerks.getInstance().getData(perk),
				ConfigPerks.getInstance().getDeactivationEffect());
		for (String uuid : getEffectedPlayers()) {
			MCPerksUser u = UserManager.getInstance().getMCPerksUser(new com.bencodez.advancedcore.api.user.UUID(uuid));
			u.sendMessage(msg);
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

		Bukkit.getScheduler().runTaskLaterAsynchronously(MCPerksMain.plugin, new Runnable() {

			@Override
			public void run() {
				MCPerksMain.plugin.getPerkHandler().remove(p, user);
			}
		}, 20l);

	}

	public void execute(MCPerksUser user, int length) {
		user.setPerkCoolDown(this, DateUtils.addSeconds(new Date(), getCoolDown()).getTime());
		if (getServerWideCoolDown() > 0) {
			setServerCoolDownTime(DateUtils.addSeconds(new Date(), getServerWideCoolDown()).getTime());
		}
		activatePerk(user, length);
	}

	public void setServerCoolDownTime(long time) {
		MCPerksMain.plugin.getMcperksServerData().getData().set(getPerk() + ".CoolDown", time);
	}

	public void forcePerk(String name) {
		forcePerk(name, getTime());
	}

	public void forcePerk(String name, int length) {
		MCPerksUser user = UserManager.getInstance().getMCPerksUser(name);
		if (isActive()) {
			user.sendMessage(ConfigPerks.getInstance().getPerkAlreayActive(perk));
			return;
		}

		execute(UserManager.getInstance().getMCPerksUser(name), length);
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

	public long getExperation(MCPerksUser user) {
		if (getPerkType().equals(PerkSystemType.ALL)) {
			experation = MCPerksMain.plugin.getMcperksServerData().getPerkExperation(this);
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
		int min = (int) (left / (1000 * 64));
		long sec = left / 1000 - min * 1000 * 64;

		long coolDownTime = user.getPerkCoolDown(this);
		if (coolDownTime < MCPerksMain.plugin.getMcperksServerData().getData().getLong(getPerk() + ".CoolDown", 0)) {
			coolDownTime = MCPerksMain.plugin.getMcperksServerData().getData().getLong(getPerk() + ".CoolDown");
		}
		long cooldown = coolDownTime - Calendar.getInstance().getTime().getTime();
		Duration dur = Duration.of(cooldown, ChronoUnit.MILLIS);
		int coolDownHours = (int) dur.toHours();
		int coolDownMin = (int) dur.toMinutes() - coolDownHours * 60;

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
			placeholders.put("TimeLeft", "" + left / (1000 * 64) + " minutes " + sec + " seconds");
		} else {
			placeholders.put("TimeLeft", "Forever");
		}
		int CooldownMin = getCoolDown() / 60;
		int CooldownHour = CooldownMin / 60;
		CooldownMin = CooldownHour * 60 - CooldownMin;
		placeholders.put("CoolDown", "" + CooldownHour + " Hours " + CooldownMin + " Minutes");
		// lore = Utils.getInstance().replacePlaceStringList(lore, "", "");

		lore = ArrayUtils.getInstance().replacePlaceHolder(lore, placeholders);
		return ArrayUtils.getInstance().convert(lore);
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

	public PerkSystemType getPerkType() {
		// Main.plugin.debug(perkType);
		return PerkSystemType.getType(perkType);
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
			effect.runEffect(this, null, uuids);
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
		if (world != null && world.isEmpty()) {
			for (String str : disabledWorlds) {
				if (str.equalsIgnoreCase(world)) {
					return false;
				}
			}
		}
		return true;
	}

	public void runPerk(MCPerksUser user) {

		if (user.isInWorld(disabledWorlds)) {
			user.sendMessage(Lang.getInstance().getDisabledWorlds());
			return;
		}

		if (isPerkActive(user) && !plugin.getConfigFile().getAllowDuplicatePerksActive()) {
			user.sendMessage(ConfigPerks.getInstance().getPerkAlreayActive(perk));
			return;
		}

		if (!checkTimesUsed(user)) {
			user.sendMessage(ConfigPerks.getInstance().getPerkLimitReached(perk));
			return;
		}

		if (!checkCoolDown(user)) {
			user.sendMessage(ConfigPerks.getInstance().getPerkInCoolDown(perk));
			return;
		}

		if (!checkServerWideCoolDown(user)) {
			user.sendMessage(ConfigPerks.getInstance().getPerkInCoolDown(perk));
			return;
		}

		if (plugin.getConfigFile().getLimitPermission()) {
			if (user.getPerkLimit() != -1) {
				if (user.getActivePerks() + 1 > user.getPerkLimit()) {
					user.sendMessage(ConfigPerks.getInstance().getPerkLimitReached(perk));
					return;
				}
			}
		}

		boolean globalActivation = true;

		if (plugin.getConfigFile().getLimitActivations()) {
			boolean passed = false;

			if (user.getActivations(getPerk()) > 0) {
				globalActivation = false;
				passed = true;
				user.addActivation(getPerk(), -1);
			}

			if (user.getActivations() > 0) {
				globalActivation = true;
				passed = true;
				user.addActivation(-1);
			}

			if (!passed) {
				user.sendMessage(ConfigPerks.getInstance().getPerkLimitReached(perk));
				return;
			}
		}

		runPerkPerk(user, globalActivation);

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
			MCPerksMain.plugin.getMcperksServerData().setPerkExperation(this, experation);
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

}
