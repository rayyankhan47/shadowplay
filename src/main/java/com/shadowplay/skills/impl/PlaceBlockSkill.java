package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.harness.Interact;
import com.shadowplay.harness.InventoryHelper;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Places an item (e.g. a crafting table) on flat ground within reach, preferring a spot a couple of blocks in
 * front of the player, and saves where it went under `name`.
 */
public class PlaceBlockSkill implements Skill {
	private static final int SEARCH_RADIUS = 4;

	private final Item item;
	private final String name;
	private Interact.PlaceTask task;

	public PlaceBlockSkill(SkillArgs args) {
		this.item = args.getItem("item");
		this.name = args.getString("name", null);
	}

	@Override
	public void start(TaskContext ctx) {
		task = null;
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		if (task == null) {
			if (InventoryHelper.count(item) == 0) {
				return SkillResult.fatal("no " + Interact.itemName(item) + " to place");
			}
			BlockPos support = findFlatGround();
			if (support == null) {
				return SkillResult.failure("no flat spot to place the " + Interact.itemName(item));
			}
			task = new Interact.PlaceTask(support, Direction.UP, item);
		}
		SkillResult result = task.tick();
		if (result.status() == SkillResult.Status.SUCCESS && name != null) {
			ctx.putPosition(name, task.target());
		}
		return result;
	}

	@Override
	public void cancel() {
		task = null;
	}

	@Override
	public boolean isAlreadySatisfied(TaskContext ctx) {
		BlockPos placed = name != null ? ctx.position(name) : null;
		return placed != null && isPlacedAt(placed);
	}

	@Override
	public boolean postconditionMet(TaskContext ctx) {
		return task != null && isPlacedAt(task.target());
	}

	private boolean isPlacedAt(BlockPos pos) {
		return MinecraftClient.getInstance().world.getBlockState(pos).getBlock().asItem() == item;
	}

	/** Solid top face, two air blocks above, not overlapping the player, top face visible and within reach. */
	private static BlockPos findFlatGround() {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		Vec3d eye = player.getEyePos();
		Vec3d facing = Vec3d.fromPolar(0, player.getYaw());
		double reach = player.getBlockInteractionRange() - 0.5;
		Box playerBox = player.getBoundingBox();
		BlockPos feet = player.getBlockPos();

		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.iterate(feet.add(-SEARCH_RADIUS, -2, -SEARCH_RADIUS), feet.add(SEARCH_RADIUS, 1, SEARCH_RADIUS))) {
			BlockPos above = pos.up();
			if (!world.getBlockState(pos).isSideSolidFullSquare(world, pos, Direction.UP)
					|| !world.getBlockState(above).isAir() || !world.getBlockState(above.up()).isAir()
					|| new Box(above).intersects(playerBox)) {
				continue;
			}
			Vec3d top = Vec3d.ofCenter(pos).add(0, 0.5, 0);
			if (eye.distanceTo(top) > reach || !topVisible(world, player, eye, pos)) {
				continue;
			}
			Vec3d flat = new Vec3d(top.x - player.getX(), 0, top.z - player.getZ());
			double horizontal = flat.length();
			double ahead = horizontal > 0 ? flat.normalize().dotProduct(facing) : -1;
			double score = Math.abs(horizontal - 2.5) + (1 - ahead) * 1.5 + Math.abs(pos.getY() + 1 - feet.getY()) * 0.5;
			if (score < bestScore) {
				bestScore = score;
				best = pos.toImmutable();
			}
		}
		return best;
	}

	private static boolean topVisible(ClientWorld world, ClientPlayerEntity player, Vec3d eye, BlockPos pos) {
		Vec3d inside = Vec3d.ofCenter(pos).add(0, 0.4, 0);
		BlockHitResult hit = world.raycast(new RaycastContext(eye, inside, RaycastContext.ShapeType.OUTLINE,
				RaycastContext.FluidHandling.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && hit.getSide() == Direction.UP;
	}
}
