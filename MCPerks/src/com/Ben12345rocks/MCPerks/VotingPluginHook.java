package com.Ben12345rocks.MCPerks;

import java.util.HashMap;

import org.bukkit.entity.Player;

import com.Ben12345rocks.MCPerks.UserAPI.UserManager;
import com.Ben12345rocks.VotingPlugin.VotingPluginHooks;
import com.Ben12345rocks.VotingPlugin.AdvancedCore.Rewards.Reward;
import com.Ben12345rocks.VotingPlugin.AdvancedCore.Rewards.Injected.RewardInjectInt;
import com.Ben12345rocks.VotingPlugin.AdvancedCore.UserManager.User;
import com.Ben12345rocks.VotingPlugin.AdvancedCore.Util.EditGUI.EditGUIButton;
import com.Ben12345rocks.VotingPlugin.AdvancedCore.Util.EditGUI.ValueTypes.EditGUIValueNumber;

public class VotingPluginHook {
	private static VotingPluginHook instance = new VotingPluginHook();

	public static VotingPluginHook getInstance() {
		return instance;
	}

	public void loadRewards() {
		VotingPluginHooks.getInstance().addCustomReward(new RewardInjectInt("Activations") {

			@Override
			public String onRewardRequest(Reward reward, User user, int value, HashMap<String, String> placeholders) {
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

		for (final String perk : Main.plugin.getPerkHandler().getLoadedPerks().keySet()) {
			VotingPluginHooks.getInstance().addCustomReward(new RewardInjectInt("PerkActivations." + perk) {

				@Override
				public String onRewardRequest(Reward reward, User user, int value,
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
