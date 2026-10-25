package com.shadowplay.plan;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Golden prompt matching: lowercase, strip punctuation, collapse spaces, then every keyword must appear.
 * A keyword may list alternatives with '|' ("lake|underwater"). A word matches a keyword if it starts with it,
 * so plurals still match ("pickaxes" matches "pickaxe").
 */
public final class PromptMatcher {
	private PromptMatcher() {
	}

	public static String normalize(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
	}

	public static boolean matches(String prompt, List<String> keywords) {
		if (keywords == null || keywords.isEmpty()) {
			return false;
		}
		List<String> words = Arrays.asList(normalize(prompt).split(" "));
		for (String keyword : keywords) {
			boolean found = false;
			for (String alternative : keyword.toLowerCase(Locale.ROOT).split("\\|")) {
				String alt = alternative.trim();
				if (!alt.isEmpty() && words.stream().anyMatch(w -> w.startsWith(alt))) {
					found = true;
					break;
				}
			}
			if (!found) {
				return false;
			}
		}
		return true;
	}
}
