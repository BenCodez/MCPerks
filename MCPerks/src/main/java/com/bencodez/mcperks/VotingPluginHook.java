package com.bencodez.mcperks;

import java.util.HashMap;

import org.bukkit.OfflinePlayer;

import com.bencodez.advancedcore.api.javascript.JavascriptPlaceholderRequest;
import com.bencodez.mcperks.rewardedit.VotingPluginRewardEditActivations;
import com.bencodez.votingplugin.VotingPluginHooks;
import com.bencodez.votingplugin.VotingPluginMain;
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
				return VotingPluginMain.plugin.getVotingPluginUserManager().getVotingPluginUser(player);
			}
		});

		VotingPluginHooks.getInstance().addCustomReward(new RewardInjectInt("Activations") {
            @Override public boolean supportsAsyncRequest() { return true; }
            @Override public boolean requiresConfiguredDataForAsync() { return true; }
            public java.util.concurrent.CompletionStage<Void> onReplayCheckpointPersisted(Reward reward, AdvancedCoreUser user,
                    String occurrenceId, String injectionKey, boolean selectionPresent) {
                if (!selectionPresent) return java.util.concurrent.CompletableFuture.completedFuture(null);
                return onReplayCheckpointPersisted(reward, user, occurrenceId, injectionKey);
            }
            @Override public boolean hasPendingReplayWork(HashMap<String, String> placeholders) {
                try { return Reward.hasReplaySelection(placeholders); }
                catch (NoSuchMethodError unavailable) { return false; }
            }
            @Override public java.util.concurrent.CompletionStage<Object> onRewardRequestAsync(Reward reward, AdvancedCoreUser user,
                    org.bukkit.configuration.ConfigurationSection data, HashMap<String, String> placeholders) {
                if (data.isInt(getPath()) || (isAlwaysForce() && data.contains(getPath(), true)) || isAlwaysForceNoData())
                    return super.onRewardRequestAsync(reward, user, data, placeholders);
                if (!hasPendingReplayWork(placeholders)) return java.util.concurrent.CompletableFuture.completedFuture(null);
                return onRewardRequestAsync(reward, user, 0, placeholders).thenApply(result -> (Object) result);
            }

			@Override
			public java.util.concurrent.CompletionStage<String> onRewardRequestAsync(Reward reward, AdvancedCoreUser user, int value,
					HashMap<String, String> placeholders) {
				var plugin = MCPerksMain.plugin;
				var target = plugin.getMcperksUserManager().getMCPerksUser(user.getPlayerName());
				String identity; int selected; java.util.concurrent.CompletionStage<Void> metadata;
				try {
					identity = Reward.currentReplaySideEffectOccurrenceId();
					selected = Integer.parseInt(Reward.replaySelection(placeholders, () -> Integer.toString(value)));
					metadata = Reward.persistReplayMetadataAsync(VotingPluginMain.plugin, placeholders);
				} catch (NoSuchMethodError unavailable) { return target.addActivationAsync(value).thenApply(ignored -> null); }
				boolean shared = plugin.getUserManager().getDataManager().usesSharedSqlStorage(plugin.getStorageType());
				return metadata.thenCompose(ignored ->
						shared && identity != null ? target.addReplayActivationAsync(null, selected, "votingplugin:" + identity)
								: target.addActivationAsync(selected)).thenApply(ignored -> null);
			}
			@Override
			public java.util.concurrent.CompletionStage<Void> onReplayCheckpointPersisted(Reward reward, AdvancedCoreUser user,
					String occurrenceId, String injectionKey) {
				var plugin = MCPerksMain.plugin;
				if (!plugin.getUserManager().getDataManager().usesSharedSqlStorage(plugin.getStorageType()))
					return super.onReplayCheckpointPersisted(reward, user, occurrenceId, injectionKey);
				String identity;
				try { identity = Reward.replaySideEffectOccurrenceId(occurrenceId, injectionKey); }
				catch (NoSuchMethodError unavailable) { return java.util.concurrent.CompletableFuture.completedFuture(null); }
				if (identity == null) return super.onReplayCheckpointPersisted(reward, user, occurrenceId, injectionKey);
				return plugin.getMcperksUserManager().getMCPerksUser(user.getPlayerName()).checkpointReplayActivationAsync("votingplugin:" + identity);
			}

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
            @Override public boolean supportsAsyncRequest() { return true; }
            @Override public boolean requiresConfiguredDataForAsync() { return true; }
            public java.util.concurrent.CompletionStage<Void> onReplayCheckpointPersisted(Reward reward, AdvancedCoreUser user,
                    String occurrenceId, String injectionKey, boolean selectionPresent) {
                if (!selectionPresent) return java.util.concurrent.CompletableFuture.completedFuture(null);
                return onReplayCheckpointPersisted(reward, user, occurrenceId, injectionKey);
            }
            @Override public boolean hasPendingReplayWork(HashMap<String, String> placeholders) {
                try { return Reward.hasReplaySelection(placeholders); }
                catch (NoSuchMethodError unavailable) { return false; }
            }
            @Override public java.util.concurrent.CompletionStage<Object> onRewardRequestAsync(Reward reward, AdvancedCoreUser user,
                    org.bukkit.configuration.ConfigurationSection data, HashMap<String, String> placeholders) {
                if (data.isInt(getPath()) || (isAlwaysForce() && data.contains(getPath(), true)) || isAlwaysForceNoData())
                    return super.onRewardRequestAsync(reward, user, data, placeholders);
                if (!hasPendingReplayWork(placeholders)) return java.util.concurrent.CompletableFuture.completedFuture(null);
                return onRewardRequestAsync(reward, user, 0, placeholders).thenApply(result -> (Object) result);
            }

				@Override
				public java.util.concurrent.CompletionStage<String> onRewardRequestAsync(Reward reward, AdvancedCoreUser user, int value,
						HashMap<String, String> placeholders) {
					var plugin = MCPerksMain.plugin;
					var target = plugin.getMcperksUserManager().getMCPerksUser(user.getPlayerName());
					String identity; int selected; java.util.concurrent.CompletionStage<Void> metadata;
					try {
						identity = Reward.currentReplaySideEffectOccurrenceId();
						selected = Integer.parseInt(Reward.replaySelection(placeholders, () -> Integer.toString(value)));
						metadata = Reward.persistReplayMetadataAsync(VotingPluginMain.plugin, placeholders);
					} catch (NoSuchMethodError unavailable) { return target.addActivationAsync(perk, value).thenApply(ignored -> null); }
					boolean shared = plugin.getUserManager().getDataManager().usesSharedSqlStorage(plugin.getStorageType());
					return metadata.thenCompose(ignored ->
							shared && identity != null ? target.addReplayActivationAsync(perk, selected, "votingplugin:" + identity)
									: target.addActivationAsync(perk, selected)).thenApply(ignored -> null);
				}
				@Override
				public java.util.concurrent.CompletionStage<Void> onReplayCheckpointPersisted(Reward reward, AdvancedCoreUser user,
						String occurrenceId, String injectionKey) {
					var plugin = MCPerksMain.plugin;
					if (!plugin.getUserManager().getDataManager().usesSharedSqlStorage(plugin.getStorageType()))
						return super.onReplayCheckpointPersisted(reward, user, occurrenceId, injectionKey);
					String identity;
					try { identity = Reward.replaySideEffectOccurrenceId(occurrenceId, injectionKey); }
					catch (NoSuchMethodError unavailable) { return java.util.concurrent.CompletableFuture.completedFuture(null); }
					if (identity == null) return super.onReplayCheckpointPersisted(reward, user, occurrenceId, injectionKey);
					return plugin.getMcperksUserManager().getMCPerksUser(user.getPlayerName()).checkpointReplayActivationAsync("votingplugin:" + identity);
				}

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
