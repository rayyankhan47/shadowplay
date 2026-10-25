package com.shadowplay.agent;

import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/** Shared per-task data. Lives from task start until the task is discarded, finished, or the world is left. */
public class TaskContext {
	private final long startTimeMs = System.currentTimeMillis();
	private long pausedMs = 0;
	private long pausedAtMs = -1;
	private final Map<String, BlockPos> positions = new HashMap<>();
	private final Map<String, Integer> baselines = new HashMap<>();
	private int stepIndex = 0;
	private Float requestYaw;
	private Map<Item, Integer> breakSnapshot;
	private int breakSnapshotStep = -1;

	public boolean torchReflexEnabled = false;
	public int torchLightThreshold = 8;
	public int torchSpacing = 5;
	public final List<BlockPos> placedTorches = new ArrayList<>();

	public long startTimeMs() {
		return startTimeMs;
	}

	/** Seconds the agent has spent working on this task, not counting time spent interrupted. */
	public long elapsedSeconds() {
		long now = pausedAtMs >= 0 ? pausedAtMs : System.currentTimeMillis();
		return (now - startTimeMs - pausedMs) / 1000;
	}

	public void pauseClock() {
		if (pausedAtMs < 0) {
			pausedAtMs = System.currentTimeMillis();
		}
	}

	public void resumeClock() {
		if (pausedAtMs >= 0) {
			pausedMs += System.currentTimeMillis() - pausedAtMs;
			pausedAtMs = -1;
		}
	}

	/** Where the player was facing when the task was given (for "to your right"), or null if unknown. */
	public Float requestYaw() {
		return requestYaw;
	}

	public void setRequestYaw(float yaw) {
		requestYaw = yaw;
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

	/** Inventory counts taken right before a break_block step, so a following collect_drops can see auto-pickups. */
	public void setBreakSnapshot(Map<Item, Integer> snapshot) {
		breakSnapshot = snapshot;
		breakSnapshotStep = stepIndex;
	}

	/** The pre-break count of item if the previous step was a break, otherwise null. */
	public Integer countBeforePreviousBreak(Item item) {
		if (breakSnapshot == null || breakSnapshotStep != stepIndex - 1) {
			return null;
		}
		return breakSnapshot.getOrDefault(item, 0);
	}
}
