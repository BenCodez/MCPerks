/*
 *
 */
package com.Ben12345rocks.MCPerks.Objects;

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
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.Ben12345rocks.AdvancedCore.Objects.RewardBuilder;
import com.Ben12345rocks.AdvancedCore.Util.Effects.BossBar;
import com.Ben12345rocks.AdvancedCore.Util.Item.ItemBuilder;
import com.Ben12345rocks.AdvancedCore.Util.Misc.ArrayUtils;
import com.Ben12345rocks.AdvancedCore.Util.Misc.StringUtils;
import com.Ben12345rocks.MCPerks.Main;
import com.Ben12345rocks.MCPerks.Configs.Config;
import com.Ben12345rocks.MCPerks.Configs.ConfigPerks;
import com.Ben12345rocks.MCPerks.Configs.Lang;
import com.Ben12345rocks.MCPerks.Data.ServerData;

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

	/** The increase percent. */
	private int increasePercent;

	/** The limit. */
	private int limit;

	/** The special items. */
	// private ArrayList<String> specialItems;

	/** The is enabled. */
	private boolean isEnabled;

	private ArrayList<Effect> effects;

	private boolean active;

	private long experation;

	private ArrayList<ItemStack> specialDrops;

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

	private User activater;

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
	}

	/**
	 * Instantiates a new perk.
	 *
	 * @param perk
	 *            the perk
	 */
	public Perk(String perk) {
		this.perk = perk;
		setPriority(ConfigPerks.getInstance().getPerkPriority(perk));
		name = ConfigPerks.getInstance().getPerkName(perk);
		description = ConfigPerks.getInstance().getPerkDescription(perk).replace("%TimeLasts%", "" + getTime());
		isEnabled = ConfigPerks.getInstance().getPerkEnabled(perk);
		time = ConfigPerks.getInstance().getPerkTimeLasts(perk);
		coolDown = ConfigPerks.getInstance().getPerkCoolDown(perk);
		increasePercent = ConfigPerks.getInstance().getPerkPercentIncrease(perk);
		limit = ConfigPerks.getInstance().getPerkLimit(perk);
		setEffects(ConfigPerks.getInstance().getPerkEffects(perk));
		specialDrops = ConfigPerks.getInstance().getPerkSpecialDrops(perk);
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
	}

	/**
	 * Activate perk.
	 */
	public void activatePerk(User user, int length) {
		activater = user;
		Main.plugin.getPerkHandler().activePerk(this, user, length);
	}

	public void addEffectedPlayer(String uuid) {
		this.effectedPlayers.add(uuid);
	}

	/**
	 * Announce perk.
	 */
	public void announcePerk(String playerName, int length) {
		if (playerName != null) {
			String msg = Lang.getInstance().getPerkActivated().replace("%Player%", playerName).replace("%Perk%",
					getName());
			ArrayList<Player> players = new ArrayList<Player>();
			for (String uuid : getEffectedPlayers()) {
				Player p = Bukkit.getPlayer(UUID.fromString(uuid));
				// Main.plugin.debug(uuid);
				if (p != null) {
					players.add(p);
				}
			}

			for (Player p : players) {
				p.sendMessage(StringUtils.getInstance().colorize(msg));
			}

			if (isTimed() && !isLastForever()) {
				msg = Lang.getInstance().getPerkActivatedTimed().replace("%TimeLasts%", "" + length);
				for (Player p : players) {
					p.sendMessage(StringUtils.getInstance().colorize(msg));
				}
			}
		}

	}

	public boolean checkCoolDown(User user) {
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

	public boolean checkTimesUsed(User user) {
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

	public void deactivatePerk(User user) {
		setExperation(user, 0);
		setActive(false);
		// Main.plugin.getEffectHandler().deactivate(this);
		Main.plugin.debug("Perk '" + getPerk() + "' deactivated for "
				+ ArrayUtils.getInstance().makeStringList(getEffectedPlayers()));
		String msg = Lang.getInstance().getPerkDeactivated().replace("%Perk%", getName());
		ArrayList<User> users = new ArrayList<User>();
		for (String uuid : getEffectedPlayers()) {
			User u = UserManager.getInstance().getMCPerksUser(new com.Ben12345rocks.AdvancedCore.Objects.UUID(uuid));
			u.sendMessage(msg);
			users.add(u);
			new RewardBuilder(Config.getInstance().getData(), Config.getInstance().getDeactivationEffect())
					.setGiveOffline(false).send(user);
		}

		if (getBossBar() != null) {
			getBossBar().hide();
			setBossBar(null);
		}

		Main.plugin.getPerkHandler().remove(this, user);

		for (Effect effect : getEffects()) {
			effect.removeEffect(getEffectedPlayers());
		}

	}

	public void execute(User user, int length) {

		user.setPerkCoolDown(this, DateUtils.addSeconds(new Date(), getCoolDown()).getTime());
		activatePerk(user, length);
	}

	public void forcePerk(String name) {
		forcePerk(name, getTime());
	}

	public void forcePerk(String name, int length) {
		User user = UserManager.getInstance().getMCPerksUser(name);
		if (isActive()) {
			user.sendMessage(Lang.getInstance().getPerkAlreayActive());
			return;
		}

		execute(UserManager.getInstance().getMCPerksUser(name), length);
	}

	/**
	 * @return the activater
	 */
	public User getActivater() {
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

	public long getExperation(User user) {
		if (getPerkType().equals(PerkSystemType.ALL)) {
			experation = ServerData.getInstance().getPerkExperation(this);
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
	 * @return the increase percent
	 */
	public int getIncreasePercent() {
		return increasePercent;
	}

	public ItemStack getItem(User user) {
		return new ItemBuilder(item).setName(getItemName()).setLore(getItemLore(user)).toItemStack(user.getPlayer());
	}

	public String[] getItemLore(User user) {
		ArrayList<String> lore = new ArrayList<String>();
		if (isPerkActive(user)) {
			lore.addAll(ConfigPerks.getInstance().getLoreActive(getPerk()));
		} else if (!checkTimesUsed(user)) {
			lore.addAll(ConfigPerks.getInstance().getLoreLimitReached(getPerk()));
		} else if (!checkCoolDown(user)) {
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
		}

		long left = getExperation(user) - Calendar.getInstance().getTime().getTime();
		int min = (int) (left / (1000 * 64));
		long sec = left / 1000 - min * 1000 * 64;

		long cooldown = user.getPerkCoolDown(this) - Calendar.getInstance().getTime().getTime();
		long coolDownMins = cooldown / (1000 * 64);
		int coolDownHours = (int) (coolDownMins / 60);
		int coolDownMin = (int) (coolDownHours * 60 - coolDownMins);

		lore = ArrayUtils.getInstance().replaceIgnoreCase(lore, "%TimeLasts%", Integer.toString(getTime()));
		lore = ArrayUtils.getInstance().replaceIgnoreCase(lore, "%Description%", getDescription());
		lore = ArrayUtils.getInstance().replaceIgnoreCase(lore, "%HasPerm%", Boolean.toString(hasPerm));
		lore = ArrayUtils.getInstance().replaceIgnoreCase(lore, "%CoolDownLeft%",
				"" + coolDownHours + " hours " + coolDownMin + " minutes");
		if (!isLastForever()) {
			lore = ArrayUtils.getInstance().replaceIgnoreCase(lore, "%TimeLeft%",
					"" + left / (1000 * 64) + " minutes " + sec + " seconds");
		} else {
			lore = ArrayUtils.getInstance().replaceIgnoreCase(lore, "%TimeLeft%", "Forever");
		}
		int CooldownMin = getCoolDown() / 60;
		int CooldownHour = CooldownMin / 60;
		CooldownMin = CooldownHour * 60 - CooldownMin;
		lore = ArrayUtils.getInstance().replaceIgnoreCase(lore, "%CoolDown%",
				"" + CooldownHour + " Hours " + CooldownMin + " Minutes");
		// lore = Utils.getInstance().replacePlaceStringList(lore, "", "");

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

	public boolean isPerkActive(User user) {
		for (Perk perk : Main.plugin.getPerkHandler().getActivePerks()) {
			if (perk.getActivater().getPlayerName().equals(user.getPlayerName())) {
				if (perk.getPerk().equals(getPerk())) {
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

	public void runPerk(User user) {

		if (isPerkActive(user)) {
			user.sendMessage(Lang.getInstance().getPerkAlreayActive());
			return;
		}

		if (!checkTimesUsed(user)) {
			user.sendMessage(Lang.getInstance().getPerkLimitReached());
			return;
		}

		if (!checkCoolDown(user)) {
			user.sendMessage(Lang.getInstance().getPerkInCoolDown());
			return;
		}

		runPerkPerk(user);

	}

	public void runPerkPerk(User user) {
		if (Main.plugin.getPerkHandler().getActivePerks().size() != 0 && Config.getInstance().getPerkQue()) {
			user.sendMessage(Lang.getInstance().getPerkAddedToQue());
			Main.plugin.getPerkHandler().addQue(user, this);
			return;
		}

		execute(user, getTime());
	}

	public void scheduleDeactivation(final User user) {
		if (getExperation(user) > 0 && !isLastForever()) {
			TimerTask deactivate = new TimerTask() {

				@Override
				public void run() {
					deactivatePerk(user);
				}
			};
			long timemill = getExperation(user);
			new Timer().schedule(deactivate, new Date(timemill));
		}
	}

	/**
	 * @param activater
	 *            the activater to set
	 */
	public void setActivater(User activater) {
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
	 * @param coolDown
	 *            the new cool down
	 */
	public void setCoolDown(int coolDown) {
		this.coolDown = coolDown;
	}

	/**
	 * Sets the description.
	 *
	 * @param description
	 *            the new description
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
	 * @param isEnabled
	 *            the new enabled
	 */
	public void setEnabled(boolean isEnabled) {
		this.isEnabled = isEnabled;
	}

	public void setExperation(User user, long experation) {
		if (getPerkType().equals(PerkSystemType.ALL)) {
			ServerData.getInstance().setPerkExperation(this, experation);
		} else {
			user.setPerkExperation(this, experation);
		}
		this.experation = experation;
	}

	/**
	 * @param flyWorlds
	 *            the flyWorlds to set
	 */
	public void setFlyWorlds(ArrayList<String> flyWorlds) {
		this.flyWorlds = flyWorlds;
	}

	/**
	 * Sets the increase percent.
	 *
	 * @param increasePercent
	 *            the new increase percent
	 */
	public void setIncreasePercent(int increasePercent) {
		this.increasePercent = increasePercent;
	}

	/**
	 * @param item
	 *            the item to set
	 */
	public void setItem(ConfigurationSection item) {
		this.item = item;
	}

	/**
	 * Sets the limit.
	 *
	 * @param limit
	 *            the new limit
	 */
	public void setLimit(int limit) {
		this.limit = limit;
	}

	/**
	 * Sets the name.
	 *
	 * @param name
	 *            the new name
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * Sets the perk.
	 *
	 * @param perk
	 *            the new perk
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
	 * @param time
	 *            the new time
	 */
	public void setTime(int time) {
		this.time = time;
	}

	public void startUp(User user) {
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
