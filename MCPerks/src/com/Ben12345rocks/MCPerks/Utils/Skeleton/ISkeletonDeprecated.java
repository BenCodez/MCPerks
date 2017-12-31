package com.Ben12345rocks.MCPerks.Utils.Skeleton;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Skeleton.SkeletonType;

@SuppressWarnings("deprecation")
public class ISkeletonDeprecated implements ISkeleton {

	@Override
	public boolean isWitherSkeletonType(LivingEntity e) {
		if (e instanceof Skeleton) {
			if (((Skeleton) e).getSkeletonType().equals(SkeletonType.WITHER)) {
				return true;
			}
		}
		return false;
	}

}
