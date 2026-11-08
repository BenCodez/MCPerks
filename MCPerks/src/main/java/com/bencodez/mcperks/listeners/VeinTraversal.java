package com.bencodez.mcperks.listeners;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.block.Block;

/** Bounded connected-ore search used before owner-scheduled native breaks. */
final class VeinTraversal {
    private record Point(int x, int y, int z) {}
    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private VeinTraversal() {}

    static List<Block> connected(Block origin, Material ore, int maximum, int distance,
                                 BooleanSupplier folia, Predicate<Block> owns) {
        List<Block> found = new ArrayList<>();
        Set<Point> visited = new HashSet<>();
        ArrayDeque<Point> queue = new ArrayDeque<>();
        Point start = new Point(origin.getX(), origin.getY(), origin.getZ());
        queue.add(start);
        while (!queue.isEmpty() && found.size() < maximum - 1 && visited.size() < 512) {
            Point p = queue.remove();
            if (!visited.add(p)) continue;
            if (Math.abs(p.x - start.x) > distance || Math.abs(p.y - start.y) > distance
                    || Math.abs(p.z - start.z) > distance || p.y < origin.getWorld().getMinHeight()
                    || p.y >= origin.getWorld().getMaxHeight()) continue;
            if (!origin.getWorld().isChunkLoaded(p.x >> 4, p.z >> 4)) continue;
            if (folia.getAsBoolean() && ((p.x >> 4) != (start.x >> 4) || (p.z >> 4) != (start.z >> 4))) continue;
            Block block = origin.getWorld().getBlockAt(p.x, p.y, p.z);
            if (!owns.test(block) || block.getType() != ore || block.hasMetadata("Placed")) continue;
            if (!p.equals(start)) found.add(block);
            for (int[] offset : NEIGHBORS) {
                queue.add(new Point(p.x + offset[0], p.y + offset[1], p.z + offset[2]));
            }
        }
        return found;
    }
}
