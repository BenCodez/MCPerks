package com.bencodez.mcperks.commands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.mcperks.userapi.MCPerksUser;

// TODO: Auto-generated Javadoc
/**
 * The Class Commands.
 */
public class PerkCommandExecutor {

	/** The plugin. */
	static MCPerksMain plugin = MCPerksMain.plugin;

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
	 * @param plugin the plugin
	 */
	public PerkCommandExecutor(MCPerksMain plugin) {
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

			Perk p = plugin.getPerkHandler().getPerk(perk);
			MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser((Player) sender);
			if (plugin.getConfigFile().getDisableOnClick() && p.isPerkActive(user)) {
				Perk ap = plugin.getPerkHandler().getActivePerk(user, p);
				ap.deactivatePerk(user);
			} else {
				p.runPerk(user);
			}

		} else {
			sender.sendMessage("Must be a player to use this command");
		}

		return false;
	}

}
