package com.shadowplay.agent;

import com.shadowplay.Log;
import com.shadowplay.narration.Narrator;
import com.shadowplay.plan.Plan;
import com.shadowplay.plan.PlanExecutor;
import net.minecraft.client.MinecraftClient;

/** The agent state machine. Owns the current task, plan and executor. All methods run on the client thread. */
public final class AgentController {
	private static final AgentController INSTANCE = new AgentController();

	private AgentState state = AgentState.IDLE;
	private String task;
	private Plan plan;
	private PlanExecutor executor;
	private TaskContext context;

	private AgentController() {
	}

	public static AgentController get() {
		return INSTANCE;
	}

	public AgentState state() {
		return state;
	}

	public void tick(MinecraftClient client) {
		if (client.player == null || client.world == null) {
			return;
		}
		try {
			if (state == AgentState.RUNNING) {
				tickRunning();
			}
		} catch (RuntimeException e) {
			Log.error("Agent tick crashed", e);
			abort("internal error");
		}
	}

	private void tickRunning() {
		PlanExecutor.Status result = executor.tick();
		if (result == PlanExecutor.Status.DONE) {
			Narrator.done(plan.summary(), context.elapsedSeconds());
			finish(AgentState.DONE);
		} else if (result == PlanExecutor.Status.FAILED) {
			Narrator.fail(executor.failureReason());
			finish(AgentState.FAILED);
		}
	}

	/** Entry point for /agent <task>. */
	public void startTask(String text) {
		if (!beginNewTask()) {
			return;
		}
		Narrator.say("I can't plan tasks yet.");
	}

	/** Starts a ready-made plan (golden or debug). */
	public void startPlan(Plan newPlan) {
		if (!beginNewTask()) {
			return;
		}
		run(newPlan);
	}

	private boolean beginNewTask() {
		if (state.isBusy()) {
			Narrator.say("Already working on a task. Use '/agent stop' first.");
			return false;
		}
		if (state == AgentState.INTERRUPTED) {
			clear();
			Narrator.say("Discarded the interrupted task.");
		}
		return true;
	}

	private void run(Plan newPlan) {
		TaskContext newContext = new TaskContext();
		PlanExecutor newExecutor;
		try {
			newExecutor = new PlanExecutor(newPlan, newContext);
		} catch (IllegalArgumentException e) {
			Log.warn("Rejected plan: {}", e.getMessage());
			Narrator.fail("the plan was invalid (" + e.getMessage() + ")");
			finish(AgentState.FAILED);
			return;
		}
		task = newPlan.task;
		plan = newPlan;
		context = newContext;
		executor = newExecutor;
		setState(AgentState.RUNNING);
		executor.start();
	}

	public void resume() {
		if (state != AgentState.INTERRUPTED || executor == null) {
			Narrator.say("Nothing to resume.");
			return;
		}
		setState(AgentState.RUNNING);
		executor.resumeFrom(context);
	}

	public void stop() {
		if (state != AgentState.PLANNING && state != AgentState.RUNNING && state != AgentState.INTERRUPTED) {
			Narrator.say("Nothing to stop.");
			return;
		}
		cancelActivity();
		clear();
		setState(AgentState.IDLE);
		Narrator.say("Stopped.");
	}

	public void status() {
		StringBuilder sb = new StringBuilder("Status: ").append(state.name());
		if (task != null) {
			sb.append(" · task: ").append(task);
		}
		if (executor != null && (state == AgentState.RUNNING || state == AgentState.INTERRUPTED)) {
			sb.append(" · step ").append(executor.currentStepNumber()).append('/').append(executor.totalSteps())
					.append(' ').append(executor.currentLabel());
		}
		Narrator.say(sb.toString());
	}

	/** Leaving the world wipes everything; nothing from the old session is reused. */
	public void onDisconnect() {
		cancelActivity();
		clear();
		setState(AgentState.IDLE);
	}

	private void abort(String reason) {
		cancelActivity();
		Narrator.fail(reason);
		finish(AgentState.FAILED);
	}

	private void finish(AgentState end) {
		clear();
		setState(end);
	}

	/** Releases everything the agent is doing right now (skill, keys, screens). */
	private void cancelActivity() {
		if (executor != null) {
			executor.cancel();
		}
	}

	private void clear() {
		task = null;
		plan = null;
		executor = null;
		context = null;
	}

	private void setState(AgentState next) {
		if (state != next) {
			Log.info("State {} -> {}", state, next);
			state = next;
		}
	}
}
