package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.harness.BaritoneBridge;
import com.shadowplay.harness.Glance;
import com.shadowplay.harness.Interact;
import com.shadowplay.harness.InventoryHelper;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Breaks exactly one block (a context target or a position). Walks into reach first if needed. */
public class BreakBlockSkill implements Skill {
	private static final int APPROACH_TIMEOUT_TICKS = 20 * 30;

	private final String targetName;
	private final BlockPos fixedPos;

	private BlockPos pos;
	private Interact.BreakTask breakTask;
	private boolean approaching;
	private int approachTicks;
	private Glance glance;
	private boolean snapshotTaken;

	public BreakBlockSkill(SkillArgs args) {
		this.targetName = args.getString("target", null);
		this.fixedPos = args.getPos("pos");
	}

	public static String check(SkillArgs args) {
		return args.has("target") ^ args.has("pos") ? null : "give exactly one of 'target' or 'pos'";
	}

	@Override
	public void start(TaskContext ctx) {
		pos = resolve(ctx);
		breakTask = null;
		approaching = false;
		approachTicks = 0;
		glance = null;
		if (!snapshotTaken) {
			ctx.setBreakSnapshot(InventoryHelper.snapshot());
			snapshotTaken = true;
		}
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		if (pos == null) {
			return SkillResult.failure(targetName != null ? "I don't know where '" + targetName + "' is" : "no block given");
		}
		if (breakTask == null) {
			if (!inReach()) {
				if (!approaching) {
					approaching = true;
					glance = Glance.beforeTrip(pos);
					if (glance == null) {
						BaritoneBridge.goToBlock(pos);
					}
				} else if (glance != null) {
					if (glance.tick()) {
						glance = null;
						BaritoneBridge.goToBlock(pos);
					}
				} else if (++approachTicks > APPROACH_TIMEOUT_TICKS || !BaritoneBridge.isBusy()) {
					BaritoneBridge.cancelAll();
					return SkillResult.failure(BaritoneBridge.lastFailure() != null ? BaritoneBridge.lastFailure() : "can't reach the block");
				}
				return SkillResult.RUNNING;
			}
			if (approaching) {
				BaritoneBridge.cancelAll();
				approaching = false;
			}
			breakTask = new Interact.BreakTask(pos);
		}
		return breakTask.tick();
	}

	@Override
	public void cancel() {
		if (breakTask != null) {
			breakTask.cancel();
		}
		if (approaching) {
			BaritoneBridge.cancelAll();
		}
	}

	@Override
	public boolean isAlreadySatisfied(TaskContext ctx) {
		BlockPos at = resolve(ctx);
		return at != null && isGone(at);
	}

	@Override
	public boolean postconditionMet(TaskContext ctx) {
		return pos != null && isGone(pos);
	}

	private static boolean isGone(BlockPos at) {
		BlockState state = MinecraftClient.getInstance().world.getBlockState(at);
		return state.isAir() || state.isReplaceable();
	}

	private boolean inReach() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		return player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) <= player.getBlockInteractionRange() - 0.5;
	}

	private BlockPos resolve(TaskContext ctx) {
		return fixedPos != null ? fixedPos : ctx.position(targetName);
	}
}
