package com.shadowplay;

import baritone.api.BaritoneAPI;
import com.shadowplay.config.ShadowplayConfig;
import net.fabricmc.api.ClientModInitializer;

public class ShadowplayClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ShadowplayConfig.load();
		Log.info("Baritone settings loaded: {}", BaritoneAPI.getSettings().getClass().getName());
	}
}
