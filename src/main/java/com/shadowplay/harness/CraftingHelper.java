package com.shadowplay.harness;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Crafting by slot clicks (deterministic, no recipe book). Clicks are queued and sent one every
 * CLICK_INTERVAL_TICKS so the audience can watch; waits are queued too, because the result slot is filled by the
 * server. One instance per crafting session; tick() it every client tick while the screen is open.
 */
public final class CraftingHelper {
	public static final int CLICK_INTERVAL_TICKS = 2;
	private static final int RESULT_SLOT = 0;

	/** Slot ids of the two crafting screen handlers. */
	public enum Layout {
		/** PlayerScreenHandler (syncId 0): result 0, grid 1–4, main inventory 9–35, hotbar 36–44. */
		PLAYER(2, 9, 36),
		/** CraftingScreenHandler: result 0, grid 1–9, main inventory 10–36, hotbar 37–45. */
		TABLE(3, 10, 37);

		public final int gridSize;
		private final int mainStart;
		private final int hotbarStart;

		Layout(int gridSize, int mainStart, int hotbarStart) {
			this.gridSize = gridSize;
			this.mainStart = mainStart;
			this.hotbarStart = hotbarStart;
		}

		/** Grid slot id for a recipe cell (row-major, 1-based like "1 2 / 3 4"). */
		public int gridSlot(int row, int col) {
			return 1 + row * gridSize + col;
		}

		public int gridSlotCount() {
			return gridSize * gridSize;
		}

		/** Maps a PlayerInventory index (hotbar 0–8, main 9–35) to this handler's slot id. */
		public int toHandlerSlot(int inventoryIndex) {
			return inventoryIndex < 9 ? hotbarStart + inventoryIndex : mainStart + (inventoryIndex - 9);
		}

		/** Handler slot ids of the player's inventory, main first then hotbar. */
		public List<Integer> inventorySlots() {
			List<Integer> ids = new ArrayList<>();
			for (int i = mainStart; i < hotbarStart + 9; i++) {
				ids.add(i);
			}
			return ids;
		}
	}

	private record Click(int syncId, int slot, int button, SlotActionType type) {
	}

	private record WaitFor(BooleanSupplier condition, int timeoutTicks, String failure) {
	}

	/** Expands into more actions at execution time, when the screen contents are current. */
	private record Deferred(Supplier<List<Object>> actions) {
	}

	private final Deque<Object> queue = new ArrayDeque<>();
	private int cooldown;
	private int waited;
	private String failure;

	public void tick() {
		if (failure != null || queue.isEmpty()) {
			return;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		Object next = queue.peekFirst();
		if (next instanceof Deferred deferred) {
			queue.pollFirst();
			List<Object> expanded = deferred.actions().get();
			for (int i = expanded.size() - 1; i >= 0; i--) {
				queue.addFirst(expanded.get(i));
			}
			tick();
		} else if (next instanceof WaitFor wait) {
			if (wait.condition().getAsBoolean()) {
				queue.pollFirst();
				waited = 0;
				tick();
			} else if (++waited > wait.timeoutTicks()) {
				failure = wait.failure();
			}
		} else if (next instanceof Click click) {
			MinecraftClient client = MinecraftClient.getInstance();
			ClientPlayerEntity player = client.player;
			if (player == null || client.interactionManager == null || player.currentScreenHandler.syncId != click.syncId()) {
				failure = "the crafting screen closed";
				return;
			}
			queue.pollFirst();
			client.interactionManager.clickSlot(click.syncId(), click.slot(), click.button(), click.type(), player);
			cooldown = CLICK_INTERVAL_TICKS - 1;
		}
	}

	public boolean isIdle() {
		return queue.isEmpty();
	}

	/** Why the queue stopped, or null. */
	public String failure() {
		return failure;
	}

	public void clear() {
		queue.clear();
		cooldown = 0;
		waited = 0;
		failure = null;
	}

	public void click(int slot, int button, SlotActionType type) {
		queue.addLast(clickNow(slot, button, type));
	}

	public void waitFor(BooleanSupplier condition, int timeoutTicks, String failureReason) {
		queue.addLast(new WaitFor(condition, timeoutTicks, failureReason));
	}

	/**
	 * Queues the clicks that put exactly one matching item into each listed grid slot: pick up a source stack,
	 * right-click one item into each grid slot, put the rest back; repeat with more stacks if one is not enough.
	 * cells maps grid slot id → what may go there. Returns null if queued, or what is missing.
	 */
	public String placeIngredients(Layout layout, Map<Integer, Predicate<ItemStack>> cells) {
		ScreenHandler handler = handler();
		if (handler == null) {
			return "no crafting screen";
		}
		// Group grid slots by ingredient so each source stack is picked up once.
		Map<Predicate<ItemStack>, List<Integer>> byIngredient = new LinkedHashMap<>();
		cells.forEach((slot, ingredient) -> byIngredient.computeIfAbsent(ingredient, k -> new ArrayList<>()).add(slot));

		Map<Integer, Integer> remaining = new LinkedHashMap<>();
		for (int slot : layout.inventorySlots()) {
			remaining.put(slot, handler.getSlot(slot).getStack().getCount());
		}
		List<Click> clicks = new ArrayList<>();
		for (Map.Entry<Predicate<ItemStack>, List<Integer>> entry : byIngredient.entrySet()) {
			List<Integer> targets = new ArrayList<>(entry.getValue());
			for (int source : layout.inventorySlots()) {
				if (targets.isEmpty()) {
					break;
				}
				ItemStack stack = handler.getSlot(source).getStack();
				int available = remaining.get(source);
				if (stack.isEmpty() || available == 0 || !entry.getKey().test(stack)) {
					continue;
				}
				clicks.add(clickNow(source, 0, SlotActionType.PICKUP));
				while (available > 0 && !targets.isEmpty()) {
					clicks.add(clickNow(targets.remove(0), 1, SlotActionType.PICKUP));
					available--;
				}
				remaining.put(source, available);
				if (available > 0) {
					clicks.add(clickNow(source, 0, SlotActionType.PICKUP));
				}
			}
			if (!targets.isEmpty()) {
				return "not enough ingredients";
			}
		}
		queue.addAll(clicks);
		return null;
	}

	/** Shift-clicks the result into the inventory once the server has filled the result slot. */
	public void takeResult(Predicate<ItemStack> expected, String failureReason) {
		waitFor(() -> {
			ScreenHandler handler = handler();
			return handler != null && expected.test(handler.getSlot(RESULT_SLOT).getStack());
		}, 20, failureReason);
		click(RESULT_SLOT, 0, SlotActionType.QUICK_MOVE);
		waitFor(() -> {
			ScreenHandler handler = handler();
			return handler != null && handler.getSlot(RESULT_SLOT).getStack().isEmpty();
		}, 20, failureReason);
	}

	/** Shift-clicks anything left in the grid back into the inventory. */
	public void returnGrid(Layout layout) {
		queue.addLast(new Deferred(() -> {
			List<Object> clicks = new ArrayList<>();
			ScreenHandler handler = handler();
			if (handler != null) {
				for (int slot = 1; slot <= layout.gridSlotCount(); slot++) {
					if (!handler.getSlot(slot).getStack().isEmpty()) {
						clicks.add(clickNow(slot, 0, SlotActionType.QUICK_MOVE));
					}
				}
			}
			return clicks;
		}));
	}

	private static Click clickNow(int slot, int button, SlotActionType type) {
		ScreenHandler handler = handler();
		return new Click(handler != null ? handler.syncId : -1, slot, button, type);
	}

	private static ScreenHandler handler() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		return player != null ? player.currentScreenHandler : null;
	}
}
