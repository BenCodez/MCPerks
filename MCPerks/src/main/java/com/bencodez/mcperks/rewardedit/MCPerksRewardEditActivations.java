package com.bencodez.mcperks.rewardedit;

import org.bukkit.entity.Player;

import com.bencodez.advancedcore.api.inventory.editgui.EditGUI;
import com.bencodez.advancedcore.api.rewards.RewardEditData;
import com.bencodez.advancedcore.api.rewards.editbuttons.RewardEdit;
import com.bencodez.mcperks.MCPerksMain;

public abstract class MCPerksRewardEditActivations extends RewardEdit {
	public MCPerksRewardEditActivations() {
	}

	public void open(Player player, RewardEditData reward) {
		EditGUI inv = new EditGUI("Edit ActionBar: " + reward.getName());
		inv.addData("Reward", reward);

		inv.addButton(getIntButton("Activations", reward));
		for (final String perk : MCPerksMain.plugin.getPerkHandler().getLoadedPerks().keySet()) {
			inv.addButton(getIntButton("PerkActivations." + perk, reward));
		}

		inv.addButton(getBackButton(reward));

		inv.openInventory(player);
	}

}
