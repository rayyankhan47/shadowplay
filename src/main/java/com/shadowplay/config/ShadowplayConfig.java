package com.shadowplay.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.shadowplay.Log;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Mirrors config/shadowplay.json (project_details.md §5.8). This file must never be committed. */
public class ShadowplayConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
	private static final String FILE_NAME = "shadowplay.json";
	private static final String API_KEY_ENV = "GEMINI_API_KEY";

	private static ShadowplayConfig instance = new ShadowplayConfig();

	public String geminiApiKey = "";
	public String geminiModel = "gemini-flash-latest";
	public boolean useGoldenPlans = true;
	public Demo demo = new Demo();
	public int torchLightThreshold = 8;
	public int torchSpacing = 5;

	public static class Demo {
		public Pos caveEntrance = null;
		public Pos startPos = null;
	}

	public static class Pos {
		public int x;
		public int y;
		public int z;
	}

	public static ShadowplayConfig get() {
		return instance;
	}

	/** Loads the config file, writing defaults if it does not exist. Falls back to defaults on a parse error. */
	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				ShadowplayConfig loaded = GSON.fromJson(reader, ShadowplayConfig.class);
				instance = loaded != null ? loaded : new ShadowplayConfig();
				if (instance.demo == null) {
					instance.demo = new Demo();
				}
				Log.info("Loaded config from {}", path);
			} catch (IOException | JsonParseException e) {
				Log.error("Could not read {}, using defaults: {}", path, e.getMessage());
				instance = new ShadowplayConfig();
			}
		} else {
			instance = new ShadowplayConfig();
			try {
				Files.createDirectories(path.getParent());
				try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
					GSON.toJson(instance, writer);
				}
				Log.info("Wrote default config to {}", path);
			} catch (IOException e) {
				Log.error("Could not write default config to {}: {}", path, e.getMessage());
			}
		}
		Log.info("Gemini model: {}, API key {}", instance.geminiModel, instance.hasGeminiApiKey() ? "present" : "missing");
	}

	/** The effective API key: the GEMINI_API_KEY environment variable wins over the file. Never log this. */
	public String geminiApiKey() {
		String env = System.getenv(API_KEY_ENV);
		if (env != null && !env.isBlank()) {
			return env.trim();
		}
		return geminiApiKey == null ? "" : geminiApiKey.trim();
	}

	public boolean hasGeminiApiKey() {
		return !geminiApiKey().isEmpty();
	}
}
