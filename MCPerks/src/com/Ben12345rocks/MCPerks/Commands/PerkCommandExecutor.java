package com.Ben12345rocks.MCPerks.Commands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.Ben12345rocks.MCPerks.Main;
import com.Ben12345rocks.MCPerks.UserAPI.UserManager;

// TODO: Auto-generated Javadoc
/**
 * The Class Commands.
 */
public class PerkCommandExecutor {

	/** The plugin. */
	static Main plugin = Main.plugin;

	private static PerkCommandExecutor instance = new PerkCommandExecutor();

	/**
	 * Gets the single instance of Commands.
	 *
	 * @return single instance of Commands
	 */
	public static PerkCommandExecutor getInstance() {
		return instance;
	}

	/**
	 * Instantiates a new commands.
	 */
	private PerkCommandExecutor() {
	}

	/**
	 * Instantiates a new commands.
	 *
	 * @param plugin
	 *            the plugin
	 */
	public PerkCommandExecutor(Main plugin) {
		PerkCommandExecutor.plugin = plugin;
	}

	public String getPerk(String perk) {
		for (String name : plugin.getPerkHandler().getLoadedPerks().keySet()) {
			if (name.equalsIgnoreCase(perk)) {
				return name;
			}
		}
		return perk;
	}

	public boolean issuePerkCommand(CommandSender sender, String perk, boolean timed) {
		if (sender instanceof Player) {
			perk = getPerk(perk);

			plugin.debug("Issuing perk " + perk + ", Timed: " + Boolean.toString(timed));

			plugin.getPerkHandler().getPerk(perk).runPerk(UserManager.getInstance().getMCPerksUser((Player) sender));

		} else {
			sender.sendMessage("Must be a player to use this command");
		}

		return false;
	}

}
