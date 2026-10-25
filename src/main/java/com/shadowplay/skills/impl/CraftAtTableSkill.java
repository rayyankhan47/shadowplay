package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.harness.Aim;
import com.shadowplay.harness.BaritoneBridge;
import com.shadowplay.harness.CraftingHelper;
import com.shadowplay.harness.Interact;
import com.shadowplay.interrupt.InterruptDetector;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** 3×3 crafting at a crafting table: walks into reach if needed, opens the table, crafts, closes. */
public class CraftAtTableSkill extends AbstractCraftSkill {
	private static final String DEFAULT_TABLE_NAME = "crafting_table";
	private static final int TABLE_SEARCH_RADIUS = 8;
	private static final int APPROACH_TIMEOUT_TICKS = 20 * 30;
	private static final int OPEN_TIMEOUT_TICKS = 60;
	private static final int AIM_TIMEOUT_TICKS = 20;

	private final String tableName;
	private BlockPos table;
	private final Aim aim = new Aim();
	private boolean approaching;
	private int approachTicks;
	private boolean aiming;
	private int aimTicks;
	private int openWaitTicks = -1;

	public CraftAtTableSkill(SkillArgs args) {
		super(args);
		this.tableName = args.getString("table", null);
	}

	@Override
	protected CraftingHelper.Layout layout() {
		return CraftingHelper.Layout.TABLE;
	}

	@Override
	public void start(TaskContext ctx) {
		super.start(ctx);
		table = null;
		approaching = false;
		approachTicks = 0;
		aiming = false;
		aimTicks = 0;
		openWaitTicks = -1;
	}

	@Override
	protected SkillResult open(TaskContext ctx) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		if (player.currentScreenHandler instanceof CraftingScreenHandler && openWaitTicks >= 0) {
			if (!InterruptDetector.isAgentScreenOpen()) {
				InterruptDetector.agentOpenedScreen();
			}
			return SkillResult.SUCCESS;
		}
		if (table == null) {
			table = findTable(ctx);
			if (table == null) {
				return SkillResult.fatal("no crafting table nearby");
			}
		}
		if (client.world.getBlockState(table).getBlock() != Blocks.CRAFTING_TABLE) {
			return SkillResult.fatal("the crafting table is gone");
		}
		if (openWaitTicks >= 0) {
			return ++openWaitTicks > OPEN_TIMEOUT_TICKS ? SkillResult.failure("the crafting table didn't open") : SkillResult.RUNNING;
		}
		if (!inReach(player, table)) {
			if (!approaching) {
				approaching = true;
				BaritoneBridge.goToBlock(table);
			} else if (++approachTicks > APPROACH_TIMEOUT_TICKS || !BaritoneBridge.isBusy()) {
				BaritoneBridge.cancelAll();
				return SkillResult.failure("can't reach the crafting table");
			}
			return SkillResult.RUNNING;
		}
		if (approaching) {
			BaritoneBridge.cancelAll();
			approaching = false;
		}
		if (!aiming) {
			Direction face = Aim.bestVisibleFace(table);
			aim.lookAtBlockFace(table, face != null ? face : Direction.UP);
			aiming = true;
		}
		aim.tick();
		if (!aim.isDone()) {
			return SkillResult.RUNNING;
		}
		if (!Aim.isTargeting(table, null)) {
			if (++aimTicks > AIM_TIMEOUT_TICKS) {
				return SkillResult.failure("can't aim at the crafting table");
			}
			if (aimTicks % 5 == 0) {
				aiming = false;
			}
			return SkillResult.RUNNING;
		}
		if (Interact.useBlock(table)) {
			openWaitTicks = 0;
		}
		return SkillResult.RUNNING;
	}

	@Override
	protected void cancelOpen() {
		if (approaching) {
			BaritoneBridge.cancelAll();
			approaching = false;
		}
	}

	private static boolean inReach(ClientPlayerEntity player, BlockPos pos) {
		return player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) <= player.getBlockInteractionRange() - 0.5;
	}

	private BlockPos findTable(TaskContext ctx) {
		ClientWorld world = MinecraftClient.getInstance().world;
		BlockPos named = ctx.position(tableName != null ? tableName : DEFAULT_TABLE_NAME);
		if (named != null && world.getBlockState(named).getBlock() == Blocks.CRAFTING_TABLE) {
			return named;
		}
		if (tableName != null) {
			return null;
		}
		BlockPos feet = MinecraftClient.getInstance().player.getBlockPos();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		int r = TABLE_SEARCH_RADIUS;
		for (BlockPos pos : BlockPos.iterate(feet.add(-r, -r, -r), feet.add(r, r, r))) {
			if (world.getBlockState(pos).getBlock() == Blocks.CRAFTING_TABLE) {
				double d = pos.getSquaredDistance(feet);
				if (d < bestDist) {
					bestDist = d;
					best = pos.toImmutable();
				}
			}
		}
		return best;
	}
}
