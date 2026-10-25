package com.shadowplay.narration;

import com.shadowplay.Log;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.stream.Collectors;

/** All [Agent] chat lines (project_details.md §3.2). Must be called on the client thread. */
public final class Narrator {
	private Narrator() {
	}

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

	/** "Plan: chop logs → craft planks → …" */
	public static void plan(List<String> labels) {
		String joined = labels.stream().map(Narrator::lowerFirst).collect(Collectors.joining(" → "));
		say("Plan: " + joined);
	}

	/** "▶ 3/7 Crafting a wooden pickaxe" */
	public static void step(int index, int total, String label) {
		say("▶ " + index + "/" + total + " " + label);
	}

	/** "✔ Done: stone pickaxe acquired (52s)" */
	public static void done(String summary, long seconds) {
		say("✔ Done: " + summary + " (" + seconds + "s)", Formatting.GREEN);
	}

	/** "⚠ Step failed (path blocked), retrying" */
	public static void retry(String reason) {
		say("⚠ Step failed (" + reason + "), retrying", Formatting.YELLOW);
	}

	/** "✖ Couldn't finish: no sand found near the lake" */
	public static void fail(String reason) {
		say("✖ Couldn't finish: " + reason, Formatting.RED);
	}

	public static void interrupted() {
		say("You interrupted the agent's task. Enter '/agent resume' to resume.", Formatting.GOLD);
	}

	public static void thinking() {
		say("Thinking…", Formatting.GRAY);
	}

	private static String lowerFirst(String s) {
		if (s == null || s.isEmpty()) {
			return "";
		}
		return Character.toLowerCase(s.charAt(0)) + s.substring(1);
	}
}
