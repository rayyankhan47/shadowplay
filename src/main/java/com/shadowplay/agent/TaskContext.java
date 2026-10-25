package com.shadowplay.agent;

import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/** Shared per-task data. Lives from task start until the task is discarded, finished, or the world is left. */
public class TaskContext {
	private final long startTimeMs = System.currentTimeMillis();
	private final Map<String, BlockPos> positions = new HashMap<>();
	private final Map<String, Integer> baselines = new HashMap<>();
	private int stepIndex = 0;

	public boolean torchReflexEnabled = false;

	public long startTimeMs() {
		return startTimeMs;
	}

	public long elapsedSeconds() {
		return (System.currentTimeMillis() - startTimeMs) / 1000;
	}

	public void putPosition(String name, BlockPos pos) {
		positions.put(name, pos.toImmutable());
	}

	public BlockPos position(String name) {
		return positions.get(name);
	}

	public Map<String, BlockPos> positions() {
		return positions;
	}

	/** Set by the executor before every skill call so baselines are scoped to the current step. */
	public void setStepIndex(int stepIndex) {
		this.stepIndex = stepIndex;
	}

	public int stepIndex() {
		return stepIndex;
	}

	/**
	 * The value of a counter (usually an item count) the first time the current step asked for it. Survives retries
	 * and resume, so "count +N" postconditions keep the original target instead of moving the goalposts.
	 */
	public int baseline(String key, IntSupplier current) {
		return baselines.computeIfAbsent(stepIndex + ":" + key, k -> current.getAsInt());
	}

	/** The stored baseline for the current step, or null if the step never captured one. */
	public Integer existingBaseline(String key) {
		return baselines.get(stepIndex + ":" + key);
	}
}
