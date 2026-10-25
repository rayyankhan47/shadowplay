package com.shadowplay.skills;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Typed access to a plan step's JSON args. Values are assumed validated by SkillRegistry. */
public final class SkillArgs {
	private final JsonObject json;

	public SkillArgs(JsonObject json) {
		this.json = json == null ? new JsonObject() : json;
	}

	public boolean has(String name) {
		return json.has(name) && !json.get(name).isJsonNull();
	}

	public JsonElement raw(String name) {
		return json.get(name);
	}

	public int getInt(String name, int fallback) {
		return has(name) ? json.get(name).getAsInt() : fallback;
	}

	public String getString(String name, String fallback) {
		return has(name) ? json.get(name).getAsString() : fallback;
	}

	public boolean getBool(String name, boolean fallback) {
		return has(name) ? json.get(name).getAsBoolean() : fallback;
	}

	public Item getItem(String name) {
		Identifier id = parseId(getString(name, null));
		return id != null && Registries.ITEM.containsId(id) ? Registries.ITEM.get(id) : null;
	}

	public Block getBlock(String name) {
		Identifier id = parseId(getString(name, null));
		return id != null && Registries.BLOCK.containsId(id) ? Registries.BLOCK.get(id) : null;
	}

	public BlockPos getPos(String name) {
		return has(name) ? parsePos(json.get(name)) : null;
	}

	public static Identifier parseId(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return Identifier.tryParse(value.trim().toLowerCase());
	}

	/** Accepts [x, y, z], {"x":..,"y":..,"z":..} or "x y z". Returns null if malformed. */
	public static BlockPos parsePos(JsonElement e) {
		try {
			if (e.isJsonArray()) {
				JsonArray a = e.getAsJsonArray();
				if (a.size() != 3) {
					return null;
				}
				return new BlockPos(intOf(a.get(0)), intOf(a.get(1)), intOf(a.get(2)));
			}
			if (e.isJsonObject()) {
				JsonObject o = e.getAsJsonObject();
				if (!o.has("x") || !o.has("y") || !o.has("z")) {
					return null;
				}
				return new BlockPos(intOf(o.get("x")), intOf(o.get("y")), intOf(o.get("z")));
			}
			if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
				String[] parts = e.getAsString().trim().split("[\\s,]+");
				if (parts.length != 3) {
					return null;
				}
				return new BlockPos((int) Math.floor(Double.parseDouble(parts[0])),
						(int) Math.floor(Double.parseDouble(parts[1])),
						(int) Math.floor(Double.parseDouble(parts[2])));
			}
		} catch (RuntimeException ignored) {
			// malformed number
		}
		return null;
	}

	private static int intOf(JsonElement e) {
		JsonPrimitive p = e.getAsJsonPrimitive();
		return (int) Math.floor(p.getAsDouble());
	}

	@Override
	public String toString() {
		return json.toString();
	}
}
