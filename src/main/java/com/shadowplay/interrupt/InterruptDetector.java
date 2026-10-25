package com.shadowplay.interrupt;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Detects real player input by reading physical key/mouse state from GLFW. KeyBinding.isPressed() cannot be used
 * because Baritone writes to the same input state while it drives.
 */
public final class InterruptDetector {
	/** Mouse movement (in screen pixels within one tick) that counts as the player taking over. */
	public static final double MOUSE_MOVE_THRESHOLD_PX = 20.0;

	private static double lastMouseX = Double.NaN;
	private static double lastMouseY = Double.NaN;

	private InterruptDetector() {
	}

	/** Returns a short description of the input if the player touched the controls this tick, otherwise null. */
	public static String poll(MinecraftClient client) {
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

		double x = client.mouse.getX();
		double y = client.mouse.getY();
		boolean moved = !Double.isNaN(lastMouseX)
				&& Math.hypot(x - lastMouseX, y - lastMouseY) > MOUSE_MOVE_THRESHOLD_PX;
		lastMouseX = x;
		lastMouseY = y;
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
