package com.bencodez.mcperks;

import java.util.HashMap;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.bencodez.advancedcore.api.javascript.JavascriptPlaceholderRequest;
import com.bencodez.mcperks.userapi.UserManager;
import com.bencodez.votingplugin.VotingPluginHooks;
import com.bencodez.votingplugin.advancedcore.api.inventory.editgui.EditGUIButton;
import com.bencodez.votingplugin.advancedcore.api.inventory.editgui.valuetypes.EditGUIValueNumber;
import com.bencodez.votingplugin.advancedcore.api.rewards.Reward;
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
			public String onRewardRequest(Reward reward, AdvancedCoreUser user, int value, HashMap<String, String> placeholders) {
				UserManager.getInstance().getMCPerksUser(user.getPlayerName()).addActivation(value);
				return null;
			}
		}.addEditButton(new EditGUIButton(new EditGUIValueNumber("Activations", null) {

			@Override
			public void setValue(Player player, Number num) {
				Reward reward = (Reward) getInv().getData("Reward");
				reward.getConfig().set(getKey(), num.intValue());
				VotingPluginHooks.getInstance().getMainClass().reload();
			}
		})));

		for (final String perk : MCPerksMain.plugin.getPerkHandler().getLoadedPerks().keySet()) {
			VotingPluginHooks.getInstance().addCustomReward(new RewardInjectInt("PerkActivations." + perk) {

				@Override
				public String onRewardRequest(Reward reward, AdvancedCoreUser user, int value,
						HashMap<String, String> placeholders) {
					UserManager.getInstance().getMCPerksUser(user.getPlayerName()).addActivation(perk, value);
					return null;
				}
			}.addEditButton(new EditGUIButton(new EditGUIValueNumber("PerkActivations." + perk, null) {

				@Override
				public void setValue(Player player, Number num) {
					Reward reward = (Reward) getInv().getData("Reward");
					reward.getConfig().set(getKey(), num.intValue());
					VotingPluginHooks.getInstance().getMainClass().reload();
				}
			})));
		}
	}
}
