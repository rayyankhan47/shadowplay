package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.harness.BaritoneBridge;
import com.shadowplay.harness.BaritoneBridge.GoalKind;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Walks with Baritone to pos, a named context position, or the nearest block of a type ("near").
 * range 0 (default) means stand on the spot, or touch it if it is a solid block; range > 0 means within range.
 */
public class GotoSkill implements Skill {
	private static final int TIMEOUT_TICKS = 20 * 60;
	private static final int STUCK_TICKS = 20 * 8;
	private static final int MAX_REPATHS = 2;

	private final BlockPos pos;
	private final String name;
	private final Block near;
	private final int range;

	private BlockPos target;
	private GoalKind kind;
	private int ticks;
	private int repaths;
	private Vec3d lastProgressPos;
	private int lastProgressTick;

	public GotoSkill(SkillArgs args) {
		this.pos = args.getPos("pos");
		this.name = args.getString("name", null);
		this.near = args.has("near") ? args.getBlock("near") : null;
		this.range = Math.max(0, args.getInt("range", 0));
	}

	/** Exactly one of pos / name / near must be given. */
	public static String check(SkillArgs args) {
		int given = (args.has("pos") ? 1 : 0) + (args.has("name") ? 1 : 0) + (args.has("near") ? 1 : 0);
		return given == 1 ? null : "give exactly one of 'pos', 'name' or 'near'";
	}

	@Override
	public void start(TaskContext ctx) {
		ticks = 0;
		repaths = 0;
		target = resolve(ctx);
		if (target == null) {
			return;
		}
		kind = goalKind(target);
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		lastProgressPos = player.getPos();
		lastProgressTick = 0;
		if (!BaritoneBridge.isInGoal(kind, target, range)) {
			BaritoneBridge.go(kind, target, range);
		}
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		if (target == null) {
			return SkillResult.failure(describeMissingTarget());
		}
		ticks++;
		if (BaritoneBridge.isInGoal(kind, target, range)) {
			if (BaritoneBridge.isBusy()) {
				BaritoneBridge.cancelAll();
			}
			return SkillResult.SUCCESS;
		}
		if (ticks > TIMEOUT_TICKS) {
			return SkillResult.failure("took too long to get there");
		}
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player.getPos().squaredDistanceTo(lastProgressPos) > 1.0) {
			lastProgressPos = player.getPos();
			lastProgressTick = ticks;
		} else if (ticks - lastProgressTick > STUCK_TICKS) {
			return SkillResult.failure("stuck on the way");
		}
		if (!BaritoneBridge.isBusy()) {
			String failure = BaritoneBridge.lastFailure();
			if (failure != null || repaths >= MAX_REPATHS) {
				return SkillResult.failure(failure != null ? failure : "couldn't get there");
			}
			repaths++;
			BaritoneBridge.go(kind, target, range);
		}
		return SkillResult.RUNNING;
	}

	@Override
	public void cancel() {
		BaritoneBridge.cancelAll();
	}

	@Override
	public boolean isAlreadySatisfied(TaskContext ctx) {
		BlockPos resolved = resolve(ctx);
		return resolved != null && BaritoneBridge.isInGoal(goalKind(resolved), resolved, range);
	}

	@Override
	public boolean postconditionMet(TaskContext ctx) {
		return target != null && BaritoneBridge.isInGoal(kind, target, range);
	}

	private BlockPos resolve(TaskContext ctx) {
		if (pos != null) {
			return pos;
		}
		if (name != null) {
			return ctx.position(name);
		}
		if (near != null) {
			return BaritoneBridge.findNearest(near, 4);
		}
		return null;
	}

	private GoalKind goalKind(BlockPos at) {
		if (range > 0) {
			return GoalKind.NEAR;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		BlockState state = client.world.getBlockState(at);
		// You cannot stand inside a solid block, so for one of those "go to" means "go next to it".
		boolean solid = near != null || !state.getCollisionShape(client.world, at).isEmpty();
		return solid ? GoalKind.TOUCH : GoalKind.EXACT;
	}

	private String describeMissingTarget() {
		if (name != null) {
			return "I don't know where '" + name + "' is";
		}
		if (near != null) {
			return "no " + near.getName().getString().toLowerCase() + " nearby";
		}
		return "no destination";
	}
}
