package com.bencodez.mcperks;

import java.util.HashMap;

import org.bukkit.OfflinePlayer;

import com.bencodez.advancedcore.api.javascript.JavascriptPlaceholderRequest;
import com.bencodez.mcperks.rewardedit.VotingPluginRewardEditActivations;
import com.bencodez.votingplugin.VotingPluginHooks;
import com.bencodez.votingplugin.advancedcore.api.inventory.BInventory.ClickEvent;
import com.bencodez.votingplugin.advancedcore.api.inventory.editgui.EditGUIButton;
import com.bencodez.votingplugin.advancedcore.api.inventory.editgui.valuetypes.EditGUIValueInventory;
import com.bencodez.votingplugin.advancedcore.api.rewards.Reward;
import com.bencodez.votingplugin.advancedcore.api.rewards.RewardEditData;
import com.bencodez.votingplugin.advancedcore.api.rewards.injected.RewardInjectInt;
import com.bencodez.votingplugin.advancedcore.api.user.AdvancedCoreUser;

public class VotingPluginHook {
	private static VotingPluginHook instance = new VotingPluginHook();

	public static VotingPluginHook getInstance() {
		return instance;
	}

	public void loadRewards() {
		MCPerksMain.plugin.getJavascriptEngineRequests().add(new JavascriptPlaceholderRequest("VotingPluginUser") {

			@Override
			public Object getObject(OfflinePlayer player) {
				return com.bencodez.votingplugin.user.UserManager.getInstance().getVotingPluginUser(player);
			}
		});

		VotingPluginHooks.getInstance().addCustomReward(new RewardInjectInt("Activations") {

			@Override
			public String onRewardRequest(Reward reward, AdvancedCoreUser user, int value,
					HashMap<String, String> placeholders) {
				MCPerksMain.plugin.getMcperksUserManager().getMCPerksUser(user.getPlayerName()).addActivation(value);
				return null;
			}
		}.addEditButton(new EditGUIButton(new EditGUIValueInventory("Activations") {

			@Override
			public void openInventory(ClickEvent event) {
				RewardEditData reward = (RewardEditData) getInv().getData("Reward");
				new VotingPluginRewardEditActivations() {

					@Override
					public void setVal(String key, Object value) {
						RewardEditData reward = (RewardEditData) getInv().getData("Reward");
						reward.setValue(getKey(), value);
						VotingPluginHooks.getInstance().getMainClass().reload();
					}
				}.open(event.getPlayer(), reward);
			}
		})));

		for (final String perk : MCPerksMain.plugin.getPerkHandler().getLoadedPerks().keySet()) {
			VotingPluginHooks.getInstance().addCustomReward(new RewardInjectInt("PerkActivations." + perk) {

				@Override
				public String onRewardRequest(Reward reward, AdvancedCoreUser user, int value,
						HashMap<String, String> placeholders) {
					MCPerksMain.plugin.getMcperksUserManager().getMCPerksUser(user.getPlayerName()).addActivation(perk,
							value);
					return null;
				}
			});
		}
	}
}
