package com.bencodez.mcperks.rewardedit;

import org.bukkit.entity.Player;

import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.votingplugin.advancedcore.api.inventory.editgui.EditGUI;
import com.bencodez.votingplugin.advancedcore.api.rewards.RewardEditData;

public abstract class VotingPluginRewardEditActivations extends com.bencodez.votingplugin.advancedcore.api.rewards.editbuttons.RewardEdit {
	public VotingPluginRewardEditActivations() {
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
