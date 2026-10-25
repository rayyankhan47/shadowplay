package com.shadowplay.skills.impl;

import com.shadowplay.Log;
import com.shadowplay.agent.TaskContext;
import com.shadowplay.harness.Aim;
import com.shadowplay.harness.BaritoneBridge;
import com.shadowplay.harness.Drops;
import com.shadowplay.harness.Glance;
import com.shadowplay.harness.Humanize;
import com.shadowplay.harness.Interact;
import com.shadowplay.harness.InventoryHelper;
import com.shadowplay.harness.Swim;
import com.shadowplay.perception.WorldScanner;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mines ore that is already exposed to air (visible in the cave), one block at a time, nearest first:
 * walk next to it, aim, break it with the best pickaxe, pick up the drop. Deterministic, unlike Baritone's
 * explore-for-ore mining. Fails after MAX_MISSES consecutive blocks that couldn't be mined or collected.
 */
public class CollectExposedOreSkill implements Skill {
	private static final int TIMEOUT_TICKS = 20 * 120;
	private static final int APPROACH_TIMEOUT_TICKS = 20 * 20;
	private static final int COLLECT_TIMEOUT_TICKS = 20 * 6;
	private static final int MAX_MISSES = 3;
	private static final int MAX_REPATHS = 2;
	private static final int DROP_RADIUS = 6;
	private static final int SPOT_MS = 550;
	private static final int REST_MS = 300;

	private enum Phase {
		SELECT,
		SPOT,
		APPROACH,
		BREAK,
		COLLECT
	}

	private final Block block;
	private final Item dropItem;
	private final int count;
	private final String within;
	private final int radius;

	private int target;
	private int ticks;
	private int phaseTicks;
	private int misses;
	private int repaths;
	private int countBeforeBreak;
	private Phase phase;
	private BlockPos ore;
	private BlockPos standAt;
	private Interact.BreakTask breakTask;
	private Glance glance;
	private boolean dropGlanced;
	private int restTicks;
	private final Set<BlockPos> blacklist = new HashSet<>();

	public CollectExposedOreSkill(SkillArgs args) {
		this.block = args.has("block") ? args.getBlock("block") : Blocks.COAL_ORE;
		this.dropItem = Drops.of(block);
		this.count = Math.max(1, args.getInt("count", 1));
		this.within = args.getString("within", FindCaveSkill.INSIDE);
		this.radius = Math.max(4, Math.min(WorldScanner.MAX_RADIUS, args.getInt("radius", 24)));
	}

	@Override
	public void start(TaskContext ctx) {
		target = ctx.baseline(key(), () -> InventoryHelper.count(dropItem)) + count;
		ticks = 0;
		misses = 0;
		restTicks = 0;
		glance = null;
		blacklist.clear();
		enter(Phase.SELECT);
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		int have = InventoryHelper.count(dropItem);
		if (have >= target) {
			cancel();
			return SkillResult.SUCCESS;
		}
		if (++ticks > TIMEOUT_TICKS) {
			cancel();
			return SkillResult.failure("took too long mining " + what());
		}
		phaseTicks++;
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		switch (phase) {
			case SELECT -> {
				if (restTicks-- > 0) {
					return SkillResult.RUNNING; // a short beat between ores
				}
				ore = pickOre(ctx);
				if (ore == null) {
					return SkillResult.fatal("no more exposed " + what() + " ore nearby");
				}
				repaths = 0;
				standAt = approachSpot(player, ore);
				// Spot it first: turn to look at the ore and hold for a moment, like noticing it.
				glance = Glance.at(Vec3d.ofCenter(ore), SPOT_MS);
				enter(glance != null ? Phase.SPOT : Phase.APPROACH);
			}
			case SPOT -> {
				if (glance.tick()) {
					glance = null;
					enter(Phase.APPROACH);
				}
			}
			case APPROACH -> {
				if (!isOre(ore)) {
					enter(Phase.SELECT); // someone else mined it
				} else if (inReach(player, ore) && Aim.bestVisibleFace(ore) != null) {
					BaritoneBridge.cancelAll();
					countBeforeBreak = have;
					breakTask = new Interact.BreakTask(ore);
					enter(Phase.BREAK);
				} else if (phaseTicks > APPROACH_TIMEOUT_TICKS) {
					return miss("couldn't reach it");
				} else if (!BaritoneBridge.isBusy()) {
					if (repaths++ > MAX_REPATHS) {
						return miss(BaritoneBridge.lastFailure() != null ? BaritoneBridge.lastFailure() : "couldn't reach it");
					}
					if (standAt != null && repaths == 1) {
						// Walk through the cave to the exposed side; don't tunnel in from somewhere else.
						BaritoneBridge.go(BaritoneBridge.GoalKind.EXACT, standAt, 0, false);
					} else {
						BaritoneBridge.goToBlock(ore);
					}
				}
			}
			case BREAK -> {
				SkillResult result = breakTask.tick();
				if (result.status() == SkillResult.Status.SUCCESS) {
					breakTask = null;
					dropGlanced = false;
					enter(Phase.COLLECT);
				} else if (result.status() == SkillResult.Status.FAILURE) {
					breakTask.cancel();
					breakTask = null;
					return miss(result.reason());
				}
			}
			case COLLECT -> {
				if (have > countBeforeBreak) {
					misses = 0;
					BaritoneBridge.cancelAll();
					Swim.release();
					glance = null;
					restTicks = Humanize.ticks(REST_MS);
					enter(Phase.SELECT);
				} else if (phaseTicks > COLLECT_TIMEOUT_TICKS) {
					return miss("couldn't pick up the drop");
				} else {
					chaseDrop(player);
				}
			}
		}
		return SkillResult.RUNNING;
	}

	@Override
	public void cancel() {
		if (breakTask != null) {
			breakTask.cancel();
			breakTask = null;
		}
		BaritoneBridge.cancelAll();
		Swim.release();
	}

	@Override
	public boolean isAlreadySatisfied(TaskContext ctx) {
		Integer before = ctx.existingBaseline(key());
		return before != null && InventoryHelper.count(dropItem) >= before + count;
	}

	@Override
	public boolean postconditionMet(TaskContext ctx) {
		return InventoryHelper.count(dropItem) >= target;
	}

	private SkillResult miss(String reason) {
		Log.info("collect_exposed_ore: gave up on {} ({})", ore != null ? ore.toShortString() : "?", reason);
		if (ore != null) {
			blacklist.add(ore);
		}
		BaritoneBridge.cancelAll();
		Swim.release();
		if (++misses >= MAX_MISSES) {
			return SkillResult.failure(reason);
		}
		enter(Phase.SELECT);
		return SkillResult.RUNNING;
	}

	private void enter(Phase next) {
		phase = next;
		phaseTicks = 0;
	}

	/** Nearest (to the player) exposed, non-blacklisted ore within radius of the anchor position. */
	private BlockPos pickOre(TaskContext ctx) {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		BlockPos anchor = ctx.position(within);
		if (anchor == null) {
			anchor = player.getBlockPos();
		}
		List<BlockPos> found = WorldScanner.all(anchor, state -> Drops.of(state.getBlock()) == dropItem && !state.isAir(),
				(pos, state) -> !blacklist.contains(pos) && WorldScanner.isExposed(pos), radius, 64);
		BlockPos feet = player.getBlockPos();
		return found.stream().min(Comparator.comparingDouble(p -> p.getSquaredDistance(feet))).orElse(null);
	}

	/**
	 * A standable spot in the air next to the ore's exposed side(s), within reach of the ore, nearest to the
	 * player. Null if there is none (then we fall back to Baritone's "touch the block" goal).
	 */
	private static BlockPos approachSpot(ClientPlayerEntity player, BlockPos ore) {
		var world = MinecraftClient.getInstance().world;
		double reach = player.getBlockInteractionRange() - 0.5;
		Vec3d oreCenter = Vec3d.ofCenter(ore);
		BlockPos feet = player.getBlockPos();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
			BlockPos air = ore.offset(d);
			if (!world.getBlockState(air).isAir()) {
				continue;
			}
			for (BlockPos p : BlockPos.iterate(air.add(-2, -4, -2), air.add(2, 1, 2))) {
				if (p.equals(ore) || !WorldScanner.isStandable(p)) {
					continue;
				}
				Vec3d eye = Vec3d.ofBottomCenter(p).add(0, player.getStandingEyeHeight(), 0);
				if (eye.distanceTo(oreCenter) > reach) {
					continue;
				}
				double dist = p.getSquaredDistance(feet);
				if (dist < bestDist) {
					bestDist = dist;
					best = p.toImmutable();
				}
			}
		}
		return best;
	}

	private boolean isOre(BlockPos pos) {
		return Drops.of(MinecraftClient.getInstance().world.getBlockState(pos).getBlock()) == dropItem;
	}

	private void chaseDrop(ClientPlayerEntity player) {
		ItemEntity drop = player.getWorld().getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(DROP_RADIUS),
						e -> e.isAlive() && e.getStack().isOf(dropItem))
				.stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(player))).orElse(null);
		if (drop == null) {
			return;
		}
		if (Swim.isInWater()) {
			Swim.towardTick(drop.getPos());
			return;
		}
		if (!dropGlanced) {
			dropGlanced = true;
			glance = Glance.beforeTrip(drop.getPos(), drop.getBlockPos()); // turn toward the drop first
		}
		if (glance != null) {
			if (glance.tick()) {
				glance = null;
			}
			return;
		}
		if (!BaritoneBridge.isBusy()) {
			BaritoneBridge.goTo(drop.getBlockPos());
		}
	}

	private static boolean inReach(ClientPlayerEntity player, BlockPos pos) {
		return player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) <= player.getBlockInteractionRange() - 0.5;
	}

	private String what() {
		return Interact.itemName(dropItem);
	}

	private String key() {
		return "ore:" + Registries.ITEM.getId(dropItem);
	}
}
