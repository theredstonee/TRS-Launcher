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
	/** Wechselt das HUD-Profil (standardmäßig unbelegt). */
	public static KeyBinding hudProfile;

	private TrsKeys() {
	}

	static void register() {
		menu = register(new KeyBinding("key.trsclient.menu", Keyboard.KEY_RSHIFT, CATEGORY));
		// Zoom liegt auf V: ab Minecraft 1.12 belegt Vanilla C mit "Schnellleiste speichern".
		zoom = register(new KeyBinding("key.trsclient.zoom", Keyboard.KEY_V, CATEGORY));
		// Standardmäßig unbelegt – Fullbright lässt sich auch im Menü schalten.
		fullbright = register(new KeyBinding("key.trsclient.fullbright", Keyboard.KEY_NONE, CATEGORY));
		freelook = register(new KeyBinding("key.trsclient.freelook", Keyboard.KEY_LMENU, CATEGORY));
		// Standardmäßig unbelegt – Profile lassen sich auch im Menü wechseln.
		hudProfile = register(new KeyBinding("key.trsclient.hudProfile", Keyboard.KEY_NONE, CATEGORY));
	}

	/**
	 * Stellt den alten Zoom-Standard C auf V um – aber nur, wenn die Taste noch auf C liegt,
	 * also nie geändert wurde.
	 * @return true, wenn umgestellt wurde
	 */
	public static boolean migrateZoomKey() {
		if (zoom == null || zoom.getKeyCode() != Keyboard.KEY_C) return false;
		zoom.setKeyCode(Keyboard.KEY_V);
		KeyBinding.resetKeyBindingArrayAndHash();
		return true;
	}

	private static KeyBinding register(KeyBinding key) {
		ClientRegistry.registerKeyBinding(key);
		return key;
	}
}
