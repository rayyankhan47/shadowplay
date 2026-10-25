package com.shadowplay.harness;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Underwater movement, which Baritone does not do (it only paths through the top layer of water). Steers by
 * holding movement keys; the interrupt detector reads physical keys, so this never counts as player input.
 * Call release() whenever the owner stops ticking it.
 */
public final class Swim {
	private static final double ARRIVE_HORIZONTAL = 0.3;
	/** Max head turn per tick while swimming (humanize on), so steering looks like a mouse, not a snap. */
	private static final float MAX_TURN_PER_TICK = 15f;

	private Swim() {
	}

	/** True if the player's eyes are under water. */
	public static boolean isSubmerged() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		return player != null && player.isSubmergedInWater();
	}

	public static boolean isInWater() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		return player != null && player.isTouchingWater();
	}

	/** True if pos is water and so is the block above it: deeper than Baritone will path. */
	public static boolean isDeepWater(BlockPos pos) {
		ClientWorld world = MinecraftClient.getInstance().world;
		return world.getFluidState(pos).isIn(FluidTags.WATER) && world.getFluidState(pos.up()).isIn(FluidTags.WATER);
	}

	/** The top water block of the column above pos (where Baritone can swim), or null if pos.up() is not water. */
	public static BlockPos surfaceAbove(BlockPos pos) {
		ClientWorld world = MinecraftClient.getInstance().world;
		BlockPos p = pos.up();
		if (!world.getFluidState(p).isIn(FluidTags.WATER)) {
			return null;
		}
		while (world.getFluidState(p.up()).isIn(FluidTags.WATER) && p.getY() < world.getTopY()) {
			p = p.up();
		}
		return p;
	}

	/** One tick of swimming straight up. Returns true once the eyes are out of the water. */
	public static boolean surfaceTick() {
		if (!isSubmerged()) {
			release();
			return true;
		}
		set(false, true, false);
		return false;
	}

	/** One tick of steering toward point: horizontal via forward, vertical via jump (up) or sneak (down). */
	public static void towardTick(Vec3d point) {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		double dx = point.x - player.getX();
		double dz = point.z - player.getZ();
		double dy = point.y - player.getY();
		boolean move = Math.sqrt(dx * dx + dz * dz) > ARRIVE_HORIZONTAL;
		if (move) {
			float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
			if (Humanize.enabled()) {
				float step = MathHelper.clamp(MathHelper.wrapDegrees(yaw - player.getYaw()), -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
				player.setYaw(player.getYaw() + step);
				float pitch = MathHelper.clamp(Aim.anglesTo(player.getEyePos(), point)[1], -45f, 45f);
				player.setPitch(player.getPitch() + MathHelper.clamp(pitch - player.getPitch(), -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK));
			} else {
				player.setYaw(yaw);
			}
		}
		set(move, dy > 0.3, dy < -0.3);
	}

	/** One tick of sinking onto the top of the block at bed while steering over it. True once standing there. */
	public static boolean diveTick(BlockPos bed) {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player.getBlockPos().equals(bed.up()) && player.isOnGround()) {
			release();
			return true;
		}
		towardTick(Vec3d.ofBottomCenter(bed.up()));
		return false;
	}

	public static void release() {
		set(false, false, false);
	}

	private static void set(boolean forward, boolean jump, boolean sneak) {
		GameOptions options = MinecraftClient.getInstance().options;
		options.forwardKey.setPressed(forward);
		options.jumpKey.setPressed(jump);
		options.sneakKey.setPressed(sneak);
	}
}
