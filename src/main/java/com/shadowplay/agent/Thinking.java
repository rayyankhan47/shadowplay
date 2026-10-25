package com.shadowplay.agent;

import com.shadowplay.config.ShadowplayConfig;
import com.shadowplay.harness.Drops;
import com.shadowplay.harness.Glance;
import com.shadowplay.harness.Humanize;
import com.shadowplay.perception.CaveFinder;
import com.shadowplay.perception.WorldScanner;
import com.shadowplay.plan.Plan;
import com.shadowplay.plan.PlanStep;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.impl.FindUnderwaterBlockSkill;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * The "figuring it out" beat before a plan starts: a pause with a Thinking… line, during which the player glances
 * at what the task is about (the trees, the lake, the cave). tick() returns true when it is time to start.
 */
final class Thinking {
	private static final int GLANCE_AFTER_MS = 900;
	private static final int FOCUS_STEPS = 3;

	private final Plan plan;
	private int ticksLeft;
	private int glanceIn;
	private Vec3d focus;
	private Glance glance;

	Thinking(Plan plan) {
		this.plan = plan;
		this.ticksLeft = Humanize.ticks(Humanize.settings().thinkingDelayMs);
		this.glanceIn = Humanize.ticks(GLANCE_AFTER_MS);
		this.focus = focusFor(plan);
	}

	Plan plan() {
		return plan;
	}

	boolean tick() {
		if (focus != null && --glanceIn <= 0) {
			if (glance == null) {
				glance = Glance.at(focus, 0);
			}
			if (glance != null) {
				glance.tick();
			}
		}
		return --ticksLeft <= 0;
	}

	/** What the first steps of the plan are about, as a point to look at, or null. */
	private static Vec3d focusFor(Plan plan) {
		for (int i = 0; i < Math.min(FOCUS_STEPS, plan.steps.size()); i++) {
			BlockPos pos = focusOf(plan.steps.get(i));
			if (pos != null) {
				return Vec3d.ofCenter(pos);
			}
		}
		return null;
	}

	private static BlockPos focusOf(PlanStep step) {
		SkillArgs args = new SkillArgs(step.args);
		switch (step.skill) {
			case "chop_logs" -> {
				Block log = args.has("block") ? args.getBlock("block") : null;
				return WorldScanner.nearest(s -> log != null ? s.isOf(log) : s.isIn(BlockTags.LOGS), 32);
			}
			case "mine_blocks" -> {
				Block block = args.getBlock("block");
				return block == null ? null : WorldScanner.nearest(s -> s.isOf(block), 16);
			}
			case "find_underwater_block" -> {
				Block block = args.getBlock("block");
				return block == null ? null : FindUnderwaterBlockSkill.choose(block, args.getString("side", "any").toLowerCase(), 32,
						net.minecraft.client.MinecraftClient.getInstance().player.getYaw());
			}
			case "find_cave" -> {
				ShadowplayConfig.Pos override = ShadowplayConfig.get().demo.caveEntrance;
				if (override != null) {
					return new BlockPos(override.x, override.y, override.z);
				}
				CaveFinder.Cave cave = CaveFinder.find(args.getInt("radius", CaveFinder.DEFAULT_RADIUS));
				return cave != null ? cave.entrance() : null;
			}
			case "collect_exposed_ore" -> {
				Block block = args.has("block") ? args.getBlock("block") : Blocks.COAL_ORE;
				Item drop = Drops.of(block);
				return WorldScanner.nearest(s -> Drops.of(s.getBlock()) == drop && !s.isAir(),
						(pos, s) -> WorldScanner.isExposed(pos), 24);
			}
			case "goto" -> {
				if (args.has("pos")) {
					return args.getPos("pos");
				}
				Block near = args.has("near") ? args.getBlock("near") : null;
				return near == null ? null : WorldScanner.nearest(s -> s.isOf(near), 32);
			}
			default -> {
				return null;
			}
		}
	}
}
