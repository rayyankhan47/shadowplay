package com.shadowplay.harness;

import com.shadowplay.skills.SkillResult;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Placing and breaking blocks through the interaction manager, as non-blocking tasks ticked by skills. */
public final class Interact {
	private Interact() {
	}

	/**
	 * Places the held item against a face of a support block, using the crosshair hit so the server sees a
	 * legitimate interaction. Requires the item to be selected and the crosshair on that face.
	 * Returns true if the client accepted the interaction.
	 */
	public static boolean place(BlockPos support, Direction face, Item item) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		if (player == null || client.interactionManager == null) {
			return false;
		}
		if (!InventoryHelper.select(item) || !Aim.isTargeting(support, face)) {
			return false;
		}
		BlockHitResult hit = (BlockHitResult) client.crosshairTarget;
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
		if (result.isAccepted()) {
			player.swingHand(Hand.MAIN_HAND);
			return true;
		}
		return false;
	}

	/** Right-clicks the block under the crosshair (e.g. to open a crafting table). Requires the crosshair on pos. */
	public static boolean useBlock(BlockPos pos) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		if (player == null || client.interactionManager == null || !Aim.isTargeting(pos, null)) {
			return false;
		}
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, (BlockHitResult) client.crosshairTarget);
		if (result.isAccepted()) {
			player.swingHand(Hand.MAIN_HAND);
			return true;
		}
		return false;
	}

	/** One tick of mining: starts or continues breaking the block and swings the arm. */
	public static void breakBlockTick(BlockPos pos, Direction side) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.interactionManager == null) {
			return;
		}
		client.interactionManager.updateBlockBreakingProgress(pos, side);
		client.player.swingHand(Hand.MAIN_HAND);
	}

	public static void stopBreaking() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.interactionManager != null && client.interactionManager.isBreakingBlock()) {
			client.interactionManager.cancelBlockBreaking();
		}
	}

	/** Selects the fastest suitable tool for the block (hand if nothing helps). */
	public static void selectBestTool(BlockState state) {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) {
			return;
		}
		ItemStack best = null;
		float bestScore = scoreTool(player.getMainHandStack(), state);
		for (ItemStack stack : player.getInventory().main) {
			float score = scoreTool(stack, state);
			if (score > bestScore) {
				bestScore = score;
				best = stack;
			}
		}
		if (best != null) {
			ItemStack chosen = best;
			InventoryHelper.select(stack -> stack == chosen);
		}
	}

	private static float scoreTool(ItemStack stack, BlockState state) {
		float speed = stack.isEmpty() ? 1f : stack.getMiningSpeedMultiplier(state);
		boolean suitable = !state.isToolRequired() || (!stack.isEmpty() && stack.isSuitableFor(state));
		return (suitable ? 1000f : 0f) + speed;
	}

	/** Aim at a face of support, place item, then wait until the block shows up next to it. */
	public static final class PlaceTask {
		private static final int AIM_TIMEOUT_TICKS = 20;
		private static final int APPEAR_TIMEOUT_TICKS = 10;
		private static final int MAX_ATTEMPTS = 2;

		private final BlockPos support;
		private final Direction face;
		private final Item item;
		private final BlockPos target;
		private final Aim aim = new Aim();
		private int waitTicks;
		private int attempts;
		private boolean placed;

		public PlaceTask(BlockPos support, Direction face, Item item) {
			this.support = support.toImmutable();
			this.face = face;
			this.item = item;
			this.target = support.offset(face).toImmutable();
			aim.lookAtBlockFace(support, face);
		}

		public BlockPos target() {
			return target;
		}

		public SkillResult tick() {
			ClientWorld world = MinecraftClient.getInstance().world;
			if (world == null) {
				return SkillResult.failure("no world");
			}
			if (world.getBlockState(target).getBlock().asItem() == item) {
				return SkillResult.SUCCESS;
			}
			if (InventoryHelper.count(item) == 0) {
				return SkillResult.failure("no " + itemName(item) + " left");
			}
			aim.tick();
			if (!aim.isDone()) {
				return SkillResult.RUNNING;
			}
			if (placed) {
				if (++waitTicks <= APPEAR_TIMEOUT_TICKS) {
					return SkillResult.RUNNING;
				}
				placed = false;
				waitTicks = 0;
			}
			if (attempts >= MAX_ATTEMPTS) {
				return SkillResult.failure("couldn't place the " + itemName(item));
			}
			if (!Aim.isTargeting(support, face)) {
				if (++waitTicks > AIM_TIMEOUT_TICKS) {
					return SkillResult.failure("couldn't aim at the placement spot");
				}
				return SkillResult.RUNNING;
			}
			attempts++;
			waitTicks = 0;
			placed = place(support, face, item);
			return SkillResult.RUNNING;
		}
	}

	/** Aim at the best visible face, pick the best tool, and mine until the block changes or the timeout hits. */
	public static final class BreakTask {
		private static final int MIN_TIMEOUT_TICKS = 40;
		private static final int SUBMERGED_TIMEOUT_TICKS = 160;
		private static final int AIM_TIMEOUT_TICKS = 20;

		private final BlockPos pos;
		private final Block original;
		private final Aim aim = new Aim();
		private Direction face;
		private int noFaceTicks;
		private int notTargetingTicks;
		private int breakTicks;
		private int timeoutTicks = -1;

		public BreakTask(BlockPos pos) {
			this.pos = pos.toImmutable();
			ClientWorld world = MinecraftClient.getInstance().world;
			this.original = world != null ? world.getBlockState(pos).getBlock() : null;
		}

		public BlockPos pos() {
			return pos;
		}

		public SkillResult tick() {
			MinecraftClient client = MinecraftClient.getInstance();
			ClientPlayerEntity player = client.player;
			ClientWorld world = client.world;
			if (player == null || world == null) {
				return SkillResult.failure("no world");
			}
			BlockState state = world.getBlockState(pos);
			if (state.isAir() || state.getBlock() != original) {
				stopBreaking();
				return SkillResult.SUCCESS;
			}
			if (face == null) {
				face = Aim.bestVisibleFace(pos);
				if (face == null) {
					return ++noFaceTicks > AIM_TIMEOUT_TICKS ? SkillResult.failure("can't reach the block") : SkillResult.RUNNING;
				}
				aim.lookAtBlockFace(pos, face);
			}
			aim.tick();
			if (!aim.isDone()) {
				return SkillResult.RUNNING;
			}
			HitResult hit = client.crosshairTarget;
			if (!Aim.isTargeting(pos, null)) {
				if (++notTargetingTicks > AIM_TIMEOUT_TICKS) {
					return SkillResult.failure("can't aim at the block");
				}
				if (notTargetingTicks % 5 == 0) {
					face = null; // re-pick the face, the view may have changed
				}
				return SkillResult.RUNNING;
			}
			if (timeoutTicks < 0) {
				selectBestTool(state);
				float delta = state.calcBlockBreakingDelta(player, world, pos);
				int expected = delta > 0 ? (int) Math.ceil(1.0f / delta) : 20 * 60;
				timeoutTicks = Math.max(MIN_TIMEOUT_TICKS, expected * 2);
				if (player.isSubmergedInWater()) {
					timeoutTicks = Math.max(timeoutTicks, SUBMERGED_TIMEOUT_TICKS);
				}
			}
			if (++breakTicks > timeoutTicks) {
				stopBreaking();
				return SkillResult.failure("block didn't break");
			}
			breakBlockTick(pos, ((BlockHitResult) hit).getSide());
			return SkillResult.RUNNING;
		}

		public void cancel() {
			stopBreaking();
		}
	}

	public static String itemName(Item item) {
		return item.getName().getString().toLowerCase();
	}
}
