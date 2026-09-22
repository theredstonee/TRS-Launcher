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

	private TrsKeys() {
	}

	static void register() {
		menu = register(new KeyBinding("key.trsclient.menu", Keyboard.KEY_RSHIFT, CATEGORY));
		// Bis 1.11.2 ist C frei; ab 1.12 belegt Vanilla C mit "Schnellleiste speichern" (bei Bedarf umbelegen).
		zoom = register(new KeyBinding("key.trsclient.zoom", Keyboard.KEY_C, CATEGORY));
		// Standardmäßig unbelegt – Fullbright lässt sich auch im Menü schalten.
		fullbright = register(new KeyBinding("key.trsclient.fullbright", Keyboard.KEY_NONE, CATEGORY));
		freelook = register(new KeyBinding("key.trsclient.freelook", Keyboard.KEY_LMENU, CATEGORY));
	}

	private static KeyBinding register(KeyBinding key) {
		ClientRegistry.registerKeyBinding(key);
		return key;
	}
}
