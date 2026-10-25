package com.shadowplay.plan;

import com.shadowplay.Log;
import com.shadowplay.agent.TaskContext;
import com.shadowplay.harness.Humanize;
import com.shadowplay.narration.Narrator;
import com.shadowplay.skills.Skill;
import com.shadowplay.skills.SkillRegistry;
import com.shadowplay.skills.SkillResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a plan's steps in order, one tick at a time. Checks postconditions, retries a failed step up to
 * MAX_RETRIES times, and supports cancel + resume.
 */
public final class PlanExecutor {
	public enum Status {
		RUNNING,
		DONE,
		FAILED
	}

	private static final int MAX_RETRIES = 2;
	private static final int RETRY_DELAY_TICKS = 10;
	/** A human beat between finishing one step and starting the next (humanize). */
	private static final int STEP_GAP_MS = 600;

	private final Plan plan;
	private final TaskContext ctx;
	private final List<Skill> skills = new ArrayList<>();

	private int index = 0;
	private int retries = 0;
	private int retryDelay = 0;
	private boolean stepActive = false;
	private boolean paused = false;
	private boolean resumeActiveStep = false;
	private int nextStepIn = -1;
	private Status status = Status.RUNNING;
	private String failureReason;

	/** Builds every step's skill up front, so an invalid plan fails before anything moves. */
	public PlanExecutor(Plan plan, TaskContext ctx) {
		this.plan = plan;
		this.ctx = ctx;
		if (plan.steps.isEmpty()) {
			throw new IllegalArgumentException("the plan has no steps");
		}
		for (PlanStep step : plan.steps) {
			skills.add(SkillRegistry.create(step));
		}
	}

	public void start() {
		Narrator.plan(plan.labels());
		scheduleStep(0);
	}

	public Status tick() {
		if (status != Status.RUNNING) {
			return status;
		}
		Skill skill = skills.get(index);
		ctx.setStepIndex(index);

		if (paused) {
			return status;
		}
		if (nextStepIn >= 0) {
			if (nextStepIn-- <= 0) {
				nextStepIn = -1;
				beginStep(index);
			}
			return status;
		}
		if (retryDelay > 0) {
			if (--retryDelay == 0) {
				safeStart(skill);
			}
			return status;
		}
		if (!stepActive) {
			return status;
		}

		SkillResult result;
		try {
			result = skill.tick(ctx);
		} catch (RuntimeException e) {
			Log.error("Skill {} crashed", plan.steps.get(index).skill, e);
			result = SkillResult.failure("internal error");
		}

		switch (result.status()) {
			case RUNNING -> {
			}
			case SUCCESS -> {
				if (skill.postconditionMet(ctx)) {
					stepActive = false;
					scheduleStep(index + 1);
				} else {
					handleFailure(skill, "result not confirmed");
				}
			}
			case FAILURE -> {
				if (result.retryable()) {
					handleFailure(skill, result.reason());
				} else {
					safeCancel(skill);
					stepActive = false;
					giveUp(result.reason());
				}
			}
		}
		return status;
	}

	/** Stops the current step and releases everything it holds. The executor can be resumed afterwards. */
	public void cancel() {
		if (stepActive || retryDelay > 0) {
			safeCancel(skills.get(index));
		}
		stepActive = false;
		retryDelay = 0;
		paused = false;
		nextStepIn = -1;
	}

	/** Temporarily stops the current step (e.g. for the torch reflex) without counting it as a failure. */
	public void pauseCurrent() {
		if (paused || status != Status.RUNNING) {
			return;
		}
		paused = true;
		resumeActiveStep = stepActive;
		if (stepActive) {
			ctx.setStepIndex(index);
			safeCancel(skills.get(index));
			stepActive = false;
		}
	}

	/** Restarts the step stopped by pauseCurrent(); its baselines live in the context, so progress is kept. */
	public void resumeCurrent() {
		if (!paused) {
			return;
		}
		paused = false;
		if (resumeActiveStep && status == Status.RUNNING) {
			ctx.setStepIndex(index);
			safeStart(skills.get(index));
		}
	}

	/**
	 * Continues after an interrupt from the step that was running: rebuilds fresh skill instances for the remaining
	 * steps, skips those whose goal already holds, and starts the first one that does not.
	 */
	public void resumeFrom(TaskContext resumeCtx) {
		cancel();
		for (int i = index; i < skills.size(); i++) {
			skills.set(i, SkillRegistry.create(plan.steps.get(i)));
		}
		int next = index;
		while (next < skills.size()) {
			resumeCtx.setStepIndex(next);
			if (!skills.get(next).isAlreadySatisfied(resumeCtx)) {
				break;
			}
			Log.info("Resume: step {} already satisfied, skipping", next + 1);
			next++;
		}
		retries = 0;
		status = Status.RUNNING;
		beginStep(next);
	}

	public Status status() {
		return status;
	}

	public String failureReason() {
		return failureReason;
	}

	public Plan plan() {
		return plan;
	}

	/** 1-based index of the current step, for status lines. */
	public int currentStepNumber() {
		return Math.min(index + 1, skills.size());
	}

	public int totalSteps() {
		return skills.size();
	}

	public String currentLabel() {
		return index < plan.steps.size() ? plan.steps.get(index).label() : "";
	}

	/** Starts step `next` after a short beat (immediately when humanize is off or the plan is finished). */
	private void scheduleStep(int next) {
		index = next;
		int gap = Humanize.ticks(STEP_GAP_MS);
		if (next >= skills.size() || gap <= 0) {
			beginStep(next);
		} else {
			nextStepIn = gap;
		}
	}

	private void beginStep(int next) {
		index = next;
		retries = 0;
		if (index >= skills.size()) {
			status = Status.DONE;
			return;
		}
		ctx.setStepIndex(index);
		PlanStep step = plan.steps.get(index);
		if (!step.quiet) {
			Narrator.step(narratedNumber(index), narratedTotal(), step.label());
		}
		safeStart(skills.get(index));
	}

	/** 1-based position of step i among the narrated steps, so "▶ n/N" skips quiet ones. */
	private int narratedNumber(int i) {
		int n = 0;
		for (int j = 0; j <= i; j++) {
			if (!plan.steps.get(j).quiet) {
				n++;
			}
		}
		return n;
	}

	private int narratedTotal() {
		return (int) plan.steps.stream().filter(step -> !step.quiet).count();
	}

	private void handleFailure(Skill skill, String reason) {
		safeCancel(skill);
		stepActive = false;
		if (retries < MAX_RETRIES) {
			retries++;
			Log.info("Step {} failed ({}), retry {}/{}", index + 1, reason, retries, MAX_RETRIES);
			Narrator.retry(reason);
			retryDelay = RETRY_DELAY_TICKS;
		} else {
			giveUp(reason);
		}
	}

	private void giveUp(String reason) {
		Log.info("Step {} failed ({}), giving up", index + 1, reason);
		failureReason = reason;
		status = Status.FAILED;
	}

	private void safeStart(Skill skill) {
		try {
			skill.start(ctx);
			stepActive = true;
		} catch (RuntimeException e) {
			Log.error("Skill {} failed to start", plan.steps.get(index).skill, e);
			handleFailure(skill, "internal error");
		}
	}

	private void safeCancel(Skill skill) {
		try {
			skill.cancel();
		} catch (RuntimeException e) {
			Log.error("Skill {} failed to cancel", plan.steps.get(index).skill, e);
		}
	}
}
