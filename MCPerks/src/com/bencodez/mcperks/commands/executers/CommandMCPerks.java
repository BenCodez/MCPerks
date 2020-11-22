package com.bencodez.mcperks.commands.executers;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.mcperks.MCPerksMain;

public class CommandMCPerks implements CommandExecutor {

	/** The instance. */
	private static CommandMCPerks instance = new CommandMCPerks();

	static MCPerksMain plugin = MCPerksMain.plugin;

	public static CommandMCPerks getInstance() {
		return instance;
	}

	public CommandMCPerks() {
	}

	/*
	 * (non-Javadoc)
	 * @see org.bukkit.command.CommandExecutor#onCommand(org.bukkit.command.
	 * CommandSender , org.bukkit.command.Command, java.lang.String,
	 * java.lang.String[])
	 */
	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

		for (CommandHandler commandHandler : plugin.getCommands()) {
			if (commandHandler.runCommand(sender, args)) {
				return true;
			}
		}

		// invalid command
		sender.sendMessage(ChatColor.RED + "No valid arguments, see /mcperks help!");

		return true;
	}

}
