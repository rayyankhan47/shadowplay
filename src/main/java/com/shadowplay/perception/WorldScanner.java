package com.shadowplay.perception;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Block search in loaded chunks around the player. Each 16³ chunk section is first checked against its palette
 * (ChunkSection.hasAny), so sections that cannot contain a match cost almost nothing. Results are nearest-first.
 * Scans are not free: callers should run them once per step or every few ticks, not every tick.
 */
public final class WorldScanner {
	public static final int MAX_RADIUS = 48;
	private static final int DEFAULT_MAX_RESULTS = 256;

	private WorldScanner() {
	}

	/** The nearest matching block within radius of the player, or null. */
	public static BlockPos nearest(Predicate<BlockState> stateFilter, int radius) {
		return nearest(stateFilter, null, radius);
	}

	public static BlockPos nearest(Predicate<BlockState> stateFilter, BiPredicate<BlockPos, BlockState> posFilter, int radius) {
		List<BlockPos> found = all(stateFilter, posFilter, radius, DEFAULT_MAX_RESULTS);
		return found.isEmpty() ? null : found.get(0);
	}

	public static List<BlockPos> all(Predicate<BlockState> stateFilter, int radius) {
		return all(stateFilter, null, radius, DEFAULT_MAX_RESULTS);
	}

	/**
	 * All blocks within radius (sphere) of the player's feet whose state passes stateFilter and, if given, whose
	 * position passes posFilter. Sorted nearest-first and capped at maxResults.
	 */
	public static List<BlockPos> all(Predicate<BlockState> stateFilter, BiPredicate<BlockPos, BlockState> posFilter,
			int radius, int maxResults) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		List<BlockPos> found = new ArrayList<>();
		if (player == null || world == null) {
			return found;
		}
		int r = Math.min(radius, MAX_RADIUS);
		long r2 = (long) r * r;
		BlockPos center = player.getBlockPos();
		int minY = Math.max(world.getBottomY(), center.getY() - r);
		int maxY = Math.min(world.getTopY() - 1, center.getY() + r);

		BlockPos.Mutable mutable = new BlockPos.Mutable();
		for (int cx = (center.getX() - r) >> 4; cx <= (center.getX() + r) >> 4; cx++) {
			for (int cz = (center.getZ() - r) >> 4; cz <= (center.getZ() + r) >> 4; cz++) {
				WorldChunk chunk = (WorldChunk) world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
					ChunkSection section = chunk.getSection(world.sectionCoordToIndex(sy));
					if (!section.hasAny(stateFilter)) {
						continue;
					}
					int baseX = ChunkSectionPos.getBlockCoord(cx);
					int baseY = ChunkSectionPos.getBlockCoord(sy);
					int baseZ = ChunkSectionPos.getBlockCoord(cz);
					for (int ly = 0; ly < 16; ly++) {
						int y = baseY + ly;
						if (y < minY || y > maxY) {
							continue;
						}
						for (int lz = 0; lz < 16; lz++) {
							for (int lx = 0; lx < 16; lx++) {
								int x = baseX + lx;
								int z = baseZ + lz;
								long dx = x - center.getX();
								long dy = y - center.getY();
								long dz = z - center.getZ();
								if (dx * dx + dy * dy + dz * dz > r2) {
									continue;
								}
								BlockState state = section.getBlockState(lx, ly, lz);
								if (!stateFilter.test(state)) {
									continue;
								}
								mutable.set(x, y, z);
								if (posFilter == null || posFilter.test(mutable, state)) {
									found.add(mutable.toImmutable());
								}
							}
						}
					}
				}
			}
		}
		found.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
		return found.size() > maxResults ? new ArrayList<>(found.subList(0, maxResults)) : found;
	}

	/** True if at least one of the 6 neighbors is air (including cave air): the block can be seen and mined. */
	public static boolean isExposed(BlockPos pos) {
		ClientWorld world = MinecraftClient.getInstance().world;
		for (Direction d : Direction.values()) {
			if (world.getBlockState(pos.offset(d)).isAir()) {
				return true;
			}
		}
		return false;
	}

	/** Flat ground for placing: solid top face with two air blocks above it. */
	public static boolean isFlatPlaceable(BlockPos pos) {
		ClientWorld world = MinecraftClient.getInstance().world;
		BlockPos above = pos.up();
		return world.getBlockState(pos).isSideSolidFullSquare(world, pos, Direction.UP)
				&& world.getBlockState(above).isAir()
				&& world.getBlockState(above.up()).isAir();
	}

	/** True if the block directly above is water (a lakebed block). */
	public static boolean isUnderwater(BlockPos pos) {
		return MinecraftClient.getInstance().world.getFluidState(pos.up()).isIn(FluidTags.WATER);
	}

	/** Air at feet and head height with a solid block below: somewhere the player can stand. */
	public static boolean isStandable(BlockPos feet) {
		ClientWorld world = MinecraftClient.getInstance().world;
		BlockPos below = feet.down();
		return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
				&& world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
				&& world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP);
	}
}
