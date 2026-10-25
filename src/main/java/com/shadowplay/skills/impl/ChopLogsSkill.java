package com.shadowplay.skills.impl;

import com.shadowplay.skills.SkillArgs;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;

import java.util.function.Predicate;

/** Chops N logs (of one type, or any log by default) with Baritone and picks them up. */
public class ChopLogsSkill extends AbstractMineSkill {
	private final Block block;

	public ChopLogsSkill(SkillArgs args) {
		super(args.getInt("count", 1));
		this.block = args.has("block") ? args.getBlock("block") : null;
	}

	public static String check(SkillArgs args) {
		if (!args.has("block")) {
			return null;
		}
		Block block = args.getBlock("block");
		return block != null && block.getDefaultState().isIn(BlockTags.LOGS) ? null : "block must be a kind of log";
	}

	@Override
	protected Block[] blocks() {
		if (block != null) {
			return new Block[] {block};
		}
		return Registries.BLOCK.stream().filter(b -> b.getDefaultState().isIn(BlockTags.LOGS)).toArray(Block[]::new);
	}

	@Override
	protected Predicate<ItemStack> drop() {
		return block != null ? stack -> stack.isOf(block.asItem()) : stack -> stack.isIn(ItemTags.LOGS);
	}

	@Override
	protected String what() {
		return "logs";
	}

	@Override
	protected String key() {
		return "chop:" + (block != null ? Registries.BLOCK.getId(block) : "logs");
	}
}
