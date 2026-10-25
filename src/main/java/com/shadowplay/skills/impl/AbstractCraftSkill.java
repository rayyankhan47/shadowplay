package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.harness.CraftingHelper;
import com.shadowplay.harness.Interact;
import com.shadowplay.harness.InventoryHelper;
import com.shadowplay.harness.Recipes;
import com.shadowplay.interrupt.InterruptDetector;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;

/**
 * Shared crafting flow: check ingredients, open a crafting screen (subclass), run one slot-click craft per
 * repetition, return leftovers, close. Postcondition: item count ≥ count before the step + requested count.
 */
public abstract class AbstractCraftSkill implements Skill {
	private static final int SHOW_SCREEN_TICKS = 4;

	private enum Phase {
		OPENING,
		CRAFTING,
		CLOSING
	}

	protected final Item item;
	protected final int count;
	protected final Recipes.Recipe recipe;

	private int target;
	private int craftsLeft;
	private int showTicks;
	private Phase phase;
	private final CraftingHelper helper = new CraftingHelper();

	protected AbstractCraftSkill(SkillArgs args) {
		this.item = args.getItem("item");
		this.count = Math.max(1, args.getInt("count", 1));
		this.recipe = item != null ? Recipes.find(item) : null;
	}

	protected abstract CraftingHelper.Layout layout();

	/** Get the crafting screen open. RUNNING until it is, SUCCESS once it is, FAILURE if it can't be. */
	protected abstract SkillResult open(TaskContext ctx);

	/** Release anything open() started (e.g. pathing). */
	protected void cancelOpen() {
	}

	@Override
	public void start(TaskContext ctx) {
		helper.clear();
		phase = Phase.OPENING;
		showTicks = 0;
		target = ctx.baseline(baselineKey(), () -> InventoryHelper.count(item)) + count;
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		if (recipe == null) {
			return SkillResult.fatal("I don't know how to craft " + (item != null ? Interact.itemName(item) : "that"));
		}
		switch (phase) {
			case OPENING -> {
				int have = InventoryHelper.count(item);
				if (have >= target) {
					return SkillResult.SUCCESS;
				}
				craftsLeft = recipe.craftsFor(target - have);
				String missing = recipe.missingFor(craftsLeft);
				if (missing != null) {
					return SkillResult.fatal(missing);
				}
				SkillResult opened = open(ctx);
				if (opened.status() != SkillResult.Status.SUCCESS) {
					return opened;
				}
				// Hold the open screen for a moment so the audience sees it before items start moving.
				if (++showTicks >= SHOW_SCREEN_TICKS) {
					phase = Phase.CRAFTING;
				}
				return SkillResult.RUNNING;
			}
			case CRAFTING -> {
				helper.tick();
				if (helper.failure() != null) {
					closeScreen();
					return SkillResult.failure(helper.failure());
				}
				if (!helper.isIdle()) {
					return SkillResult.RUNNING;
				}
				if (craftsLeft > 0) {
					String error = helper.placeIngredients(layout(), recipe.gridFor(layout()));
					if (error != null) {
						closeScreen();
						return SkillResult.failure(error);
					}
					helper.takeResult(stack -> stack.isOf(item), "the " + Interact.itemName(item) + " recipe didn't work");
					craftsLeft--;
				} else {
					helper.returnGrid(layout());
					phase = Phase.CLOSING;
				}
				return SkillResult.RUNNING;
			}
			case CLOSING -> {
				helper.tick();
				if (!helper.isIdle() && helper.failure() == null) {
					return SkillResult.RUNNING;
				}
				closeScreen();
				return InventoryHelper.count(item) >= target
						? SkillResult.SUCCESS
						: SkillResult.failure("crafting made too few " + Interact.itemName(item));
			}
		}
		return SkillResult.RUNNING;
	}

	@Override
	public void cancel() {
		helper.clear();
		closeScreen();
		cancelOpen();
	}

	@Override
	public boolean isAlreadySatisfied(TaskContext ctx) {
		Integer before = ctx.existingBaseline(baselineKey());
		return before != null && InventoryHelper.count(item) >= before + count;
	}

	@Override
	public boolean postconditionMet(TaskContext ctx) {
		return InventoryHelper.count(item) >= target;
	}

	/** Closes the crafting screen if the agent opened it. */
	protected static void closeScreen() {
		if (!InterruptDetector.isAgentScreenOpen()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		if (player != null && client.currentScreen != null) {
			player.closeHandledScreen();
		}
		InterruptDetector.agentClosedScreen();
	}

	private String baselineKey() {
		return "craft:" + (item != null ? Registries.ITEM.getId(item) : "?");
	}
}
