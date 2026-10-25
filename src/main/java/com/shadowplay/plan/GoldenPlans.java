package com.shadowplay.plan;

import com.google.gson.JsonParseException;
import com.shadowplay.Log;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Pinned plans for the demo prompts, loaded from assets/shadowplay/golden/*.json. Each file carries its
 * goldenPrompt and match keywords. The raw JSON is kept so every run gets a fresh, unmodified Plan.
 */
public final class GoldenPlans {
	private static final String DIR = "assets/shadowplay/golden";

	private record Entry(String file, String json, Plan plan) {
	}

	private static List<Entry> entries;

	private GoldenPlans() {
	}

	/** The golden plan whose keywords all appear in the prompt (first match wins), or null. */
	public static Plan match(String prompt) {
		for (Entry entry : entries()) {
			if (PromptMatcher.matches(prompt, entry.plan().keywords)) {
				Log.info("Prompt matched golden plan {}", entry.file());
				return Plan.fromJson(entry.json());
			}
		}
		return null;
	}

	private static synchronized List<Entry> entries() {
		if (entries == null) {
			entries = load();
		}
		return entries;
	}

	private static List<Entry> load() {
		List<Entry> loaded = new ArrayList<>();
		Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer("shadowplay");
		Optional<Path> dir = mod.flatMap(m -> m.findPath(DIR));
		if (dir.isEmpty()) {
			Log.warn("No golden plan directory found at {}", DIR);
			return loaded;
		}
		try (Stream<Path> files = Files.list(dir.get())) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
				try {
					String json = Files.readString(file, StandardCharsets.UTF_8);
					Plan plan = Plan.fromJson(json);
					if (plan.keywords.isEmpty()) {
						Log.warn("Golden plan {} has no keywords, skipping", file.getFileName());
						continue;
					}
					loaded.add(new Entry(file.getFileName().toString(), json, plan));
				} catch (IOException | JsonParseException e) {
					Log.error("Could not load golden plan {}: {}", file.getFileName(), e.getMessage());
				}
			}
		} catch (IOException e) {
			Log.error("Could not list golden plans: {}", e.getMessage());
		}
		Log.info("Loaded {} golden plans", loaded.size());
		return loaded;
	}
}
