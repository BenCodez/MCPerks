package com.bencodez.mcperks.commands.executers;

import java.util.ArrayList;
import java.util.Set;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.advancedcore.api.misc.ArrayUtils;
import com.bencodez.mcperks.MCPerksMain;

// TODO: Auto-generated Javadoc
/**
 * The Class CommandAliases.
 */
public class CommandAliases implements CommandExecutor {

	/** The plugin. */
	private MCPerksMain plugin = MCPerksMain.plugin;

	/** The cmd handle. */
	private CommandHandler cmdHandle;

	/**
	 * Instantiates a new command aliases.
	 *
	 * @param cmdHandle
	 *            the cmd handle
	 */
	public CommandAliases(CommandHandler cmdHandle) {
		this.cmdHandle = cmdHandle;
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

		ArrayList<String> argsNew = new ArrayList<String>();
		argsNew.add(cmdHandle.getArgs()[0]);
		for (String arg : args) {
			argsNew.add(arg);
		}
		plugin.debug("Attempting cmd...");
		plugin.debug("Inputed args: " + ArrayUtils.getInstance().makeStringList(argsNew));

		ArrayList<CommandHandler> cmdHandlers = new ArrayList<CommandHandler>();
		cmdHandlers.addAll(plugin.getCommands());
		for (CommandHandler cmdHandle : cmdHandlers) {
			if (cmdHandle.getArgs().length > args.length) {
				for (String arg : cmdHandle.getArgs()[0].split("&")) {
					Set<String> perks = plugin.getPerkHandler().getLoadedPerks().keySet();
					if (cmd.getName().equalsIgnoreCase("mcperks" + arg)
							|| ArrayUtils.getInstance().containsIgnoreCase(perks, arg)) {
						argsNew.set(0, arg);

						boolean argsMatch = true;
						for (int i = 0; i < argsNew.size(); i++) {
							if (i < cmdHandle.getArgs().length) {
								if (!cmdHandle.argsMatch(argsNew.get(i), i)) {
									argsMatch = false;
								}
							}

						}

						if (argsMatch) {
							if (cmdHandle.runCommand(sender, ArrayUtils.getInstance().convert(argsNew))) {
								plugin.debug("cmd found, ran cmd");
								return true;
							}
						}
					}
				}
			}
		}

		// invalid command
		sender.sendMessage(ChatColor.RED + "No valid arguments, see /vote help!");
		return true;
	}
}
