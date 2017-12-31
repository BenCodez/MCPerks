package com.Ben12345rocks.MCPerks.Objects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.Ben12345rocks.AdvancedCore.Objects.RewardHandler;
import com.Ben12345rocks.AdvancedCore.Util.Misc.MiscUtils;
import com.Ben12345rocks.MCPerks.Configs.ConfigPerks;
import com.Ben12345rocks.MCPerks.Effects.CureHungerEffect;
import com.Ben12345rocks.MCPerks.Effects.CureSpellsEffect;
import com.Ben12345rocks.MCPerks.Effects.FlyEffect;
import com.Ben12345rocks.MCPerks.Effects.HealAllEffect;
import com.Ben12345rocks.MCPerks.Effects.PotionEffect;

public enum Effect {

	IncreaseMobDrops,

	CureHunger,

	Florist,

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

	Rewards, God, DoubleJump, Commands;

	public static Effect fromString(String str) {
		for (Effect eff : values()) {
			if (eff.toString().equalsIgnoreCase(str)) {
				return eff;
			}
		}
		return null;
	}

	public void removeEffect(ArrayList<String> players) {
		switch (this) {
		case Fly:
			new FlyEffect().disableFly(players);
		default:
			break;

		}
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
			new CureHungerEffect().healPlayersHunger(players);
			break;
		case HealAll:
			new HealAllEffect().healPlayers(players);
			break;
		case CureSpells:
			new CureSpellsEffect().clearBadEffects(players);
			break;
		case Fly:
			new FlyEffect().enableFly(players);
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
				RewardHandler.getInstance().giveReward(UserManager.getInstance().getMCPerksUser(p), perk.getPerk(),
						ConfigPerks.getInstance().getData(perk.getPerk()),
						ConfigPerks.getInstance().getPerkRewardsPath(perk.getPerk()));
			}

			break;
		case Commands:
			for (Player p : players) {
				HashMap<String, String> placeholders = new HashMap<String, String>();
				placeholders.put("player", p.getName());
				MiscUtils.getInstance().executeConsoleCommands(p, perk.getCommands(), placeholders);
			}
			break;
		default:
			break;

		}
	}
}
