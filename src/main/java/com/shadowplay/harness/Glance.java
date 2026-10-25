package com.shadowplay.harness;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * A human beat before acting: turn smoothly to face something, hold for a moment, then report done. Tick it every
 * client tick; tick() returns true once finished. Turning speed depends on how far the head has to turn.
 */
public final class Glance {
	private static final float DEGREES_PER_TURN_TICK = 10f;
	private static final int MIN_TURN_TICKS = 4;
	private static final int MAX_TURN_TICKS = 14;
	/** Looking toward a destination never stares at the sky or the player's feet. */
	private static final float TRIP_MIN_PITCH = -25f;
	private static final float TRIP_MAX_PITCH = 35f;

	private final Aim aim = new Aim();
	private int holdTicks;

	private Glance(Vec3d point, int holdTicks) {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		float[] goal = Aim.anglesTo(player.getEyePos(), point);
		float turn = Math.abs(MathHelper.wrapDegrees(goal[0] - player.getYaw())) + Math.abs(goal[1] - player.getPitch());
		int turnTicks = MathHelper.clamp(Math.round(turn / DEGREES_PER_TURN_TICK), MIN_TURN_TICKS, MAX_TURN_TICKS);
		aim.lookAt(point, turnTicks);
		this.holdTicks = holdTicks;
	}

	/** Look straight at a point (e.g. an ore or a dropped item) and hold for holdMs (jittered). */
	public static Glance at(Vec3d point, int holdMs) {
		return Humanize.enabled() ? new Glance(point, Humanize.ticks(holdMs)) : null;
	}

	/**
	 * Before a walk to destination: face it (pitch kept comfortable), and for trips of at least the configured
	 * distance hold for the configured pause. Short hops just turn. Null if humanize is off or already there.
	 */
	public static Glance beforeTrip(BlockPos destination) {
		return beforeTrip(Vec3d.ofCenter(destination), destination);
	}

	/** Like beforeTrip(destination) but looking at lookAt (e.g. the ore you are about to walk to). */
	public static Glance beforeTrip(Vec3d lookAt, BlockPos destination) {
		if (!Humanize.enabled()) {
			return null;
		}
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		double distance = Math.sqrt(player.getBlockPos().getSquaredDistance(destination));
		if (distance < 1.5) {
			return null;
		}
		Vec3d eye = player.getEyePos();
		float[] angles = Aim.anglesTo(eye, lookAt);
		float pitch = MathHelper.clamp(angles[1], TRIP_MIN_PITCH, TRIP_MAX_PITCH);
		Vec3d point = eye.add(Vec3d.fromPolar(pitch, angles[0]).multiply(8));
		boolean longTrip = distance >= Humanize.settings().lookBeforeMoveMinDistance;
		return new Glance(point, longTrip ? Humanize.ticks(Humanize.settings().lookBeforeMoveMs) : 0);
	}

	/** One tick; true when the turn and the hold are finished. */
	public boolean tick() {
		aim.tick();
		if (!aim.isDone()) {
			return false;
		}
		return holdTicks-- <= 0;
	}
}
