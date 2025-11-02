package com.bencodez.mcperks.listeners;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map.Entry;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.metadata.FixedMetadataValue;

import com.bencodez.advancedcore.api.misc.effects.BossBar;
import com.bencodez.advancedcore.listeners.AdvancedCoreLoginEvent;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.effects.AutoPlant;
import com.bencodez.mcperks.effects.BoneMealAura;
import com.bencodez.mcperks.effects.DoubleExperienceEffect;
import com.bencodez.mcperks.effects.FarmerEffect;
import com.bencodez.mcperks.effects.FloristEffect;
import com.bencodez.mcperks.effects.FlyEffect;
import com.bencodez.mcperks.effects.FortuneEffect;
import com.bencodez.mcperks.effects.HeadDropperEffect;
import com.bencodez.mcperks.effects.IncreaseMiningArea;
import com.bencodez.mcperks.effects.MobDropEffect;
import com.bencodez.mcperks.effects.ProtectionEffect;
import com.bencodez.mcperks.effects.TreeHarvestEffect;
import com.bencodez.mcperks.effects.UnderWaterFloristEffect;
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.mcperks.perk.PerkSystemType;
import com.bencodez.simpleapi.array.ArrayUtils;
import com.bencodez.simpleapi.player.PlayerUtils;

public class EffectListeners implements Listener {

	private MCPerksMain plugin;

	public EffectListeners(MCPerksMain plugin) {
		this.plugin = plugin;
	}

	@EventHandler(ignoreCancelled = true)
	public void entityDamaged(EntityDamageEvent event) {

		if (event.getEntity() instanceof Player) {
			Player player = (Player) event.getEntity();
			if (event.getCause().equals(DamageCause.FALL)) {
				if (plugin.getPerkHandler().effectActive(Effect.NoFallDamage, player.getUniqueId().toString(),
						player.getWorld().getName())) {
					event.setDamage(0.0D);
				}
			}

			if (event.getCause().equals(DamageCause.DROWNING)) {
				if (plugin.getPerkHandler().effectActive(Effect.WaterBreathing, player.getUniqueId().toString(),
						player.getWorld().getName())) {
					event.setCancelled(true);
				}
			}

			if (event.getCause().equals(DamageCause.LAVA) || event.getCause().equals(DamageCause.FIRE)
					|| event.getCause().equals(DamageCause.FIRE_TICK)) {
				if (plugin.getPerkHandler().effectActive(Effect.FireWalker, player.getUniqueId().toString(),
						player.getWorld().getName())) {
					event.setCancelled(true);
				}
			}
			if (plugin.getPerkHandler().effectActive(Effect.Protection, player.getUniqueId().toString(),
					player.getWorld().getName())) {
				ProtectionEffect pe = new ProtectionEffect(event);
				event.setDamage(pe.getReduction());
			}

		}

	}

	/**
	 * Entity damaged by entity.
	 *
	 * @param event the event
	 */
	@EventHandler(ignoreCancelled = true)
	public void entityDamagedByEntity(EntityDamageByEntityEvent event) {
		if (event.getEntity() instanceof Player) {
			Player player = (Player) event.getEntity();
			if (plugin.getPerkHandler().effectActive(Effect.DisablePVP, player.getUniqueId().toString(),
					player.getWorld().getName())) {
				event.setCancelled(true);
				return;
			}
		}

	}

	@EventHandler(ignoreCancelled = true)
	public void foodChange(FoodLevelChangeEvent event) {
		if (event.getEntity() instanceof Player) {
			Player player = (Player) event.getEntity();
			if (plugin.getPerkHandler().effectActive(Effect.NoHunger, player.getUniqueId().toString(),
					player.getWorld().getName())) {
				event.setCancelled(true);
				return;
			}
		}

	}

	@EventHandler(ignoreCancelled = true)
	public void entityDamagedByEntity(PlayerItemDamageEvent event) {
		Player player = (Player) event.getPlayer();
		if (plugin.getPerkHandler().effectActive(Effect.NoItemDamage, player.getUniqueId().toString(),
				player.getWorld().getName())) {
			event.setCancelled(true);
			return;
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBlockDropItem(BlockDropItemEvent event) {
		Player player = event.getPlayer();

		if (plugin.getPerkHandler().effectActive(Effect.InstantSmelt, player.getUniqueId().toString(),
				player.getWorld().getName())) {
			for (Perk active : plugin.getPerkHandler().getActivePerks()) {
				if (active.getEffects().contains(Effect.InstantSmelt)) {
					ArrayList<Item> toRemove = new ArrayList<Item>();
					for (Item item : event.getItems()) {
						ItemStack smeltedItem = null;
						Iterator<Recipe> recipeIterator = plugin.getServer().recipeIterator();
						while (recipeIterator.hasNext()) {
							Recipe recipe = recipeIterator.next();
							if (recipe instanceof FurnaceRecipe) {
								FurnaceRecipe cookingRecipe = (FurnaceRecipe) recipe;
								if (cookingRecipe.getInputChoice().test(item.getItemStack())) {
									smeltedItem = cookingRecipe.getResult();
									int stackAmount = item.getItemStack().getAmount();
									smeltedItem.setAmount(stackAmount);
									toRemove.add(item);
									player.giveExp(getExperienceAmount(cookingRecipe, stackAmount));
									final ItemStack toDrop = smeltedItem;
									if (plugin.getPerkHandler().effectActive(Effect.AutoPickupItems,
											player.getUniqueId().toString(), player.getWorld().getName())) {
										plugin.getMcperksUserManager().getMCPerksUser(player).giveItems(toDrop);
									} else {
										event.getPlayer().getWorld().dropItemNaturally(event.getBlock().getLocation(),
												toDrop);
									}

								}
							}
						}
					}
					for (Item item : toRemove) {
						event.getItems().remove(item);
					}

				}
			}
		} else if (plugin.getPerkHandler().effectActive(Effect.AutoPickupItems, player.getUniqueId().toString(),
				player.getWorld().getName())) {
			event.setCancelled(true);
			plugin.getMcperksUserManager().getMCPerksUser(player).giveItems(ArrayUtils.convertItems(event.getItems()));
		}
	}

	private int getExperienceAmount(FurnaceRecipe recipe, int stackAmount) {
		float experience = recipe.getExperience();

		int count = 1;
		int experienceAmount = 0;
		while (count <= stackAmount) {
			double rand = Math.random();
			if (rand <= (double) experience) {
				experienceAmount += (int) Math.ceil(experience);
			}
			count++;
		}
		return experienceAmount;
	}

	@SuppressWarnings("deprecation")
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event) {
		Player player = event.getPlayer();
		if (!event.isCancelled()) {
			for (BlockBreakEvent e : plugin.getEffectHandler().getBlockBreakEvents()) {
				if (e.equals(event)) {
					plugin.getEffectHandler().getBlockBreakEvents().remove(event);
					return;
				}
			}

			Block blockBroken;
			Collection<ItemStack> var5;

			if (Tag.LOGS.isTagged(event.getBlock().getType())) {
				if (plugin.getPerkHandler().effectActive(Effect.TreeHarvest, player.getUniqueId().toString(),
						player.getWorld().getName())) {
					boolean found = false;
					for (Perk active : plugin.getPerkHandler().getActivePerks()) {
						Effect e = null;
						for (Effect effect : active.getEffects()) {
							if (effect.isEffect(Effect.TreeHarvest)) {
								e = effect;
							}
						}

						if (e != null && !found) {
							new TreeHarvestEffect(plugin, player, event.getBlock(), active);
							found = true;
						}
					}

				}
			}

			if (plugin.getPerkHandler().effectActive(Effect.AutoPlant, player.getUniqueId().toString(),
					player.getWorld().getName())) {
				boolean found = false;
				for (Perk active : plugin.getPerkHandler().getActivePerks()) {
					Effect e = null;
					for (Effect effect : active.getEffects()) {
						if (effect.isEffect(Effect.AutoPlant)) {
							e = effect;
						}
					}

					if (e != null && !found) {
						new AutoPlant(plugin, player, event.getBlock(), active, event);
						found = true;

					}

				}

			}

			if (plugin.getPerkHandler().effectActive(Effect.Fortune, player.getUniqueId().toString(),
					player.getWorld().getName())) {
				for (Perk active : plugin.getPerkHandler().getActivePerks()) {
					if (active.getEffects().contains(Effect.Fortune)) {

						FortuneEffect fe = new FortuneEffect(active);
						blockBroken = event.getBlock();
						// plugin.debug("trying fortune effect");
						if (!blockBroken.hasMetadata("Placed") && fe.isWhiteListed(blockBroken)) {
							var5 = blockBroken.getDrops(event.getPlayer().getItemInHand());
							// plugin.debug("Fortune effect applying");
							for (ItemStack item : var5) {
								item.setAmount(fe.increaseDrops(item.getAmount()));
								event.getPlayer().getWorld().dropItemNaturally(event.getBlock().getLocation(), item);
							}

						}
					}
				}
			}

			if (plugin.getPerkHandler().effectActive(Effect.Farmer, player.getUniqueId().toString(),
					player.getWorld().getName())) {
				for (Perk active : plugin.getPerkHandler().getActivePerks()) {
					if (active.getEffects().contains(Effect.Farmer)) {
						FarmerEffect fe1 = new FarmerEffect(active);
						blockBroken = event.getBlock();
						if (fe1.isFarmable(blockBroken)) {
							var5 = blockBroken.getDrops(event.getPlayer().getItemInHand());

							for (ItemStack item : var5) {
								item.setAmount(fe1.increaseDrops(item.getAmount()));
								event.getPlayer().getWorld().dropItemNaturally(event.getBlock().getLocation(), item);
							}
						}
					}
				}
			}

			if (plugin.getPerkHandler().effectActive(Effect.IncreaseMiningArea, player.getUniqueId().toString(),
					player.getWorld().getName())) {
				boolean found = false;
				for (Perk active : plugin.getPerkHandler().getActivePerks()) {
					Effect e = null;
					for (Effect effect : active.getEffects()) {
						if (effect.isEffect(Effect.IncreaseMiningArea)) {
							e = effect;
						}
					}

					if (e != null && !found) {
						blockBroken = event.getBlock();
						BlockFace f = PlayerUtils.yawToFace(player.getLocation().getYaw(), false);
						new IncreaseMiningArea(plugin, event, active, player, e.getModifier(), f);
						found = true;

					}
				}
			}
		}

	}

	/**
	 * On block place event.
	 *
	 * @param event the event
	 */
	@EventHandler(ignoreCancelled = true)
	public void onBlockPlaceEvent(BlockPlaceEvent event) {
		Block block = event.getBlock();
		block.setMetadata("Placed", new FixedMetadataValue(plugin, "placed"));
	}

	@EventHandler(ignoreCancelled = true)
	public void onDisconnect(PlayerQuitEvent event) {
		Player player = event.getPlayer();
		if (player != null) {

			if (plugin.getPerkHandler().effectActive(Effect.Fly, player.getUniqueId().toString(),
					player.getWorld().getName())) {
				new FlyEffect().disableFly(player);
			}
			for (Perk perk : plugin.getPerkHandler().getActivePerks()) {
				if (perk.getBossBar() != null) {
					perk.getBossBar().removePlayer(player);
				}
				if (plugin.getConfigFile().getDeactivateOnLogout()) {
					if (perk.getActivater().getUUID().equals(player.getUniqueId().toString())) {
						perk.deactivatePerk(perk.getActivater());
					}

				}
			}
		}

	}

	@SuppressWarnings("unchecked")
	@EventHandler(ignoreCancelled = true)
	public void onEntityDeath(EntityDeathEvent event) {
		if (!(event.getEntity() instanceof Player) && !event.getEntityType().equals(EntityType.ARMOR_STAND)) {
			for (Perk active : plugin.getPerkHandler().getActivePerks()) {
				if (active.getEffects().contains(Effect.IncreaseMobDrops)) {
					boolean giveEffects = false;
					if (active.getPerkType().equals(PerkSystemType.ALL)) {
						giveEffects = true;
					} else {
						if (plugin.getVersionHandle().hasDamageSourceMethod()) {
							if (plugin.getVersionHandle().getCausingEntity(event) instanceof Player) {
								ArrayList<String> activePlayers = active.getEffectedPlayers();
								if (activePlayers.contains(
										plugin.getVersionHandle().getCausingEntity(event).getUniqueId().toString())) {
									giveEffects = true;
								}
							}
						} else {
							giveEffects = true;
						}

					}
					if (giveEffects) {
						MobDropEffect effect = new MobDropEffect(active);
						Collection<? extends ItemStack> doubled = (Collection<? extends ItemStack>) effect
								.doubleCommonDrop(event.getDrops());
						Collection<ItemStack> toAdd = new ArrayList<>(doubled);
						event.getDrops().addAll(toAdd);

						event.getDrops().addAll(effect.insertCustomItems());
						event.getDrops().addAll(effect.insertRareItems(event.getEntity()));
					}
				}

			}

			for (Perk active : plugin.getPerkHandler().getActivePerks()) {
				if (active.getEffects().contains(Effect.DoubleExperience)) {
					boolean giveEffects = false;
					if (active.getPerkType().equals(PerkSystemType.ALL)) {
						giveEffects = true;
					} else {
						if (plugin.getVersionHandle().hasDamageSourceMethod()) {
							if (plugin.getVersionHandle().getCausingEntity(event) instanceof Player) {
								ArrayList<String> activePlayers = active.getEffectedPlayers();
								if (activePlayers.contains(
										plugin.getVersionHandle().getCausingEntity(event).getUniqueId().toString())) {
									giveEffects = true;
								}
							}
						} else {
							giveEffects = true;
						}

					}
					if (giveEffects) {
						DoubleExperienceEffect effect1 = new DoubleExperienceEffect(active);
						event.setDroppedExp(effect1.increaseExperience(event.getDroppedExp()));
					}
				}
			}

			for (Perk active : plugin.getPerkHandler().getActivePerks()) {
				if (active.getEffects().contains(Effect.HeadDropper)) {
					if (!active.getBlackedListedMobs().contains(event.getEntity().getType())) {
						boolean giveEffects = false;
						if (active.getPerkType().equals(PerkSystemType.ALL)) {
							giveEffects = true;
						} else {
							if (plugin.getVersionHandle().hasDamageSourceMethod()) {
								if (plugin.getVersionHandle().getCausingEntity(event) instanceof Player) {
									ArrayList<String> activePlayers = active.getEffectedPlayers();
									if (activePlayers.contains(plugin.getVersionHandle().getCausingEntity(event)
											.getUniqueId().toString())) {
										giveEffects = true;
									}
								}
							} else {
								giveEffects = true;
							}

						}
						if (giveEffects) {
							HeadDropperEffect effect2 = new HeadDropperEffect(active, event.getEntity());
							event.getDrops().addAll(effect2.addExtraSkulls());
						}

					}
				} else {
					plugin.debug(event.getEntity().getType() + " is black listed for headdropper");
				}
			}

		}

	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerJoin(AdvancedCoreLoginEvent event) {
		Player player = event.getPlayer();
		if (player != null) {
			if (plugin.getPerkHandler().getActivePerks().size() != 0) {
				for (Perk perk : plugin.getPerkHandler().getActivePerks()) {
					boolean giveEffect = false;
					if (perk.getPerkType().equals(PerkSystemType.ALL)) {
						perk.addEffectedPlayer(player.getUniqueId().toString());
						giveEffect = true;
					} else if (perk.getPerkType().equals(PerkSystemType.PERMISSION)) {
						if (player.hasPermission(perk.getPerkType().getPermissionRequired())) {
							perk.addEffectedPlayer(player.getUniqueId().toString());
							giveEffect = true;
						}
					}
					if (perk.getEffectedPlayers().contains(player.getUniqueId().toString())) {
						giveEffect = true;
					}

					if (giveEffect && !perk.isOnlyGiveOnce()) {
						perk.giveEffect(player);
						BossBar bar = perk.getBossBar();
						if (bar != null) {
							if (plugin.getMcperksUserManager().getMCPerksUser(player).isUseBossBar()) {
								bar.addPlayer(player, plugin.getConfigFile().getBossBarHideInDelay());
								plugin.debug("adding player to bossbar");
							}
						}
					}
				}
			}

			if (plugin.getEffectHandler().getOfflineEffects().containsKey(player.getUniqueId().toString())) {
				for (Entry<Effect, Perk> entry : plugin.getEffectHandler().getOfflineEffects()
						.get(player.getUniqueId().toString()).entrySet()) {
					entry.getKey().removeEffect(plugin, entry.getValue(),
							ArrayUtils.convert(new String[] { player.getUniqueId().toString() }));
				}
			}
		}

	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		if (player != null) {
			if (plugin.getPerkHandler().getActivePerks().size() != 0) {
				for (Perk perk : plugin.getPerkHandler().getActivePerks()) {
					boolean giveEffect = false;
					if (perk.getPerkType().equals(PerkSystemType.ALL)) {
						perk.addEffectedPlayer(player.getUniqueId().toString());
						giveEffect = true;
					} else if (perk.getPerkType().equals(PerkSystemType.PERMISSION)) {
						if (player.hasPermission(perk.getPerkType().getPermissionRequired())) {
							perk.addEffectedPlayer(player.getUniqueId().toString());
							giveEffect = true;
						}
					}
					if (perk.getEffectedPlayers().contains(player.getUniqueId().toString())) {
						giveEffect = true;
					}

					if (giveEffect && !perk.isOnlyGiveOnce()) {
						perk.giveEffect(player);
						BossBar bar = perk.getBossBar();
						if (bar != null) {
							if (plugin.getMcperksUserManager().getMCPerksUser(player).isUseBossBar()) {
								bar.addPlayer(player, plugin.getConfigFile().getBossBarHideInDelay());
								plugin.debug("adding player to bossbar");
							}
						}
					}
				}
			}

			if (plugin.getEffectHandler().getOfflineEffects().containsKey(player.getUniqueId().toString())) {
				for (Entry<Effect, Perk> entry : plugin.getEffectHandler().getOfflineEffects()
						.get(player.getUniqueId().toString()).entrySet()) {
					entry.getKey().removeEffect(plugin, entry.getValue(),
							ArrayUtils.convert(new String[] { player.getUniqueId().toString() }));
				}
			}
		}

	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerDamage(EntityDamageEvent event) {
		if (event.getEntity() instanceof Player) {
			Player player = (Player) event.getEntity();
			if (plugin.getPerkHandler().effectActive(Effect.God, player.getUniqueId().toString(),
					player.getWorld().getName())) {
				event.setCancelled(true);
				return;
			}
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerDamage(PlayerDeathEvent event) {
		if (event.getEntity() instanceof Player) {
			Player player = (Player) event.getEntity();
			if (plugin.getPerkHandler().effectActive(Effect.God, player.getUniqueId().toString(),
					player.getWorld().getName())
					|| plugin.getPerkHandler().effectActive(Effect.KeepInv, player.getUniqueId().toString(),
							player.getWorld().getName())) {
				event.getDrops().clear();
				event.setKeepLevel(true);
				event.setKeepInventory(true);
				return;
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerInteractEventElytra(PlayerInteractEvent event) {
		if (!(event.getPlayer() instanceof Player)) {
			return;
		}

		Player player = event.getPlayer();

		if (plugin.getPerkHandler().effectActive(Effect.FlyBoost, player.getUniqueId().toString(),
				player.getWorld().getName())) {
			if (event.getAction() == Action.LEFT_CLICK_AIR) {
				if (player.isGliding()) {
					player.setVelocity(player.getEyeLocation().getDirection().normalize().multiply(2.0));
				}
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlayerInteractEvent(PlayerInteractEvent event) {
		if (!(event.getPlayer() instanceof Player)) {
			return;
		}

		for (PlayerInteractEvent e : plugin.getEffectHandler().getPlayerInteractEvents()) {
			if (e.equals(event)) {
				plugin.getEffectHandler().getPlayerInteractEvents().remove(event);
				return;
			}
		}

		if (event.getItem() == null) {
			return;
		}

		Player player = event.getPlayer();

		if (plugin.getPerkHandler().effectActive(Effect.Florist, player.getUniqueId().toString(),
				player.getWorld().getName())) {
			if (event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) {
				if (event.getItem().getType().equals(Material.BONE_MEAL)) {
					if (event.getClickedBlock().getType().equals(Material.GRASS_BLOCK)) {
						event.setCancelled(true);
						FloristEffect fe = new FloristEffect();
						fe.generateFlowers(event.getClickedBlock().getLocation(),
								plugin.getPerkHandler().effectActiveModifier(Effect.Florist,
										player.getUniqueId().toString(), 2, player.getWorld().getName()));
						fe.deductBoneMeal(event);
					}
				}
			}

		}

		if (plugin.getPerkHandler().effectActive(Effect.UnderWaterFlorist, player.getUniqueId().toString(),
				player.getWorld().getName())) {
			if (event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) {
				if (event.getItem().getType().equals(Material.BONE_MEAL)) {
					if (event.getClickedBlock().getRelative(BlockFace.UP).getType().equals(Material.WATER)) {
						event.setCancelled(true);
						UnderWaterFloristEffect fe = new UnderWaterFloristEffect();
						fe.generateFlowers(event.getClickedBlock().getLocation(),
								plugin.getPerkHandler().effectActiveModifier(Effect.UnderWaterFlorist,
										player.getUniqueId().toString(), 2, player.getWorld().getName()));
						fe.deductBoneMeal(event);
					}
				}
			}

		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerMove(PlayerMoveEvent event) {
		Player player = event.getPlayer();
		if (player == null) {
			return;
		}
		if (!plugin.getFlyingUUIDs().containsKey(player.getUniqueId().toString())) {
			plugin.getFlyingUUIDs().put(player.getUniqueId().toString(), false);
		}
		if ((player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR)
				&& (player.getLocation().subtract(0, 1, 0).getBlock().getType() != Material.AIR)
				&& (!player.isFlying())) {
			if (plugin.getPerkHandler().effectActive(Effect.DoubleJump, player.getUniqueId().toString(),
					player.getWorld().getName())
					|| plugin.getPerkHandler().effectActive(Effect.DoubleJumpUp, player.getUniqueId().toString(),
							player.getWorld().getName())) {
				player.setAllowFlight(true);
				plugin.getFlyingUUIDs().put(player.getUniqueId().toString(), true);
			}
		} else if (!plugin.getPerkHandler().effectActive(Effect.Fly, player.getUniqueId().toString(),
				player.getWorld().getName()) && !player.hasPermission("MCPerks.ServerFly.Bypass")) {
			if (plugin.getFlyingUUIDs().get(player.getUniqueId().toString())) {
				player.setAllowFlight(false);
				plugin.getFlyingUUIDs().put(player.getUniqueId().toString(), false);
			}
		}

		if (plugin.getPerkHandler().effectActive(Effect.BoneMealAura, player.getUniqueId().toString(),
				player.getWorld().getName())) {
			for (Perk active : plugin.getPerkHandler().getActivePerks()) {
				Effect e = null;
				for (Effect effect : active.getEffects()) {
					if (effect.isEffect(Effect.BoneMealAura)) {
						e = effect;
					}
				}

				if (e != null) {
					new BoneMealAura().checkPlants(player, event.getTo(), (int) e.getModifier());

				}
			}
		}

	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
		Player player = event.getPlayer();
		if (plugin.getPerkHandler().effectActive(Effect.DoubleJump, event.getPlayer().getUniqueId().toString(),
				player.getWorld().getName())
				&& !plugin.getPerkHandler().effectActive(Effect.Fly, event.getPlayer().getUniqueId().toString(),
						player.getWorld().getName())) {
			if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
				return;
			}
			event.setCancelled(true);
			player.setAllowFlight(false);
			player.setFlying(false);
			player.setVelocity(player.getLocation().getDirection().multiply(1.5).setY(1));
			plugin.debug(player.getName() + " double jumped!");
		} else if (plugin.getPerkHandler().effectActive(Effect.DoubleJumpUp, event.getPlayer().getUniqueId().toString(),
				player.getWorld().getName())
				&& !plugin.getPerkHandler().effectActive(Effect.Fly, event.getPlayer().getUniqueId().toString(),
						player.getWorld().getName())) {
			if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
				return;
			}
			event.setCancelled(true);
			player.setAllowFlight(false);
			player.setFlying(false);
			player.setVelocity(player.getVelocity().setY(1));
			plugin.debug(player.getName() + " double jumpedup! " + player.getVelocity().toString());
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onWorldSwitch(PlayerChangedWorldEvent event) {
		Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {

			@Override
			public void run() {
				Player player = event.getPlayer();
				if (player != null) {
					if (plugin.getPerkHandler().effectActive(Effect.Fly, player.getUniqueId().toString(),
							player.getWorld().getName())) {
						if (plugin.getEffectHandler().getFlyWorlds(player.getUniqueId().toString())
								.contains(player.getWorld().getName())) {
							new FlyEffect().enableFly(
									plugin.getEffectHandler().getFlyWorlds(player.getUniqueId().toString()), player);
						} else {
							new FlyEffect().disableFly(player);
						}

					}
				}

			}
		}, 10);
	}

}
