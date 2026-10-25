package com.shadowplay.skills.impl;

import com.shadowplay.harness.Drops;
import com.shadowplay.harness.Interact;
import com.shadowplay.harness.InventoryHelper;
import com.shadowplay.skills.SkillArgs;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.function.Predicate;

/** Mines N blocks of a type with Baritone (it digs down/through and picks the tool); counts what they drop. */
public class MineBlocksSkill extends AbstractMineSkill {
	private final Block block;
	private final Item dropItem;

	public MineBlocksSkill(SkillArgs args) {
		super(args.getInt("count", 1));
		this.block = args.getBlock("block");
		this.dropItem = Drops.of(block);
	}

	@Override
	protected String precondition() {
		BlockState state = block.getDefaultState();
		if (state.isToolRequired() && InventoryHelper.findSlot(stack -> stack.isSuitableFor(state)) < 0) {
			return "need a pickaxe to mine " + block.getName().getString().toLowerCase();
		}
		return null;
	}

	@Override
	protected Block[] blocks() {
		return new Block[] {block};
	}

	@Override
	protected Predicate<ItemStack> drop() {
		return stack -> stack.isOf(dropItem);
	}

	@Override
	protected String what() {
		return Interact.itemName(dropItem);
	}

	@Override
	protected String key() {
		return "mine:" + Registries.BLOCK.getId(block);
	}
}
