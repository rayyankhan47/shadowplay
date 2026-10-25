package com.shadowplay.harness;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import com.shadowplay.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/** The only class that touches baritone.api. Everything else goes through these static helpers. */
public final class BaritoneBridge {
	private static boolean settingsApplied = false;

	private BaritoneBridge() {
	}

	/** Applies our Baritone settings (project_details.md §5.5). Safe to call repeatedly; only the first call does work. */
	public static void applySettings() {
		if (settingsApplied) {
			return;
		}
		settingsApplied = true;

		Map<String, Object> wanted = new LinkedHashMap<>();
		wanted.put("allowSprint", true);
		wanted.put("allowBreak", true);
		wanted.put("allowPlace", true);
		wanted.put("chatDebug", false);
		wanted.put("autoTool", true);
		wanted.put("allowParkour", false);
		wanted.put("mineScanDroppedItems", true);
		wanted.put("renderPath", true);

		Settings settings = BaritoneAPI.getSettings();
		for (Map.Entry<String, Object> entry : wanted.entrySet()) {
			setSetting(settings, entry.getKey(), entry.getValue());
		}
	}

	@SuppressWarnings("unchecked")
	private static void setSetting(Settings settings, String name, Object value) {
		Settings.Setting<Object> setting = (Settings.Setting<Object>) settings.byLowerName.get(name.toLowerCase());
		if (setting == null) {
			Log.warn("Baritone setting '{}' does not exist in this version, skipping", name);
			return;
		}
		if (!setting.getValueClass().isInstance(value)) {
			Log.warn("Baritone setting '{}' has type {}, skipping", name, setting.getValueClass().getSimpleName());
			return;
		}
		setting.value = value;
		Log.info("Baritone setting {} = {}", name, value);
	}
}
