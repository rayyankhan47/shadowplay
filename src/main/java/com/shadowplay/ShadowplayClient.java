package com.shadowplay;

import baritone.api.BaritoneAPI;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShadowplayClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("shadowplay");

	@Override
	public void onInitializeClient() {
		LOGGER.info("Baritone settings loaded: {}", BaritoneAPI.getSettings().getClass().getName());
	}
}
