package com.Ben12345rocks.MCPerks.Commands.Executers;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import com.Ben12345rocks.AdvancedCore.CommandAPI.CommandHandler;
import com.Ben12345rocks.MCPerks.Main;

public class CommandMCPerks implements CommandExecutor {

	/** The instance. */
	private static CommandMCPerks instance = new CommandMCPerks();

	static Main plugin = Main.plugin;

	public static CommandMCPerks getInstance() {
		return instance;
	}

	public CommandMCPerks() {
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.bukkit.command.CommandExecutor#onCommand(org.bukkit.command.
	 * CommandSender , org.bukkit.command.Command, java.lang.String,
	 * java.lang.String[])
	 */
	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

		for (CommandHandler commandHandler : plugin.commands) {
			if (commandHandler.runCommand(sender, args)) {
				return true;
			}
		}

		// invalid command
		sender.sendMessage(ChatColor.RED + "No valid arguments, see /mcperks help!");

		return true;
	}

}
