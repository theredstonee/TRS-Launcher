package dev.theredstonee.trsclient;

import cpw.mods.fml.client.registry.ClientRegistry;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

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
		menu = register(new KeyBinding("key.trsclient.menu", Keyboard.KEY_RSHIFT, CATEGORY));
		// Zoom liegt in allen TRS-Fassungen auf V: ab Minecraft 1.12 belegt Vanilla C mit
		// "Schnellleiste speichern" – in 1.7.10 wäre C zwar frei, die gleiche Taste ist aber weniger verwirrend.
		zoom = register(new KeyBinding("key.trsclient.zoom", Keyboard.KEY_V, CATEGORY));
		// Standardmäßig unbelegt – Fullbright lässt sich auch im Menü schalten.
		fullbright = register(new KeyBinding("key.trsclient.fullbright", Keyboard.KEY_NONE, CATEGORY));
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

	/**
	 * Verbindung der Zoom-Einstellung (TRS-Menü) mit der Vanilla-Tastenbelegung: gelesen und
	 * geschrieben wird die Belegung selbst (gespeichert in options.txt). Nur Tastatur-Tasten.
	 */
	public static dev.theredstonee.trsclient.core.module.KeySetting.Link link(final KeyBinding binding) {
		return new dev.theredstonee.trsclient.core.module.KeySetting.Link() {
			@Override
			public String get() {
				int code = binding.getKeyCode();
				return code <= 0 ? dev.theredstonee.trsclient.core.module.KeySetting.NONE
						: dev.theredstonee.trsclient.compat.Keys.name(code);
			}

			@Override
			public void set(String keyName) {
				binding.setKeyCode(dev.theredstonee.trsclient.compat.Keys.code(keyName));
				KeyBinding.resetKeyBindingArrayAndHash();
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
				if (mc != null && mc.gameSettings != null) mc.gameSettings.saveOptions();
			}
		};
	}
}
