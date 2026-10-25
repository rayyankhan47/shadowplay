package com.shadowplay.plan;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/** Hardcoded plans for manual testing via /agent debug [...]. */
public final class DebugPlans {
	private DebugPlans() {
	}

	public static Plan basic() {
		return Plan.fromJson("""
				{
				  "task": "debug",
				  "summary": "debug plan finished",
				  "steps": [
				    { "skill": "say", "args": { "text": "Hello from the executor" }, "label": "Say hello" },
				    { "skill": "wait", "args": { "ticks": 40 }, "label": "Wait 2 seconds" },
				    { "skill": "say", "args": { "text": "Still here" }, "label": "Say again" }
				  ]
				}
				""");
	}

	/** Walk 10 blocks the way the player is facing, then come back. */
	public static Plan walkOutAndBack() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		float yaw = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
		BlockPos feet = player.getBlockPos();
		int x = feet.getX() + Math.round(-MathHelper.sin(yaw) * 10);
		int z = feet.getZ() + Math.round(MathHelper.cos(yaw) * 10);
		return Plan.fromJson("""
				{
				  "task": "debug goto",
				  "summary": "walked out and back",
				  "steps": [
				    { "skill": "remember_position", "args": { "name": "home" }, "label": "Remember home" },
				    { "skill": "goto", "args": { "pos": [%d, %d, %d], "range": 1 }, "label": "Walk 10 blocks ahead" },
				    { "skill": "goto", "args": { "name": "home" }, "label": "Walk back home" }
				  ]
				}
				""".formatted(x, feet.getY(), z));
	}

	/** Break the block under the crosshair and pick up its drop. Returns null if not looking at a block. */
	public static Plan breakLookedAt() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		BlockPos pos = hit.getBlockPos();
		BlockState state = client.world.getBlockState(pos);
		String item = Registries.ITEM.getId(state.getBlock().asItem()).toString();
		return Plan.fromJson("""
				{
				  "task": "debug break",
				  "summary": "broke the block",
				  "steps": [
				    { "skill": "break_block", "args": { "pos": [%d, %d, %d] }, "label": "Break the block" },
				    { "skill": "collect_drops", "args": { "item": "%s" }, "label": "Pick up the drop" }
				  ]
				}
				""".formatted(pos.getX(), pos.getY(), pos.getZ(), item));
	}
}
