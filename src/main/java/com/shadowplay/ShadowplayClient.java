package com.shadowplay;

import com.shadowplay.agent.AgentController;
import com.shadowplay.command.AgentCommand;
import com.shadowplay.config.ShadowplayConfig;
import com.shadowplay.harness.BaritoneBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class ShadowplayClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ShadowplayConfig.load();
		AgentCommand.register();

		// Baritone may not be ready at mod init, so settings are applied on the first world join (guarded inside).
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> BaritoneBridge.applySettings());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> AgentController.get().onDisconnect()));
		ClientTickEvents.END_CLIENT_TICK.register(client -> AgentController.get().tick(client));
	}
}
