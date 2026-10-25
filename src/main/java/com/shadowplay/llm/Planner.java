package com.shadowplay.llm;

import com.google.gson.JsonParseException;
import com.shadowplay.Log;
import com.shadowplay.config.ShadowplayConfig;
import com.shadowplay.perception.StateSnapshot;
import com.shadowplay.plan.Plan;
import com.shadowplay.plan.PlanStep;
import com.shadowplay.skills.SkillRegistry;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Live planning: asks Gemini for a plan (skill catalog + state snapshot + task), validates it, retries once with
 * the validation error appended. Non-blocking: start() once, then poll() every tick until it returns a Result.
 */
public final class Planner {
	public static final int MAX_STEPS = 15;

	public enum Source {
		GEMINI,
		FALLBACK
	}

	/**
	 * plan is null if nothing usable came back; error says why Gemini's answer was not used (null if it was).
	 * userMessage is a chat line explaining a Gemini outage (rate limit, overload...) for when no plan came back.
	 */
	public record Result(Plan plan, Source source, String error, String userMessage) {
	}

	private final String task;
	private final String snapshot;
	private CompletableFuture<String> pending;
	private int attempt;
	private Result result;

	public Planner(String task, Map<String, BlockPos> knownPositions) {
		this.task = task;
		this.snapshot = StateSnapshot.build(knownPositions);
	}

	/** True if a live Gemini call will be made (an API key is configured). */
	public static boolean canUseGemini() {
		return ShadowplayConfig.get().hasGeminiApiKey();
	}

	public void start() {
		attempt = 1;
		pending = GeminiClient.generateJson(systemPrompt(), userPrompt(null));
	}

	/** Null while the request is in flight, then the final result. */
	public Result poll() {
		if (result != null || pending == null || !pending.isDone()) {
			return result;
		}
		String text;
		try {
			text = pending.join();
		} catch (CompletionException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			String reason = cause instanceof GeminiClient.GeminiException ? cause.getMessage()
					: cause instanceof java.util.concurrent.TimeoutException ? "Gemini timed out" : "Gemini request failed";
			Log.warn("Planner: {}", reason);
			return finish(reason, userMessageFor(cause));
		} catch (RuntimeException e) {
			return finish("Gemini request failed", "Couldn't reach Gemini. Please try again.");
		}


		Plan plan;
		String error;
		try {
			plan = Plan.fromJson(stripFences(text));
			error = validate(plan);
		} catch (JsonParseException | IllegalStateException e) {
			plan = null;
			error = "the answer was not a JSON plan";
		}
		if (error == null) {
			if (plan.task == null || plan.task.isBlank()) {
				plan.task = task;
			}
			fillLabels(plan);
			Log.info("Planner: Gemini plan accepted ({} steps, attempt {})", plan.steps.size(), attempt);
			result = new Result(plan, Source.GEMINI, null, null);
			return result;
		}
		Log.warn("Planner: invalid plan on attempt {}: {}", attempt, error);
		if (attempt == 1) {
			attempt = 2;
			pending = GeminiClient.generateJson(systemPrompt(), userPrompt(error));
			return null;
		}
		return finish(error, null);
	}

	public void cancel() {
		if (pending != null) {
			pending.cancel(true);
		}
	}

	/** Gemini's answer is not usable: fall back to the keyword parser (its plan may be null). */
	private Result finish(String error, String userMessage) {
		result = fallback(task, error, userMessage);
		return result;
	}

	/** A short chat line for a failed Gemini call, so it is clear why nothing happens. */
	private static String userMessageFor(Throwable cause) {
		if (cause instanceof java.util.concurrent.TimeoutException) {
			return "Gemini took too long to answer. Please try again.";
		}
		int status = cause instanceof GeminiClient.GeminiException g ? g.status() : 0;
		if (status == 429) {
			return "Gemini is rate limited right now. Please try again later.";
		}
		if (status >= 500) {
			return "Gemini is overloaded right now. Please try again later.";
		}
		if (status == 400 || status == 401 || status == 403 || status == 404) {
			return "Gemini rejected the request. Check the API key and model in config/shadowplay.json.";
		}
		return "Couldn't reach Gemini. Please try again.";
	}

	/** Plans with the keyword parser only (no API key, timeout, or invalid answers). */
	public static Result fallback(String task, String error) {
		return fallback(task, error, null);
	}

	private static Result fallback(String task, String error, String userMessage) {
		Plan plan = FallbackParser.parse(task);
		if (plan != null && validate(plan) != null) {
			Log.warn("Planner: fallback plan invalid: {}", validate(plan));
			plan = null;
		}
		Log.info("Planner: fallback {} ({})", plan != null ? "plan with " + plan.steps.size() + " steps" : "found nothing",
				error != null ? error : "no reason");
		return new Result(plan, Source.FALLBACK, error, userMessage);
	}

	/** Null if the plan is usable, otherwise what is wrong with it. */
	public static String validate(Plan plan) {
		if (plan == null || plan.steps == null || plan.steps.isEmpty()) {
			return "the plan has no steps";
		}
		if (plan.steps.size() > MAX_STEPS) {
			return "the plan has " + plan.steps.size() + " steps, the maximum is " + MAX_STEPS;
		}
		for (int i = 0; i < plan.steps.size(); i++) {
			String error = SkillRegistry.validate(plan.steps.get(i));
			if (error != null) {
				return "step " + (i + 1) + ": " + error;
			}
		}
		return null;
	}

	private String userPrompt(String previousError) {
		StringBuilder sb = new StringBuilder();
		sb.append("Task: ").append(task).append("\n\nCurrent state:\n").append(snapshot);
		if (previousError != null) {
			sb.append("\nYour previous plan was invalid: ").append(previousError)
					.append(". Return a corrected plan that follows the rules.");
		}
		return sb.toString();
	}

	public static String systemPrompt() {
		StringBuilder sb = new StringBuilder();
		sb.append("""
				You are the planner for Shadowplay, an agent that plays Minecraft Java 1.21.1 (survival, peaceful) as the user's own character.
				Turn the user's task into a short plan made only of the skills below. Reply with ONLY a JSON object:
				{"task": "<the task>", "summary": "<3-6 words for the done message, e.g. \\"stone pickaxe acquired\\">", "steps": [{"skill": "<skill name>", "args": {...}, "label": "<2-5 word step name, e.g. \\"Chop logs\\">"}]}

				Rules:
				- Use only the skills and args listed below. Item and block ids look like "minecraft:oak_log".
				- At most %d steps. Use as few steps as finish the task.
				- Read the inventory in the state: do not gather or craft what the player already has.
				- craft uses the 2x2 inventory grid. craft_at_table uses a 3x3 crafting table; place one first with place_block (name "crafting_table") unless one is listed nearby.
				- For craft and craft_at_table, count is how many items to end up with, not how many times to craft.
				- Tools need a crafting table. Stone needs a pickaxe; mining stone gives cobblestone. Coal ore gives coal.
				- If the task cannot be done with these skills, reply {"task": "<the task>", "steps": []}.

				Recipes:
				- 1 log -> 4 planks of the same wood (2x2)
				- 2 planks -> 4 sticks (2x2)
				- 4 planks -> 1 crafting_table (2x2)
				- 1 coal + 1 stick -> 4 torches (2x2)
				- 3 planks + 2 sticks -> 1 wooden_pickaxe (3x3, at a table)
				- 3 cobblestone + 2 sticks -> 1 stone_pickaxe (3x3, at a table)

				Skills:
				""".formatted(MAX_STEPS));
		for (SkillRegistry.Spec spec : SkillRegistry.all()) {
			if (spec.name().equals("wait")) {
				continue;
			}
			sb.append("- ").append(spec.name()).append(": ").append(spec.description()).append('\n');
			for (SkillRegistry.Arg arg : spec.args()) {
				sb.append("    ").append(arg.name()).append(" (").append(arg.type().description)
						.append(arg.required() ? ", required" : ", optional").append("): ").append(arg.description()).append('\n');
			}
		}
		sb.append("""

				Example. Task "make me a wooden pickaxe", empty inventory, oak trees nearby:
				{"task": "make me a wooden pickaxe", "summary": "wooden pickaxe crafted", "steps": [
				 {"skill": "chop_logs", "args": {"count": 3, "block": "minecraft:oak_log"}, "label": "Chop logs"},
				 {"skill": "craft", "args": {"item": "minecraft:oak_planks", "count": 12}, "label": "Craft planks"},
				 {"skill": "craft", "args": {"item": "minecraft:crafting_table", "count": 1}, "label": "Craft a crafting table"},
				 {"skill": "craft", "args": {"item": "minecraft:stick", "count": 4}, "label": "Craft sticks"},
				 {"skill": "place_block", "args": {"item": "minecraft:crafting_table", "name": "crafting_table"}, "label": "Place the table"},
				 {"skill": "craft_at_table", "args": {"item": "minecraft:wooden_pickaxe", "count": 1}, "label": "Craft a wooden pickaxe"}]}
				""");
		return sb.toString();
	}

	private static String stripFences(String text) {
		String t = text.trim();
		if (t.startsWith("```")) {
			int firstNewline = t.indexOf('\n');
			int lastFence = t.lastIndexOf("```");
			if (firstNewline > 0 && lastFence > firstNewline) {
				t = t.substring(firstNewline + 1, lastFence).trim();
			}
		}
		return t;
	}

	/** Steps without a label get a readable one from the skill name ("chop_logs" → "Chop logs"). */
	public static void fillLabels(Plan plan) {
		for (PlanStep step : plan.steps) {
			if (step.label == null || step.label.isBlank()) {
				String name = step.skill.replace('_', ' ');
				step.label = Character.toUpperCase(name.charAt(0)) + name.substring(1);
			}
		}
	}
}
