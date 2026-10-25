package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;

public class WaitSkill implements Skill {
	private final int ticks;
	private int remaining;

	public WaitSkill(SkillArgs args) {
		this.ticks = Math.max(0, args.getInt("ticks", 20));
	}

	@Override
	public void start(TaskContext ctx) {
		remaining = ticks;
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		return remaining-- <= 0 ? SkillResult.SUCCESS : SkillResult.RUNNING;
	}

	@Override
	public void cancel() {
		remaining = 0;
	}
}
