package dev.theredstonee.trsclient;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.glfw.GLFW;

/** Tastenbelegungen – erscheinen in den Steuerungs-Optionen unter "TRS Client". */
public final class TrsKeys {
	public static final String CATEGORY = "key.categories.trsclient";

	public static KeyBinding menu;
	public static KeyBinding zoom;
	public static KeyBinding fullbright;
	/** Wechselt das HUD-Profil (standardmäßig unbelegt). */
	public static KeyBinding hudProfile;

	private TrsKeys() {
	}

	static void register() {
		menu = register(new KeyBinding("key.trsclient.menu", GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY));
		// Zoom liegt auf V: Vanilla belegt C seit 1.12 mit "Schnellleiste speichern".
		zoom = register(new KeyBinding("key.trsclient.zoom", GLFW.GLFW_KEY_V, CATEGORY));
		// Standardmäßig unbelegt – Fullbright lässt sich auch im Menü schalten.
		fullbright = register(new KeyBinding("key.trsclient.fullbright", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
		// Standardmäßig unbelegt – Profile lassen sich auch im Menü wechseln.
		hudProfile = register(new KeyBinding("key.trsclient.hudProfile", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
	}

	/**
	 * Stellt den alten Zoom-Standard C auf V um – aber nur, wenn die Taste noch auf C liegt,
	 * also nie geändert wurde.
	 * @return true, wenn umgestellt wurde
	 */
	public static boolean migrateZoomKey() {
		if (zoom == null || zoom.getKey().getKeyCode() != GLFW.GLFW_KEY_C) return false;
		zoom.bind(net.minecraft.client.util.InputMappings.Type.KEYSYM.getOrMakeInput(GLFW.GLFW_KEY_V));
		KeyBinding.resetKeyBindingArrayAndHash();
		return true;
	}

	private static KeyBinding register(KeyBinding key) {
		ClientRegistry.registerKeyBinding(key);
		return key;
	}
}
