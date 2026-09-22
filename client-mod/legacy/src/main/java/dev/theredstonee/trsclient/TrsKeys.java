package dev.theredstonee.trsclient;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.Keyboard;

/** Tastenbelegungen – erscheinen in den Steuerungs-Optionen unter "TRS Client". */
public final class TrsKeys {
	public static final String CATEGORY = "key.categories.trsclient";

	public static KeyBinding menu;
	public static KeyBinding zoom;
	public static KeyBinding fullbright;
	public static KeyBinding freelook;
	/** Wegpunkt an der eigenen Position anlegen. */
	public static KeyBinding waypointAdd;
	/** Wegpunkt-Liste öffnen. */
	public static KeyBinding waypointList;
	/** Vier frei belegbare Tasten, die je einen Text senden (Standard: unbelegt). */
	public static final KeyBinding[] textHotkeys = new KeyBinding[4];

	private TrsKeys() {
	}

	static void register() {
		menu = register(new KeyBinding("key.trsclient.menu", Keyboard.KEY_RSHIFT, CATEGORY));
		// Bis 1.11.2 ist C frei; ab 1.12 belegt Vanilla C mit "Schnellleiste speichern" (bei Bedarf umbelegen).
		zoom = register(new KeyBinding("key.trsclient.zoom", Keyboard.KEY_C, CATEGORY));
		// Standardmäßig unbelegt – Fullbright lässt sich auch im Menü schalten.
		fullbright = register(new KeyBinding("key.trsclient.fullbright", Keyboard.KEY_NONE, CATEGORY));
		freelook = register(new KeyBinding("key.trsclient.freelook", Keyboard.KEY_LMENU, CATEGORY));
		waypointAdd = register(new KeyBinding("key.trsclient.waypointAdd", Keyboard.KEY_B, CATEGORY));
		waypointList = register(new KeyBinding("key.trsclient.waypointList", Keyboard.KEY_N, CATEGORY));
		for (int i = 0; i < textHotkeys.length; i++) {
			// Standardmäßig unbelegt – niemand soll versehentlich etwas in den Chat schicken.
			textHotkeys[i] = register(new KeyBinding("key.trsclient.text" + (i + 1), Keyboard.KEY_NONE, CATEGORY));
		}
	}

	private static KeyBinding register(KeyBinding key) {
		ClientRegistry.registerKeyBinding(key);
		return key;
	}
}
