package com.shadowplay.skills;

import com.google.gson.JsonElement;
import com.shadowplay.plan.PlanStep;
import com.shadowplay.skills.impl.BreakBlockSkill;
import com.shadowplay.skills.impl.CollectDropsSkill;
import com.shadowplay.skills.impl.CraftAtTableSkill;
import com.shadowplay.skills.impl.CraftSkill;
import com.shadowplay.skills.impl.GotoSkill;
import com.shadowplay.skills.impl.PlaceBlockSkill;
import com.shadowplay.skills.impl.RememberPositionSkill;
import com.shadowplay.skills.impl.SaySkill;
import com.shadowplay.skills.impl.WaitSkill;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Skill name → factory, with argument metadata. The metadata validates plans and is also what the live planner
 * shows Gemini as the skill catalog.
 */
public final class SkillRegistry {
	public enum ArgType {
		INT("integer"),
		STRING("string"),
		BOOL("boolean"),
		ITEM("item id, e.g. \"minecraft:oak_planks\""),
		BLOCK("block id, e.g. \"minecraft:stone\""),
		POS("block position [x, y, z]");

		public final String description;

		ArgType(String description) {
			this.description = description;
		}
	}

	public record Arg(String name, ArgType type, boolean required, String description) {
	}

	/** extraCheck returns an error message or null; it runs after the per-arg type checks pass. */
	public record Spec(String name, String description, List<Arg> args, Function<SkillArgs, Skill> factory,
			Function<SkillArgs, String> extraCheck) {
	}

	private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

	static {
		register("say", "Print a short line in chat.", SaySkill::new,
				arg("text", ArgType.STRING, true, "the line to print"));
		register("wait", "Do nothing for a number of ticks (20 ticks = 1 second).", WaitSkill::new,
				arg("ticks", ArgType.INT, true, "how many ticks to wait"));
		register("goto", "Walk to a position, a remembered position, or the nearest block of a type. "
						+ "Give exactly one of pos, name or near. With range 0 it stands on the spot, or next to it if it is a solid block.",
				GotoSkill::new, GotoSkill::check,
				arg("pos", ArgType.POS, false, "destination block position"),
				arg("name", ArgType.STRING, false, "a position saved earlier in this task (e.g. by remember_position)"),
				arg("near", ArgType.BLOCK, false, "walk next to the nearest block of this type"),
				arg("range", ArgType.INT, false, "stop within this many blocks (default 0)"));
		register("remember_position", "Save the player's current position under a name for later steps.",
				RememberPositionSkill::new,
				arg("name", ArgType.STRING, true, "name to save the position under"));
		register("break_block", "Break exactly one block, walking into reach first if needed. Give exactly one of target or pos.",
				BreakBlockSkill::new, BreakBlockSkill::check,
				arg("target", ArgType.STRING, false, "a position saved earlier in this task"),
				arg("pos", ArgType.POS, false, "block position"));
		register("collect_drops", "Pick up dropped items of a type lying nearby (use right after break_block).",
				CollectDropsSkill::new,
				arg("item", ArgType.ITEM, true, "the dropped item to pick up"),
				arg("radius", ArgType.INT, false, "search radius in blocks (default 8)"));
		register("craft", "Craft an item in the 2x2 inventory grid (planks, sticks, crafting_table, torch).",
				CraftSkill::new,
				arg("item", ArgType.ITEM, true, "the item to craft"),
				arg("count", ArgType.INT, false, "how many items to end up with (default 1); repeats the recipe as needed"));
		register("craft_at_table", "Craft an item in the 3x3 grid of a crafting table (wooden_pickaxe, stone_pickaxe; 2x2 recipes work too). "
						+ "Uses the table placed earlier in this task, or the nearest one, walking to it if needed.",
				CraftAtTableSkill::new,
				arg("item", ArgType.ITEM, true, "the item to craft"),
				arg("count", ArgType.INT, false, "how many items to end up with (default 1)"),
				arg("table", ArgType.STRING, false, "saved position of the table (default: the one saved as crafting_table, else the nearest)"));
		register("place_block", "Place a block from the inventory on flat ground next to the player.",
				PlaceBlockSkill::new,
				arg("item", ArgType.ITEM, true, "the block item to place"),
				arg("name", ArgType.STRING, false, "save where it was placed under this name (e.g. crafting_table)"));
	}

	private SkillRegistry() {
	}

	public static Arg arg(String name, ArgType type, boolean required, String description) {
		return new Arg(name, type, required, description);
	}

	public static void register(String name, String description, Function<SkillArgs, Skill> factory, Arg... args) {
		register(name, description, factory, null, args);
	}

	public static void register(String name, String description, Function<SkillArgs, Skill> factory,
			Function<SkillArgs, String> extraCheck, Arg... args) {
		SPECS.put(name, new Spec(name, description, Arrays.asList(args), factory, extraCheck));
	}

	public static Collection<Spec> all() {
		return Collections.unmodifiableCollection(SPECS.values());
	}

	public static boolean exists(String name) {
		return SPECS.containsKey(name);
	}

	/** Returns null if the step is valid, otherwise a short error message. */
	public static String validate(PlanStep step) {
		if (step == null || step.skill == null) {
			return "step has no skill";
		}
		Spec spec = SPECS.get(step.skill);
		if (spec == null) {
			return "unknown skill '" + step.skill + "'";
		}
		SkillArgs args = new SkillArgs(step.args);
		for (Arg a : spec.args()) {
			if (!args.has(a.name())) {
				if (a.required()) {
					return step.skill + ": missing arg '" + a.name() + "'";
				}
				continue;
			}
			String typeError = checkType(a, args.raw(a.name()));
			if (typeError != null) {
				return step.skill + ": arg '" + a.name() + "' " + typeError;
			}
		}
		if (spec.extraCheck() != null) {
			String error = spec.extraCheck().apply(args);
			if (error != null) {
				return step.skill + ": " + error;
			}
		}
		return null;
	}

	/** Builds a fresh skill instance. Throws IllegalArgumentException if the step is invalid. */
	public static Skill create(PlanStep step) {
		String error = validate(step);
		if (error != null) {
			throw new IllegalArgumentException(error);
		}
		return SPECS.get(step.skill).factory().apply(new SkillArgs(step.args));
	}

	private static String checkType(Arg a, JsonElement e) {
		switch (a.type()) {
			case INT -> {
				if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
					return "must be an integer";
				}
				double d = e.getAsDouble();
				if (d != Math.floor(d)) {
					return "must be an integer";
				}
			}
			case STRING -> {
				if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
					return "must be a string";
				}
			}
			case BOOL -> {
				if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
					return "must be true or false";
				}
			}
			case ITEM -> {
				Identifier id = e.isJsonPrimitive() ? SkillArgs.parseId(e.getAsString()) : null;
				if (id == null || !Registries.ITEM.containsId(id)) {
					return "is not a known item";
				}
			}
			case BLOCK -> {
				Identifier id = e.isJsonPrimitive() ? SkillArgs.parseId(e.getAsString()) : null;
				if (id == null || !Registries.BLOCK.containsId(id)) {
					return "is not a known block";
				}
			}
			case POS -> {
				if (SkillArgs.parsePos(e) == null) {
					return "must be [x, y, z]";
				}
			}
		}
		return null;
	}
}
