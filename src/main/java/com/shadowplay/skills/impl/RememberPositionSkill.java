package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;
import net.minecraft.client.MinecraftClient;

/** Saves the player's current block position under a name in the task context. */
public class RememberPositionSkill implements Skill {
	private final String name;

	public RememberPositionSkill(SkillArgs args) {
		this.name = args.getString("name", "start");
	}

	@Override
	public void start(TaskContext ctx) {
		ctx.putPosition(name, MinecraftClient.getInstance().player.getBlockPos());
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		return SkillResult.SUCCESS;
	}

	@Override
	public void cancel() {
	}

	/** Never overwrite a saved position on resume: the player may have moved since (e.g. into the lake). */
	@Override
	public boolean isAlreadySatisfied(TaskContext ctx) {
		return ctx.position(name) != null;
	}
}
