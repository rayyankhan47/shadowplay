package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.harness.BaritoneBridge;
import com.shadowplay.harness.BaritoneBridge.GoalKind;
import com.shadowplay.harness.Glance;
import com.shadowplay.harness.Humanize;
import com.shadowplay.harness.Swim;
import com.shadowplay.perception.WorldScanner;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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

	private enum Phase {
		SURFACE,
		GLANCE,
		PATH,
		EDGE_LOOK,
		JUMP_IN,
		DIVE
	}

	private static final int DIVE_TIMEOUT_TICKS = 20 * 12;
	private static final int JUMP_IN_TIMEOUT_TICKS = 30;
	private static final int EDGE_LOOK_MS = 500;
	private static final int EDGE_SEARCH_RADIUS = 8;

	private BlockPos target;
	private GoalKind kind;
	private Phase phase;
	private BlockPos swimTo;
	/** For a lakebed target reached from land: the dry shore block to walk to before hopping in (humanize). */
	private BlockPos edge;
	private int phaseTicks;
	private Glance glance;
	private int ticks;
	private int repaths;
	private boolean digFallbackUsed;
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
		digFallbackUsed = false;
		target = resolve(ctx);
		if (target == null) {
			return;
		}
		kind = goalKind(target);
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		lastProgressPos = player.getPos();
		lastProgressTick = 0;
		phaseTicks = 0;
		// Baritone only swims in the top layer of water: for a lakebed target it swims to the surface above it
		// and we dive the rest of the way; when starting underwater we surface before handing over to Baritone.
		swimTo = kind == GoalKind.NEAR ? null : Swim.surfaceAbove(target);
		if (swimTo != null && swimTo.equals(target.up())) {
			swimTo = null; // shallow: one layer of water, Baritone handles it
		}
		// From land, walk to the water's edge, look at the water, then hop in, rather than letting Baritone
		// stride straight off the bank.
		edge = swimTo != null && Humanize.enabled() && !Swim.isInWater() ? findEdge(swimTo) : null;
		if (BaritoneBridge.isInGoal(kind, target, range)) {
			phase = Phase.PATH;
		} else if (Swim.isSubmerged()) {
			phase = Phase.SURFACE;
		} else {
			beginTrip();
		}
	}

	/** Face the destination and pause a moment before setting off (humanize), then path. */
	private void beginTrip() {
		glance = Glance.beforeTrip(Vec3d.ofCenter(target), edge != null ? edge : swimTo != null ? swimTo : target);
		if (glance == null) {
			startPath();
		} else {
			phase = Phase.GLANCE;
			phaseTicks = 0;
		}
	}

	private void startPath() {
		phase = Phase.PATH;
		phaseTicks = 0;
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		lastProgressPos = player.getPos();
		lastProgressTick = ticks;
		if (edge != null) {
			BaritoneBridge.go(GoalKind.EXACT, edge, 0, true);
		} else if (swimTo != null) {
			BaritoneBridge.go(GoalKind.EXACT, swimTo, 0, false);
		} else {
			BaritoneBridge.go(kind, target, range);
		}
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		if (target == null) {
			return SkillResult.failure(describeMissingTarget());
		}
		ticks++;
		phaseTicks++;
		if (phase != Phase.DIVE && BaritoneBridge.isInGoal(kind, target, range)) {
			if (BaritoneBridge.isBusy()) {
				BaritoneBridge.cancelAll();
			}
			Swim.release();
			return SkillResult.SUCCESS;
		}
		if (ticks > TIMEOUT_TICKS) {
			return SkillResult.failure("took too long to get there");
		}
		if (phase == Phase.SURFACE) {
			if (Swim.surfaceTick() || phaseTicks > DIVE_TIMEOUT_TICKS) {
				Swim.release();
				beginTrip();
			}
			return SkillResult.RUNNING;
		}
		if (phase == Phase.GLANCE) {
			if (glance.tick()) {
				glance = null;
				startPath();
			}
			return SkillResult.RUNNING;
		}
		if (phase == Phase.EDGE_LOOK) {
			if (glance == null || glance.tick()) {
				glance = null;
				phase = Phase.JUMP_IN;
				phaseTicks = 0;
			}
			return SkillResult.RUNNING;
		}
		if (phase == Phase.JUMP_IN) {
			if (Swim.jumpInTick(Vec3d.ofCenter(swimTo))) {
				phase = Phase.DIVE;
				phaseTicks = 0;
			} else if (phaseTicks > JUMP_IN_TIMEOUT_TICKS) {
				// Couldn't hop in from here: let Baritone take us to the water instead.
				Swim.release();
				edge = null;
				startPath();
			}
			return SkillResult.RUNNING;
		}
		if (phase == Phase.DIVE) {
			if (Swim.diveTick(target)) {
				return SkillResult.SUCCESS;
			}
			if (phaseTicks > DIVE_TIMEOUT_TICKS) {
				Swim.release();
				return SkillResult.failure("couldn't dive down there");
			}
			return SkillResult.RUNNING;
		}
		if (edge != null && MinecraftClient.getInstance().player.getBlockPos().equals(edge)) {
			BaritoneBridge.cancelAll();
			glance = Glance.at(Vec3d.ofCenter(swimTo), EDGE_LOOK_MS);
			phase = Phase.EDGE_LOOK;
			phaseTicks = 0;
			return SkillResult.RUNNING;
		}
		if (swimTo != null && isAtSurfaceAbove()) {
			BaritoneBridge.cancelAll();
			phase = Phase.DIVE;
			phaseTicks = 0;
			return SkillResult.RUNNING;
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
			if (failure != null && !digFallbackUsed && BaritoneBridge.isWet(target)) {
				// No swimming route: allow digging straight to the target once rather than give up.
				digFallbackUsed = true;
				swimTo = null;
				edge = null;
				BaritoneBridge.go(kind, target, range, true);
				return SkillResult.RUNNING;
			}
			if (failure != null || repaths >= MAX_REPATHS) {
				return SkillResult.failure(failure != null ? failure : "couldn't get there");
			}
			repaths++;
			startPath();
		}
		return SkillResult.RUNNING;
	}

	@Override
	public void cancel() {
		BaritoneBridge.cancelAll();
		Swim.release();
	}

	/**
	 * A dry, standable shore block next to the water, near the swim target and roughly on the way there, or null
	 * if there is none close by.
	 */
	private static BlockPos findEdge(BlockPos surface) {
		ClientWorld world = MinecraftClient.getInstance().world;
		BlockPos feet = MinecraftClient.getInstance().player.getBlockPos();
		int r = EDGE_SEARCH_RADIUS;
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.iterate(surface.add(-r, -1, -r), surface.add(r, 3, r))) {
			if (!WorldScanner.isStandable(p) || !world.getFluidState(p).isEmpty() || !world.getFluidState(p.down()).isEmpty()) {
				continue;
			}
			boolean shore = false;
			for (Direction d : Direction.Type.HORIZONTAL) {
				if (world.getFluidState(p.offset(d)).isIn(FluidTags.WATER) || world.getFluidState(p.down().offset(d)).isIn(FluidTags.WATER)) {
					shore = true;
					break;
				}
			}
			if (!shore) {
				continue;
			}
			double score = Math.sqrt(p.getSquaredDistance(surface)) + 0.5 * Math.sqrt(p.getSquaredDistance(feet));
			if (score < bestScore) {
				bestScore = score;
				best = p.toImmutable();
			}
		}
		return best;
	}

	private boolean isAtSurfaceAbove() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		BlockPos feet = player.getBlockPos();
		return Math.abs(feet.getX() - swimTo.getX()) <= 1 && Math.abs(feet.getZ() - swimTo.getZ()) <= 1
				&& Math.abs(feet.getY() - swimTo.getY()) <= 1 && Swim.isInWater();
	}

	@Override
	public boolean isAlreadySatisfied(TaskContext ctx) {
		BlockPos resolved = resolve(ctx);
		return resolved != null && BaritoneBridge.isInGoal(goalKind(resolved), resolved, range);
	}

	@Override
	public boolean postconditionMet(TaskContext ctx) {
		if (target == null) {
			return false;
		}
		// A lakebed target counts as reached when standing on the bottom right next to it (what break_block needs).
		return BaritoneBridge.isInGoal(kind, target, range) || (swimTo != null && Swim.isNextToBed(target));
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
