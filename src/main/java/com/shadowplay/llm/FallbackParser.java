package com.shadowplay.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shadowplay.harness.Drops;
import com.shadowplay.harness.InventoryHelper;
import com.shadowplay.harness.Recipes;
import com.shadowplay.perception.WorldScanner;
import com.shadowplay.plan.Plan;
import com.shadowplay.plan.PlanStep;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword planner used when Gemini is unavailable or its answer is unusable. Understands the common verbs:
 * chop/break a tree, mine N of a block, go to x y z, craft/get/bring an item. Item requests are expanded into a
 * chain (chop → planks → table → …) from the Recipes table, accounting for what the player already carries.
 * Returns null if the task is not understood.
 */
public final class FallbackParser {
	private static final Pattern GOTO = Pattern.compile("\\b(?:go|walk|move|come|head|travel|run)\\b(?:\\s+to)?\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)");
	private static final Pattern TREE = Pattern.compile("\\b(?:chop|cut|break|punch|fell|harvest)\\b.*\\b(?:tree|trees|log|logs|wood|trunk)\\b");
	private static final Pattern MINE = Pattern.compile("\\b(?:mine|dig)\\b\\s+(?:up\\s+)?(\\d+|a|an|some)?\\s*([a-z_ ]+)");
	private static final Pattern MAKE = Pattern.compile("\\b(?:craft|make|build|get|bring|give|fetch|obtain|collect|gather)\\b\\s+(?:me\\s+)?(\\d+|a|an|some)?\\s*([a-z_ ]+)");
	private static final List<String> FILLER = List.of("the", "a", "an", "some", "of", "me", "more", "few", "couple");
	private static final Map<String, String> ITEM_ALIASES = new HashMap<>();
	private static final Map<String, String> BLOCK_ALIASES = new HashMap<>();
	private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry"};

	static {
		ITEM_ALIASES.put("table", "crafting_table");
		ITEM_ALIASES.put("workbench", "crafting_table");
		ITEM_ALIASES.put("wood", "oak_log");
		ITEM_ALIASES.put("log", "oak_log");
		ITEM_ALIASES.put("tree", "oak_log");
		ITEM_ALIASES.put("plank", "oak_planks");
		ITEM_ALIASES.put("planks", "oak_planks");
		ITEM_ALIASES.put("wood_plank", "oak_planks");
		ITEM_ALIASES.put("wooden_plank", "oak_planks");
		ITEM_ALIASES.put("pickaxe", "wooden_pickaxe");
		ITEM_ALIASES.put("pick", "wooden_pickaxe");
		ITEM_ALIASES.put("wood_pickaxe", "wooden_pickaxe");
		ITEM_ALIASES.put("stone_pick", "stone_pickaxe");
		ITEM_ALIASES.put("cobble", "cobblestone");
		ITEM_ALIASES.put("rock", "cobblestone");
		BLOCK_ALIASES.put("coal", "coal_ore");
		BLOCK_ALIASES.put("iron", "iron_ore");
		BLOCK_ALIASES.put("diamond", "diamond_ore");
		BLOCK_ALIASES.put("cobblestone", "stone");
		BLOCK_ALIASES.put("cobble", "stone");
		BLOCK_ALIASES.put("rock", "stone");
		BLOCK_ALIASES.put("grass", "grass_block");
		BLOCK_ALIASES.put("wood", "oak_log");
		BLOCK_ALIASES.put("log", "oak_log");
	}

	private FallbackParser() {
	}

	public static Plan parse(String task) {
		String t = task.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\-\\s]", " ").replaceAll("\\s+", " ").trim();
		Builder builder = new Builder();

		Matcher m = GOTO.matcher(t);
		if (m.find()) {
			JsonObject args = new JsonObject();
			JsonArray pos = new JsonArray();
			pos.add(Integer.parseInt(m.group(1)));
			pos.add(Integer.parseInt(m.group(2)));
			pos.add(Integer.parseInt(m.group(3)));
			args.add("pos", pos);
			builder.add("goto", args, "Walk to " + m.group(1) + " " + m.group(2) + " " + m.group(3));
			return builder.plan(task);
		}

		if (TREE.matcher(t).find()) {
			builder.chop(logFor(t), firstNumber(t, 1));
			return builder.plan(task);
		}

		m = MINE.matcher(t);
		if (m.find()) {
			Block block = resolveBlock(words(m.group(2)));
			if (block != null) {
				if (block.getDefaultState().isIn(net.minecraft.registry.tag.BlockTags.LOGS)) {
					builder.chop(block.asItem(), count(m.group(1)));
				} else {
					builder.prefetchPlanks(Drops.of(block), builder.have(Drops.of(block)) + count(m.group(1)));
					if (!builder.mine(block, count(m.group(1)))) {
						return null;
					}
				}
				return builder.plan(task);
			}
		}

		m = MAKE.matcher(t);
		if (m.find()) {
			Item item = resolveItem(words(m.group(2)));
			if (item != null && builder.prefetchPlanks(item, builder.have(item) + count(m.group(1)))
					&& builder.obtain(item, builder.have(item) + count(m.group(1)))) {
				return builder.steps.isEmpty() ? null : builder.plan(task);
			}
		}
		return null;
	}

	/** Builds steps while simulating the inventory, so each request only produces what is still missing. */
	private static final class Builder {
		private static final int MAX_DEPTH = 8;

		final List<PlanStep> steps = new ArrayList<>();
		final Map<Item, Integer> inv = new HashMap<>(InventoryHelper.snapshot());
		boolean tableReady = WorldScanner.nearest(s -> s.isOf(Blocks.CRAFTING_TABLE), 8) != null;
		int depth;

		int have(Item item) {
			return inv.getOrDefault(item, 0);
		}

		boolean obtain(Item item, int count) {
			if (have(item) >= count) {
				return true;
			}
			if (++depth > MAX_DEPTH || steps.size() > Planner.MAX_STEPS) {
				depth--;
				return false;
			}
			try {
				int need = count - have(item);
				if (new ItemStack(item).isIn(ItemTags.LOGS)) {
					chop(item, need);
					return true;
				}
				Recipes.Recipe recipe = Recipes.find(item);
				if (recipe != null) {
					return craft(recipe, need);
				}
				Block source = Drops.sourceOf(item);
				return source != null && mine(source, need);
			} finally {
				depth--;
			}
		}

		/**
		 * Gets all the planks the request will need in one chop + craft, instead of one log at a time as each
		 * sub-recipe discovers it is short. Over-estimating slightly is fine.
		 */
		boolean prefetchPlanks(Item item, int count) {
			int demand = plankDemand(item, count, new boolean[] {tableReady}, new boolean[] {hasPickaxe()}, 0);
			if (demand <= 0) {
				return true;
			}
			Item planks = pick(new Recipes.Ingredient("planks", stack -> stack.isIn(ItemTags.PLANKS)));
			return obtain(planks, demand);
		}

		private int plankDemand(Item item, int count, boolean[] tableCounted, boolean[] pickaxeCounted, int level) {
			int need = count - have(item);
			if (need <= 0 || level > MAX_DEPTH) {
				return 0;
			}
			if (new ItemStack(item).isIn(ItemTags.PLANKS)) {
				return need;
			}
			Recipes.Recipe recipe = Recipes.find(item);
			if (recipe != null) {
				int crafts = recipe.craftsFor(need);
				int sum = 0;
				if (recipe.size() > 2 && !tableCounted[0]) {
					tableCounted[0] = true;
					sum += plankDemand(Items.CRAFTING_TABLE, 1, tableCounted, pickaxeCounted, level + 1);
				}
				Map<Recipes.Ingredient, Integer> perCraft = new LinkedHashMap<>();
				for (Recipes.Cell cell : recipe.cells()) {
					perCraft.merge(cell.ingredient(), 1, Integer::sum);
				}
				for (Map.Entry<Recipes.Ingredient, Integer> entry : perCraft.entrySet()) {
					Item choice = pick(entry.getKey());
					if (choice != null && !new ItemStack(choice).isIn(ItemTags.LOGS)) {
						sum += plankDemand(choice, entry.getValue() * crafts, tableCounted, pickaxeCounted, level + 1);
					}
				}
				return sum;
			}
			Block source = Drops.sourceOf(item);
			if (source != null && source.getDefaultState().isToolRequired() && !pickaxeCounted[0]) {
				pickaxeCounted[0] = true;
				return plankDemand(Items.WOODEN_PICKAXE, 1, tableCounted, pickaxeCounted, level + 1);
			}
			return 0;
		}

		boolean craft(Recipes.Recipe recipe, int need) {
			int crafts = recipe.craftsFor(need);
			if (recipe.size() > 2 && !ensureTable()) {
				return false;
			}
			Map<Recipes.Ingredient, Integer> perCraft = new LinkedHashMap<>();
			for (Recipes.Cell cell : recipe.cells()) {
				perCraft.merge(cell.ingredient(), 1, Integer::sum);
			}
			Map<Recipes.Ingredient, Item> chosen = new LinkedHashMap<>();
			// Gathering one ingredient can use up another (sticks eat planks), so re-check a few times.
			for (int pass = 0; pass < 3; pass++) {
				boolean satisfied = true;
				for (Map.Entry<Recipes.Ingredient, Integer> entry : perCraft.entrySet()) {
					Item choice = chosen.computeIfAbsent(entry.getKey(), this::pick);
					if (choice == null) {
						return false;
					}
					int required = entry.getValue() * crafts;
					if (have(choice) < required) {
						satisfied = false;
						if (!obtain(choice, required)) {
							return false;
						}
					}
				}
				if (satisfied) {
					break;
				}
			}
			for (Map.Entry<Recipes.Ingredient, Integer> entry : perCraft.entrySet()) {
				Item choice = chosen.get(entry.getKey());
				if (have(choice) < entry.getValue() * crafts) {
					return false;
				}
				inv.merge(choice, -entry.getValue() * crafts, Integer::sum);
			}
			JsonObject args = new JsonObject();
			args.addProperty("item", Registries.ITEM.getId(recipe.output()).toString());
			args.addProperty("count", need);
			add(recipe.size() > 2 ? "craft_at_table" : "craft", args, "Craft " + name(recipe.output()));
			inv.merge(recipe.output(), crafts * recipe.outputCount(), Integer::sum);
			return true;
		}

		boolean ensureTable() {
			if (tableReady) {
				return true;
			}
			if (!obtain(Items.CRAFTING_TABLE, 1)) {
				return false;
			}
			JsonObject args = new JsonObject();
			args.addProperty("item", "minecraft:crafting_table");
			args.addProperty("name", "crafting_table");
			add("place_block", args, "Place the table");
			inv.merge(Items.CRAFTING_TABLE, -1, Integer::sum);
			tableReady = true;
			return true;
		}

		void chop(Item log, int count) {
			JsonObject args = new JsonObject();
			args.addProperty("count", count);
			if (log != null) {
				args.addProperty("block", Registries.ITEM.getId(log).toString());
			}
			add("chop_logs", args, count == 1 ? "Chop a log" : "Chop " + count + " logs");
			if (log != null) {
				inv.merge(log, count, Integer::sum);
			}
		}

		boolean mine(Block block, int count) {
			if (block.getDefaultState().isToolRequired() && !hasPickaxe()) {
				Item pickaxe = block == Blocks.IRON_ORE ? Items.STONE_PICKAXE : Items.WOODEN_PICKAXE;
				if (!obtain(pickaxe, 1)) {
					return false;
				}
			}
			JsonObject args = new JsonObject();
			args.addProperty("block", Registries.BLOCK.getId(block).toString());
			args.addProperty("count", count);
			add("mine_blocks", args, "Mine " + block.getName().getString().toLowerCase(Locale.ROOT));
			inv.merge(Drops.of(block), count, Integer::sum);
			return true;
		}

		boolean hasPickaxe() {
			return inv.entrySet().stream().anyMatch(e -> e.getValue() > 0 && new ItemStack(e.getKey()).isIn(ItemTags.PICKAXES));
		}

		/** The concrete item to use for an ingredient: what the inventory has most of, else a sensible default. */
		Item pick(Recipes.Ingredient ingredient) {
			Item best = null;
			int bestCount = 0;
			for (Map.Entry<Item, Integer> entry : inv.entrySet()) {
				if (entry.getValue() > bestCount && ingredient.matches().test(new ItemStack(entry.getKey()))) {
					best = entry.getKey();
					bestCount = entry.getValue();
				}
			}
			if (best != null) {
				return best;
			}
			if (ingredient.name().equals("planks")) {
				for (String wood : WOODS) {
					Item log = Registries.ITEM.get(Identifier.ofVanilla(wood + "_log"));
					if (have(log) > 0) {
						return Registries.ITEM.get(Identifier.ofVanilla(wood + "_planks"));
					}
				}
				return Items.OAK_PLANKS;
			}
			return lookupItem(singular(ingredient.name().replace(' ', '_')));
		}

		void add(String skill, JsonObject args, String label) {
			PlanStep step = new PlanStep();
			step.skill = skill;
			step.args = args;
			step.label = label;
			steps.add(step);
		}

		Plan plan(String task) {
			Plan plan = new Plan();
			plan.task = task;
			plan.steps = steps;
			return plan;
		}
	}

	private static List<String> words(String phrase) {
		List<String> words = new ArrayList<>(Arrays.asList(phrase.trim().split(" ")));
		while (!words.isEmpty() && FILLER.contains(words.get(0))) {
			words.remove(0);
		}
		return words;
	}

	/** Longest leading run of words that names an item ("stone pickaxe from the" → stone_pickaxe). */
	private static Item resolveItem(List<String> words) {
		for (int len = Math.min(3, words.size()); len >= 1; len--) {
			String joined = String.join("_", words.subList(0, len));
			for (String candidate : List.of(joined, singular(joined))) {
				String alias = ITEM_ALIASES.get(candidate);
				Item item = lookupItem(alias != null ? alias : candidate);
				if (item != null) {
					return item;
				}
			}
		}
		return null;
	}

	private static Block resolveBlock(List<String> words) {
		for (int len = Math.min(3, words.size()); len >= 1; len--) {
			String joined = String.join("_", words.subList(0, len));
			for (String candidate : List.of(joined, singular(joined))) {
				String alias = BLOCK_ALIASES.get(candidate);
				Block block = lookupBlock(alias != null ? alias : candidate);
				if (block == null) {
					block = lookupBlock(candidate + "_ore");
				}
				if (block != null) {
					return block;
				}
			}
		}
		return null;
	}

	private static Item lookupItem(String path) {
		Identifier id = Identifier.tryParse("minecraft:" + path);
		return id != null && Registries.ITEM.containsId(id) ? Registries.ITEM.get(id) : null;
	}

	private static Block lookupBlock(String path) {
		Identifier id = Identifier.tryParse("minecraft:" + path);
		return id != null && Registries.BLOCK.containsId(id) ? Registries.BLOCK.get(id) : null;
	}

	private static String singular(String word) {
		if (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes") && !word.endsWith("axes")) {
			return word.substring(0, word.length() - 2);
		}
		if (word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("planks")) {
			return word.substring(0, word.length() - 1);
		}
		return word;
	}

	private static int count(String quantity) {
		if (quantity == null) {
			return 1;
		}
		return switch (quantity) {
			case "a", "an" -> 1;
			case "some" -> 4;
			default -> Math.max(1, Math.min(64, Integer.parseInt(quantity)));
		};
	}

	private static int firstNumber(String text, int fallback) {
		Matcher m = Pattern.compile("\\b(\\d+)\\b").matcher(text);
		return m.find() ? Math.max(1, Math.min(64, Integer.parseInt(m.group(1)))) : fallback;
	}

	/** The specific log type if the text names a wood ("chop a birch tree"), else null for any log. */
	private static Item logFor(String text) {
		for (String wood : WOODS) {
			if (text.contains(wood.replace('_', ' '))) {
				return Registries.ITEM.get(Identifier.ofVanilla(wood + "_log"));
			}
		}
		return null;
	}

	private static String name(Item item) {
		return item.getName().getString().toLowerCase(Locale.ROOT);
	}
}
