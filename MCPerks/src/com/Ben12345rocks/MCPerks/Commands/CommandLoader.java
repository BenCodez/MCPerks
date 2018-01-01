package com.Ben12345rocks.MCPerks.Commands;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.Ben12345rocks.AdvancedCore.Objects.CommandHandler;
import com.Ben12345rocks.AdvancedCore.Objects.TabCompleteHandle;
import com.Ben12345rocks.AdvancedCore.Objects.TabCompleteHandler;
import com.Ben12345rocks.AdvancedCore.Objects.User;
import com.Ben12345rocks.AdvancedCore.Util.Inventory.BInventory;
import com.Ben12345rocks.AdvancedCore.Util.Inventory.BInventory.ClickEvent;
import com.Ben12345rocks.AdvancedCore.Util.Inventory.BInventoryButton;
import com.Ben12345rocks.AdvancedCore.Util.Item.ItemBuilder;
import com.Ben12345rocks.AdvancedCore.Util.Misc.ArrayUtils;
import com.Ben12345rocks.AdvancedCore.Util.Misc.StringUtils;
import com.Ben12345rocks.AdvancedCore.Util.ValueRequest.InputMethod;
import com.Ben12345rocks.AdvancedCore.Util.ValueRequest.ValueRequestBuilder;
import com.Ben12345rocks.AdvancedCore.Util.ValueRequest.Listeners.BooleanListener;
import com.Ben12345rocks.AdvancedCore.Util.ValueRequest.Listeners.NumberListener;
import com.Ben12345rocks.MCPerks.Main;
import com.Ben12345rocks.MCPerks.Commands.Executers.CommandAliases;
import com.Ben12345rocks.MCPerks.Commands.Executers.CommandPerkAliases;
import com.Ben12345rocks.MCPerks.Commands.TabCompleter.AliasesTabCompleter;
import com.Ben12345rocks.MCPerks.Configs.Config;
import com.Ben12345rocks.MCPerks.Configs.ConfigPerks;
import com.Ben12345rocks.MCPerks.Objects.Perk;
import com.Ben12345rocks.MCPerks.Objects.UserManager;

import net.md_5.bungee.api.chat.TextComponent;

// TODO: Auto-generated Javadoc
/**
 * The Class CommandLoader.
 */
public class CommandLoader {

	/** The instance. */
	static CommandLoader instance = new CommandLoader();

	/** The plugin. */
	static Main plugin = Main.plugin;

	/**
	 * Gets the single instance of CommandLoader.
	 *
	 * @return single instance of CommandLoader
	 */
	public static CommandLoader getInstance() {
		return instance;
	}

	/** The commands. */
	private HashMap<String, CommandHandler> commands;

	/**
	 * Instantiates a new command loader.
	 */
	private CommandLoader() {
	}

	/**
	 * Instantiates a new command loader.
	 *
	 * @param plugin
	 *            the plugin
	 */
	public CommandLoader(Main plugin) {
		CommandLoader.plugin = plugin;
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
		if (Config.getInstance().getLoadCommandAliases()) {
			loadCommand(new CommandPerkAliases(perk, cmdHandle, perk.getAliases()), perk.getPerk());
		}

	}

	/**
	 * Load aliases.
	 */
	public void loadAliases() {

		for (CommandHandler cmdHandle : plugin.commands) {
			if (cmdHandle.getArgs().length > 0) {
				String[] args = cmdHandle.getArgs()[0].split("&");
				for (String arg : args) {
					try {
						plugin.getCommand("mcperks" + arg).setExecutor(new CommandAliases(cmdHandle));

						plugin.getCommand("mcperks" + arg)
								.setTabCompleter(new AliasesTabCompleter().setCMDHandle(cmdHandle));
						commands.put("mcperks" + arg, cmdHandle);
					} catch (Exception ex) {
						plugin.debug("Failed to load command and tab completer for /mcperks" + arg);
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
		plugin.commands = new ArrayList<CommandHandler>();
		commands = new HashMap<String, CommandHandler>();

		plugin.commands.add(new CommandHandler(new String[] { "Reload" }, "MCPerks.Reload", "Reloads the plugin") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				plugin.reload();
				sender.sendMessage(StringUtils.getInstance().colorize(
						"&c" + plugin.getName() + " v" + plugin.getDescription().getVersion() + " reloaded!"));
			}
		});

		plugin.commands.add(new CommandHandler(new String[] { "Edit" }, "MCPerks.Edit", "Edit perks", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				BInventory inv = new BInventory("MCPerks Perk Edit");
				for (final String name : ConfigPerks.getInstance().getPerksNames()) {
					inv.addButton(new BInventoryButton(new ItemBuilder(ConfigPerks.getInstance().getPerkItem(name))
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

		plugin.commands.add(new CommandHandler(new String[] { "Perms" }, "MCPerks.Perms", "List perms from plugin") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				ArrayList<String> msg = new ArrayList<String>();
				for (CommandHandler cmdHandle : plugin.commands) {
					msg.add("&c&l" + cmdHandle.getHelpLineCommand("/mcperks") + ": &c" + cmdHandle.getPerm());
				}
				msg.add("&cMCPerks.AllPerks");
				msg.add("&cMCPerks.Perks.BypassCoolDown");
				sender.sendMessage(ArrayUtils.getInstance().convert(ArrayUtils.getInstance().colorize(msg)));
			}
		});

		plugin.commands.add(
				new CommandHandler(new String[] { "Random" }, "MCPerks.Random", "Give yourself a random perk", false) {

					@Override
					public void execute(CommandSender sender, String[] args) {
						Player player = (Player) sender;
						ArrayList<Perk> perks = (ArrayList<Perk>) plugin.getPerkHandler().getLoadedPerks().values();
						Perk perk = perks.get(ThreadLocalRandom.current().nextInt(perks.size()));
						perk.runPerk(UserManager.getInstance().getMCPerksUser(player));
					}
				});

		plugin.commands.add(new CommandHandler(new String[] { "Random", "(player)" }, "MCPerks.Random",
				"Give someone else a random perk") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				ArrayList<Perk> perks = new ArrayList<Perk>();
				for (Perk perk : plugin.getPerkHandler().getLoadedPerks().values()) {
					perks.add(perk);
				}
				Perk perk = perks.get(ThreadLocalRandom.current().nextInt(perks.size()));
				perk.runPerk(UserManager.getInstance().getMCPerksUser(args[1]));
			}
		});

		plugin.commands.add(new CommandHandler(new String[] { "Help" }, "MCPerks.Help", "View This Page") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				if (sender instanceof Player) {
					Player player = (Player) sender;
					for (TextComponent comp : Commands.getInstance().perksHelpText(sender)) {
						User user = UserManager.getInstance().getMCPerksUser(player);
						user.sendJson(comp);
					}

				} else {
					sender.sendMessage(ArrayUtils.getInstance().convert(
							ArrayUtils.getInstance().comptoString(Commands.getInstance().perksHelpText(sender))));
				}
			}
		});

		plugin.commands.add(new CommandHandler(new String[] {}, "MCPerks.GUI" + "|MCPerks.AllPerks", "Open Perk GUI") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				Commands.getInstance().openGUI(sender);
			}
		});

		plugin.commands.add(
				new CommandHandler(new String[] { "Perks" }, "MCPerks.GUI" + "|MCPerks.AllPerks", "Open Perk GUI") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						Commands.getInstance().openGUI(sender);
					}
				});

		plugin.commands
				.add(new CommandHandler(new String[] { "ClearQueue" }, "MCPerks.ClearQueue", "Clear Perk Queue") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						plugin.getPerkHandler().clearQueue();
						sender.sendMessage(StringUtils.getInstance().colorize("&cPerk Queue Cleared"));
					}
				});

		plugin.commands.add(new CommandHandler(new String[] { "ActivatePerk", "(player)", "(perk)" },
				"MCPerks.ActivatePerk", "Forcefly Activate Perk") {

			@Override
			public void execute(CommandSender sender, String[] args) {

				Perk perk = plugin.getPerkHandler().getPerk(args[2]);
				perk.forcePerk(args[1]);
				sender.sendMessage("Forcefly activated perk " + perk.getPerk() + " for " + args[1]);
			}
		});

		plugin.commands.add(new CommandHandler(new String[] { "ActivatePerk", "(player)", "(perk)", "(number)" },
				"MCPerks.ActivatePerkLength", "Forcefly Activate Perk with a set length") {

			@Override
			public void execute(CommandSender sender, String[] args) {

				Perk perk = plugin.getPerkHandler().getPerk(args[2]);
				perk.forcePerk(args[1], Integer.parseInt(args[3]));
				sender.sendMessage("Forcefly activated perk " + perk.getPerk() + " for " + args[1] + " for " + args[3]
						+ " seconds");
			}
		});

		plugin.commands
				.add(new CommandHandler(new String[] { "ActivePerks" }, "MCPerks.ActivePerks", "See Active Perks") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						ArrayList<String> msg = new ArrayList<String>();
						msg.add("&cPerk : Activator : Effected players : Experation");
						for (Perk perk : plugin.getPerkHandler().getActivePerks()) {
							msg.add("&c" + perk.getPerk() + " : " + perk.getActivater().getPlayerName() + " : "
									+ ArrayUtils.getInstance().makeStringList(perk.getEffectedPlayers()) + " : "
									+ perk.getExperation(perk.getActivater()));
						}
						sender.sendMessage(ArrayUtils.getInstance().convert(ArrayUtils.getInstance().colorize(msg)));
					}
				});

		plugin.commands.add(new CommandHandler(new String[] { "DeactivatePerk", "(player)", "(perk)" },
				"MCPerks.DeactivatePerk", "Forcefly deactivate Perk") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				Perk perk = plugin.getPerkHandler().getPerk(args[2]);
				perk.deactivatePerk(UserManager.getInstance().getMCPerksUser(args[1]));
				sender.sendMessage("Forcefly deactivated perk " + perk.getPerk() + " for " + args[1]);
			}
		});

		// load perk commands
		for (Perk perk : plugin.getPerkHandler().getLoadedPerks().values()) {
			CommandHandler cmdHandle = new CommandHandler(new String[] { perk.getPerk() },
					"MCPerks." + perk.getPerk() + "|MCPerks.AllPerks", perk.getDescription()) {

				@Override
				public void execute(CommandSender sender, String[] args) {
					PerkCommandExecutor.getInstance().issuePerkCommand(sender, args[0],
							plugin.getPerkHandler().getPerk(args[0]).isTimed());
				}
			};
			plugin.commands.add(cmdHandle);
			loadAlias(cmdHandle, perk);
		}

		loadTabComplete();

	}

	public void loadTabComplete() {
		TabCompleteHandler.getInstance().addTabCompleteOption(new TabCompleteHandle("(Perk)",
				ArrayUtils.getInstance().convert(plugin.getPerkHandler().getLoadedPerks().keySet())) {

			@Override
			public void updateReplacements() {

			}

			@Override
			public void reload() {
				setReplace(ArrayUtils.getInstance().convert(plugin.getPerkHandler().getLoadedPerks().keySet()));
			}
		});

	}

	public void openPerkEdit(Player player, final String perk) {
		BInventory inv = new BInventory("PerkEdit: " + perk);
		inv.addButton(new BInventoryButton(new ItemBuilder(Material.REDSTONE).setName("Set Enabled")
				.addLoreLine("Enabled: " + ConfigPerks.getInstance().getPerkEnabled(perk))) {

			@Override
			public void onClick(ClickEvent event) {
				Player player = event.getPlayer();
				new ValueRequestBuilder(new BooleanListener() {

					@Override
					public void onInput(Player player, boolean value) {
						ConfigPerks.getInstance().setPerkEnabled(perk, value);
						plugin.reload();
						openPerkEdit(player, perk);
					}
				}).usingMethod(InputMethod.INVENTORY).request(player);

			}
		});

		inv.addButton(new BInventoryButton(new ItemBuilder(Material.DIAMOND_SWORD).setName("Set Priority")
				.addLoreLine("Priority: " + ConfigPerks.getInstance().getPerkPriority(perk))) {

			@Override
			public void onClick(ClickEvent event) {
				Player player = event.getPlayer();
				new ValueRequestBuilder(new NumberListener() {

					@Override
					public void onInput(Player player, Number value) {
						ConfigPerks.getInstance().setPerkPriority(perk, value.intValue());
						plugin.reload();
						openPerkEdit(player, perk);
					}
				}, new Number[] { 0, 1, 2, 3, 4, 5, 10, 20 }).usingMethod(InputMethod.INVENTORY).allowCustomOption(true)
						.request(player);
			}
		});

		inv.addButton(new BInventoryButton(new ItemBuilder(Material.DIAMOND_SWORD).setName("Set TimeLasts")
				.addLoreLine("TimeLasts: " + ConfigPerks.getInstance().getPerkTimeLasts(perk))) {

			@Override
			public void onClick(ClickEvent event) {
				Player player = event.getPlayer();
				new ValueRequestBuilder(new NumberListener() {

					@Override
					public void onInput(Player player, Number value) {
						ConfigPerks.getInstance().setPerkTimeLasts(perk, value.intValue());
						plugin.reload();
						openPerkEdit(player, perk);
					}
				}, new Number[] { 0, 1, 2, 3, 4, 5, 10, 20 }).usingMethod(InputMethod.INVENTORY).allowCustomOption(true)
						.request(player);
			}
		});

		inv.addButton(new BInventoryButton(new ItemBuilder(Material.DIAMOND_SWORD).setName("Set CoolDown")
				.addLoreLine("CoolDown: " + ConfigPerks.getInstance().getPerkCoolDown(perk))) {

			@Override
			public void onClick(ClickEvent event) {
				Player player = event.getPlayer();
				new ValueRequestBuilder(new NumberListener() {

					@Override
					public void onInput(Player player, Number value) {
						ConfigPerks.getInstance().setPerkCoolDown(perk, value.intValue());
						plugin.reload();
						openPerkEdit(player, perk);
					}
				}, new Number[] { 0, 1, 2, 3, 4, 5, 10, 20 }).usingMethod(InputMethod.INVENTORY).allowCustomOption(true)
						.request(player);
			}
		});

		inv.openInventory(player);
	}

	/**
	 * Sets the commands.
	 *
	 * @param commands
	 *            the commands
	 */
	public void setCommands(HashMap<String, CommandHandler> commands) {
		this.commands = commands;
	}
}
