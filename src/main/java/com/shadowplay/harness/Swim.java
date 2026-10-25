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
	/** Closer than this horizontally, don't steer: tiny offsets would swing the heading around for nothing. */
	private static final double ARRIVE_HORIZONTAL = 0.8;
	/** Within this, small corrections are made by backing up or sidestepping rather than turning around. */
	private static final double CLOSE_HORIZONTAL = 2.5;
	/** A direction component at least this large (sin/cos) presses that movement key: 8-way movement like WASD. */
	private static final double KEY_COMPONENT = 0.38;
	/** On the lakebed within this horizontal distance of a block's column counts as next to it. */
	private static final double BED_REACH_HORIZONTAL = 1.6;
	/** Max head turn per tick while swimming (humanize on), so steering looks like a mouse, not a snap. */
	private static final float MAX_TURN_PER_TICK = 10f;
	/** Swimming up to the surface, a person looks up toward the light. */
	private static final float SURFACE_PITCH = -35f;
	private static final float SURFACE_TURN_PER_TICK = 6f;

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

	/** One tick of swimming straight up, looking up toward the surface. Returns true once the eyes are out. */
	public static boolean surfaceTick() {
		if (!isSubmerged()) {
			release();
			return true;
		}
		if (Humanize.enabled()) {
			ClientPlayerEntity player = MinecraftClient.getInstance().player;
			player.setPitch(player.getPitch() + MathHelper.clamp(SURFACE_PITCH - player.getPitch(), -SURFACE_TURN_PER_TICK, SURFACE_TURN_PER_TICK));
		}
		set(false, false, false, false, true, false);
		return false;
	}

	/** One tick of hopping off the shore toward point (forward + jump). Returns true once the player is in the water. */
	public static boolean jumpInTick(Vec3d point) {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player.isTouchingWater()) {
			release();
			return true;
		}
		turnToward(player, point);
		set(true, false, false, false, true, false);
		return false;
	}

	/**
	 * One tick of steering toward point: vertical via jump (up) or sneak (down). Horizontally, when the point is
	 * close it moves straight there with forward/back/strafe keys relative to the view (like WASD) instead of
	 * spinning around; further away it turns toward the point and swims forward once roughly facing it.
	 */
	public static void towardTick(Vec3d point) {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		double dx = point.x - player.getX();
		double dz = point.z - player.getZ();
		double dy = point.y - player.getY();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		boolean forward = false;
		boolean back = false;
		boolean left = false;
		boolean right = false;
		if (horizontal > ARRIVE_HORIZONTAL) {
			float targetYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
			float delta = MathHelper.wrapDegrees(targetYaw - player.getYaw());
			boolean close = horizontal < CLOSE_HORIZONTAL && Humanize.enabled();
			if (close && Math.abs(delta) > 45f) {
				double rad = Math.toRadians(delta);
				forward = Math.cos(rad) > KEY_COMPONENT;
				back = Math.cos(rad) < -KEY_COMPONENT;
				right = Math.sin(rad) > KEY_COMPONENT;
				left = Math.sin(rad) < -KEY_COMPONENT;
				tiltToward(player, point);
			} else {
				turnToward(player, point);
				forward = !Humanize.enabled() || Math.abs(MathHelper.wrapDegrees(targetYaw - player.getYaw())) < 60f;
			}
		}
		set(forward, back, left, right, dy > 0.3, dy < -0.3);
	}

	/** One tick of sinking onto (or right next to) the block at bed while steering over it. True once standing there. */
	public static boolean diveTick(BlockPos bed) {
		if (isNextToBed(bed)) {
			release();
			return true;
		}
		towardTick(Vec3d.ofBottomCenter(bed.up()));
		return false;
	}

	/** Standing on the bottom at the level of bed's top, on it or beside it (diagonals included). */
	public static boolean isNextToBed(BlockPos bed) {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null || !player.isOnGround() || Math.abs(player.getY() - (bed.getY() + 1)) > 0.6) {
			return false;
		}
		double dx = player.getX() - (bed.getX() + 0.5);
		double dz = player.getZ() - (bed.getZ() + 0.5);
		return Math.sqrt(dx * dx + dz * dz) <= BED_REACH_HORIZONTAL;
	}

	/** Only tilts the head up/down toward point (used while backing up or sidestepping). */
	private static void tiltToward(ClientPlayerEntity player, Vec3d point) {
		float pitch = MathHelper.clamp(Aim.anglesTo(player.getEyePos(), point)[1], -45f, 45f);
		player.setPitch(player.getPitch() + MathHelper.clamp(pitch - player.getPitch(), -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK));
	}

	/** Turns the head toward point: gradually (and with matching pitch) when humanize is on, instantly otherwise. */
	private static void turnToward(ClientPlayerEntity player, Vec3d point) {
		float[] angles = Aim.anglesTo(player.getEyePos(), point);
		if (!Humanize.enabled()) {
			player.setYaw(angles[0]);
			return;
		}
		float step = MathHelper.clamp(MathHelper.wrapDegrees(angles[0] - player.getYaw()), -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
		player.setYaw(player.getYaw() + step);
		float pitch = MathHelper.clamp(angles[1], -45f, 45f);
		player.setPitch(player.getPitch() + MathHelper.clamp(pitch - player.getPitch(), -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK));
	}

	public static void release() {
		set(false, false, false, false, false, false);
	}

	private static void set(boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean sneak) {
		GameOptions options = MinecraftClient.getInstance().options;
		options.forwardKey.setPressed(forward);
		options.backKey.setPressed(back);
		options.leftKey.setPressed(left);
		options.rightKey.setPressed(right);
		options.jumpKey.setPressed(jump);
		options.sneakKey.setPressed(sneak);
	}
}
