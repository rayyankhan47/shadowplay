package com.shadowplay.agent;

public enum AgentState {
	IDLE,
	PLANNING,
	RUNNING,
	INTERRUPTED,
	DONE,
	FAILED;

	/** True while a task owns the player; new tasks are rejected in these states. */
	public boolean isBusy() {
		return this == PLANNING || this == RUNNING;
	}
}
