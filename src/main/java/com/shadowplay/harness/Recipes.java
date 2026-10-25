package com.shadowplay.harness;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** The shaped recipes the agent can craft by slot clicks (project_details.md §5.5). */
public final class Recipes {
	/** Something that may go in a grid cell. The same instance is shared by all cells that take it. */
	public record Ingredient(String name, Predicate<ItemStack> matches) {
		static Ingredient of(String name, TagKey<Item> tag) {
			return new Ingredient(name, stack -> stack.isIn(tag));
		}

		static Ingredient of(String name, Item item) {
			return new Ingredient(name, stack -> stack.isOf(item));
		}
	}

	public record Cell(int row, int col, Ingredient ingredient) {
	}

	/** size is the smallest grid the shape fits: 2 (player inventory) or 3 (crafting table). */
	public record Recipe(Item output, int outputCount, int size, List<Cell> cells) {
		/** Grid slot id → ingredient for the given screen layout. */
		public Map<Integer, Predicate<ItemStack>> gridFor(CraftingHelper.Layout layout) {
			Map<Integer, Predicate<ItemStack>> grid = new LinkedHashMap<>();
			for (Cell cell : cells) {
				grid.put(layout.gridSlot(cell.row(), cell.col()), cell.ingredient().matches());
			}
			return grid;
		}

		/** How many crafts produce at least count items. */
		public int craftsFor(int count) {
			return Math.max(1, (count + outputCount - 1) / outputCount);
		}

		/** Null if the inventory holds enough for this many crafts, else e.g. "need 3 planks, have 1". */
		public String missingFor(int crafts) {
			Map<Ingredient, Integer> needed = new LinkedHashMap<>();
			for (Cell cell : cells) {
				needed.merge(cell.ingredient(), crafts, Integer::sum);
			}
			for (Map.Entry<Ingredient, Integer> entry : needed.entrySet()) {
				int have = InventoryHelper.count(entry.getKey().matches());
				if (have < entry.getValue()) {
					return "need " + entry.getValue() + " " + entry.getKey().name() + ", have " + have;
				}
			}
			return null;
		}
	}

	private static final Map<Item, Recipe> RECIPES = new LinkedHashMap<>();

	static {
		Ingredient planks = Ingredient.of("planks", ItemTags.PLANKS);
		Ingredient stick = Ingredient.of("sticks", Items.STICK);
		Ingredient coal = Ingredient.of("coal", ItemTags.COALS);
		Ingredient cobblestone = Ingredient.of("cobblestone", ItemTags.STONE_TOOL_MATERIALS);

		plank(Items.OAK_PLANKS, ItemTags.OAK_LOGS, "oak logs");
		plank(Items.SPRUCE_PLANKS, ItemTags.SPRUCE_LOGS, "spruce logs");
		plank(Items.BIRCH_PLANKS, ItemTags.BIRCH_LOGS, "birch logs");
		plank(Items.JUNGLE_PLANKS, ItemTags.JUNGLE_LOGS, "jungle logs");
		plank(Items.ACACIA_PLANKS, ItemTags.ACACIA_LOGS, "acacia logs");
		plank(Items.DARK_OAK_PLANKS, ItemTags.DARK_OAK_LOGS, "dark oak logs");
		plank(Items.MANGROVE_PLANKS, ItemTags.MANGROVE_LOGS, "mangrove logs");
		plank(Items.CHERRY_PLANKS, ItemTags.CHERRY_LOGS, "cherry logs");

		add(Items.STICK, 4, 2, cell(0, 0, planks), cell(1, 0, planks));
		add(Items.CRAFTING_TABLE, 1, 2, cell(0, 0, planks), cell(0, 1, planks), cell(1, 0, planks), cell(1, 1, planks));
		add(Items.TORCH, 4, 2, cell(0, 0, coal), cell(1, 0, stick));
		add(Items.WOODEN_PICKAXE, 1, 3, cell(0, 0, planks), cell(0, 1, planks), cell(0, 2, planks),
				cell(1, 1, stick), cell(2, 1, stick));
		add(Items.STONE_PICKAXE, 1, 3, cell(0, 0, cobblestone), cell(0, 1, cobblestone), cell(0, 2, cobblestone),
				cell(1, 1, stick), cell(2, 1, stick));
	}

	private Recipes() {
	}

	public static Recipe find(Item output) {
		return RECIPES.get(output);
	}

	public static Collection<Recipe> all() {
		return RECIPES.values();
	}

	private static void plank(Item planks, TagKey<Item> logs, String name) {
		add(planks, 4, 2, cell(0, 0, Ingredient.of(name, logs)));
	}

	private static Cell cell(int row, int col, Ingredient ingredient) {
		return new Cell(row, col, ingredient);
	}

	private static void add(Item output, int outputCount, int size, Cell... cells) {
		RECIPES.put(output, new Recipe(output, outputCount, size, new ArrayList<>(List.of(cells))));
	}
}
