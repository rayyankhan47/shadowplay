package com.shadowplay.harness;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.HashMap;
import java.util.Map;

/** What a block drops when mined with the right tool (the common cases; everything else drops itself). */
public final class Drops {
	private static final Map<Block, Item> DROPS = new HashMap<>();

	static {
		DROPS.put(Blocks.STONE, Items.COBBLESTONE);
		DROPS.put(Blocks.DEEPSLATE, Items.COBBLED_DEEPSLATE);
		DROPS.put(Blocks.GRASS_BLOCK, Items.DIRT);
		DROPS.put(Blocks.COAL_ORE, Items.COAL);
		DROPS.put(Blocks.DEEPSLATE_COAL_ORE, Items.COAL);
		DROPS.put(Blocks.IRON_ORE, Items.RAW_IRON);
		DROPS.put(Blocks.DEEPSLATE_IRON_ORE, Items.RAW_IRON);
		DROPS.put(Blocks.COPPER_ORE, Items.RAW_COPPER);
		DROPS.put(Blocks.DEEPSLATE_COPPER_ORE, Items.RAW_COPPER);
		DROPS.put(Blocks.GOLD_ORE, Items.RAW_GOLD);
		DROPS.put(Blocks.DEEPSLATE_GOLD_ORE, Items.RAW_GOLD);
		DROPS.put(Blocks.DIAMOND_ORE, Items.DIAMOND);
		DROPS.put(Blocks.DEEPSLATE_DIAMOND_ORE, Items.DIAMOND);
		DROPS.put(Blocks.REDSTONE_ORE, Items.REDSTONE);
		DROPS.put(Blocks.DEEPSLATE_REDSTONE_ORE, Items.REDSTONE);
		DROPS.put(Blocks.LAPIS_ORE, Items.LAPIS_LAZULI);
		DROPS.put(Blocks.DEEPSLATE_LAPIS_ORE, Items.LAPIS_LAZULI);
		DROPS.put(Blocks.EMERALD_ORE, Items.EMERALD);
		DROPS.put(Blocks.DEEPSLATE_EMERALD_ORE, Items.EMERALD);
		DROPS.put(Blocks.CLAY, Items.CLAY_BALL);
		DROPS.put(Blocks.GLOWSTONE, Items.GLOWSTONE_DUST);
		DROPS.put(Blocks.SNOW_BLOCK, Items.SNOWBALL);
	}

	private Drops() {
	}

	public static Item of(Block block) {
		return DROPS.getOrDefault(block, block.asItem());
	}

	/** A block that drops this item when mined (e.g. cobblestone → stone), or null if it isn't mined from a block. */
	public static Block sourceOf(Item item) {
		for (Map.Entry<Block, Item> entry : DROPS.entrySet()) {
			if (entry.getValue() == item && entry.getKey() != Blocks.GRASS_BLOCK && !entry.getKey().getTranslationKey().contains("deepslate")) {
				return entry.getKey();
			}
		}
		Block block = Block.getBlockFromItem(item);
		return block == Blocks.AIR ? null : block;
	}
}
