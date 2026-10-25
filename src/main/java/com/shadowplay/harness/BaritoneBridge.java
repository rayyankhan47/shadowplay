package com.shadowplay.harness;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import com.shadowplay.Log;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The only class that touches baritone.api. Everything else goes through these static helpers. */
public final class BaritoneBridge {
	private static boolean settingsApplied = false;
	private static boolean listenerRegistered = false;
	private static String lastFailure;

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

	private static IBaritone baritone() {
		return BaritoneAPI.getProvider().getPrimaryBaritone();
	}

	/** How a destination is satisfied: stand exactly on it, be within a range of it, or stand touching it. */
	public enum GoalKind {
		EXACT,
		NEAR,
		TOUCH
	}

	/** Starts walking toward a goal of the given kind (range is only used for NEAR). */
	public static void go(GoalKind kind, BlockPos pos, int range) {
		switch (kind) {
			case EXACT -> goTo(pos);
			case NEAR -> goNear(pos, range);
			case TOUCH -> goToBlock(pos);
		}
	}

	/** True if the player's feet satisfy the goal right now. */
	public static boolean isInGoal(GoalKind kind, BlockPos pos, int range) {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		return player != null && goalOf(kind, pos, range).isInGoal(player.getBlockPos());
	}

	private static Goal goalOf(GoalKind kind, BlockPos pos, int range) {
		return switch (kind) {
			case EXACT -> new GoalBlock(pos);
			case NEAR -> new GoalNear(pos, range);
			case TOUCH -> new GoalGetToBlock(pos);
		};
	}

	/** The nearest loaded block of this type within chunkRadius chunks (Baritone's scanner), or null. */
	public static BlockPos findNearest(Block block, int chunkRadius) {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) {
			return null;
		}
		List<BlockPos> found = BaritoneAPI.getProvider().getWorldScanner()
				.scanChunkRadius(baritone().getPlayerContext(), List.of(block), 64, 10, chunkRadius);
		BlockPos feet = player.getBlockPos();
		return found.stream().min(Comparator.comparingDouble(p -> p.getSquaredDistance(feet))).orElse(null);
	}

	/** Walk until the feet are exactly at pos. */
	public static void goTo(BlockPos pos) {
		path(new GoalBlock(pos));
	}

	/** Walk until within range blocks of pos. */
	public static void goNear(BlockPos pos, int range) {
		path(new GoalNear(pos, range));
	}

	/** Walk until standing next to (touching) the block at pos — the right goal for mining or using it. */
	public static void goToBlock(BlockPos pos) {
		path(new GoalGetToBlock(pos));
	}

	/** Walk next to the nearest block of this type that Baritone knows about. */
	public static void getToBlock(Block block) {
		prepare();
		baritone().getGetToBlockProcess().getToBlock(block);
	}

	/**
	 * Mine until the inventory holds quantity items of what these blocks drop (0 = no limit). Baritone digs
	 * down/through as needed and picks the tool itself (autoTool).
	 */
	public static void mine(int quantity, Block... blocks) {
		prepare();
		baritone().getMineProcess().mine(quantity, blocks);
	}

	/** True while any Baritone process is pathing, mining or computing. */
	public static boolean isBusy() {
		IBaritone b = baritone();
		return b.getCustomGoalProcess().isActive()
				|| b.getMineProcess().isActive()
				|| b.getGetToBlockProcess().isActive()
				|| b.getPathingBehavior().isPathing();
	}

	/** Stops every Baritone process and releases any keys it is holding. */
	public static void cancelAll() {
		baritone().getPathingBehavior().cancelEverything();
	}

	/** The reason the latest path request failed, or null. Cleared by every new request. */
	public static String lastFailure() {
		return lastFailure;
	}

	private static void path(Goal goal) {
		prepare();
		baritone().getCustomGoalProcess().setGoalAndPath(goal);
	}

	private static void prepare() {
		registerListener();
		lastFailure = null;
	}

	private static void registerListener() {
		if (listenerRegistered) {
			return;
		}
		listenerRegistered = true;
		baritone().getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
			@Override
			public void onPathEvent(PathEvent event) {
				if (event == PathEvent.CALC_FAILED || event == PathEvent.NEXT_CALC_FAILED) {
					lastFailure = "no path found";
					Log.info("Baritone path event {}", event);
				}
			}
		});
	}
}
