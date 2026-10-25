package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.config.ShadowplayConfig;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;

/** enable_torch_reflex / disable_torch_reflex: toggles the torch reflex for the rest of the task. */
public class TorchReflexSkill implements Skill {
	private final boolean enable;
	private final Integer threshold;
	private final Integer spacing;

	private TorchReflexSkill(SkillArgs args, boolean enable) {
		this.enable = enable;
		this.threshold = args.has("threshold") ? args.getInt("threshold", 8) : null;
		this.spacing = args.has("spacing") ? args.getInt("spacing", 5) : null;
	}

	public static TorchReflexSkill enable(SkillArgs args) {
		return new TorchReflexSkill(args, true);
	}

	public static TorchReflexSkill disable(SkillArgs args) {
		return new TorchReflexSkill(args, false);
	}

	@Override
	public void start(TaskContext ctx) {
		if (enable) {
			ShadowplayConfig config = ShadowplayConfig.get();
			ctx.torchLightThreshold = threshold != null ? threshold : config.torchLightThreshold;
			ctx.torchSpacing = spacing != null ? spacing : config.torchSpacing;
		}
		ctx.torchReflexEnabled = enable;
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		return SkillResult.SUCCESS;
	}

	@Override
	public void cancel() {
	}

	@Override
	public boolean isAlreadySatisfied(TaskContext ctx) {
		return ctx.torchReflexEnabled == enable;
	}
}
