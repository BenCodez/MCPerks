package com.bencodez.mcperks.tests.listeners;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VeinTraversalTest {
    private World world;
    private final Map<String, Block> blocks = new HashMap<>();

    @BeforeEach void setup() {
        world = mock(World.class);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(256);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0), y = call.getArgument(1), z = call.getArgument(2);
            return blocks.computeIfAbsent(key(x, y, z), ignored -> block(x, y, z, Material.AIR));
        });
    }

    private static String key(int x, int y, int z) { return x + ":" + y + ":" + z; }

    private Block block(int x, int y, int z, Material material) {
        Block block = mock(Block.class);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(material);
        return block;
    }

    private Block ore(int x, int y, int z) {
        Block block = block(x, y, z, Material.DIAMOND_ORE);
        blocks.put(key(x, y, z), block);
        return block;
    }

    @SuppressWarnings("unchecked")
    private List<Block> connected(Block origin, int maximum, int distance,
                                  BooleanSupplier folia, Predicate<Block> owns) throws Exception {
        Class<?> type = Class.forName("com.bencodez.mcperks.listeners.VeinTraversal");
        Method method = type.getDeclaredMethod("connected", Block.class, Material.class,
                int.class, int.class, BooleanSupplier.class, Predicate.class);
        method.setAccessible(true);
        return (List<Block>) method.invoke(null, origin, Material.DIAMOND_ORE, maximum, distance, folia, owns);
    }

    @Test void maximumIncludesOriginalAndSearchUsesConnectedNeighbors() throws Exception {
        Block origin = ore(2, 64, 2), first = ore(3, 64, 2), second = ore(4, 64, 2);
        ore(3, 65, 3); // Diagonal ore is not adjacent to an accepted block in this bounded search.
        List<Block> result = connected(origin, 3, 3, () -> false, ignored -> true);
        assertEquals(2, result.size());
        assertTrue(result.contains(first));
        assertTrue(result.contains(second));
        assertFalse(result.contains(origin));
    }

    @Test void unloadedAdjacentChunkIsNeverRead() throws Exception {
        Block origin = ore(15, 64, 4);
        when(world.isChunkLoaded(eq(1), anyInt())).thenReturn(false);
        assertTrue(connected(origin, 8, 3, () -> false, ignored -> true).isEmpty());
        verify(world, never()).getBlockAt(eq(16), anyInt(), anyInt());
    }

    @Test void distanceAndWorldHeightPreventOutOfBoundsReads() throws Exception {
        Block origin = ore(4, 255, 4), adjacent = ore(5, 255, 4);
        ore(6, 255, 4);
        assertEquals(List.of(adjacent), connected(origin, 8, 1, () -> false, ignored -> true));
        verify(world, never()).getBlockAt(eq(6), anyInt(), anyInt());
        verify(world, never()).getBlockAt(anyInt(), eq(256), anyInt());
    }

    @Test void unloadedOriginIsNotResolvedOrInspected() throws Exception {
        Block origin = ore(4, 64, 4);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        assertTrue(connected(origin, 8, 3, () -> false, ignored -> true).isEmpty());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(origin, never()).getType();
    }

    @Test void oneBlockLimitDoesNotReadNeighborBlocks() throws Exception {
        Block origin = ore(4, 64, 4);
        assertTrue(connected(origin, 1, 3, () -> false, ignored -> true).isEmpty());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test void ownershipPlacedAndFoliaBoundariesStopTraversal() throws Exception {
        Block origin = ore(15, 64, 4), denied = ore(14, 64, 4), placed = ore(15, 65, 4);
        when(placed.hasMetadata("Placed")).thenReturn(true);
        ore(16, 64, 4);
        assertTrue(connected(origin, 8, 3, () -> true, block -> block != denied).isEmpty());
        verify(world, never()).getBlockAt(eq(16), anyInt(), anyInt());
    }
}
