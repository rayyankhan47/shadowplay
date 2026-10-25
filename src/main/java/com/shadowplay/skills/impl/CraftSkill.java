package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.harness.CraftingHelper;
import com.shadowplay.interrupt.InterruptDetector;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;

/** 2×2 crafting in the player's own inventory screen, opened visibly. */
public class CraftSkill extends AbstractCraftSkill {
	public CraftSkill(SkillArgs args) {
		super(args);
	}

	@Override
	protected CraftingHelper.Layout layout() {
		return CraftingHelper.Layout.PLAYER;
	}

	@Override
	protected SkillResult open(TaskContext ctx) {
		if (recipe.size() > 2) {
			return SkillResult.fatal("that needs a crafting table");
		}
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		if (client.currentScreen instanceof InventoryScreen && player.currentScreenHandler == player.playerScreenHandler) {
			return SkillResult.SUCCESS;
		}
		client.setScreen(new InventoryScreen(player));
		InterruptDetector.agentOpenedScreen();
		return SkillResult.RUNNING;
	}
}
