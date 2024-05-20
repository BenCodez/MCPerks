package com.bencodez.mcperks.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.advancedcore.api.inventory.BInventory;
import com.bencodez.advancedcore.api.inventory.BInventory.ClickEvent;
import com.bencodez.advancedcore.api.inventory.BInventoryButton;
import com.bencodez.advancedcore.api.inventory.UpdatingBInventoryButton;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.advancedcore.api.messages.StringParser;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.configs.Lang;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.bencodez.simpleapi.messages.MessageAPI;

import net.md_5.bungee.api.chat.TextComponent;

// TODO: Auto-generated Javadoc
/**
 * The Class Commands.
 */
public class Commands {

	/** The plugin. */
	static MCPerksMain plugin = MCPerksMain.plugin;

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
	 * @param plugin the plugin
	 */
	public Commands(MCPerksMain plugin) {
		Commands.plugin = plugin;
	}

	public void openGUI(CommandSender sender) {
		if (sender instanceof Player) {
			Player player = (Player) sender;
			MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(player);
			String title = StringParser.getInstance().replacePlaceHolder(Lang.getInstance().getGUIName(), "Activations",
					"" + user.getActivations());
			BInventory inv = new BInventory(title);
			if (plugin.getConfigFile().getKeepGUIOpen()) {
				inv.dontClose();
			}
			inv.addPlaceholder("Activations", "" + user.getActivations());
			inv.addData("MCPerksUser", user);
			for (String item : plugin.getConfigFile().getPerkGUIExtraItems()) {
				inv.addButton(
						new BInventoryButton(new ItemBuilder(plugin.getConfigFile().getPerkGUIExtraItemsItem(item))) {

							@Override
							public void onClick(ClickEvent arg0) {

							}
						});
			}
			for (final Integer num : plugin.getPerkHandler().invPerks.keySet()) {

				Perk perk = plugin.getPerkHandler().invPerks.get(num);
				if ((player.hasPermission(plugin.getPerkHandler().invPerks.get(num).getPermission())
						|| player.hasPermission("MCPerks.AllPerks"))
						|| !plugin.getConfigFile().getRequirePermToView()) {
					inv.addPlaceholder("Activations_" + perk.getPerk(), "" + user.getActivations(perk.getPerk()));

					inv.addButton(new UpdatingBInventoryButton(plugin, perk.getItem(user).setAmountNone(1)
							.addPlaceholder("PerkActivations", "" + user.getActivations(perk.getPerk())), 1000, 1000) {

						@Override
						public void onClick(ClickEvent event) {

							Player player = event.getWhoClicked();
							Perk perk = plugin.getPerkHandler().invPerks.get(num);
							Bukkit.getScheduler().runTask(plugin, new Runnable() {

								@Override
								public void run() {
									player.performCommand("mcperks " + perk.getPerk());
								}
							});

							if (!plugin.getConfigFile().getKeepGUIOpen()) {
								event.closeInventory();
							}

						}

						@Override
						public ItemBuilder onUpdate(Player player) {
							MCPerksUser user = (MCPerksUser) inv.getData("MCPerksUser");
							return perk.getItem(user).setAmountNone(1).addPlaceholder("PerkActivations",
									"" + user.getActivations(perk.getPerk()));
						}
					});

				}

			}
			inv.openInventory(player);
		} else {
			sender.sendMessage(MessageAPI.colorize("Must be a player to do this"));
		}
	}

	/**
	 * Vote help text.
	 *
	 * @param sender the sender
	 * @return the array list
	 */
	public ArrayList<TextComponent> perksHelpText(CommandSender sender) {
		ArrayList<TextComponent> texts = new ArrayList<TextComponent>();
		HashMap<String, TextComponent> unsorted = new HashMap<String, TextComponent>();
		texts.add(MessageAPI.stringToComp(Lang.getInstance().getHelpTitle()));

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

	public ArrayList<TextComponent> perksHelpText(CommandSender sender, int page) {
		ArrayList<TextComponent> cmds = new ArrayList<TextComponent>();
		ArrayList<TextComponent> help = perksHelpText(sender);
		int pagesize = 10;
		page = page - 1;
		int maxPage = (int) (Math.ceil(help.size() / pagesize) + 1);
		cmds.add(MessageAPI.stringToComp("&3&lPage " + (page + 1) + "/" + maxPage));
		for (int i = pagesize * page; (i < help.size()) && (i < ((page + 1) * pagesize)); i++) {
			cmds.add(help.get(i));
		}
		return cmds;
	}
}
