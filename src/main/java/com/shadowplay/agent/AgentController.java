package com.shadowplay.agent;

import com.shadowplay.Log;
import com.shadowplay.config.ShadowplayConfig;
import com.shadowplay.harness.BaritoneBridge;
import com.shadowplay.harness.Humanize;
import com.shadowplay.harness.Interact;
import com.shadowplay.harness.Swim;
import com.shadowplay.interrupt.InterruptDetector;
import com.shadowplay.llm.Planner;
import com.shadowplay.narration.Narrator;
import com.shadowplay.plan.GoldenPlans;
import com.shadowplay.plan.Plan;
import com.shadowplay.plan.PlanExecutor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;

/** The agent state machine. Owns the current task, plan and executor. All methods run on the client thread. */
public final class AgentController {
	private static final AgentController INSTANCE = new AgentController();

	private AgentState state = AgentState.IDLE;
	private String task;
	private Plan plan;
	private PlanExecutor executor;
	private TaskContext context;
	private final TorchReflex torchReflex = new TorchReflex();
	private Thinking thinking;
	/** The player's facing when the current task was requested, before any glancing around. */
	private float requestYaw;
	private Planner planner;
	private Planner.Result plannedResult;
	private long planningSinceMs;

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
			if (state == AgentState.PLANNING && thinking != null) {
				if (thinking.tick()) {
					Plan ready = thinking.plan();
					thinking = null;
					run(ready);
				}
			} else if (state == AgentState.PLANNING && planner != null) {
				if (plannedResult == null) {
					plannedResult = planner.poll();
				}
				// Even a fast answer waits out the same "thinking" time as a golden prompt.
				if (plannedResult != null && thinkingTimeElapsed()) {
					Planner.Result result = plannedResult;
					planner = null;
					plannedResult = null;
					onPlanned(result);
				}
			} else if (state == AgentState.RUNNING) {
				String input = InterruptDetector.poll(client);
				if (input != null) {
					interrupt(client, input);
				} else {
					tickRunning();
				}
			}
		} catch (RuntimeException e) {
			Log.error("Agent tick crashed", e);
			abort("internal error");
		}
	}

	private void tickRunning() {
		if (torchReflex.tick(context, executor)) {
			return;
		}
		PlanExecutor.Status result = executor.tick();
		if (result == PlanExecutor.Status.DONE) {
			Narrator.done(plan.summary(), context.elapsedSeconds());
			finish(AgentState.DONE);
		} else if (result == PlanExecutor.Status.FAILED) {
			Narrator.fail(executor.failureReason());
			finish(AgentState.FAILED);
		}
	}

	/** The player touched the controls: stop everything, hand control back, and wait for /agent resume. */
	private void interrupt(MinecraftClient client, String input) {
		Log.info("Interrupted by player input: {}", input);
		cancelActivity();
		if (InterruptDetector.isAgentScreenOpen()) {
			if (client.currentScreen != null && !(client.currentScreen instanceof GameMenuScreen)) {
				client.player.closeHandledScreen();
			}
			InterruptDetector.agentClosedScreen();
		}
		context.pauseClock();
		setState(AgentState.INTERRUPTED);
		Narrator.interrupted();
	}

	/** Entry point for /agent <task>: a golden prompt runs its pinned plan (after a thinking beat), else the planner. */
	public void startTask(String text) {
		if (!beginNewTask()) {
			return;
		}
		if (ShadowplayConfig.get().useGoldenPlans) {
			Plan golden = GoldenPlans.match(text);
			if (golden != null) {
				startThinking(golden);
				return;
			}
		}
		task = text.trim();
		if (Planner.canUseGemini()) {
			planner = new Planner(task, java.util.Map.of());
			plannedResult = null;
			planningSinceMs = System.currentTimeMillis();
			setState(AgentState.PLANNING);
			Narrator.thinking();
			planner.start();
		} else {
			onPlanned(Planner.fallback(task, "no Gemini API key"));
		}
	}

	private boolean thinkingTimeElapsed() {
		return !Humanize.enabled()
				|| System.currentTimeMillis() - planningSinceMs >= Humanize.settings().thinkingDelayMs;
	}

	private void onPlanned(Planner.Result result) {
		if (result.plan() == null) {
			Log.info("No plan for '{}' ({})", task, result.error());
			clear();
			setState(AgentState.IDLE);
			// If Gemini was down (rate limited, overloaded...) say so; otherwise the task just isn't supported.
			String line = result.userMessage() != null ? result.userMessage() : "Sorry, I can't do that yet.";
			Narrator.paced(line, net.minecraft.util.Formatting.WHITE);
			return;
		}
		Log.info("Running {} plan for '{}'", result.source(), task);
		result.plan().task = task;
		run(result.plan());
	}

	/** Starts a ready-made plan (golden or debug). */
	public void startPlan(Plan newPlan) {
		if (!beginNewTask()) {
			return;
		}
		run(newPlan);
	}

	/** The pinned plan is ready instantly, but a person would take a moment: think (and look around), then go. */
	private void startThinking(Plan ready) {
		if (!Humanize.enabled()) {
			run(ready);
			return;
		}
		task = ready.task;
		thinking = new Thinking(ready);
		setState(AgentState.PLANNING);
		Narrator.thinking();
	}

	private boolean beginNewTask() {
		requestYaw = MinecraftClient.getInstance().player.getYaw();
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
		newContext.setRequestYaw(requestYaw);
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
		InterruptDetector.startGrace();
		executor.start();
	}

	/** Continues an interrupted task from the current world state, skipping steps that are already done. */
	public void resume() {
		if (state != AgentState.INTERRUPTED || executor == null) {
			Narrator.say("Nothing to resume.");
			return;
		}
		context.resumeClock();
		setState(AgentState.RUNNING);
		InterruptDetector.startGrace();
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
		Narrator.clearPending();
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
		Narrator.clearPending();
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

	/** Releases everything the agent is doing right now: thinking/planning, the skill, Baritone, block breaking. */
	private void cancelActivity() {
		thinking = null;
		if (planner != null) {
			planner.cancel();
			planner = null;
		}
		plannedResult = null;
		torchReflex.cancel();
		if (executor != null) {
			executor.cancel();
		}
		BaritoneBridge.cancelAll();
		Interact.stopBreaking();
		Swim.release();
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
