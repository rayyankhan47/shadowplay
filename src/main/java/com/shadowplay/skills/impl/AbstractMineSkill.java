package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.harness.BaritoneBridge;
import com.shadowplay.harness.InventoryHelper;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillResult;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;

import java.util.Comparator;
import java.util.function.Predicate;

/**
 * Mines blocks with Baritone's mine process until the inventory holds `count` more of their drop. Baritone gets
 * no quantity limit; we count drops ourselves and stop it. If Baritone goes idle early we pick up nearby drops,
 * then re-issue the mine a couple of times before failing.
 */
public abstract class AbstractMineSkill implements Skill {
	private static final int TIMEOUT_TICKS = 20 * 90;
	private static final int NO_PROGRESS_TICKS = 20 * 30;
	private static final int MAX_REISSUES = 2;
	private static final int DROP_RADIUS = 6;

	protected final int count;
	private int target;
	private int ticks;
	private int lastCount;
	private int lastProgressTick;
	private int reissues;

	protected AbstractMineSkill(int count) {
		this.count = Math.max(1, count);
	}

	/** The blocks Baritone should mine. */
	protected abstract Block[] blocks();

	/** What counts as progress in the inventory. */
	protected abstract Predicate<ItemStack> drop();

	/** Short plural noun for messages, e.g. "logs". */
	protected abstract String what();

	/** Baseline key, unique per skill type and drop. */
	protected abstract String key();

	/** A reason the mining can't work at all (e.g. no pickaxe), or null. Checked at every start. */
	protected String precondition() {
		return null;
	}

	@Override
	public void start(TaskContext ctx) {
		target = ctx.baseline(key(), () -> InventoryHelper.count(drop())) + count;
		ticks = 0;
		reissues = 0;
		lastCount = InventoryHelper.count(drop());
		lastProgressTick = 0;
		if (lastCount < target && precondition() == null) {
			BaritoneBridge.mine(0, blocks());
		}
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		int have = InventoryHelper.count(drop());
		if (have >= target) {
			BaritoneBridge.cancelAll();
			return SkillResult.SUCCESS;
		}
		String problem = precondition();
		if (problem != null) {
			return SkillResult.fatal(problem);
		}
		ticks++;
		if (have > lastCount) {
			lastCount = have;
			lastProgressTick = ticks;
		}
		if (ticks > TIMEOUT_TICKS || ticks - lastProgressTick > NO_PROGRESS_TICKS) {
			BaritoneBridge.cancelAll();
			return SkillResult.failure("couldn't get enough " + what());
		}
		if (!BaritoneBridge.isBusy()) {
			ItemEntity straggler = nearestDrop();
			if (straggler != null) {
				BaritoneBridge.goTo(straggler.getBlockPos());
			} else if (reissues < MAX_REISSUES) {
				reissues++;
				BaritoneBridge.mine(0, blocks());
			} else {
				String failure = BaritoneBridge.lastFailure();
				return SkillResult.failure(failure != null ? failure : "no more " + what() + " nearby");
			}
		}
		return SkillResult.RUNNING;
	}

	@Override
	public void cancel() {
		BaritoneBridge.cancelAll();
	}

	@Override
	public boolean isAlreadySatisfied(TaskContext ctx) {
		Integer before = ctx.existingBaseline(key());
		return before != null && InventoryHelper.count(drop()) >= before + count;
	}

	@Override
	public boolean postconditionMet(TaskContext ctx) {
		return InventoryHelper.count(drop()) >= target;
	}

	private ItemEntity nearestDrop() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		Predicate<ItemStack> wanted = drop();
		return player.getWorld().getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(DROP_RADIUS),
						e -> e.isAlive() && wanted.test(e.getStack()))
				.stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(player))).orElse(null);
	}
}
