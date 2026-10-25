package com.shadowplay.skills;

import com.shadowplay.agent.TaskContext;

/**
 * A non-blocking unit of work. The executor calls start() once, then tick() every client tick until it returns
 * SUCCESS or FAILURE. cancel() must release everything (keys, Baritone, screens) immediately.
 * A failed attempt is cancelled and start() is called again on the same instance for a retry.
 */
public interface Skill {
	void start(TaskContext ctx);

	SkillResult tick(TaskContext ctx);

	void cancel();

	/** Checked on resume: true means this step's goal already holds and it can be skipped. */
	default boolean isAlreadySatisfied(TaskContext ctx) {
		return false;
	}

	/** Checked after tick() returns SUCCESS; false turns the success into a failed attempt. */
	default boolean postconditionMet(TaskContext ctx) {
		return true;
	}
}
