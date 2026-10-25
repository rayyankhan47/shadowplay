package com.shadowplay.plan;

import com.google.gson.JsonObject;

/** One step of a plan: {"skill": "...", "args": {...}, "label": "..."}. */
public class PlanStep {
	public String skill;
	public JsonObject args = new JsonObject();
	public String label;
	/** A behind-the-scenes step (e.g. remembering a position): it runs normally but gets no chat line. */
	public boolean quiet;

	/** Short name used in the "Plan:" and "▶" narration lines. */
	public String label() {
		return label != null && !label.isBlank() ? label : skill;
	}
}
