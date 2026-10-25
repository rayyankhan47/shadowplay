package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.harness.BaritoneBridge;
import com.shadowplay.harness.Interact;
import com.shadowplay.harness.InventoryHelper;
import com.shadowplay.harness.Swim;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.List;

/** Walks to nearby dropped items of a type until the inventory count goes up. */
public class CollectDropsSkill implements Skill {
	private static final int TIMEOUT_TICKS = 20 * 8;

	private final Item item;
	private final int radius;

	private int baseline;
	private int ticks;
	private BlockPos pathedTo;

	public CollectDropsSkill(SkillArgs args) {
		this.item = args.getItem("item");
		this.radius = Math.max(1, args.getInt("radius", 8));
	}

	@Override
	public void start(TaskContext ctx) {
		Integer beforeBreak = ctx.countBeforePreviousBreak(item);
		baseline = ctx.baseline("collect:" + Registries.ITEM.getId(item), () -> beforeBreak != null ? beforeBreak : InventoryHelper.count(item));
		ticks = 0;
		pathedTo = null;
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		if (InventoryHelper.count(item) > baseline) {
			stopPathing();
			Swim.release();
			return SkillResult.SUCCESS;
		}
		if (++ticks > TIMEOUT_TICKS) {
			stopPathing();
			Swim.release();
			return SkillResult.failure("couldn't pick up the " + Interact.itemName(item));
		}
		ItemEntity drop = nearestDrop();
		if (drop == null) {
			Swim.release();
			return SkillResult.RUNNING;
		}
		if (Swim.isInWater()) {
			// Drops float up in water and Baritone does not swim below the surface: steer to the item directly,
			// re-targeting it every tick.
			stopPathing();
			Swim.towardTick(drop.getPos());
			return SkillResult.RUNNING;
		}
		Swim.release();
		BlockPos at = drop.getBlockPos();
		if (pathedTo == null || !pathedTo.equals(at) || !BaritoneBridge.isBusy()) {
			pathedTo = at;
			BaritoneBridge.goTo(at);
		}
		return SkillResult.RUNNING;
	}

	@Override
	public void cancel() {
		stopPathing();
		Swim.release();
	}

	@Override
	public boolean isAlreadySatisfied(TaskContext ctx) {
		Integer existing = ctx.existingBaseline("collect:" + Registries.ITEM.getId(item));
		return existing != null && InventoryHelper.count(item) > existing;
	}

	@Override
	public boolean postconditionMet(TaskContext ctx) {
		return InventoryHelper.count(item) > baseline;
	}

	private void stopPathing() {
		if (pathedTo != null) {
			BaritoneBridge.cancelAll();
			pathedTo = null;
		}
	}

	private ItemEntity nearestDrop() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		List<ItemEntity> drops = player.getWorld().getEntitiesByClass(ItemEntity.class,
				player.getBoundingBox().expand(radius), e -> e.isAlive() && e.getStack().isOf(item));
		return drops.stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(player))).orElse(null);
	}
}
