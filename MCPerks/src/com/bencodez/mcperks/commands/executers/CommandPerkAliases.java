package com.bencodez.mcperks.commands.executers;

import java.util.ArrayList;

import org.bukkit.command.CommandSender;
import org.bukkit.command.defaults.BukkitCommand;

import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.simpleapi.array.ArrayUtils;

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
		cmdHandle.runCommand(sender, ArrayUtils.convert(argsNew));
		return true;
	}
}