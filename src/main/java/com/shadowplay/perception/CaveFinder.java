package com.shadowplay.perception;

import com.shadowplay.Log;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds a cave near the player without relying on cave_air: dark air below the surface heightmap is flood-filled
 * into connected volumes; a cave is a volume of at least MIN_VOLUME blocks that touches open sky somewhere (its
 * mouth). Returns a standable entrance at the mouth and a standable point a few blocks deeper.
 */
public final class CaveFinder {
	public static final int DEFAULT_RADIUS = 40;
	private static final int FILL_CAP = 4000;
	private static final int MIN_VOLUME = 150;
	private static final int TOTAL_WORK_CAP = 60000;
	private static final int INSIDE_DEPTH = 6;
	private static final int OPEN_AIR_ALLOWANCE = 4;

	public record Cave(BlockPos entrance, BlockPos inside, int volume) {
	}

	private CaveFinder() {
	}

	/** The nearest cave within radius of the player, or null. */
	public static Cave find(int radius) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientWorld world = client.world;
		BlockPos center = client.player.getBlockPos();

		List<BlockPos> candidates = darkAirCandidates(world, center, radius);
		Set<Long> visited = new HashSet<>();
		int work = 0;
		for (BlockPos seed : candidates) {
			if (visited.contains(seed.asLong())) {
				continue;
			}
			Fill fill = fill(world, seed, visited);
			work += fill.size;
			if (fill.size >= MIN_VOLUME && fill.mouth != null) {
				BlockPos entrance = standableNear(world, fill.mouth, 3);
				if (entrance == null) {
					continue;
				}
				BlockPos inside = deeperPoint(world, entrance, INSIDE_DEPTH);
				Log.info("CaveFinder: cave of {}{} blocks, entrance {}, inside {}", fill.size,
						fill.size >= FILL_CAP ? "+" : "", entrance.toShortString(), inside.toShortString());
				return new Cave(entrance, inside, fill.size);
			}
			if (work > TOTAL_WORK_CAP) {
				break;
			}
		}
		Log.info("CaveFinder: no cave within {} blocks ({} candidates, {} blocks filled)", radius, candidates.size(), work);
		return null;
	}

	/** For a known entrance (config override): just find the point a few blocks deeper. */
	public static Cave fromEntrance(BlockPos entrance) {
		ClientWorld world = MinecraftClient.getInstance().world;
		return new Cave(entrance, deeperPoint(world, entrance, INSIDE_DEPTH), 0);
	}

	/** 6.3.1: air blocks below the surface with no sky light, nearest first. */
	private static List<BlockPos> darkAirCandidates(ClientWorld world, BlockPos center, int radius) {
		List<BlockPos> found = new ArrayList<>();
		long r2 = (long) radius * radius;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int minY = Math.max(world.getBottomY(), center.getY() - radius);
		for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
			for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
				long dx = x - center.getX();
				long dz = z - center.getZ();
				if (dx * dx + dz * dz > r2 || !world.isChunkLoaded(x >> 4, z >> 4)) {
					continue;
				}
				int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
				int maxY = Math.min(top - 2, center.getY() + radius);
				for (int y = minY; y <= maxY; y++) {
					pos.set(x, y, z);
					if (world.getBlockState(pos).isAir() && world.getLightLevel(LightType.SKY, pos) == 0) {
						found.add(pos.toImmutable());
					}
				}
			}
		}
		found.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
		return found;
	}

	private static final class Fill {
		int size;
		BlockPos mouth;
		double mouthDistance = Double.MAX_VALUE;
	}

	/** 6.3.2: flood-fill covered air from seed (capped); remembers the covered block nearest the player that touches open sky. */
	private static Fill fill(ClientWorld world, BlockPos seed, Set<Long> visited) {
		Fill fill = new Fill();
		BlockPos player = MinecraftClient.getInstance().player.getBlockPos();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(seed);
		visited.add(seed.asLong());
		while (!queue.isEmpty() && fill.size < FILL_CAP) {
			BlockPos pos = queue.poll();
			fill.size++;
			for (Direction d : Direction.values()) {
				BlockPos next = pos.offset(d);
				if (!world.getBlockState(next).isAir()) {
					continue;
				}
				if (isOpenSky(world, next)) {
					double dist = pos.getSquaredDistance(player);
					if (dist < fill.mouthDistance) {
						fill.mouthDistance = dist;
						fill.mouth = pos;
					}
					continue;
				}
				if (visited.add(next.asLong())) {
					queue.add(next);
				}
			}
		}
		return fill;
	}

	private static boolean isOpenSky(ClientWorld world, BlockPos pos) {
		return pos.getY() >= world.getTopY(Heightmap.Type.WORLD_SURFACE, pos.getX(), pos.getZ());
	}

	/** The standable spot nearest to pos within radius, or null. */
	private static BlockPos standableNear(ClientWorld world, BlockPos pos, int radius) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.iterate(pos.add(-radius, -radius, -radius), pos.add(radius, radius, radius))) {
			if (WorldScanner.isStandable(p)) {
				double d = p.getSquaredDistance(pos);
				if (d < bestDist) {
					bestDist = d;
					best = p.toImmutable();
				}
			}
		}
		return best;
	}

	/**
	 * Walks (BFS over air) from start into covered air; open-sky blocks are allowed only right next to start so the
	 * search cannot leave the cave. Returns the standable block closest to `depth` steps in, else the deepest one.
	 */
	private static BlockPos deeperPoint(ClientWorld world, BlockPos start, int depth) {
		Map<Long, Integer> dist = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		dist.put(start.asLong(), 0);
		BlockPos best = null;
		int bestScore = Integer.MAX_VALUE;
		BlockPos deepest = start;
		int deepestDist = 0;
		while (!queue.isEmpty() && dist.size() < FILL_CAP) {
			BlockPos pos = queue.poll();
			int d = dist.get(pos.asLong());
			if (d > 0 && WorldScanner.isStandable(pos)) {
				if (d >= depth && d - depth < bestScore) {
					bestScore = d - depth;
					best = pos;
				}
				if (d > deepestDist) {
					deepestDist = d;
					deepest = pos;
				}
			}
			if (best != null && d > depth + 4) {
				break;
			}
			for (Direction dir : Direction.values()) {
				BlockPos next = pos.offset(dir);
				if (dist.containsKey(next.asLong()) || !world.getBlockState(next).isAir()) {
					continue;
				}
				if (d + 1 > OPEN_AIR_ALLOWANCE && isOpenSky(world, next)) {
					continue;
				}
				dist.put(next.asLong(), d + 1);
				queue.add(next);
			}
		}
		return best != null ? best : deepest;
	}
}
