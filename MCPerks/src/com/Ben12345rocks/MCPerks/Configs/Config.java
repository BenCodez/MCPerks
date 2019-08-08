/*
 *
 */
package com.Ben12345rocks.MCPerks.Configs;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import com.Ben12345rocks.AdvancedCore.YML.YMLFile;
import com.Ben12345rocks.MCPerks.Main;

// TODO: Auto-generated Javadoc
/**
 * The Class Config.
 */
public class Config extends YMLFile {

	/** The instance. */
	static Config instance = new Config();

	/** The plugin. */
	static Main plugin = Main.plugin;

	/**
	 * Gets the single instance of Config.
	 *
	 * @return single instance of Config
	 */
	public static Config getInstance() {
		return instance;
	}

	/**
	 * Instantiates a new config.
	 */
	public Config() {
		super(new File(Main.plugin.getDataFolder(), "Config.yml"));
	}

	public String getActivationEffectPath() {
		return "ActivationEffect";
	}

	public String getBossBarColor() {
		return getData().getString("BossBar.Color", "BLUE");
	}

	public boolean getBossBarEnabled() {
		return getData().getBoolean("BossBar.Enabled");
	}

	public boolean getLogActivation() {
		return getData().getBoolean("LogActivation");
	}

	public String getBossBarMessage() {
		return getData().getString("BossBar.Message", "%perk% ends in %seconds% seconds");
	}

	public String getBossBarStyle() {
		return getData().getString("BossBar.Style", "SOLID");
	}

	public int getBossBarHideInDelay() {
		return getData().getInt("BossBar.HideInDelay", -1);
	}

	public String getCountDownEffectPath() {
		return "CountDownEffect";
	}

	public boolean getLimitPermission() {
		return getData().getBoolean("LimitPermission", false);
	}

	public boolean getLimitActivations() {
		return getData().getBoolean("LimitActivations", false);
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getCountDownTimes() {
		return (ArrayList<String>) getData().getList("CountDownTimes", new ArrayList<String>());
	}

	public String getDataStorage() {
		return getData().getString("DataStorage", "FLAT");
	}

	public String getDeactivationEffect() {
		return "DeactivationEffect";
	}

	/**
	 * Gets the debug enabled.
	 *
	 * @return the debug enabled
	 */
	public boolean getDebugEnabled() {
		return getData().getBoolean("Debug");
	}

	public boolean getExtraDebug() {
		return getData().getBoolean("ExtraDebug");
	}

	public boolean getLoadCommandAliases() {
		return getData().getBoolean("LoadCommandAliases", true);
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getLoreActive() {
		return (ArrayList<String>) getData().getList("Lore.Active", new ArrayList<String>());
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getLoreAvailable() {
		return (ArrayList<String>) getData().getList("Lore.Available", new ArrayList<String>());
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getLoreInCoolDown() {
		return (ArrayList<String>) getData().getList("Lore.InCoolDown", new ArrayList<String>());
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getLoreLimitReached() {
		return (ArrayList<String>) getData().getList("Lore.LimitReached", new ArrayList<String>());
	}

	public ConfigurationSection getMysql() {
		return getData().getConfigurationSection("MySQL");
	}

	/**
	 * Gets the perk description.
	 *
	 * @param perk
	 *            the perk
	 * @return the perk description
	 */
	public String getPerkDescription(String perk) {
		return getData().getString(perk + ".description");
	}

	/**
	 * Gets the perk enabled.
	 *
	 * @param perk
	 *            the perk
	 * @return the perk enabled
	 */
	public boolean getPerkEnabled(String perk) {
		return getData().getBoolean(perk + ".isEnabled");
	}

	/**
	 * Gets the perk increase percent.
	 *
	 * @param perk
	 *            the perk
	 * @return the perk increase percent
	 */
	public int getPerkIncreasePercent(String perk) {
		return getData().getInt(perk + ".increasedPercent");
	}

	/**
	 * Gets the perk limit.
	 *
	 * @param perk
	 *            the perk
	 * @return the perk limit
	 */
	public int getPerkLimit(String perk) {
		return getData().getInt(perk + ".limit");
	}

	/**
	 * Gets the perk name.
	 *
	 * @param perk
	 *            the perk
	 * @return the perk name
	 */
	public String getPerkName(String perk) {
		return getData().getString(perk + ".name");
	}

	public boolean getPerkQue() {
		return getData().getBoolean("PerkQue", true);
	}

	public Set<String> getPerks() {

		Set<String> set = getData().getConfigurationSection("Perks").getKeys(false);
		if (set == null) {
			return new HashSet<String>();
		}
		return set;
	}

	/**
	 * Gets the perk special items.
	 *
	 * @param perk
	 *            the perk
	 * @return the perk special items
	 */
	@SuppressWarnings("unchecked")
	public ArrayList<String> getPerkSpecialItems(String perk) {
		List<?> list = getData().getList(perk + ".specialItems");
		if (list != null) {
			return (ArrayList<String>) list;
		}
		return new ArrayList<String>();
	}

	public String getPerkSystemType() {
		String type = getData().getString("PerkSystemType", "");
		if (type.equals("")) {
			if (getPerPlayerPerks()) {
				type = "PLAYER";
			} else {
				type = "ALL";
			}
		}
		if (type.equalsIgnoreCase("towny")) {
			if (Bukkit.getPluginManager().getPlugin("Towny") == null) {
				plugin.getLogger().warning("Towny not found, switching to ALL perk system");
				type = "ALL";
			}
		}
		return type;
	}

	/**
	 * Gets the perk time cool down.
	 *
	 * @param perk
	 *            the perk
	 * @return the perk time cool down
	 */
	public int getPerkTimeCoolDown(String perk) {
		return getData().getInt(perk + ".coolDown");
	}

	/**
	 * Gets the perk time lasts.
	 *
	 * @param perk
	 *            the perk
	 * @return the perk time lasts
	 */
	public int getPerkTimeLasts(String perk) {
		return getData().getInt(perk + ".timeLasts");
	}

	public String getPerkURL(String perk) {
		String str = getData().getString("Perks." + perk + ".URL");
		if (str != null) {
			return str;
		}
		return "";

	}

	private boolean getPerPlayerPerks() {
		return getData().getBoolean("PerPlayerPerks");
	}

	public boolean getRequirePermToView() {
		return getData().getBoolean("RequirePermToView", true);
	}

	@Override
	public void onFileCreation() {
		plugin.saveResource("Config.yml", true);
	}

	public boolean getAllowDuplicatePerksActive() {
		return getData().getBoolean("DuplicatePerksActive", false);
	}

}
