package com.bencodez.mcperks.commands;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;

import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.advancedcore.api.inventory.BInventory;
import com.bencodez.advancedcore.api.inventory.BInventory.ClickEvent;
import com.bencodez.advancedcore.api.inventory.BInventoryButton;
import com.bencodez.advancedcore.api.inventory.editgui.EditGUIButton;
import com.bencodez.advancedcore.api.inventory.editgui.valuetypes.EditGUIValueBoolean;
import com.bencodez.advancedcore.api.inventory.editgui.valuetypes.EditGUIValueList;
import com.bencodez.advancedcore.api.inventory.editgui.valuetypes.EditGUIValueNumber;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.advancedcore.api.placeholder.PlaceHolder;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.commands.executers.CommandAliases;
import com.bencodez.mcperks.commands.executers.CommandPerkAliases;
import com.bencodez.mcperks.commands.tabcompleter.AliasesTabCompleter;
import com.bencodez.mcperks.configs.ConfigPerks;
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.bencodez.simpleapi.array.ArrayUtils;
import com.bencodez.simpleapi.command.TabCompleteHandle;
import com.bencodez.simpleapi.command.TabCompleteHandler;
import com.bencodez.simpleapi.messages.MessageAPI;

import net.md_5.bungee.api.chat.TextComponent;

// TODO: Auto-generated Javadoc
/**
 * The Class CommandLoader.
 */
public class CommandLoader {

	MCPerksMain plugin = MCPerksMain.plugin;

	/** The commands. */
	private HashMap<String, CommandHandler> commands;

	private Object activationsLock = new Object();

	/**
	 * Instantiates a new command loader.
	 *
	 * @param plugin the plugin
	 */
	public CommandLoader(MCPerksMain plugin) {
		this.plugin = plugin;
	}

	/**
	 * Gets the commands.
	 *
	 * @return the commands
	 */
	public HashMap<String, CommandHandler> getCommands() {
		return commands;
	}

	public void loadAlias(CommandHandler cmdHandle, Perk perk) {
		if (plugin.getConfigFile().getLoadCommandAliases()) {
			loadCommand(new CommandPerkAliases(perk, cmdHandle, perk.getAliases()), perk.getPerk());
		}

	}

	/**
	 * Load aliases.
	 */
	public void loadAliases() {

		for (CommandHandler cmdHandle : plugin.getCommands()) {
			int argLength = cmdHandle.getArgs().length;
			String arg0 = "";
			if (argLength > 0) {
				arg0 = cmdHandle.getArgs()[0];
			}
			String[] perms = cmdHandle.getPerm().split(Pattern.quote("|"));
			try {
				if (perms.length > 1) {
					// has another perm
					plugin.devDebug("Adding child perm " + perms[0] + " to " + perms[1] + " from /mcperks" + arg0);
					Permission p = Bukkit.getPluginManager().getPermission(perms[1]);
					p.getChildren().put(perms[0], true);
					p.recalculatePermissibles();
				}
			} catch (Exception e) {
				plugin.debug("Failed to set permission for /mcperks" + arg0);
			}
			if (cmdHandle.getArgs().length > 0) {
				String[] args = cmdHandle.getArgs()[0].split("&");
				for (String arg : args) {
					try {
						plugin.getCommand("mcperks" + arg).setExecutor(new CommandAliases(cmdHandle));

						plugin.getCommand("mcperks" + arg)
								.setTabCompleter(new AliasesTabCompleter().setCMDHandle(cmdHandle));
						String currentPerm = plugin.getCommand("mcperks" + arg).getPermission();
						if (currentPerm == null || currentPerm.length() > perms[0].length()) {
							plugin.getCommand("mcperks" + arg).setPermission(perms[0]);
						}
						for (String str : plugin.getCommand("vote" + arg).getAliases()) {
							commands.put(str, cmdHandle);
						}
					} catch (Exception ex) {
						// plugin.devDebug("Failed to load command and tab completer for /mcperks" +
						// arg);
					}
				}
			}
		}
	}

	public void loadCommand(CommandPerkAliases commandPerkAliases, String perk) {
		try {

			Field bukkitCommandMap = Bukkit.getServer().getClass().getDeclaredField("commandMap");

			bukkitCommandMap.setAccessible(true);

			CommandMap commandMap = (CommandMap) bukkitCommandMap.get(Bukkit.getServer());

			commandMap.register("" + perk, commandPerkAliases);
		} catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public void loadCommands() {
		plugin.setCommands(new ArrayList<CommandHandler>());
		commands = new HashMap<String, CommandHandler>();

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "ToggleBossBar" }, "MCPerks.ToggleBossBar",
				"Toggle BossBar", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser((Player) sender);
				boolean setTo = !user.isUseBossBar();
				user.setUseBossBar(setTo);
				user.sendMessage("&cSetting using bossbar to " + setTo);
			}
		});

		plugin.getCommands()
				.add(new CommandHandler(plugin, new String[] { "Reload" }, "MCPerks.Reload", "Reloads the plugin") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						plugin.reload();
						sender.sendMessage(MessageAPI.colorize(
								"&c" + plugin.getName() + " v" + plugin.getDescription().getVersion() + " reloaded!"));
					}
				});

		plugin.getCommands()
				.add(new CommandHandler(plugin, new String[] { "Version" }, "MCPerks.Version", "List version info") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						if (sender instanceof Player) {
							Player player = (Player) sender;
							plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

								@Override
								public void run() {
									player.performCommand("bukkit:version " + plugin.getName());
								}
							});

						} else {
							plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

								@Override
								public void run() {
									Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(),
											"bukkit:version " + plugin.getName());
								}
							});

						}

						sendMessage(sender,
								"Using AdvancedCore " + plugin.getVersion() + "' built on '" + plugin.getBuildTime());
						if (!plugin.getAdvancedCoreBuildNumber().equals("NOTSET")) {
							sendMessage(sender,
									"AdvancedCore Jenkins build number: " + plugin.getAdvancedCoreBuildNumber());
						}
					}
				});

		plugin.getCommands()
				.add(new CommandHandler(plugin, new String[] { "Edit" }, "MCPerks.Edit", "Edit perks", false) {

					@Override
					public void execute(CommandSender sender, String[] args) {
						BInventory inv = new BInventory("MCPerks Perk Edit");
						for (final String name : ConfigPerks.getInstance().getPerksNames()) {
							inv.addButton(
									new BInventoryButton(new ItemBuilder(ConfigPerks.getInstance().getPerkItem(name))
											.setAmountNone(1).setName("&c" + name)) {

										@Override
										public void onClick(ClickEvent event) {
											openPerkEdit(event.getPlayer(), name);
										}
									});
						}

						inv.openInventory((Player) sender);
					}
				});

		plugin.getCommands()
				.add(new CommandHandler(plugin, new String[] { "Perms" }, "MCPerks.Perms", "List perms from plugin") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						ArrayList<String> msg = new ArrayList<String>();
						for (CommandHandler cmdHandle : plugin.getCommands()) {
							msg.add("&c&l" + cmdHandle.getHelpLineCommand("/mcperks") + ": &c" + cmdHandle.getPerm());
						}
						msg.add("&cMCPerks.AllPerks");
						msg.add("&cMCPerks.Perks.BypassCoolDown");
						sender.sendMessage(ArrayUtils.convert(ArrayUtils.colorize(msg)));
					}
				});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "IgnoreEffects" }, "MCPerks.IgnoreEffects",
				"Toggle ignore perk effects", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(sender.getName());
				user.setIgnoreEffects(!user.isIgnorePerkEffects());
				if (user.isIgnorePerkEffects()) {
					sendMessage(sender, "&aNow ignoring perk effects");
				} else {
					sendMessage(sender, "&aNo longer ignoring perk effects");
				}
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "IgnoreEffects", "(Player)" },
				"MCPerks.IgnoreEffects.Other", "Toggle ignore perk effects") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(args[1]);
				user.setIgnoreEffects(!user.isIgnorePerkEffects());
				if (user.isIgnorePerkEffects()) {
					sendMessage(sender, "&aNow ignoring perk effects");
				} else {
					sendMessage(sender, "&aNo longer ignoring perk effects");
				}
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "Random" }, "MCPerks.Random",
				"Give yourself a random perk", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				Player player = (Player) sender;
				ArrayList<Perk> perks = (ArrayList<Perk>) plugin.getPerkHandler().getLoadedPerks().values();
				Perk perk = perks.get(ThreadLocalRandom.current().nextInt(perks.size()));
				perk.runPerk(plugin.getMcperksUserManager().getMCPerksUser(player));
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "Random", "(player)" }, "MCPerks.Random",
				"Give someone else a random perk") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				ArrayList<Perk> perks = new ArrayList<Perk>();
				for (Perk perk : plugin.getPerkHandler().getLoadedPerks().values()) {
					perks.add(perk);
				}
				Perk perk = perks.get(ThreadLocalRandom.current().nextInt(perks.size()));
				perk.runPerk(plugin.getMcperksUserManager().getMCPerksUser(args[1]));
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "Help" }, "MCPerks.Help", "View This Page") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				if (sender instanceof Player) {
					Player player = (Player) sender;
					for (TextComponent comp : Commands.getInstance().perksHelpText(sender, 1)) {
						MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(player);
						user.sendJson(comp);
					}

				} else {
					sender.sendMessage(
							ArrayUtils.convert(ArrayUtils.comptoString(Commands.getInstance().perksHelpText(sender))));
				}
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "Placeholders" }, "MCPerks.Placeholders",
				"See possible placeholderapi placeholders") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				ArrayList<String> msg = new ArrayList<String>();
				msg.add("&cPlaceholders:");
				for (PlaceHolder<MCPerksUser> placeholder : plugin.getPlaceholders()) {
					String identifier = placeholder.getIdentifier();
					if (identifier.endsWith("_")) {
						identifier += "#";
					}
					if (placeholder.hasDescription()) {
						msg.add("%MCPerks_" + identifier + "% - " + placeholder.getDescription());
					} else {
						msg.add("%MCPerks_" + identifier + "%");
					}
				}

				sendMessage(sender, msg);
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "Placeholders", "(player)" },
				"MCPerks.Placeholders.Player", "See possible placeholderapi placeholders with player values") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				ArrayList<String> msg = new ArrayList<String>();
				msg.add("&cPlaceholders:");
				MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(args[1]);
				for (PlaceHolder<MCPerksUser> placeholder : plugin.getPlaceholders()) {
					String identifier = placeholder.getIdentifier();
					if (identifier.endsWith("_")) {
						identifier += "1";
					}
					msg.add("%MCPerks_" + identifier + "% = " + placeholder.placeholderRequest(user, identifier));
				}

				sendMessage(sender, msg);
			}
		});

		plugin.getCommands()
				.add(new CommandHandler(plugin, new String[] { "Help", "(number)" }, "MCPerks.Help", "View This Page") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						if (sender instanceof Player) {
							Player player = (Player) sender;
							for (TextComponent comp : Commands.getInstance().perksHelpText(sender,
									Integer.parseInt(args[1]))) {
								MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(player);
								user.sendJson(comp);
							}

						} else {
							sender.sendMessage(ArrayUtils
									.convert(ArrayUtils.comptoString(Commands.getInstance().perksHelpText(sender))));
						}
					}
				});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] {}, "MCPerks.GUI" + "|MCPerks.AllPerks",
				"Open Perk GUI", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				Commands.getInstance().openGUI(sender);
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "Perks" },
				"MCPerks.GUI" + "|MCPerks.AllPerks", "Open Perk GUI", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				Commands.getInstance().openGUI(sender);
			}
		});

		plugin.getCommands().add(
				new CommandHandler(plugin, new String[] { "ClearQueue" }, "MCPerks.ClearQueue", "Clear Perk Queue") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						plugin.getPerkHandler().clearQueue();
						sender.sendMessage(MessageAPI.colorize("&cPerk Queue Cleared"));
					}
				});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "ActivatePerk", "(player)", "(perk)" },
				"MCPerks.ActivatePerk", "Forcefly Activate Perk") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				Perk perk = plugin.getPerkHandler().getPerk(args[2]);
				perk.forcePerk(args[1]);
				sender.sendMessage("Forcefly activated perk " + perk.getPerk() + " for " + args[1]);
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "ActivatePerkSilent", "(player)", "(perk)" },
				"MCPerks.ActivatePerkSilent", "Forcefly Activate Perk, with no message") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				Perk perk = plugin.getPerkHandler().getPerk(args[2]);
				perk.forcePerk(args[1]);
				// sender.sendMessage("Forcefly activated perk " + perk.getPerk() + " for " +
				// args[1]);
			}
		});

		plugin.getCommands()
				.add(new CommandHandler(plugin, new String[] { "ActivatePerk", "(player)", "(perk)", "(number)" },
						"MCPerks.ActivatePerkLength", "Forcefully Activate Perk with a set length") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						Perk perk = plugin.getPerkHandler().getPerk(args[2]);
						perk.forcePerk(args[1], Integer.parseInt(args[3]));
						sender.sendMessage("Forcefly activated perk " + perk.getPerk() + " for " + args[1] + " for "
								+ args[3] + " seconds");
					}
				});

		plugin.getCommands().add(
				new CommandHandler(plugin, new String[] { "ActivePerks" }, "MCPerks.ActivePerks", "See Active Perks") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						ArrayList<String> msg = new ArrayList<String>();
						msg.add("&cPerk : Activator : Effected players : Experation");
						for (Perk perk : plugin.getPerkHandler().getActivePerks()) {
							msg.add("&c" + perk.getPerk() + " : " + perk.getActivater().getPlayerName() + " : "
									+ ArrayUtils.makeStringList(perk.getEffectedPlayers()) + " : "
									+ perk.getExperation(perk.getActivater()));
						}
						sender.sendMessage(ArrayUtils.convert(ArrayUtils.colorize(msg)));
					}
				});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "ActivePerks", "(player)" },
				"MCPerks.ActivePerks", "See Active Perks per player") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				ArrayList<String> msg = new ArrayList<String>();
				msg.add("&cPerk : Effected players : Experation");
				for (Perk perk : plugin.getPerkHandler().getActivePerks()) {
					if (perk.getActivater().getPlayerName().equalsIgnoreCase(args[1])) {
						msg.add("&c" + perk.getPerk() + " : " + ArrayUtils.makeStringList(perk.getEffectedPlayers())
								+ " : " + perk.getExperation(perk.getActivater()));
					}
				}
				sender.sendMessage(ArrayUtils.convert(ArrayUtils.colorize(msg)));
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "DeactivatePerk", "(player)", "(perk)" },
				"MCPerks.DeactivatePerk", "Forcefully deactivate Perk") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				Perk perk = plugin.getPerkHandler().getActivePerk(
						plugin.getMcperksUserManager().getMCPerksUser(args[1]),
						plugin.getPerkHandler().getPerk(args[2]));
				perk.deactivatePerk(plugin.getMcperksUserManager().getMCPerksUser(args[1]));
				sender.sendMessage("Forcefly deactivated perk " + perk.getPerk() + " for " + args[1]);
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "RemoveModifiers", "(player)" },
				"MCPerks.RemoveModifiers", "Forcefly remove modiifers") {

			@Override
			public void execute(CommandSender sender, String[] args) {

				Player p = Bukkit.getPlayer(args[1]);

				if (p != null) {
					for (AttributeModifier m : p
							.getAttribute(plugin.getVersionHandle().getAttribute("MAX_HEALTH", "GENERIC_MAX_HEALTH"))
							.getModifiers()) {
						p.getAttribute(plugin.getVersionHandle().getAttribute("MAX_HEALTH", "GENERIC_MAX_HEALTH"))
								.removeModifier(m);
					}

					for (AttributeModifier m : p
							.getAttribute(
									plugin.getVersionHandle().getAttribute("ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE"))
							.getModifiers()) {
						p.getAttribute(plugin.getVersionHandle().getAttribute("ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE"))
								.removeModifier(m);
					}

					for (AttributeModifier m : p
							.getAttribute(plugin.getVersionHandle().getAttribute("LUCK", "GENERIC_LUCK"))
							.getModifiers()) {
						p.getAttribute(plugin.getVersionHandle().getAttribute("LUCK", "GENERIC_LUCK"))
								.removeModifier(m);
					}

					for (AttributeModifier m : p
							.getAttribute(
									plugin.getVersionHandle().getAttribute("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED"))
							.getModifiers()) {
						p.getAttribute(
								plugin.getVersionHandle().getAttribute("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED"))
								.removeModifier(m);
					}

					sendMessage(sender, "&cAttributes cleared");
				}

			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "RemoveModifiersAllOnline" },
				"MCPerks.RemoveModifiersAllOnline", "Forcefly remove modiifers of all online players") {

			@Override
			public void execute(CommandSender sender, String[] args) {

				for (Player p : Bukkit.getOnlinePlayers()) {

					if (p != null) {
						for (AttributeModifier m : p
								.getAttribute(
										plugin.getVersionHandle().getAttribute("MAX_HEALTH", "GENERIC_MAX_HEALTH"))
								.getModifiers()) {
							p.getAttribute(plugin.getVersionHandle().getAttribute("MAX_HEALTH", "GENERIC_MAX_HEALTH"))
									.removeModifier(m);
						}

						for (AttributeModifier m : p.getAttribute(
								plugin.getVersionHandle().getAttribute("ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE"))
								.getModifiers()) {
							p.getAttribute(
									plugin.getVersionHandle().getAttribute("ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE"))
									.removeModifier(m);
						}

						for (AttributeModifier m : p
								.getAttribute(plugin.getVersionHandle().getAttribute("LUCK", "GENERIC_LUCK"))
								.getModifiers()) {
							p.getAttribute(plugin.getVersionHandle().getAttribute("LUCK", "GENERIC_LUCK"))
									.removeModifier(m);
						}

						for (AttributeModifier m : p.getAttribute(
								plugin.getVersionHandle().getAttribute("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED"))
								.getModifiers()) {
							p.getAttribute(
									plugin.getVersionHandle().getAttribute("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED"))
									.removeModifier(m);
						}

						sendMessage(sender, "&cAttributes cleared");
					}
				}

			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "SetActivations", "(player)", "(number)" },
				"MCPerks.SetActivations", "Add amount of activations") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				synchronized (activationsLock) {
					MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(args[1]);
					user.setActivations(Integer.parseInt(args[2]));
					sendMessage(sender, "&cSet activations to " + args[2]);
				}
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "AddActivations", "(player)", "(number)" },
				"MCPerks.AddActivations", "Add amount of activations") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				synchronized (activationsLock) {
					if (args[1].equals("*")) {
						for (Player p : Bukkit.getOnlinePlayers()) {
							MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(p);
							user.addActivation(Integer.parseInt(args[2]));
						}
						sendMessage(sender, "&cAdded activations to " + args[2]);
					} else {
						MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(args[1]);
						user.addActivation(Integer.parseInt(args[2]));
						sendMessage(sender, "&cSet activations to " + user.getActivations());
					}
				}
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "GetActivations", "(player)" },
				"MCPerks.GetActivations.Other", "Get amount of activations") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(args[1]);
				int activations = user.getActivations();
				sendMessage(sender, "&bCurrent acivations for " + user.getPlayerName() + ": " + activations);
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "GetActivations" }, "MCPerks.GetActivations",
				"Get amount of activations") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(sender.getName());
				int activations = user.getActivations();
				sendMessage(sender, "&bCurrent acivations: " + activations);
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "GetPerkActivations", "(player)", "(perk)" },
				"MCPerks.GetActivations.Perk.Other", "Get amount of activations") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(args[2]);
				int activations = user.getActivations(args[1]);
				sendMessage(sender, "&bCurrent acivations for " + user.getPlayerName() + ": " + activations);
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "GetPerkActivations", "(perk)" },
				"MCPerks.GetActivations.Perk", "Get amount of activations") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(sender.getName());
				int activations = user.getActivations(args[1]);
				sendMessage(sender, "&bCurrent acivations: " + activations);
			}
		});

		plugin.getCommands()
				.add(new CommandHandler(plugin, new String[] { "RemoveActivations", "(player)", "(number)" },
						"MCPerks.RemoveActivations.Perk", "Remove amount of activations") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(args[1]);
						user.addActivation(-Integer.parseInt(args[2]));
						if (user.getActivations() < 0) {
							user.setActivations(0);
						}
						sendMessage(sender, "&cSet activations to " + user.getActivations());
					}
				});

		plugin.getCommands()
				.add(new CommandHandler(plugin, new String[] { "SetPerkActivations", "(player)", "(Perk)", "(number)" },
						"MCPerks.SetActivations.Perk", "Add amount of activations") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						synchronized (activationsLock) {
							MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(args[1]);
							user.setActivations(args[2], Integer.parseInt(args[3]));
							sendMessage(sender, "&cSet activations to " + args[3]);
						}
					}
				});

		plugin.getCommands()
				.add(new CommandHandler(plugin, new String[] { "AddPerkActivations", "(player)", "(Perk)", "(number)" },
						"MCPerks.AddActivations.Perk", "Add amount of activations") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						synchronized (activationsLock) {
							if (args[1].equals("*")) {
								for (Player p : Bukkit.getOnlinePlayers()) {
									MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(p);
									user.addActivation(args[2], Integer.parseInt(args[3]));
								}
								sendMessage(sender, "&cAdded activations to " + args[3]);
							} else {
								MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(args[1]);
								user.addActivation(args[2], Integer.parseInt(args[3]));
								sendMessage(sender, "&cSet activations to " + user.getActivations(args[2]));
							}
						}
					}
				});

		plugin.getCommands()
				.add(new CommandHandler(plugin,
						new String[] { "RemovePerkActivations", "(player)", "(Perk)", "(number)" },
						"MCPerks.RemoveActivations.Perk", "Remove amount of activations") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						synchronized (activationsLock) {
							MCPerksUser user = plugin.getMcperksUserManager().getMCPerksUser(args[1]);
							user.addActivation(-Integer.parseInt(args[3]));
							if (user.getActivations(args[2]) < 0) {
								user.setActivations(args[2], 0);
							}
							sendMessage(sender, "&cSet activations to " + user.getActivations());
						}
					}
				});

		// load perk commands
		for (Perk perk : plugin.getPerkHandler().getLoadedPerks().values()) {
			CommandHandler cmdHandle = new CommandHandler(plugin, new String[] { perk.getPerk() },
					"MCPerks." + perk.getPerk() + "|MCPerks.AllPerks", perk.getDescription(), false) {

				@Override
				public void execute(CommandSender sender, String[] args) {
					PerkCommandExecutor.getInstance().issuePerkCommand(sender, args[0],
							plugin.getPerkHandler().getPerk(args[0]).isTimed());
				}
			};
			plugin.getCommands().add(cmdHandle);
			loadAlias(cmdHandle, perk);

			plugin.getCommands().add(new CommandHandler(plugin, new String[] { perk.getPerk(), "(player)" },
					"MCPerks.ActivatePerk.Other", "Activate Perk for another player") {

				@Override
				public void execute(CommandSender sender, String[] args) {

					Perk perk = plugin.getPerkHandler().getPerk(args[0]);
					PerkCommandExecutor.getInstance().issuePerkCommand(Bukkit.getPlayer(args[1]), args[0],
							perk.isTimed());
					sender.sendMessage("Activated perk " + perk.getPerk() + " for " + args[1]);
				}
			});

			plugin.getCommands().add(new CommandHandler(plugin, new String[] { perk.getPerk(), "(player)", "(number)" },
					"MCPerks.ActivatePerkLength", "Forcefully Activate Perk with a set length") {

				@Override
				public void execute(CommandSender sender, String[] args) {

					Perk perk = plugin.getPerkHandler().getPerk(args[0]);
					perk.forcePerk(args[1], Integer.parseInt(args[2]));
					sender.sendMessage("Forcefully activated perk " + perk.getPerk() + " for " + args[1] + " for "
							+ args[2] + " seconds");
				}
			});
		}

		// advancedcore commands
		ArrayList<CommandHandler> advancedCoreCommands = new ArrayList<CommandHandler>();
		advancedCoreCommands.addAll(com.bencodez.advancedcore.command.CommandLoader.getInstance()
				.getBasicAdminCommands(MCPerksMain.plugin.getName()));
		advancedCoreCommands.addAll(com.bencodez.advancedcore.command.CommandLoader.getInstance()
				.getBasicCommands(MCPerksMain.plugin.getName()));
		for (CommandHandler handle : advancedCoreCommands) {
			String[] args = handle.getArgs();
			String[] newArgs = new String[args.length + 1];
			newArgs[0] = "AdvancedCore";
			for (int i = 0; i < args.length; i++) {
				newArgs[i + 1] = args[i];
			}
			handle.setArgs(newArgs);
			plugin.getCommands().add(handle);
		}

		loadAliases();
		loadTabComplete();

	}

	public void loadTabComplete() {
		TabCompleteHandler.getInstance().addTabCompleteOption(
				new TabCompleteHandle("(Perk)", ArrayUtils.convert(plugin.getPerkHandler().getLoadedPerks().keySet())) {

					@Override
					public void reload() {
						setReplace(ArrayUtils.convert(plugin.getPerkHandler().getLoadedPerks().keySet()));
					}

					@Override
					public void updateReplacements() {

					}
				});

	}

	public void openPerkEdit(Player player, final String perk) {
		Perk p = plugin.getPerkHandler().getPerk(perk);
		BInventory inv = new BInventory("PerkEdit: " + perk);

		inv.addButton(new EditGUIButton(new ItemBuilder(Material.REDSTONE),
				new EditGUIValueBoolean("Enabled", p.isEnabled()) {

					@Override
					public void setValue(Player player, boolean value) {
						ConfigPerks.getInstance().set(perk, getKey(), value);
						plugin.reload();
						openPerkEdit(player, perk);
					}
				}.addLore("Set whether perk is enabled/disabled")));

		inv.addButton(new EditGUIButton(new ItemBuilder(Material.DIAMOND_SWORD),
				new EditGUIValueNumber("Priority", p.getPriority()) {

					@Override
					public void setValue(Player player, Number value) {
						ConfigPerks.getInstance().set(perk, getKey(), value.intValue());
						plugin.reload();
						openPerkEdit(player, perk);
					}
				}.addLore("Set perk priority")));

		inv.addButton(new EditGUIButton(new ItemBuilder(Material.DIAMOND_SWORD),
				new EditGUIValueNumber("TimeLasts", p.getTime()) {

					@Override
					public void setValue(Player player, Number value) {
						ConfigPerks.getInstance().set(perk, getKey(), value.intValue());
						plugin.reload();
						openPerkEdit(player, perk);
					}
				}.addLore("Set perk time lasts")));

		inv.addButton(new EditGUIButton(new ItemBuilder(Material.DIAMOND_SWORD),
				new EditGUIValueNumber("CoolDown", p.getCoolDown()) {

					@Override
					public void setValue(Player player, Number value) {
						ConfigPerks.getInstance().set(perk, getKey(), value.intValue());
						plugin.reload();
						openPerkEdit(player, perk);
					}
				}.addLore("Set perk cooldown")));

		ArrayList<String> effects = new ArrayList<String>();
		for (Effect e : p.getEffects()) {
			effects.add(e.toString());
		}
		inv.addButton(
				new EditGUIButton(new ItemBuilder(Material.DIAMOND_SWORD), new EditGUIValueList("Effects", effects) {

					@Override
					public void setValue(Player player, ArrayList<String> value) {
						ConfigPerks.getInstance().set(perk, getKey(), value);
						plugin.reload();
						openPerkEdit(player, perk);
					}
				}.addLore("Set perk effects")));

		inv.openInventory(player);
	}

	/**
	 * Sets the commands.
	 *
	 * @param commands the commands
	 */
	public void setCommands(HashMap<String, CommandHandler> commands) {
		this.commands = commands;
	}
}
