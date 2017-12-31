package com.Ben12345rocks.MCPerks.Utils.Skeleton;

import org.bukkit.entity.LivingEntity;

import com.Ben12345rocks.AdvancedCore.NMSManager.NMSManager;

public class SkeletonHandler {
	/** The instance. */
	static SkeletonHandler instance = new SkeletonHandler();

	/**
	 * Gets the single instance of SkeletonHandler.
	 *
	 * @return single instance of SkeletonHandler
	 */
	public static SkeletonHandler getInstance() {
		return instance;
	}

	private ISkeleton handle;

	/**
	 * Instantiates a new SkeletonHandler.
	 */
	private SkeletonHandler() {
	}

	public boolean isWitherSkeleton(LivingEntity e) {
		if (handle == null) {
			load();
		}
		return handle.isWitherSkeletonType(e);
	}

	public void load() {
		if (handle == null) {
			if (NMSManager.get().getVersion().contains("1_7") || NMSManager.get().getVersion().contains("1_8")
					|| NMSManager.get().getVersion().contains("1_9")
					|| NMSManager.get().getVersion().contains("1_10")) {
				handle = new ISkeletonDeprecated();
			} else {
				handle = new ISkeletonClass();
			}
		}
	}
}
