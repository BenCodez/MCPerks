package com.bencodez.mcperks.commands.tabcompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.advancedcore.api.messages.StringParser;
import com.bencodez.advancedcore.api.misc.ArrayUtils;
import com.bencodez.mcperks.MCPerksMain;

/**
 * The Class AliasesTabCompleter.
 */
public class AliasesTabCompleter implements TabCompleter {

	/** The plugin. */
	MCPerksMain plugin = MCPerksMain.plugin;

	/** The cmd handle. */
	public CommandHandler cmdHandle;

	/**
	 * Gets the tab complete options.
	 *
	 * @param sender
	 *            the sender
	 * @param args
	 *            the args
	 * @param argNum
	 *            the arg num
	 * @param cmdHandle
	 *            the cmd handle
	 * @return the tab complete options
	 */
	public ArrayList<String> getTabCompleteOptions(CommandSender sender, String[] args, int argNum,
			CommandHandler cmdHandle) {
		ArrayList<String> cmds = new ArrayList<String>();
		if (sender.hasPermission(cmdHandle.getPerm())) {
			String[] cmdArgs = cmdHandle.getArgs();
			if (cmdArgs.length > argNum) {
				boolean argsMatch = true;
				for (int i = 0; i < argNum; i++) {
					if (args.length >= i) {
						if (!cmdHandle.argsMatch(args[i], i)) {
							argsMatch = false;
						}
					}
				}

				if (argsMatch) {
					if (cmdArgs[argNum].equalsIgnoreCase("(player)")) {
						for (Object playerOb : Bukkit.getOnlinePlayers().toArray()) {
							Player player = (Player) playerOb;
							if (!cmds.contains(player.getName())) {
								cmds.add(player.getName());
							}
						}
					} else if (cmdArgs[argNum].equalsIgnoreCase("(perk)")) {
						for (String perk : plugin.getPerkHandler().getLoadedPerks().keySet()) {
							if (!cmds.contains(perk)) {
								cmds.add(perk);
							}
						}
					} else if (cmdArgs[argNum].equalsIgnoreCase("(boolean)")) {
						if (!cmds.contains("True")) {
							cmds.add("True");
						}
						if (!cmds.contains("False")) {
							cmds.add("False");
						}
					} else if (cmdArgs[argNum].equalsIgnoreCase("(number)")) {

					} else if (!cmds.contains(cmdArgs[argNum])) {
						cmds.add(cmdArgs[argNum]);
					}
				}

			}
		}

		Collections.sort(cmds, String.CASE_INSENSITIVE_ORDER);

		return cmds;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.bukkit.command.TabCompleter#onTabComplete(org.bukkit.command.
	 * CommandSender, org.bukkit.command.Command, java.lang.String,
	 * java.lang.String[])
	 */
	@Override
	public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] argsIn) {
		ArrayList<String> msgArray = new ArrayList<String>();
		msgArray.add("");
		for (String arg : argsIn) {
			msgArray.add(arg);
		}

		String[] args = ArrayUtils.getInstance().convert(msgArray);

		ArrayList<String> tab = new ArrayList<String>();

		ArrayList<String> cmds = new ArrayList<String>();

		ArrayList<CommandHandler> cmdHandlers = new ArrayList<CommandHandler>();
		cmdHandlers.addAll(plugin.getCommands());
		for (CommandHandler cmdHandle : cmdHandlers) {
			if (cmdHandle.getArgs().length >= argsIn.length) {
				for (String arg : cmdHandle.getArgs()[0].split("&")) {
					Set<String> perks = plugin.getPerkHandler().getLoadedPerks().keySet();
					if (cmd.getName().equalsIgnoreCase("mcperks" + arg)
							|| ArrayUtils.getInstance().containsIgnoreCase(perks, arg)) {
						// plugin.debug("Found cmd... attempting to get tab
						// complete");
						args[0] = arg;
						boolean argsMatch = true;
						for (int i = 0; i < argsIn.length; i++) {
							if (args.length >= i) {
								if (!cmdHandle.argsMatch(args[i], i)) {
									argsMatch = false;
								}
							}
						}

						if (argsMatch) {

							cmds.addAll(getTabCompleteOptions(sender, args, argsIn.length, cmdHandle));
						}

					}
				}
			}
		}

		for (int i = 0; i < cmds.size(); i++) {
			if (StringParser.getInstance().startsWithIgnoreCase(cmds.get(i), args[argsIn.length])) {
				tab.add(cmds.get(i));
			}
		}

		return tab;
	}

	/**
	 * Sets the CMD handle.
	 *
	 * @param cmd
	 *            the cmd
	 * @return the aliases tab completer
	 */
	public AliasesTabCompleter setCMDHandle(CommandHandler cmd) {
		cmdHandle = cmd;
		return this;
	}

}
