package com.shadowplay.agent;

import com.shadowplay.Log;
import com.shadowplay.harness.Interact;
import com.shadowplay.harness.InventoryHelper;
import com.shadowplay.perception.LightMonitor;
import com.shadowplay.perception.WorldScanner;
import com.shadowplay.plan.PlanExecutor;
import com.shadowplay.skills.SkillResult;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * While enabled: when it is dark at the player's feet and no torch is within the spacing, pause the current step,
 * place a torch on a nearby wall (head height preferred, floor as a fallback), then restart the step.
 */
public final class TorchReflex {
	private static final int CHECK_INTERVAL_TICKS = 5;
	private static final int NO_SPOT_COOLDOWN_TICKS = 20;
	private static final int SEARCH_RADIUS = 3;

	private Interact.PlaceTask placing;
	private int sinceCheck;
	private int cooldown;
	private boolean warnedNoTorches;

	/** Returns true while the reflex is using the player this tick, so the executor must not tick. */
	public boolean tick(TaskContext ctx, PlanExecutor executor) {
		if (placing != null) {
			SkillResult result = placing.tick();
			if (result.status() == SkillResult.Status.RUNNING) {
				return true;
			}
			if (result.status() == SkillResult.Status.SUCCESS) {
				ctx.placedTorches.add(placing.target());
				Log.info("Torch reflex: placed a torch at {}", placing.target().toShortString());
			} else {
				Log.info("Torch reflex: couldn't place a torch ({})", result.reason());
				cooldown = NO_SPOT_COOLDOWN_TICKS;
			}
			placing = null;
			executor.resumeCurrent();
			return false;
		}
		if (!ctx.torchReflexEnabled) {
			return false;
		}
		if (cooldown > 0) {
			cooldown--;
			return false;
		}
		if (++sinceCheck < CHECK_INTERVAL_TICKS) {
			return false;
		}
		sinceCheck = 0;

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.currentScreen != null) {
			return false;
		}
		BlockPos feet = client.player.getBlockPos();
		if (!LightMonitor.isDark(feet, ctx.torchLightThreshold)) {
			return false;
		}
		if (WorldScanner.nearest(s -> s.isOf(Blocks.TORCH) || s.isOf(Blocks.WALL_TORCH), ctx.torchSpacing) != null) {
			return false;
		}
		if (InventoryHelper.count(Items.TORCH) == 0) {
			if (!warnedNoTorches) {
				warnedNoTorches = true;
				Log.info("Torch reflex: out of torches");
			}
			return false;
		}
		Spot spot = findSpot(client.player, client.world);
		if (spot == null) {
			cooldown = NO_SPOT_COOLDOWN_TICKS;
			return false;
		}
		executor.pauseCurrent();
		placing = new Interact.PlaceTask(spot.support, spot.face, Items.TORCH);
		return true;
	}

	/** Drops a torch placement in progress (on interrupt / stop). */
	public void cancel() {
		placing = null;
		cooldown = 0;
		sinceCheck = 0;
	}

	private record Spot(BlockPos support, Direction face) {
	}

	/** A visible, reachable solid face whose neighbor is air, preferring walls at head height. */
	private static Spot findSpot(ClientPlayerEntity player, ClientWorld world) {
		BlockPos feet = player.getBlockPos();
		BlockPos head = feet.up();
		Vec3d eye = player.getEyePos();
		double reach = player.getBlockInteractionRange() - 0.5;
		Spot best = null;
		double bestScore = Double.MAX_VALUE;
		int r = SEARCH_RADIUS;
		for (BlockPos support : BlockPos.iterate(feet.add(-r, -1, -r), feet.add(r, 2, r))) {
			if (!world.getBlockState(support).isSolidBlock(world, support)) {
				continue;
			}
			for (Direction face : Direction.values()) {
				if (face == Direction.DOWN) {
					continue;
				}
				BlockPos torchPos = support.offset(face);
				if (torchPos.equals(feet) || torchPos.equals(head) || !world.getBlockState(torchPos).isAir()
						|| !world.getBlockState(support).isSideSolidFullSquare(world, support, face)) {
					continue;
				}
				Vec3d center = Vec3d.ofCenter(support).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
				double dist = eye.distanceTo(center);
				if (dist > reach || !visible(world, player, eye, support, face)) {
					continue;
				}
				// Walls beat floors; head height beats feet height; closer is better.
				double score = dist
						+ (face == Direction.UP ? 4 : 0)
						+ Math.abs(torchPos.getY() - head.getY()) * 1.5;
				if (score < bestScore) {
					bestScore = score;
					best = new Spot(support.toImmutable(), face);
				}
			}
		}
		return best;
	}

	private static boolean visible(ClientWorld world, ClientPlayerEntity player, Vec3d eye, BlockPos support, Direction face) {
		Vec3d center = Vec3d.ofCenter(support);
		Vec3d onFace = center.add(face.getOffsetX() * 0.4, face.getOffsetY() * 0.4, face.getOffsetZ() * 0.4);
		BlockHitResult hit = world.raycast(new RaycastContext(eye, onFace, RaycastContext.ShapeType.OUTLINE,
				RaycastContext.FluidHandling.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(support) && hit.getSide() == face;
	}
}
