package com.shadowplay.plan;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.List;

/** A plan in the project_details.md §5.3 format. Golden plans also carry goldenPrompt and keywords. */
public class Plan {
	private static final Gson GSON = new Gson();

	public String task;
	/** Optional text for the "✔ Done:" line, e.g. "stone pickaxe acquired". Defaults to the task. */
	public String summary;
	public String goldenPrompt;
	public List<String> keywords = new ArrayList<>();
	public List<PlanStep> steps = new ArrayList<>();

	public static Plan fromJson(String json) throws JsonParseException {
		Plan plan = GSON.fromJson(json, Plan.class);
		if (plan == null) {
			throw new JsonParseException("empty plan");
		}
		if (plan.steps == null) {
			plan.steps = new ArrayList<>();
		}
		if (plan.keywords == null) {
			plan.keywords = new ArrayList<>();
		}
		return plan;
	}

	public String summary() {
		if (summary != null && !summary.isBlank()) {
			return summary;
		}
		return task != null ? task : "task complete";
	}

	public List<String> labels() {
		List<String> labels = new ArrayList<>();
		for (PlanStep step : steps) {
			labels.add(step.label());
		}
		return labels;
	}
}
