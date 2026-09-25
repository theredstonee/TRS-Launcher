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
	/** Schaltet das Redstone-Signal-Overlay (F8 – F6/F7 sind in 1.7.10 die Stream-Tasten). */
	public static KeyBinding redstoneOverlay;
	/** Clip der letzten Sekunden speichern (F9 – in keiner Vanilla-Version belegt). */
	public static KeyBinding saveClip;
	/** Aufnahme starten/stoppen (F10 – in keiner Vanilla-Version belegt). */
	public static KeyBinding toggleRecording;
	/** Öffnet die Garderobe (standardmäßig unbelegt). */
	public static KeyBinding wardrobe;
	/** Öffnet die Weltkarte (M – in keiner Vanilla-Version belegt; bei Doppelbelegung einmalig freigegeben). */
	public static KeyBinding worldMap;

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
		redstoneOverlay = register(new KeyBinding("key.trsclient.redstoneOverlay", Keyboard.KEY_F8, CATEGORY));
		saveClip = register(new KeyBinding("key.trsclient.saveClip", Keyboard.KEY_F9, CATEGORY));
		toggleRecording = register(new KeyBinding("key.trsclient.toggleRecording", Keyboard.KEY_F10, CATEGORY));
		wardrobe = register(new KeyBinding("key.trsclient.wardrobe", Keyboard.KEY_NONE, CATEGORY));
		worldMap = register(new KeyBinding("key.trsclient.worldMap", Keyboard.KEY_M, CATEGORY));
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

	/**
	 * Einmalig: liegt die Weltkarten-Taste noch auf M und nutzt eine andere Belegung (z. B. eine andere Karten-Mod)
	 * ebenfalls M, wird die TRS-Taste freigegeben.
	 * @return true, wenn freigegeben wurde
	 */
	public static boolean resolveWorldMapConflict() {
		if (worldMap == null) return false;
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
		if (mc == null || mc.gameSettings == null) return false;
		java.util.List<String> others = new java.util.ArrayList<String>();
		for (KeyBinding k : mc.gameSettings.keyBindings) {
			if (k != worldMap) others.add(Integer.toString(k.getKeyCode()));
		}
		if (!dev.theredstonee.trsclient.core.config.KeyDefaults.conflicts(Integer.toString(worldMap.getKeyCode()),
				Integer.toString(Keyboard.KEY_M), others)) return false;
		worldMap.setKeyCode(Keyboard.KEY_NONE);
		KeyBinding.resetKeyBindingArrayAndHash();
		mc.gameSettings.saveOptions();
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
