package com.shadowplay.skills;

import com.google.gson.JsonElement;
import com.shadowplay.plan.PlanStep;
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
