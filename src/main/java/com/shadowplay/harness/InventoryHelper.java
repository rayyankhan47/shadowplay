package com.shadowplay.harness;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.screen.slot.SlotActionType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Inventory reads and hotbar management. Inventory indices are PlayerInventory indices: hotbar 0–8, main 9–35,
 * offhand 40. Slot clicks use the player screen handler, which is valid whenever no container screen is open.
 */
public final class InventoryHelper {
	private static final int HOTBAR_SIZE = 9;

	private InventoryHelper() {
	}

	private static ClientPlayerEntity player() {
		return MinecraftClient.getInstance().player;
	}

	public static int count(Item item) {
		return count(stack -> stack.isOf(item));
	}

	public static int count(TagKey<Item> tag) {
		return count(stack -> stack.isIn(tag));
	}

	/** Counts matching items across hotbar, main inventory and offhand (not armor). */
	public static int count(Predicate<ItemStack> filter) {
		ClientPlayerEntity player = player();
		if (player == null) {
			return 0;
		}
		PlayerInventory inv = player.getInventory();
		int total = 0;
		for (ItemStack stack : inv.main) {
			if (!stack.isEmpty() && filter.test(stack)) {
				total += stack.getCount();
			}
		}
		for (ItemStack stack : inv.offHand) {
			if (!stack.isEmpty() && filter.test(stack)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** Item → total count for everything the player carries (hotbar, main, offhand). */
	public static Map<Item, Integer> snapshot() {
		Map<Item, Integer> counts = new HashMap<>();
		ClientPlayerEntity player = player();
		if (player == null) {
			return counts;
		}
		PlayerInventory inv = player.getInventory();
		for (ItemStack stack : inv.main) {
			if (!stack.isEmpty()) {
				counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		for (ItemStack stack : inv.offHand) {
			if (!stack.isEmpty()) {
				counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		return counts;
	}

	/** The PlayerInventory index of the first stack of the item (hotbar first), or -1. */
	public static int findSlot(Item item) {
		return findSlot(stack -> stack.isOf(item));
	}

	public static int findSlot(Predicate<ItemStack> filter) {
		ClientPlayerEntity player = player();
		if (player == null) {
			return -1;
		}
		PlayerInventory inv = player.getInventory();
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack stack = inv.main.get(i);
			if (!stack.isEmpty() && filter.test(stack)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Makes sure the item is in the hotbar, swapping it from the main inventory into preferredSlot (0–8) if needed.
	 * Returns the hotbar index holding it, or -1 if the player has none.
	 */
	public static int ensureInHotbar(Item item, int preferredSlot) {
		return ensureInHotbar(stack -> stack.isOf(item), preferredSlot);
	}

	public static int ensureInHotbar(Predicate<ItemStack> filter, int preferredSlot) {
		int slot = findSlot(filter);
		if (slot < 0) {
			return -1;
		}
		if (slot < HOTBAR_SIZE) {
			return slot;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		if (client.interactionManager == null || player.currentScreenHandler != player.playerScreenHandler) {
			return -1;
		}
		int hotbar = Math.max(0, Math.min(HOTBAR_SIZE - 1, preferredSlot));
		client.interactionManager.clickSlot(player.playerScreenHandler.syncId, toPlayerHandlerSlot(slot), hotbar,
				SlotActionType.SWAP, player);
		return hotbar;
	}

	/** Puts the item in hand: moves it to the hotbar if needed and selects it. Returns false if the player has none. */
	public static boolean select(Item item) {
		return select(stack -> stack.isOf(item));
	}

	public static boolean select(Predicate<ItemStack> filter) {
		ClientPlayerEntity player = player();
		if (player == null) {
			return false;
		}
		if (filter.test(player.getMainHandStack())) {
			return true;
		}
		int hotbar = ensureInHotbar(filter, player.getInventory().selectedSlot);
		if (hotbar < 0) {
			return false;
		}
		player.getInventory().selectedSlot = hotbar;
		return true;
	}

	/** Maps a PlayerInventory index to its PlayerScreenHandler slot id (hotbar 36–44, main 9–35, offhand 45). */
	public static int toPlayerHandlerSlot(int inventoryIndex) {
		if (inventoryIndex < HOTBAR_SIZE) {
			return 36 + inventoryIndex;
		}
		if (inventoryIndex == PlayerInventory.OFF_HAND_SLOT) {
			return 45;
		}
		return inventoryIndex;
	}
}
