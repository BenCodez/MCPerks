package com.Ben12345rocks.MCPerks.Perk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import com.Ben12345rocks.AdvancedCore.Rewards.RewardBuilder;
import com.Ben12345rocks.AdvancedCore.Util.Misc.MiscUtils;
import com.Ben12345rocks.MCPerks.Main;
import com.Ben12345rocks.MCPerks.Configs.ConfigPerks;
import com.Ben12345rocks.MCPerks.Effects.FlyEffect;
import com.Ben12345rocks.MCPerks.Effects.PotionEffect;
import com.Ben12345rocks.MCPerks.UserAPI.User;
import com.Ben12345rocks.MCPerks.UserAPI.UserManager;

public enum Effect {

	IncreaseMobDrops,

	CureHunger,

	Florist,

	UnderWaterFlorist,

	CureSpells,

	DoubleExperience,

	Farmer,

	FireWalker,

	Fortune,

	HeadDropper,

	HealAll,

	McmmoXP,

	NoFallDamage,

	Protection,

	WaterBreathing,

	Fly,

	Potions,

	DisablePVP,

	Rewards,

	God,

	DoubleJump,

	Commands,

	IncreaseMaxHealth,

	MoveSpeed,

	IncreaseStrength,

	IncreaseLuck;

	public static Effect fromString(String str) {
		for (Effect eff : values()) {
			if (eff.toString().equalsIgnoreCase(str)) {
				return eff;
			}
		}
		return null;
	}

	public void removeEffect(ArrayList<String> players1) {
		ArrayList<String> uuids = new ArrayList<String>();
		for (String uuid : players1) {
			if (!Main.plugin.getPerkHandler().effectActive(this, uuid)) {
				uuids.add(uuid);
			}
		}
		ArrayList<Player> players = new ArrayList<Player>();
		for (String uuid : uuids) {
			Player player = Bukkit.getPlayer(UUID.fromString(uuid));
			if (player != null) {
				players.add(player);
			}
		}
		switch (this) {
			case Fly:
				new FlyEffect().disableFly(players);
				break;
			case IncreaseMaxHealth:
				for (Player p : players) {
					for (AttributeModifier modifier : p.getAttribute(Attribute.GENERIC_MAX_HEALTH).getModifiers()) {
						if (modifier.getName().equalsIgnoreCase("Increase health")) {
							p.getAttribute(Attribute.GENERIC_MAX_HEALTH).removeModifier(modifier);
						}
					}
				}
				break;
			case IncreaseStrength:
				for (Player p : players) {
					for (AttributeModifier modifier : p.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).getModifiers()) {
						if (modifier.getName().equalsIgnoreCase("Increase strength")) {
							p.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).removeModifier(modifier);
						}
					}
				}
				break;
			case IncreaseLuck:
				for (Player p : players) {
					for (AttributeModifier modifier : p.getAttribute(Attribute.GENERIC_LUCK).getModifiers()) {
						if (modifier.getName().equalsIgnoreCase("Increase luck")) {
							p.getAttribute(Attribute.GENERIC_LUCK).removeModifier(modifier);
						}
					}
				}
				break;
			case MoveSpeed:
				for (Player p : players) {
					for (AttributeModifier modifier : p.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getModifiers()) {
						if (modifier.getName().equalsIgnoreCase("Increase speed")) {
							p.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).removeModifier(modifier);
						}
					}
				}
				break;
			default:
				break;

		}
	}

	private int modifier = 1;

	/**
	 * @return the modifier
	 */
	public int getModifier() {
		return modifier;
	}

	/**
	 * @param modifier
	 *            the modifier to set
	 */
	public void setModifier(int modifier) {
		this.modifier = modifier;
	}

	public void runEffect(Perk perk, User user, ArrayList<String> uuids) {
		ArrayList<Player> players = new ArrayList<Player>();
		for (String uuid : uuids) {
			Player player = Bukkit.getPlayer(UUID.fromString(uuid));
			if (player != null) {
				players.add(player);
			}
		}
		switch (this) {
			case CureHunger:
				Bukkit.getScheduler().runTask(Main.plugin, new Runnable() {

					@Override
					public void run() {
						for (Player player : players) {
							player.setFoodLevel(20);
						}
					}
				});
				break;
			case HealAll:
				Bukkit.getScheduler().runTask(Main.plugin, new Runnable() {

					@Override
					public void run() {
						for (Player player : players) {
							player.setHealth(player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
						}
					}
				});
				break;
			case CureSpells:
				for (Player player : players) {
					if (player.hasPotionEffect(PotionEffectType.BLINDNESS)) {
						player.removePotionEffect(PotionEffectType.BLINDNESS);
					}

					if (player.hasPotionEffect(PotionEffectType.CONFUSION)) {
						player.removePotionEffect(PotionEffectType.CONFUSION);
					}

					if (player.hasPotionEffect(PotionEffectType.HARM)) {
						player.removePotionEffect(PotionEffectType.HARM);
					}

					if (player.hasPotionEffect(PotionEffectType.POISON)) {
						player.removePotionEffect(PotionEffectType.POISON);
					}

					if (player.hasPotionEffect(PotionEffectType.SLOW)) {
						player.removePotionEffect(PotionEffectType.SLOW);
					}

					if (player.hasPotionEffect(PotionEffectType.SLOW_DIGGING)) {
						player.removePotionEffect(PotionEffectType.SLOW_DIGGING);
					}

					if (player.hasPotionEffect(PotionEffectType.WEAKNESS)) {
						player.removePotionEffect(PotionEffectType.WEAKNESS);
					}

					if (player.hasPotionEffect(PotionEffectType.WITHER)) {
						player.removePotionEffect(PotionEffectType.WITHER);
					}
				}
				break;
			case Fly:
				new FlyEffect().enableFly(perk.getFlyWorlds(), players);
				break;
			case Potions:
				Set<String> potions = perk.getPotions();
				if (potions != null) {
					for (String potion : potions) {
						new PotionEffect().giveEffect(potion, perk.getPotionDurations().get(potion),
								perk.getPotionAmplifiers().get(potion), players);
					}
				}

				break;
			case Rewards:
				for (Player p : players) {
					new RewardBuilder(ConfigPerks.getInstance().getData(perk.getPerk()),
							ConfigPerks.getInstance().getPerkRewardsPath(perk.getPerk()))
									.send(UserManager.getInstance().getMCPerksUser(p));

				}

				break;
			case Commands:
				for (Player p : players) {
					HashMap<String, String> placeholders = new HashMap<String, String>();
					placeholders.put("player", p.getName());
					MiscUtils.getInstance().executeConsoleCommands(p, perk.getCommands(), placeholders);
				}
				break;
			case IncreaseMaxHealth:
				for (Player p : players) {
					p.getAttribute(Attribute.GENERIC_MAX_HEALTH)
							.addModifier(new AttributeModifier("Increase health", getModifier(), Operation.ADD_NUMBER));
				}
				break;
			case IncreaseStrength:
				for (Player p : players) {
					p.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).addModifier(
							new AttributeModifier("Increase strength", getModifier(), Operation.ADD_NUMBER));
				}
				break;
			case IncreaseLuck:
				for (Player p : players) {
					p.getAttribute(Attribute.GENERIC_LUCK)
							.addModifier(new AttributeModifier("Increase luck", getModifier(), Operation.ADD_NUMBER));
				}
				break;
			case MoveSpeed:
				for (Player p : players) {
					p.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).addModifier(
							new AttributeModifier("Increase speed", getModifier(), Operation.MULTIPLY_SCALAR_1));
				}
				break;
			default:
				break;

		}
	}

	public boolean usesIncreasePercentage() {
		return isEffect(Effect.IncreaseMobDrops, Effect.DoubleExperience, Effect.Farmer, Effect.Fortune, Effect.McmmoXP,
				Effect.HeadDropper, Effect.IncreaseStrength);
	}

	public boolean usesModifier() {
		return isEffect(Effect.IncreaseMobDrops, Effect.DoubleExperience, Effect.Farmer, Effect.Fortune, Effect.McmmoXP,
				Effect.HeadDropper, Effect.IncreaseStrength, Effect.Florist, Effect.UnderWaterFlorist);
	}

	public boolean isEffect(Effect... effects) {
		for (Effect effect : effects) {
			if (effect.equals(this)) {
				return true;
			}
		}
		return false;
	}
}
