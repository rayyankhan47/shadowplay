package com.shadowplay.harness;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Smooth, natural-looking aiming. One instance per skill: call lookAt once, then tick() every client tick.
 * The target angles are recomputed each tick from the current eye position, so aiming stays correct while the
 * player drifts; after the turn completes, tick() keeps the crosshair locked on the target.
 */
public final class Aim {
	public static final int DEFAULT_TICKS = 4;

	private Vec3d target;
	private int totalTicks;
	private int elapsed;
	private float startYaw;
	private float startPitch;

	public void lookAt(Vec3d point, int ticks) {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		target = point;
		totalTicks = Math.max(1, ticks);
		elapsed = 0;
		if (player != null) {
			startYaw = player.getYaw();
			startPitch = player.getPitch();
		}
	}

	/**
	 * Aims at the given face of a block: its center, or a point near the center when humanize is on. The turn takes
	 * longer the further the head has to move, like a hand on a mouse.
	 */
	public void lookAtBlockFace(BlockPos pos, Direction face) {
		Vec3d point = Humanize.facePoint(pos, face);
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		int ticks = DEFAULT_TICKS;
		if (player != null && Humanize.enabled()) {
			float[] goal = anglesTo(player.getEyePos(), point);
			float turn = Math.abs(MathHelper.wrapDegrees(goal[0] - player.getYaw())) + Math.abs(goal[1] - player.getPitch());
			ticks = MathHelper.clamp(Math.round(turn / 12f), 3, 10);
		}
		lookAt(point, ticks);
	}

	public void tick() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null || target == null) {
			return;
		}
		float[] goal = anglesTo(player.getEyePos(), target);
		if (elapsed < totalTicks) {
			elapsed++;
		}
		float t = (float) elapsed / totalTicks;
		float eased = t * t * (3 - 2 * t); // smoothstep
		float yaw = startYaw + MathHelper.wrapDegrees(goal[0] - startYaw) * eased;
		float pitch = startPitch + (goal[1] - startPitch) * eased;
		player.setYaw(yaw);
		player.setPitch(MathHelper.clamp(pitch, -90f, 90f));
	}

	public boolean isDone() {
		return target != null && elapsed >= totalTicks;
	}

	public void clear() {
		target = null;
	}

	/** True if the crosshair is on the block (and on the given face, if face is not null). */
	public static boolean isTargeting(BlockPos pos, Direction face) {
		HitResult hit = MinecraftClient.getInstance().crosshairTarget;
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
			return false;
		}
		return blockHit.getBlockPos().equals(pos) && (face == null || blockHit.getSide() == face);
	}

	public static Vec3d faceCenter(BlockPos pos, Direction face) {
		return Vec3d.ofCenter(pos).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
	}

	/** {yaw, pitch} in degrees for looking from eye to point. */
	public static float[] anglesTo(Vec3d eye, Vec3d point) {
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
		float pitch = (float) -(MathHelper.atan2(dy, horizontal) * (180.0 / Math.PI));
		return new float[] {yaw, pitch};
	}

	/**
	 * The face of the block that the player can see and reach, closest first, or null if none.
	 * A face is usable if the block on that side is not a full solid and a ray from the eye to its center hits
	 * this block first (fluids are ignored, so lakebed blocks work).
	 */
	public static Direction bestVisibleFace(BlockPos pos) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) {
			return null;
		}
		Vec3d eye = player.getEyePos();
		double reach = player.getBlockInteractionRange();
		Direction best = null;
		double bestDist = Double.MAX_VALUE;
		for (Direction face : Direction.values()) {
			BlockPos neighbor = pos.offset(face);
			if (client.world.getBlockState(neighbor).isSolidBlock(client.world, neighbor)) {
				continue;
			}
			Vec3d center = faceCenter(pos, face);
			double dist = eye.distanceTo(center);
			if (dist > reach || dist >= bestDist) {
				continue;
			}
			// End the ray slightly inside the block so float error at the face boundary cannot miss it.
			Vec3d inside = center.add(Vec3d.ofCenter(pos).subtract(center).multiply(0.2));
			BlockHitResult hit = client.world.raycast(new RaycastContext(eye, inside,
					RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
			if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
				best = face;
				bestDist = dist;
			}
		}
		return best;
	}
}
