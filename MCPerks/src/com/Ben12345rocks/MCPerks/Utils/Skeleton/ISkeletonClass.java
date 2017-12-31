package com.Ben12345rocks.MCPerks.Utils.Skeleton;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.WitherSkeleton;

public class ISkeletonClass implements ISkeleton {

	@Override
	public boolean isWitherSkeletonType(LivingEntity e) {
		if (e instanceof WitherSkeleton) {
			return true;
		}
		return false;
	}

}
