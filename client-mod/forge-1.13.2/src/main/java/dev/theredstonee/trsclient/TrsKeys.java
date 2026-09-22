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
	/** Wegpunkt an der eigenen Position anlegen. */
	public static KeyBinding waypointAdd;
	/** Wegpunkt-Liste öffnen. */
	public static KeyBinding waypointList;
	/** Vier frei belegbare Tasten, die je einen Text senden (Standard: unbelegt). */
	public static final KeyBinding[] textHotkeys = new KeyBinding[4];

	private TrsKeys() {
	}

	static void register() {
		menu = register(new KeyBinding("key.trsclient.menu", GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY));
		// Vanilla belegt C seit 1.12 mit "Schnellleiste laden" (Kreativ) – bei Bedarf umbelegen.
		zoom = register(new KeyBinding("key.trsclient.zoom", GLFW.GLFW_KEY_C, CATEGORY));
		// Standardmäßig unbelegt – Fullbright lässt sich auch im Menü schalten.
		fullbright = register(new KeyBinding("key.trsclient.fullbright", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
		waypointAdd = register(new KeyBinding("key.trsclient.waypointAdd", GLFW.GLFW_KEY_B, CATEGORY));
		waypointList = register(new KeyBinding("key.trsclient.waypointList", GLFW.GLFW_KEY_N, CATEGORY));
		for (int i = 0; i < textHotkeys.length; i++) {
			// Standardmäßig unbelegt – niemand soll versehentlich etwas in den Chat schicken.
			textHotkeys[i] = register(new KeyBinding("key.trsclient.text" + (i + 1), GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
		}
	}

	private static KeyBinding register(KeyBinding key) {
		ClientRegistry.registerKeyBinding(key);
		return key;
	}
}
