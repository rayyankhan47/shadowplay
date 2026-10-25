package com.shadowplay.narration;

import com.shadowplay.Log;
import com.shadowplay.harness.Humanize;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

/**
 * All [Agent] chat lines (project_details.md §3.2). Must be called on the client thread.
 *
 * Task narration (plan, steps, done, failures) is paced: with humanize on, consecutive lines are at least
 * LINE_GAP_MS apart, like someone typing updates, instead of landing in the same tick. Direct replies to commands
 * (status, help, "Stopped.") and the interrupt line appear immediately.
 */
public final class Narrator {
	private static final int LINE_GAP_MS = 1000;

	private record Line(String message, Formatting color) {
	}

	private static final Deque<Line> pending = new ArrayDeque<>();
	private static int cooldownTicks = 0;

	private Narrator() {
	}

	/** Immediate line (replies to commands). */
	public static void say(String message) {
		say(message, Formatting.WHITE);
	}

	public static void say(String message, Formatting color) {
		Log.info("Agent: {}", message);
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.inGameHud == null) {
			return;
		}
		MutableText line = Text.literal("[Agent] ").formatted(Formatting.AQUA)
				.append(Text.literal(message).formatted(color));
		client.inGameHud.getChatHud().addMessage(line);
	}

	/** Task narration: shown now if the last line was long enough ago, otherwise queued behind it. */
	public static void paced(String message, Formatting color) {
		if (!Humanize.enabled()) {
			say(message, color);
			return;
		}
		pending.addLast(new Line(message, color));
		if (cooldownTicks <= 0 && pending.size() == 1) {
			flushOne();
		}
	}

	/** Call every client tick: releases the next queued line when its gap has passed. */
	public static void tick() {
		if (cooldownTicks > 0) {
			cooldownTicks--;
		}
		if (cooldownTicks <= 0 && !pending.isEmpty()) {
			flushOne();
		}
	}

	/** Drops narration that hasn't been shown yet (it is stale once the task stops). */
	public static void clearPending() {
		pending.clear();
	}

	private static void flushOne() {
		Line line = pending.pollFirst();
		say(line.message(), line.color());
		cooldownTicks = Humanize.ticks(LINE_GAP_MS);
	}

	/** "Plan: chop logs → craft planks → …" */
	public static void plan(List<String> labels) {
		String joined = labels.stream().map(Narrator::lowerFirst).collect(Collectors.joining(" → "));
		paced("Plan: " + joined, Formatting.WHITE);
	}

	/** "▶ 3/7 Crafting a wooden pickaxe" */
	public static void step(int index, int total, String label) {
		paced("▶ " + index + "/" + total + " " + label, Formatting.WHITE);
	}

	/** "✔ Done: stone pickaxe acquired (52s)" */
	public static void done(String summary, long seconds) {
		paced("✔ Done: " + summary + " (" + seconds + "s)", Formatting.GREEN);
	}

	/** "⚠ Step failed (path blocked), retrying" */
	public static void retry(String reason) {
		paced("⚠ Step failed (" + reason + "), retrying", Formatting.YELLOW);
	}

	/** "✖ Couldn't finish: no sand found near the lake" */
	public static void fail(String reason) {
		paced("✖ Couldn't finish: " + reason, Formatting.RED);
	}

	/** Shown immediately, and anything still queued from the task is dropped. */
	public static void interrupted() {
		clearPending();
		say("You interrupted the agent's task. Enter '/agent resume' to resume.", Formatting.GOLD);
	}

	public static void thinking() {
		paced("Thinking…", Formatting.GRAY);
	}

	private static String lowerFirst(String s) {
		if (s == null || s.isEmpty()) {
			return "";
		}
		return Character.toLowerCase(s.charAt(0)) + s.substring(1);
	}
}
