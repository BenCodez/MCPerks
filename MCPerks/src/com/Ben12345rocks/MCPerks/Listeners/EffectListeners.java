package com.Ben12345rocks.MCPerks.Listeners;

import java.util.Collection;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;

import com.Ben12345rocks.AdvancedCore.Listeners.AdvancedCoreLoginEvent;
import com.Ben12345rocks.AdvancedCore.Util.Effects.BossBar;
import com.Ben12345rocks.MCPerks.Main;
import com.Ben12345rocks.MCPerks.Configs.Config;
import com.Ben12345rocks.MCPerks.Effects.DoubleExperienceEffect;
import com.Ben12345rocks.MCPerks.Effects.FarmerEffect;
import com.Ben12345rocks.MCPerks.Effects.FloristEffect;
import com.Ben12345rocks.MCPerks.Effects.FlyEffect;
import com.Ben12345rocks.MCPerks.Effects.FortuneEffect;
import com.Ben12345rocks.MCPerks.Effects.HeadDropperEffect;
import com.Ben12345rocks.MCPerks.Effects.MobDropEffect;
import com.Ben12345rocks.MCPerks.Effects.ProtectionEffect;
import com.Ben12345rocks.MCPerks.Effects.UnderWaterFloristEffect;
import com.Ben12345rocks.MCPerks.Perk.Effect;
import com.Ben12345rocks.MCPerks.Perk.Perk;
import com.Ben12345rocks.MCPerks.Perk.PerkSystemType;
import com.Ben12345rocks.MCPerks.UserAPI.UserManager;

public class EffectListeners implements Listener {

	private Main plugin;

	public EffectListeners(Main plugin) {
		this.plugin = plugin;
	}

	@EventHandler(ignoreCancelled = true)
	public void entityDamaged(EntityDamageEvent event) {

		if (event.getEntity() instanceof Player) {
			Player player = (Player) event.getEntity();
			if (event.getCause().equals(DamageCause.FALL)) {
				if (plugin.getPerkHandler().effectActive(Effect.NoFallDamage, player.getUniqueId().toString())) {
					event.setDamage(0.0D);
				}
			}

			if (event.getCause().equals(DamageCause.DROWNING)) {
				if (plugin.getPerkHandler().effectActive(Effect.WaterBreathing, player.getUniqueId().toString())) {
					event.setCancelled(true);
				}
			}

			if (event.getCause().equals(DamageCause.LAVA) || event.getCause().equals(DamageCause.FIRE)
					|| event.getCause().equals(DamageCause.FIRE_TICK)) {
				if (plugin.getPerkHandler().effectActive(Effect.FireWalker, player.getUniqueId().toString())) {
					event.setCancelled(true);
				}
			}
			if (plugin.getPerkHandler().effectActive(Effect.Protection, player.getUniqueId().toString())) {
				ProtectionEffect pe = new ProtectionEffect(event);
				event.setDamage(pe.getReduction());
			}

		}

	}

	/**
	 * Entity damaged by entity.
	 *
	 * @param event
	 *            the event
	 */
	@EventHandler(ignoreCancelled = true)
	public void entityDamagedByEntity(EntityDamageByEntityEvent event) {
		if (event.getEntity() instanceof Player) {
			Player player = (Player) event.getEntity();
			if (plugin.getPerkHandler().effectActive(Effect.DisablePVP, player.getUniqueId().toString())) {
				event.setCancelled(true);
			}
		}

	}

	@SuppressWarnings("deprecation")
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event) {
		Player player = event.getPlayer();
		if (!event.isCancelled()) {
			Block blockBroken;
			Collection<ItemStack> var5;

			if (plugin.getPerkHandler().effectActive(Effect.Fortune, player.getUniqueId().toString())) {
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

			if (plugin.getPerkHandler().effectActive(Effect.Farmer, player.getUniqueId().toString())) {
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
		}

	}

	/**
	 * On block place event.
	 *
	 * @param event
	 *            the event
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
			if (plugin.getPerkHandler().effectActive(Effect.Fly, player.getUniqueId().toString())) {
				new FlyEffect().disableFly(player);
			}
			for (Perk perk : plugin.getPerkHandler().getActivePerks()) {
				if (perk.getBossBar() != null) {
					perk.getBossBar().removePlayer(player);
				}
			}
		}

	}

	@SuppressWarnings("unchecked")
	@EventHandler(ignoreCancelled = true)
	public void onEntityDeath(EntityDeathEvent event) {

		if (!(event.getEntity() instanceof Player)) {

			for (Perk active : plugin.getPerkHandler().getActivePerks()) {
				if (active.getEffects().contains(Effect.IncreaseMobDrops)) {
					MobDropEffect effect = new MobDropEffect(active);
					event.getDrops()
							.addAll((Collection<? extends ItemStack>) effect.doubleCommonDrop(event.getDrops()));
					event.getDrops().addAll(effect.insertCustomItems());
					event.getDrops().addAll(effect.insertRareItems(event.getEntity()));
				}

			}

			for (Perk active : plugin.getPerkHandler().getActivePerks()) {
				if (active.getEffects().contains(Effect.DoubleExperience)) {
					DoubleExperienceEffect effect1 = new DoubleExperienceEffect(active);
					event.setDroppedExp(effect1.increaseExperience(event.getDroppedExp()));
				}
			}

		}

		for (Perk active : plugin.getPerkHandler().getActivePerks()) {
			if (active.getEffects().contains(Effect.HeadDropper)) {
				if (!active.getBlackedListedMobs().contains(event.getEntity().getType())) {
					HeadDropperEffect effect2 = new HeadDropperEffect(active, event.getEntity());
					event.getDrops().addAll(effect2.addExtraSkulls());
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
					}
					if (perk.getEffectedPlayers().contains(player.getUniqueId().toString())) {
						giveEffect = true;
					}

					if (giveEffect) {
						perk.giveEffect(player);
						BossBar bar = perk.getBossBar();
						if (bar != null) {
							if (UserManager.getInstance().getMCPerksUser(player).isUseBossBar()) {
								bar.addPlayer(player, Config.getInstance().getBossBarHideInDelay());
								plugin.debug("adding player to bossbar");
							}
						}
					}
				}
			}
		}

	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerDamage(EntityDamageEvent event) {
		if (event.getEntity() instanceof Player) {
			Player player = (Player) event.getEntity();
			if (plugin.getPerkHandler().effectActive(Effect.God, player.getUniqueId().toString())) {
				event.setCancelled(true);
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlayerInteractEvent(PlayerInteractEvent event) {
		if (event.getItem() == null || !(event.getPlayer() instanceof Player)) {
			return;
		}
		Player player = event.getPlayer();
		if (plugin.getPerkHandler().effectActive(Effect.Florist, player.getUniqueId().toString())) {
			if (event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) {
				if (event.getItem().getType().equals(Material.BONE_MEAL)) {
					event.setCancelled(true);
					FloristEffect fe = new FloristEffect();
					fe.generateFlowers(event.getClickedBlock().getLocation(), plugin.getPerkHandler()
							.effectActiveModifier(Effect.Florist, player.getUniqueId().toString(), 2));
					fe.deductBoneMeal(event);
				}
			}

		}

		if (plugin.getPerkHandler().effectActive(Effect.UnderWaterFlorist, player.getUniqueId().toString())) {
			if (event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) {
				if (event.getItem().getType().equals(Material.BONE_MEAL)) {
					event.setCancelled(true);
					UnderWaterFloristEffect fe = new UnderWaterFloristEffect();
					fe.generateFlowers(event.getClickedBlock().getLocation(), plugin.getPerkHandler()
							.effectActiveModifier(Effect.UnderWaterFlorist, player.getUniqueId().toString(), 2));
					fe.deductBoneMeal(event);
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
		if ((player.getGameMode() != GameMode.CREATIVE)
				&& (player.getLocation().subtract(0, 1, 0).getBlock().getType() != Material.AIR)
				&& (!player.isFlying())) {
			if (plugin.getPerkHandler().effectActive(Effect.DoubleJump, player.getUniqueId().toString())) {
				player.setAllowFlight(true);
				plugin.getFlyingUUIDs().put(player.getUniqueId().toString(), true);
			}
		} else if (!plugin.getPerkHandler().effectActive(Effect.Fly, player.getUniqueId().toString())
				&& !player.hasPermission("MCPerks.ServerFly.Bypass")) {
			if (plugin.getFlyingUUIDs().get(player.getUniqueId().toString())) {
				player.setAllowFlight(false);
				plugin.getFlyingUUIDs().put(player.getUniqueId().toString(), false);
			}
		}

	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
		if (plugin.getPerkHandler().effectActive(Effect.DoubleJump, event.getPlayer().getUniqueId().toString())
				&& !plugin.getPerkHandler().effectActive(Effect.Fly, event.getPlayer().getUniqueId().toString())) {
			Player player = event.getPlayer();
			if (player.getGameMode() == GameMode.CREATIVE) {
				return;
			}
			event.setCancelled(true);
			player.setAllowFlight(false);
			player.setFlying(false);
			player.setVelocity(player.getLocation().getDirection().multiply(1.5).setY(1));
			plugin.debug(player.getName() + " double jumped!");
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onWorldSwitch(PlayerChangedWorldEvent event) {
		Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {

			@Override
			public void run() {
				Player player = event.getPlayer();
				if (player != null) {
					if (plugin.getPerkHandler().effectActive(Effect.Fly, player.getUniqueId().toString())) {
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
