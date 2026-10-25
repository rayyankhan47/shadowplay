package com.shadowplay.perception;

import com.shadowplay.harness.InventoryHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** A compact text description of the player's situation for the live planner (kept under MAX_CHARS). */
public final class StateSnapshot {
	public static final int MAX_CHARS = 1500;
	private static final int NEARBY_RADIUS = 24;

	private static final Map<String, Predicate<BlockState>> NOTABLE = new LinkedHashMap<>();

	static {
		NOTABLE.put("logs", s -> s.isIn(BlockTags.LOGS));
		NOTABLE.put("stone", s -> s.isOf(Blocks.STONE));
		NOTABLE.put("dirt/grass", s -> s.isOf(Blocks.DIRT) || s.isOf(Blocks.GRASS_BLOCK));
		NOTABLE.put("sand", s -> s.isOf(Blocks.SAND));
		NOTABLE.put("water", s -> s.isOf(Blocks.WATER));
		NOTABLE.put("coal_ore", s -> s.isIn(BlockTags.COAL_ORES));
		NOTABLE.put("iron_ore", s -> s.isIn(BlockTags.IRON_ORES));
		NOTABLE.put("crafting_table", s -> s.isOf(Blocks.CRAFTING_TABLE));
	}

	private StateSnapshot() {
	}

	/** knownPositions are the task context's saved positions (may be empty). */
	public static String build(Map<String, BlockPos> knownPositions) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		BlockPos feet = player.getBlockPos();
		StringBuilder sb = new StringBuilder();

		sb.append("Position: ").append(fmt(feet)).append(", facing ").append(player.getHorizontalFacing().asString());
		BlockState ground = world.getBlockState(feet.down());
		sb.append(", standing on ").append(id(ground.getBlock().asItem()));
		if (player.isSubmergedInWater()) {
			sb.append(", underwater");
		}
		sb.append('\n');

		ItemStack held = player.getMainHandStack();
		sb.append("Holding: ").append(held.isEmpty() ? "nothing" : id(held.getItem())).append('\n');

		Map<Item, Integer> inventory = InventoryHelper.snapshot();
		sb.append("Inventory: ");
		if (inventory.isEmpty()) {
			sb.append("empty");
		} else {
			sb.append(inventory.entrySet().stream()
					.sorted(Map.Entry.<Item, Integer>comparingByValue().reversed())
					.map(e -> id(e.getKey()) + " x" + e.getValue())
					.collect(Collectors.joining(", ")));
		}
		sb.append('\n');

		sb.append("Nearby (within ").append(NEARBY_RADIUS).append(" blocks):\n");
		for (Map.Entry<String, Predicate<BlockState>> entry : NOTABLE.entrySet()) {
			List<BlockPos> found = WorldScanner.all(entry.getValue(), null, NEARBY_RADIUS, 256);
			if (found.isEmpty()) {
				continue;
			}
			BlockPos nearest = found.stream().min(Comparator.comparingDouble(p -> p.getSquaredDistance(feet))).get();
			sb.append("- ").append(entry.getKey()).append(": ").append(found.size() >= 256 ? "256+" : found.size())
					.append(", nearest ").append(Math.round(Math.sqrt(nearest.getSquaredDistance(feet))))
					.append(" blocks away at ").append(fmt(nearest)).append('\n');
		}

		if (knownPositions != null && !knownPositions.isEmpty()) {
			sb.append("Saved positions: ").append(knownPositions.entrySet().stream()
					.map(e -> e.getKey() + " " + fmt(e.getValue()))
					.collect(Collectors.joining(", "))).append('\n');
		}

		String text = sb.toString();
		return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS - 3) + "..." : text;
	}

	private static String fmt(BlockPos pos) {
		return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
	}

	private static String id(Item item) {
		return Registries.ITEM.getId(item).getPath();
	}
}
