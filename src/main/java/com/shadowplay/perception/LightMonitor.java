package com.shadowplay.perception;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;

/** Light readings. Dark means both block light and sky light are below the threshold. */
public final class LightMonitor {
	private LightMonitor() {
	}

	public static int blockLight(BlockPos pos) {
		return MinecraftClient.getInstance().world.getLightLevel(LightType.BLOCK, pos);
	}

	public static int skyLight(BlockPos pos) {
		return MinecraftClient.getInstance().world.getLightLevel(LightType.SKY, pos);
	}

	public static boolean isDark(BlockPos pos, int threshold) {
		ClientWorld world = MinecraftClient.getInstance().world;
		return world.getLightLevel(LightType.BLOCK, pos) < threshold && world.getLightLevel(LightType.SKY, pos) < threshold;
	}
}
