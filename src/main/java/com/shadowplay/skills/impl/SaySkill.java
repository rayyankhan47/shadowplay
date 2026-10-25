package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.narration.Narrator;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;

public class SaySkill implements Skill {
	private final String text;

	public SaySkill(SkillArgs args) {
		this.text = args.getString("text", "");
	}

	@Override
	public void start(TaskContext ctx) {
		Narrator.say(text);
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		return SkillResult.SUCCESS;
	}

	@Override
	public void cancel() {
	}
}
