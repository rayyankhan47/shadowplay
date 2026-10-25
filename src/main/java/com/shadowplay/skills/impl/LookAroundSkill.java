package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.harness.Aim;
import com.shadowplay.harness.Humanize;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Takes in the surroundings like a person entering a new place: a slow pan to one side, then the other, back to
 * the middle, and a short pause. Purely visual; does nothing when humanize (or lookAroundInCaves) is off.
 */
public class LookAroundSkill implements Skill {
	/** Yaw offset factor (of arc), pitch, turn ms, hold ms. */
	private static final float[][] FRAMES = {
			{-1f, -4f, 800, 250},
			{1f, 8f, 1200, 250},
			{0f, 4f, 600, 1000},
	};

	private final int arc;
	private final Aim aim = new Aim();
	private float baseYaw;
	private int frame;
	private int holdTicks;
	private boolean turning;

	public LookAroundSkill(SkillArgs args) {
		this.arc = MathHelper.clamp(args.getInt("arc", 60), 10, 120);
	}

	@Override
	public void start(TaskContext ctx) {
		baseYaw = MinecraftClient.getInstance().player.getYaw();
		frame = 0;
		turning = false;
		boolean on = Humanize.enabled() && Humanize.settings().lookAroundInCaves;
		if (!on) {
			frame = FRAMES.length;
		}
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		if (frame >= FRAMES.length) {
			return SkillResult.SUCCESS;
		}
		float[] f = FRAMES[frame];
		if (!turning) {
			ClientPlayerEntity player = MinecraftClient.getInstance().player;
			Vec3d eye = player.getEyePos();
			Vec3d point = eye.add(Vec3d.fromPolar(f[1], baseYaw + f[0] * arc).multiply(8));
			aim.lookAt(point, Math.max(1, Humanize.ticks((int) f[2])));
			holdTicks = Humanize.ticks((int) f[3]);
			turning = true;
		}
		aim.tick();
		if (aim.isDone() && holdTicks-- <= 0) {
			frame++;
			turning = false;
		}
		return SkillResult.RUNNING;
	}

	@Override
	public void cancel() {
		frame = FRAMES.length;
	}
}
