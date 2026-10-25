package com.shadowplay.plan;

/** Hardcoded plans for manual testing via /agent debug [...]. */
public final class DebugPlans {
	private DebugPlans() {
	}

	public static Plan basic() {
		return Plan.fromJson("""
				{
				  "task": "debug",
				  "summary": "debug plan finished",
				  "steps": [
				    { "skill": "say", "args": { "text": "Hello from the executor" }, "label": "Say hello" },
				    { "skill": "wait", "args": { "ticks": 40 }, "label": "Wait 2 seconds" },
				    { "skill": "say", "args": { "text": "Still here" }, "label": "Say again" }
				  ]
				}
				""");
	}
}
