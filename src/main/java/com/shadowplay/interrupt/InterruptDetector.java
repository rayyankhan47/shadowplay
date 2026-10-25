package com.shadowplay.interrupt;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Detects real player input by reading physical key/mouse state from GLFW. KeyBinding.isPressed() cannot be used
 * because Baritone writes to the same input state while it drives.
 *
 * Exemptions: any open screen except the pause menu (chat, the agent's crafting screens), a grace period at task
 * start/resume while Enter is released, and a short grace after the agent closes a screen it opened.
 */
public final class InterruptDetector {
	/** Mouse movement (in screen pixels within one tick) that counts as the player taking over. */
	public static final double MOUSE_MOVE_THRESHOLD_PX = 20.0;
	public static final int TASK_START_GRACE_TICKS = 10;
	public static final int SCREEN_CLOSE_GRACE_TICKS = 5;

	private static double lastMouseX = Double.NaN;
	private static double lastMouseY = Double.NaN;
	private static int graceTicks = 0;
	private static boolean agentScreenOpen = false;

	private InterruptDetector() {
	}

	/** Call when a task starts or resumes. */
	public static void startGrace() {
		grace(TASK_START_GRACE_TICKS);
	}

	public static void grace(int ticks) {
		graceTicks = Math.max(graceTicks, ticks);
	}

	public static void agentOpenedScreen() {
		agentScreenOpen = true;
	}

	public static void agentClosedScreen() {
		agentScreenOpen = false;
		grace(SCREEN_CLOSE_GRACE_TICKS);
	}

	public static boolean isAgentScreenOpen() {
		return agentScreenOpen;
	}

	/** Returns a short description of the input if the player took control this tick, otherwise null. */
	public static String poll(MinecraftClient client) {
		if (client.currentScreen instanceof GameMenuScreen) {
			return "pause menu";
		}
		if (client.currentScreen != null) {
			// Chat, the agent's crafting screen, etc.: inputs there are not player movement. The cursor is unlocked
			// and gets re-centered on close, so the mouse baseline must start over afterwards.
			resetMouse();
			return null;
		}

		double x = client.mouse.getX();
		double y = client.mouse.getY();
		boolean moved = !Double.isNaN(lastMouseX)
				&& Math.hypot(x - lastMouseX, y - lastMouseY) > MOUSE_MOVE_THRESHOLD_PX;
		lastMouseX = x;
		lastMouseY = y;

		if (graceTicks > 0) {
			graceTicks--;
			return null;
		}

		long window = client.getWindow().getHandle();
		GameOptions options = client.options;
		KeyBinding[] movement = {options.forwardKey, options.backKey, options.leftKey, options.rightKey,
				options.jumpKey, options.sneakKey, options.sprintKey};
		for (KeyBinding binding : movement) {
			if (isPhysicallyDown(window, binding)) {
				return binding.getTranslationKey();
			}
		}
		if (isPhysicallyDown(window, options.attackKey) || isPhysicallyDown(window, options.useKey)) {
			return "mouse click";
		}
		return moved ? "mouse move" : null;
	}

	/** Forget the last mouse position, e.g. after the cursor was re-centered. */
	public static void resetMouse() {
		lastMouseX = Double.NaN;
		lastMouseY = Double.NaN;
	}

	private static boolean isPhysicallyDown(long window, KeyBinding binding) {
		InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(binding);
		int code = key.getCode();
		if (code == InputUtil.UNKNOWN_KEY.getCode()) {
			return false;
		}
		if (key.getCategory() == InputUtil.Type.MOUSE) {
			return GLFW.glfwGetMouseButton(window, code) == GLFW.GLFW_PRESS;
		}
		if (key.getCategory() == InputUtil.Type.KEYSYM) {
			return InputUtil.isKeyPressed(window, code);
		}
		return false;
	}
}
