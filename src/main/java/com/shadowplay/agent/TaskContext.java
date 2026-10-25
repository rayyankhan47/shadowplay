package com.shadowplay.agent;

/** Shared per-task data. Lives from task start until the task is discarded, finished, or the world is left. */
public class TaskContext {
	private final long startTimeMs = System.currentTimeMillis();

	public long startTimeMs() {
		return startTimeMs;
	}

	public long elapsedSeconds() {
		return (System.currentTimeMillis() - startTimeMs) / 1000;
	}
}
