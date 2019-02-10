/*
 *
 */
package com.Ben12345rocks.MCPerks.Configs;

import java.io.File;
import java.io.IOException;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import com.Ben12345rocks.MCPerks.Main;

// TODO: Auto-generated Javadoc
/**
 * The Class Lang.
 */
public class Lang {

	/** The instance. */
	static Lang instance = new Lang();

	/** The plugin. */
	static Main plugin = Main.plugin;

	/**
	 * Gets the single instance of Lang.
	 *
	 * @return single instance of Lang
	 */
	public static Lang getInstance() {
		return instance;
	}

	/** The data. */
	FileConfiguration data;

	/** The d file. */
	File dFile;

	/**
	 * Instantiates a new lang.
	 */
	private Lang() {
	}

	/**
	 * Instantiates a new lang.
	 *
	 * @param plugin
	 *            the plugin
	 */
	public Lang(Main plugin) {
		Lang.plugin = plugin;
	}

	public String getCountDownTimer() {
		return getData().getString("CountDownTimer", "&a%countdown% seconds left until %perk% ends!");
	}

	/**
	 * Gets the data.
	 *
	 * @return the data
	 */
	public FileConfiguration getData() {
		return data;
	}

	public String getGUIName() {
		return getData().getString("GUIName", "MCPerks");
	}

	public String getHelpLine() {
		return getData().getString("HelpLine", "&3&l%Command% - &3%HelpMessage%");
	}

	public String getIgnoreEffect() {
		return getData().getString("IgnoreEffect", "&aIgnoring perk effect");
	}

	public String getHelpTitle() {
		return getData().getString("HelpTitle", "&3&lMCPerks Help");
	}

	/**
	 * Gets the incorrect command reply.
	 *
	 * @return the incorrect command reply
	 */
	public String getIncorrectCommandReply() {
		return getData().getString("IncorrectCommandReply", "&cIncorrect command format. For help do /mcperks help");
	}

	public String getMustBePlayer() {
		return getData().getString("MustBePlayer", "&cYou must be a player to use this command");
	}

	/**
	 * Gets the no permission.
	 *
	 * @return the no permission
	 */
	public String getNoPermission() {
		return getData().getString("NoPermission", "&cSorry, you do not have permission to execute this command.");
	}

	@Deprecated
	public String getPerkActivated() {
		return getData().getString("PerkActivated", "&6&l%Player% &3&lenacted %Perk% for the entire server!");
	}

	@Deprecated
	public String getPerkActivatedTimed() {
		return getData().getString("PerkActivatedTimed", "&3&lThis effect will last for %TimeLasts% seconds.");
	}

	@Deprecated
	public String getPerkAddedToQue() {
		return getData().getString("PerkAddedToQue", "&cPerk has been added to queue");
	}

	@Deprecated
	public String getPerkAlreayActive() {
		return getData().getString("PerkAlreayActive", "&cPerk already active");
	}

	@Deprecated
	public String getPerkDeactivated() {
		return getData().getString("PerkDeactivated", "&3&l%Perk% has been deactivated");
	}

	@Deprecated
	public String getPerkInCoolDown() {
		return getData().getString("PerkInCoolDown", "&cCooldown still in affect");
	}

	@Deprecated
	public String getPerkLimitReached() {
		return getData().getString("PerkLimitReached", "&cPerk limited reached");
	}

	/**
	 * Reload data.
	 */
	public void reloadData() {
		data = YamlConfiguration.loadConfiguration(dFile);
	}

	/**
	 * Save data.
	 */
	public void saveData() {
		try {
			data.save(dFile);
		} catch (IOException e) {
			Bukkit.getServer().getLogger().severe(ChatColor.RED + "Could not save " + dFile.getName());
		}
	}

	/**
	 * Sets the up.
	 *
	 * @param p
	 *            the new up
	 */
	public void setup(Plugin p) {
		if (!p.getDataFolder().exists()) {
			p.getDataFolder().mkdir();
		}

		dFile = new File(p.getDataFolder(), "Lang.yml");

		if (!dFile.exists()) {
			try {
				dFile.createNewFile();
				plugin.saveResource("Lang.yml", true);
			} catch (IOException e) {
				Bukkit.getServer().getLogger().severe(ChatColor.RED + "Could not create Lang.yml!");
			}
		}

		data = YamlConfiguration.loadConfiguration(dFile);
	}

}
