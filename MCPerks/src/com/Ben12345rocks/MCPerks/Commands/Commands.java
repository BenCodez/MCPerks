package com.Ben12345rocks.MCPerks.Commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.Ben12345rocks.AdvancedCore.CommandAPI.CommandHandler;
import com.Ben12345rocks.AdvancedCore.Util.Inventory.BInventory;
import com.Ben12345rocks.AdvancedCore.Util.Inventory.BInventory.ClickEvent;
import com.Ben12345rocks.AdvancedCore.Util.Inventory.BInventoryButton;
import com.Ben12345rocks.AdvancedCore.Util.Item.ItemBuilder;
import com.Ben12345rocks.AdvancedCore.Util.Misc.StringUtils;
import com.Ben12345rocks.MCPerks.Main;
import com.Ben12345rocks.MCPerks.Configs.Config;
import com.Ben12345rocks.MCPerks.Configs.Lang;
import com.Ben12345rocks.MCPerks.Perk.Perk;
import com.Ben12345rocks.MCPerks.UserAPI.UserManager;

import net.md_5.bungee.api.chat.TextComponent;

// TODO: Auto-generated Javadoc
/**
 * The Class Commands.
 */
public class Commands {

	/** The plugin. */
	static Main plugin = Main.plugin;

	private static Commands instance = new Commands();

	/**
	 * Gets the single instance of Commands.
	 *
	 * @return single instance of Commands
	 */
	public static Commands getInstance() {
		return instance;
	}

	/**
	 * Instantiates a new commands.
	 */
	private Commands() {
	}

	/**
	 * Instantiates a new commands.
	 *
	 * @param plugin
	 *            the plugin
	 */
	public Commands(Main plugin) {
		Commands.plugin = plugin;
	}

	public void openGUI(CommandSender sender) {
		if (sender instanceof Player) {
			Player player = (Player) sender;
			BInventory inv = new BInventory(Lang.getInstance().getGUIName());
			int slot = 0;
			for (final Integer num : plugin.getPerkHandler().invPerks.keySet()) {

				Perk perk = plugin.getPerkHandler().invPerks.get(num);
				if ((player.hasPermission(plugin.getPerkHandler().invPerks.get(num).getPermission())
						|| player.hasPermission("MCPerks.AllPerks")) || !Config.getInstance().getRequirePermToView()) {
					inv.addButton(slot,
							new BInventoryButton(
									new ItemBuilder(perk.getItem(UserManager.getInstance().getMCPerksUser(player)))
											.setAmountNone(1)) {

								@Override
								public void onClick(ClickEvent event) {

									Player player = event.getWhoClicked();
									Perk perk = plugin.getPerkHandler().invPerks.get(num);
									Bukkit.getScheduler().runTask(plugin, new Runnable() {

										@Override
										public void run() {
											player.performCommand("mcperks " + perk.getPerk());
											player.closeInventory();
										}
									});

								}
							});
					slot++;
				}

			}
			inv.openInventory(player);
		} else {
			sender.sendMessage(StringUtils.getInstance().colorize("Must be a player to do this"));
		}
	}

	/**
	 * Vote help text.
	 *
	 * @param sender
	 *            the sender
	 * @return the array list
	 */
	public ArrayList<TextComponent> perksHelpText(CommandSender sender) {
		ArrayList<TextComponent> texts = new ArrayList<TextComponent>();
		HashMap<String, TextComponent> unsorted = new HashMap<String, TextComponent>();
		texts.add(StringUtils.getInstance().stringToComp(Lang.getInstance().getHelpTitle()));

		boolean requirePerms = true;

		for (CommandHandler cmdHandle : plugin.getCommands()) {
			if (cmdHandle.hasPerm(sender) && requirePerms) {
				unsorted.put("&3" + cmdHandle.getHelpLineCommand("/mcperks"), cmdHandle.getHelpLine("/mcperks"));
			} else {
				unsorted.put(cmdHandle.getHelpLineCommand("/mcperks"), cmdHandle.getHelpLine("/mcperks"));
			}
		}

		ArrayList<String> unsortedList = new ArrayList<String>();
		unsortedList.addAll(unsorted.keySet());
		Collections.sort(unsortedList, String.CASE_INSENSITIVE_ORDER);
		for (String cmd : unsortedList) {
			texts.add(unsorted.get(cmd));
		}
		return texts;
	}
}
