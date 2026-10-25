package com.shadowplay.harness;

import com.shadowplay.config.ShadowplayConfig;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/** Small helpers that make timings and aim less robotic. Everything is a no-op when humanize is disabled. */
public final class Humanize {
	private static final Random RANDOM = new Random();
	/** How far from a face's center an aim point may land (a face is 1 wide, so this stays well inside it). */
	private static final double FACE_OFFSET = 0.22;

	private Humanize() {
	}

	public static boolean enabled() {
		return settings().enabled;
	}

	public static ShadowplayConfig.Humanize settings() {
		return ShadowplayConfig.get().humanize;
	}

	/** A duration in ms as ticks, varied by ±timingJitter. Zero when humanize is off. */
	public static int ticks(int ms) {
		if (!enabled() || ms <= 0) {
			return 0;
		}
		double jitter = Math.max(0, Math.min(0.9, settings().timingJitter));
		double factor = 1 + (RANDOM.nextDouble() * 2 - 1) * jitter;
		return Math.max(1, (int) Math.round(ms / 50.0 * factor));
	}

	/** A random whole number in [min, max] (min when humanize is off). */
	public static int between(int min, int max) {
		return enabled() && max > min ? min + RANDOM.nextInt(max - min + 1) : min;
	}

	/** A point on the face of a block: the center, or near it at random so aim isn't pixel-perfect every time. */
	public static Vec3d facePoint(BlockPos pos, Direction face) {
		Vec3d center = Vec3d.ofCenter(pos).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
		if (!enabled()) {
			return center;
		}
		double a = (RANDOM.nextDouble() * 2 - 1) * FACE_OFFSET;
		double b = (RANDOM.nextDouble() * 2 - 1) * FACE_OFFSET;
		return switch (face.getAxis()) {
			case X -> center.add(0, a, b);
			case Y -> center.add(a, 0, b);
			case Z -> center.add(a, b, 0);
		};
	}
}
