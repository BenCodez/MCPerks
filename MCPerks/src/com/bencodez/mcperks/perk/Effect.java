package com.bencodez.mcperks.perk;

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

import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.advancedcore.api.rewards.RewardBuilder;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.configs.ConfigPerks;
import com.bencodez.mcperks.effects.FlyEffect;
import com.bencodez.mcperks.effects.PotionEffect;
import com.bencodez.mcperks.userapi.MCPerksUser;

import lombok.Getter;
import lombok.Setter;

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

	DoubleJumpUp,

	Commands,

	IncreaseMaxHealth,

	MoveSpeed,

	IncreaseStrength,

	IncreaseLuck,

	IncreaseMiningArea,

	BoneMealAura,

	TempPermission,

	FlyBoost,

	NoItemDamage,

	NoHunger,

	KeepInv,

	AutoPickupItems,

	TreeHarvest,

	AutoPlant;

	public static Effect fromString(String str) {
		for (Effect eff : values()) {
			if (eff.toString().equalsIgnoreCase(str)) {
				return eff;
			}
		}
		return null;
	}

	public void removeEffect(Perk perk, ArrayList<String> players1) {
		ArrayList<Player> players = new ArrayList<Player>();
		for (String uuid : players1) {
			Player player = Bukkit.getPlayer(UUID.fromString(uuid));
			if (player != null) {
				players.add(player);
			} else {
				MCPerksMain.plugin.getEffectHandler().addOfflineCheck(perk, uuid, this);
			}
		}

		switch (this) {
		case Fly:
			new FlyEffect().disableFly(perk.getFlyWorlds(), players);
			break;
		case IncreaseMaxHealth:
			for (Player p : players) {
				for (AttributeModifier modifier : p.getAttribute(Attribute.GENERIC_MAX_HEALTH).getModifiers()) {
					if (MCPerksMain.plugin.getEffectHandler().isActive(modifier.getUniqueId())) {
						p.getAttribute(Attribute.GENERIC_MAX_HEALTH).removeModifier(modifier);
						MCPerksMain.plugin.getEffectHandler().remove(modifier.getUniqueId());
					}
				}
			}
			break;
		case IncreaseStrength:
			for (Player p : players) {
				for (AttributeModifier modifier : p.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).getModifiers()) {
					if (MCPerksMain.plugin.getEffectHandler().isActive(modifier.getUniqueId())) {
						p.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).removeModifier(modifier);
						MCPerksMain.plugin.getEffectHandler().remove(modifier.getUniqueId());
					}
				}
			}
			break;
		case IncreaseLuck:
			for (Player p : players) {
				for (AttributeModifier modifier : p.getAttribute(Attribute.GENERIC_LUCK).getModifiers()) {
					if (MCPerksMain.plugin.getEffectHandler().isActive(modifier.getUniqueId())) {
						p.getAttribute(Attribute.GENERIC_LUCK).removeModifier(modifier);
						MCPerksMain.plugin.getEffectHandler().remove(modifier.getUniqueId());
					}
				}
			}
			break;
		case MoveSpeed:
			for (Player p : players) {
				for (AttributeModifier modifier : p.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getModifiers()) {
					if (MCPerksMain.plugin.getEffectHandler().isActive(modifier.getUniqueId())) {
						p.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).removeModifier(modifier);
						MCPerksMain.plugin.getEffectHandler().remove(modifier.getUniqueId());
					}
				}
			}
			break;
		case Potions:
			for (Player p : players) {
				for (String potion : perk.getPotions()) {
					try {
						if (p.hasPotionEffect(PotionEffectType.getByName(potion))) {
							Bukkit.getScheduler().runTask(MCPerksMain.plugin, new Runnable() {

								@Override
								public void run() {
									p.removePotionEffect(PotionEffectType.getByName(potion));
								}
							});

						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				}

			}
			break;
		case TempPermission:
			if (!getPermission().isEmpty()) {
				for (Player p : players) {
					perk.getPlugin().getMcperksUserManager().getMCPerksUser(p).removePermission(getPermission());
				}
			}
			break;
		case Commands:
			for (Player p : players) {
				HashMap<String, String> placeholders = new HashMap<String, String>();
				placeholders.put("player", p.getName());
				MiscUtils.getInstance().executeConsoleCommands(p, perk.getDisableCommands(), placeholders);
			}
			break;
		default:
			break;

		}
	}

	@Getter
	@Setter
	private double modifier = 1;

	@Getter
	@Setter
	private String permission = "";

	public void runEffect(Perk perk, MCPerksUser user, ArrayList<String> uuids) {
		ArrayList<Player> players = new ArrayList<Player>();
		for (String uuid : uuids) {
			Player player = Bukkit.getPlayer(UUID.fromString(uuid));
			if (player != null) {
				players.add(player);
			}
		}
		switch (this) {
		case CureHunger:
			Bukkit.getScheduler().runTask(MCPerksMain.plugin, new Runnable() {

				@Override
				public void run() {
					for (Player player : players) {
						player.setFoodLevel(20);
					}
				}
			});
			break;
		case NoHunger:
			Bukkit.getScheduler().runTask(MCPerksMain.plugin, new Runnable() {

				@Override
				public void run() {
					for (Player player : players) {
						player.setFoodLevel(20);
					}
				}
			});
			break;
		case HealAll:
			Bukkit.getScheduler().runTask(MCPerksMain.plugin, new Runnable() {

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
						.send(MCPerksMain.plugin.getMcperksUserManager().getMCPerksUser(p));

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
				AttributeModifier m = new AttributeModifier("MCPERKS", getModifier(), Operation.ADD_NUMBER);
				MCPerksMain.plugin.getEffectHandler().add(m.getUniqueId());
				p.getAttribute(Attribute.GENERIC_MAX_HEALTH).addModifier(m);
			}
			break;
		case IncreaseStrength:
			for (Player p : players) {
				AttributeModifier m = new AttributeModifier("MCPERKS", getModifier(), Operation.ADD_NUMBER);
				MCPerksMain.plugin.getEffectHandler().add(m.getUniqueId());
				p.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).addModifier(m);
			}
			break;
		case IncreaseLuck:
			for (Player p : players) {
				AttributeModifier m = new AttributeModifier("MCPERKS", getModifier(), Operation.ADD_NUMBER);
				MCPerksMain.plugin.getEffectHandler().add(m.getUniqueId());
				p.getAttribute(Attribute.GENERIC_LUCK).addModifier(m);
			}
			break;
		case MoveSpeed:
			for (Player p : players) {
				AttributeModifier m = new AttributeModifier("MCPERKS", getModifier(), Operation.MULTIPLY_SCALAR_1);
				MCPerksMain.plugin.getEffectHandler().add(m.getUniqueId());
				p.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).addModifier(m);
			}
			break;
		case TempPermission:
			if (!getPermission().isEmpty()) {
				for (Player p : players) {
					perk.getPlugin().getMcperksUserManager().getMCPerksUser(p).addPermission(getPermission());
				}
			}
			break;
		default:
			break;

		}
	}

	public boolean usesIncreasePercentage() {
		return isEffect(Effect.IncreaseMobDrops, Effect.DoubleExperience, Effect.Farmer, Effect.Fortune, Effect.McmmoXP,
				Effect.HeadDropper, Effect.IncreaseStrength, Effect.IncreaseMiningArea);
	}

	public boolean usesModifier() {
		return isEffect(Effect.IncreaseMobDrops, Effect.DoubleExperience, Effect.Farmer, Effect.Fortune, Effect.McmmoXP,
				Effect.HeadDropper, Effect.IncreaseStrength, Effect.Florist, Effect.UnderWaterFlorist,
				Effect.IncreaseMiningArea);
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
