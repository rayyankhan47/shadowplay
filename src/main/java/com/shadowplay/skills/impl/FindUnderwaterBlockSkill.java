package com.shadowplay.skills.impl;

import com.shadowplay.Log;
import com.shadowplay.agent.TaskContext;
import com.shadowplay.perception.WorldScanner;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Finds the nearest block of a type with water directly above it (e.g. sand on a lakebed), preferring the
 * requested side of the player (±60° around left/right), and saves it in the context (default name "target").
 */
public class FindUnderwaterBlockSkill implements Skill {
	private static final float SIDE_CONE_HALF_WIDTH = 60f;

	private final Block block;
	private final String side;
	private final int radius;
	private final String name;

	public FindUnderwaterBlockSkill(SkillArgs args) {
		this.block = args.getBlock("block");
		this.side = args.getString("side", "any").toLowerCase();
		this.radius = Math.max(1, Math.min(WorldScanner.MAX_RADIUS, args.getInt("radius", 32)));
		this.name = args.getString("name", "target");
	}

	public static String check(SkillArgs args) {
		String side = args.getString("side", "any").toLowerCase();
		return side.equals("left") || side.equals("right") || side.equals("any") ? null : "side must be left, right or any";
	}

	@Override
	public void start(TaskContext ctx) {
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		// "To your right" means right of where the player faced when the task was given, not after glancing around.
		float facing = ctx.requestYaw() != null ? ctx.requestYaw() : MinecraftClient.getInstance().player.getYaw();
		BlockPos chosen = choose(block, side, radius, facing);
		if (chosen == null) {
			return SkillResult.fatal("no " + block.getName().getString().toLowerCase() + " underwater nearby");
		}
		ctx.putPosition(name, chosen);
		Log.info("find_underwater_block: chose {} on the player's {}", chosen.toShortString(), WorldScanner.sideOf(chosen, facing));
		return SkillResult.SUCCESS;
	}

	/**
	 * Nearest block of this type with water above, preferring the given side ("left"/"right"/"any") of a player
	 * facing facingYaw; null if none.
	 */
	public static BlockPos choose(Block block, String side, int radius, float facingYaw) {
		List<BlockPos> candidates = WorldScanner.all(state -> state.isOf(block),
				(pos, state) -> WorldScanner.isUnderwater(pos), radius, 256);
		if (candidates.isEmpty()) {
			return null;
		}
		if (!side.equals("any")) {
			WorldScanner.Side wanted = side.equals("right") ? WorldScanner.Side.RIGHT : WorldScanner.Side.LEFT;
			for (BlockPos pos : candidates) {
				if (WorldScanner.isInCone(pos, wanted, SIDE_CONE_HALF_WIDTH, facingYaw)) {
					return pos;
				}
			}
		}
		return candidates.get(0);
	}

	@Override
	public void cancel() {
	}

	@Override
	public boolean isAlreadySatisfied(TaskContext ctx) {
		BlockPos known = ctx.position(name);
		return known != null && MinecraftClient.getInstance().world.getBlockState(known).isOf(block);
	}

	@Override
	public boolean postconditionMet(TaskContext ctx) {
		return ctx.position(name) != null;
	}
}
