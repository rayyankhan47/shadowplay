package com.shadowplay.skills.impl;

import com.shadowplay.agent.TaskContext;
import com.shadowplay.config.ShadowplayConfig;
import com.shadowplay.perception.CaveFinder;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillArgs;
import com.shadowplay.skills.SkillResult;
import net.minecraft.util.math.BlockPos;

/**
 * Locates a cave and saves "cave_entrance" and "cave_inside". If config demo.caveEntrance is set it is used
 * directly, which keeps the demo deterministic.
 */
public class FindCaveSkill implements Skill {
	public static final String ENTRANCE = "cave_entrance";
	public static final String INSIDE = "cave_inside";

	private final int radius;

	public FindCaveSkill(SkillArgs args) {
		this.radius = Math.max(8, Math.min(64, args.getInt("radius", CaveFinder.DEFAULT_RADIUS)));
	}

	@Override
	public void start(TaskContext ctx) {
	}

	@Override
	public SkillResult tick(TaskContext ctx) {
		ShadowplayConfig.Pos override = ShadowplayConfig.get().demo.caveEntrance;
		CaveFinder.Cave cave = override != null
				? CaveFinder.fromEntrance(new BlockPos(override.x, override.y, override.z))
				: CaveFinder.find(radius);
		if (cave == null) {
			return SkillResult.fatal("no cave nearby");
		}
		ctx.putPosition(ENTRANCE, cave.entrance());
		ctx.putPosition(INSIDE, cave.inside());
		return SkillResult.SUCCESS;
	}

	@Override
	public void cancel() {
	}

	@Override
	public boolean isAlreadySatisfied(TaskContext ctx) {
		return ctx.position(ENTRANCE) != null && ctx.position(INSIDE) != null;
	}

	@Override
	public boolean postconditionMet(TaskContext ctx) {
		return isAlreadySatisfied(ctx);
	}
}
