package com.shadowplay.agent;

import com.shadowplay.Log;
import com.shadowplay.narration.Narrator;
import net.minecraft.client.MinecraftClient;

/** The agent state machine. Owns the current task. All methods run on the client thread. */
public final class AgentController {
	private static final AgentController INSTANCE = new AgentController();

	private AgentState state = AgentState.IDLE;
	private String task;
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
	}

	public void startTask(String text) {
		if (state.isBusy()) {
			Narrator.say("Already working on a task. Use '/agent stop' first.");
			return;
		}
		if (state == AgentState.INTERRUPTED) {
			clear();
			Narrator.say("Discarded the interrupted task.");
		}
		task = text.trim();
		context = new TaskContext();
		setState(AgentState.RUNNING);
		Narrator.say("Task: " + task);
	}

	public void resume() {
		if (state != AgentState.INTERRUPTED) {
			Narrator.say("Nothing to resume.");
			return;
		}
		setState(AgentState.RUNNING);
	}

	public void stop() {
		if (state != AgentState.PLANNING && state != AgentState.RUNNING && state != AgentState.INTERRUPTED) {
			Narrator.say("Nothing to stop.");
			return;
		}
		clear();
		setState(AgentState.IDLE);
		Narrator.say("Stopped.");
	}

	public void status() {
		StringBuilder sb = new StringBuilder("Status: ").append(state.name());
		if (task != null) {
			sb.append(" · task: ").append(task);
		}
		Narrator.say(sb.toString());
	}

	/** Leaving the world wipes everything; nothing from the old session is reused. */
	public void onDisconnect() {
		clear();
		setState(AgentState.IDLE);
	}

	private void clear() {
		task = null;
		context = null;
	}

	private void setState(AgentState next) {
		if (state != next) {
			Log.info("State {} -> {}", state, next);
			state = next;
		}
	}
}
