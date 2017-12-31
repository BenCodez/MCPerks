package com.Ben12345rocks.MCPerks.Commands.Executers;

import java.util.ArrayList;

import org.bukkit.command.CommandSender;
import org.bukkit.command.defaults.BukkitCommand;

import com.Ben12345rocks.AdvancedCore.Objects.CommandHandler;
import com.Ben12345rocks.AdvancedCore.Util.Misc.ArrayUtils;
import com.Ben12345rocks.MCPerks.Objects.Perk;

public class CommandPerkAliases extends BukkitCommand {
	private CommandHandler cmdHandle;
	private Perk perk;

	public CommandPerkAliases(Perk perk, CommandHandler cmdHandle, ArrayList<String> aliases) {
		super(perk.getPerk());
		description = perk.getDescription();
		setAliases(aliases);
		this.cmdHandle = cmdHandle;
		this.perk = perk;
	}

	@Override
	public boolean execute(CommandSender sender, String alias, String[] args) {
		ArrayList<String> argsNew = new ArrayList<String>();
		argsNew.add(perk.getPerk());
		cmdHandle.runCommand(sender, ArrayUtils.getInstance().convert(argsNew));
		return true;
	}
}