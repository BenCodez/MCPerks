package com.Ben12345rocks.MCPerks.Configs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import com.Ben12345rocks.AdvancedCore.Util.Misc.ArrayUtils;
import com.Ben12345rocks.MCPerks.Main;

// TODO: Auto-generated Javadoc
/**
 * The Class ConfigPerks.
 */
public class ConfigPerks {

	/** The instance. */
	static ConfigPerks instance = new ConfigPerks();

	/** The plugin. */
	static Main plugin = Main.plugin;

	/**
	 * Gets the single instance of ConfigPerks.
	 *
	 * @return single instance of ConfigPerks
	 */
	public static ConfigPerks getInstance() {
		return instance;
	}

	/**
	 * Instantiates a new config vote sites.
	 */
	private ConfigPerks() {
	}

	/**
	 * Instantiates a new config vote sites.
	 *
	 * @param plugin
	 *            the plugin
	 */
	public ConfigPerks(Main plugin) {
		ConfigPerks.plugin = plugin;
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getCommands(String perk) {
		return (ArrayList<String>) getData(perk).getList("Commands", new ArrayList<String>());
	}

	/**
	 * Gets the data.
	 *
	 * @param perk
	 *            the site name
	 * @return the data
	 */
	public FileConfiguration getData(String perk) {
		File dFile = getPerkFile(perk);
		FileConfiguration data = YamlConfiguration.loadConfiguration(dFile);
		return data;
	}

	public String getCountDownEffectPath() {
		return "CountDownEffect";
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getCountDownTimes(String perk) {
		return (ArrayList<String>) getData(perk).getList("CountDownTimes", Config.getInstance().getCountDownTimes());
	}

	public String getDeactivationEffect() {
		return "DeactivationEffect";
	}

	public String getActivationEffectPath() {
		return "ActivationEffect";
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getFlyWorlds(String perk) {
		ArrayList<String> worlds = (ArrayList<String>) getData(perk).getList("FlyWorlds", new ArrayList<String>());
		if (worlds.size() == 0) {
			worlds = new ArrayList<String>();
			worlds.add("world");
		}
		return worlds;
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getLoreActive(String perk) {
		return (ArrayList<String>) getData(perk).getList("Lore.Active", Config.getInstance().getLoreActive());
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getLoreAvailable(String perk) {
		return (ArrayList<String>) getData(perk).getList("Lore.Available", Config.getInstance().getLoreAvailable());
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getLoreInCoolDown(String perk) {
		return (ArrayList<String>) getData(perk).getList("Lore.InCoolDown", Config.getInstance().getLoreInCoolDown());
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getLoreLimitReached(String perk) {
		return (ArrayList<String>) getData(perk).getList("Lore.LimitReached",
				Config.getInstance().getLoreLimitReached());
	}

	@SuppressWarnings("deprecation")
	public String getPerkActivated(String perk) {
		return getData(perk).getString("Lang.PerkActivated", Lang.getInstance().getPerkActivated());
	}

	@SuppressWarnings("deprecation")
	public String getPerkActivatedTimed(String perk) {
		return getData(perk).getString("Lang.PerkActivatedTimed", Lang.getInstance().getPerkActivatedTimed());
	}

	@SuppressWarnings("deprecation")
	public String getPerkAddedToQue(String perk) {
		return getData(perk).getString("Lang.PerkAddedToQue", Lang.getInstance().getPerkAddedToQue());
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getPerkAliases(String perk) {
		return (ArrayList<String>) getData(perk).getList("Aliases", new ArrayList<String>());
	}

	@SuppressWarnings("deprecation")
	public String getPerkAlreayActive(String perk) {
		return getData(perk).getString("Lang.PerkAlreayActive", Lang.getInstance().getPerkAlreayActive());
	}

	public int getPerkCoolDown(String perk) {
		return getData(perk).getInt("CoolDown");
	}

	@SuppressWarnings("deprecation")
	public String getPerkDeactivated(String perk) {
		return getData(perk).getString("Lang.PerkDeactivated", Lang.getInstance().getPerkDeactivated());
	}

	public String getPerkDescription(String perk) {
		String str = getData(perk).getString("Description");
		if (str != null) {
			return str;
		}
		return "Issue perk " + perk;
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getPerkEffects(String perk) {
		return (ArrayList<String>) getData(perk).getList("Effects", new ArrayList<String>());
	}

	public boolean getPerkEnabled(String perk) {
		return getData(perk).getBoolean("Enabled");
	}

	/**
	 * Gets the vote site file.
	 *
	 * @param perk
	 *            the site name
	 * @return the vote site file
	 */
	public File getPerkFile(String perk) {
		File dFile = new File(plugin.getDataFolder() + File.separator + "Perks", perk + ".yml");
		FileConfiguration data = YamlConfiguration.loadConfiguration(dFile);
		if (!dFile.exists()) {
			try {
				data.save(dFile);
			} catch (IOException e) {
				plugin.getLogger().severe(ChatColor.RED + "Could not create Perks/" + perk + ".yml!");

			}
		}
		return dFile;

	}

	@SuppressWarnings("deprecation")
	public String getPerkInCoolDown(String perk) {
		return getData(perk).getString("Lang.PerkInCoolDown", Lang.getInstance().getPerkInCoolDown());
	}

	public ConfigurationSection getPerkItem(String perk) {
		return getData(perk).getConfigurationSection("Item");
	}

	@Deprecated
	public int getPerkItemData(String perk) {
		return getData(perk).getInt("Item.Data");
	}

	@Deprecated
	public String getPerkItemMaterial(String perk) {
		return getData(perk).getString("Item.Material", "STONE");
	}

	public int getPerkLimit(String perk) {
		return getData(perk).getInt("Limit");
	}

	@SuppressWarnings("deprecation")
	public String getPerkLimitReached(String perk) {
		return getData(perk).getString("Lang.PerkLimitReached", Lang.getInstance().getPerkLimitReached());
	}

	@SuppressWarnings("unchecked")
	public ArrayList<String> getPerkMCMMOSkills(String perk) {
		return (ArrayList<String>) getData(perk).getList("MCMMOSkills", new ArrayList<String>());
	}

	public String getPerkName(String perk) {
		String str = getData(perk).getString("Name");
		if (str != null) {
			return str;
		}
		return perk;
	}

	public int getPerkPercentIncrease(String perk) {
		return getData(perk).getInt("PercentIncrease", -1);
	}

	public int getPerkPotionAmplifier(String perk, String potion) {
		return getData(perk).getInt("Potions." + potion + ".Amplifier");
	}

	public int getPerkPotionDuraton(String perk, String potion) {
		return getData(perk).getInt("Potions." + potion + ".Duration");
	}

	public Set<String> getPerkPotions(String perk) {
		try {
			Set<String> set = getData(perk).getConfigurationSection("Potions").getKeys(false);
			if (set != null) {
				return set;
			}
			return new HashSet<String>();
		} catch (Exception ex) {
			return new HashSet<String>();
		}
	}

	public int getPerkPriority(String perk) {
		return getData(perk).getInt("Priority");
	}

	public String getPerkRewardsPath(String perk) {
		return "Rewards";
	}

	/**
	 * Gets the vote sites files.
	 *
	 * @return the vote sites files
	 */
	public ArrayList<String> getPerksFiles() {
		File folder = new File(plugin.getDataFolder() + File.separator + "Perks");
		String[] fileNames = folder.list();
		return ArrayUtils.getInstance().convert(fileNames);
	}

	/**
	 * Gets the vote sites names.
	 *
	 * @return the vote sites names
	 */
	public ArrayList<String> getPerksNames() {
		ArrayList<String> perks = getPerksFiles();
		if (perks == null) {
			return new ArrayList<String>();
		}
		for (int i = 0; i < perks.size(); i++) {
			perks.set(i, perks.get(i).replace(".yml", ""));
		}
		for (int i = perks.size() - 1; i >= 0; i--) {
			// plugin.getLogger().info(perks.get(i));
			if (!getPerkEnabled(perks.get(i)) || perks.get(i).equalsIgnoreCase("Example")
					|| perks.get(i).equalsIgnoreCase("null")) {
				// plugin.getLogger().info("Removed: " + perks.get(i));
				perks.remove(i);

			}

		}

		return perks;
	}

	public ArrayList<Material> getPerkSpecialBlocks(String perk) {
		@SuppressWarnings("unchecked")
		ArrayList<String> list = (ArrayList<String>) getData(perk).getList("Blocks");
		if (list == null) {
			list = new ArrayList<String>();
		}
		ArrayList<Material> blocks = new ArrayList<Material>();
		for (String str : list) {
			blocks.add(Material.valueOf(str));
		}
		return blocks;

	}

	public ArrayList<EntityType> getPerkBlackListedMobs(String perk) {
		@SuppressWarnings("unchecked")
		ArrayList<String> list = (ArrayList<String>) getData(perk).getList("BlackListedMobs", new ArrayList<String>());
		ArrayList<EntityType> mobs = new ArrayList<EntityType>();
		for (String str : list) {
			mobs.add(EntityType.valueOf(str));
		}
		return mobs;
	}

	public ArrayList<ItemStack> getPerkSpecialDrops(String perk) {
		@SuppressWarnings("unchecked")
		ArrayList<String> list = (ArrayList<String>) getData(perk).getList("SpecialDrops");
		if (list == null) {
			list = new ArrayList<String>();
		}
		ArrayList<ItemStack> items = new ArrayList<ItemStack>();
		for (String str : list) {
			items.add(new ItemStack(Material.valueOf(str)));
		}
		return items;
	}

	public int getPerkTimeLasts(String perk) {
		return getData(perk).getInt("TimeLasts");
	}

	public String getPerkType(String perk) {
		return getData(perk).getString("PerkType", "");
	}

	/**
	 * Rename vote site.
	 *
	 * @param perk
	 *            the site name
	 * @param newName
	 *            the new name
	 * @return true, if successful
	 */
	public boolean renamePerk(String perk, String newName) {
		return getPerkFile(perk)
				.renameTo(new File(plugin.getDataFolder() + File.separator + "Perks", newName + ".yml"));
	}

	/**
	 * Save data.
	 * @param dFile dFile
	 * @param data data
	 */
	public void saveData(File dFile, FileConfiguration data) {
		try {
			data.save(dFile);
		} catch (IOException e) {
			Bukkit.getServer().getLogger().severe(ChatColor.RED + "Could not save " + dFile.getName());
		}
	}

	/**
	 * Sets the.
	 *
	 * @param perk
	 *            the site name
	 * @param path
	 *            the path
	 * @param value
	 *            the value
	 */
	public void set(String perk, String path, Object value) {
		// String playerName = user.getPlayerName();
		File dFile = getPerkFile(perk);
		FileConfiguration data = YamlConfiguration.loadConfiguration(dFile);
		data.set(path, value);
		saveData(dFile, data);
	}

	public void setPerkCoolDown(String perk, int value) {
		set(perk, "CoolDown", value);
	}

	public void setPerkEnabled(String perk, boolean value) {
		set(perk, "Enabled", value);
	}

	public void setPerkLimit(String perk, int value) {
		set(perk, "Limit", value);
	}

	public void setPerkPercentIncrease(String perk, int value) {
		set(perk, "PercentIncrease", value);
	}

	public void setPerkPriority(String perk, int value) {
		set(perk, "Priority", value);
	}

	public void setPerkTimeLasts(String perk, int value) {
		set(perk, "TimeLasts", value);
	}

	/**
	 * Sets the up.
	 *
	 * @param perk
	 *            the new up
	 */
	public void setup(String perk) {
		if (!plugin.getDataFolder().exists()) {
			plugin.getDataFolder().mkdir();
		}

		File dFile = new File(plugin.getDataFolder() + File.separator + "Perks", perk + ".yml");
		FileConfiguration data = YamlConfiguration.loadConfiguration(dFile);
		if (!dFile.exists()) {
			try {
				data.save(dFile);
				if (perk.equalsIgnoreCase("ExamplePerk")) {
					plugin.saveResource("Perks" + File.separator + "ExamplePerk.yml", true);
				}
			} catch (IOException e) {
				plugin.getLogger().severe(ChatColor.RED + "Could not create Perks/" + perk + ".yml!");
			}
		}
	}

	public double getFlySpeed(String perk) {
		return getData(perk).getDouble("FlySpeed", 1);
	}

	public double getMoveSpeed(String perk) {
		return getData(perk).getDouble("MoveSpeed", 1);
	}

	public int getPerkServerWideCoolDown(String perk) {
		return getData(perk).getInt("GlobalCoolDown");
	}

}
