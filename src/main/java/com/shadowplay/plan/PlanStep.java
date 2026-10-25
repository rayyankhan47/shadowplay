package com.shadowplay.plan;

import com.google.gson.JsonObject;

/** One step of a plan: {"skill": "...", "args": {...}, "label": "..."}. */
public class PlanStep {
	public String skill;
	public JsonObject args = new JsonObject();
	public String label;

	/** Short name used in the "Plan:" and "▶" narration lines. */
	public String label() {
		return label != null && !label.isBlank() ? label : skill;
	}
}
