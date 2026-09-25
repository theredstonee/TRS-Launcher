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
	/** Schaltet das Redstone-Signal-Overlay (F6 – in keiner Vanilla-Version ab 1.9 belegt). */
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
		menu = register(new KeyBinding("key.trsclient.menu", GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY));
		// Zoom liegt auf V: Vanilla belegt C seit 1.12 mit "Schnellleiste speichern".
		zoom = register(new KeyBinding("key.trsclient.zoom", GLFW.GLFW_KEY_V, CATEGORY));
		// Standardmäßig unbelegt – Fullbright lässt sich auch im Menü schalten.
		fullbright = register(new KeyBinding("key.trsclient.fullbright", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
		// Standardmäßig unbelegt – Profile lassen sich auch im Menü wechseln.
		hudProfile = register(new KeyBinding("key.trsclient.hudProfile", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
		redstoneOverlay = register(new KeyBinding("key.trsclient.redstoneOverlay", GLFW.GLFW_KEY_F6, CATEGORY));
		saveClip = register(new KeyBinding("key.trsclient.saveClip", GLFW.GLFW_KEY_F9, CATEGORY));
		toggleRecording = register(new KeyBinding("key.trsclient.toggleRecording", GLFW.GLFW_KEY_F10, CATEGORY));
		wardrobe = register(new KeyBinding("key.trsclient.wardrobe", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
		worldMap = register(new KeyBinding("key.trsclient.worldMap", GLFW.GLFW_KEY_M, CATEGORY));
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

	/**
	 * Einmalig: liegt die Weltkarten-Taste noch auf M und nutzt eine andere Belegung (z. B. eine andere Karten-Mod)
	 * ebenfalls M, wird die TRS-Taste freigegeben.
	 * @return true, wenn freigegeben wurde
	 */
	public static boolean resolveWorldMapConflict() {
		if (worldMap == null) return false;
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc == null || mc.gameSettings == null) return false;
		java.util.List<String> others = new java.util.ArrayList<String>();
		for (KeyBinding k : mc.gameSettings.keyBindings) {
			if (k != worldMap) others.add(k.getKey().getTranslationKey());
		}
		if (!dev.theredstonee.trsclient.core.config.KeyDefaults.conflicts(worldMap.getKey().getTranslationKey(), "key.keyboard.m", others)) return false;
		worldMap.bind(net.minecraft.client.util.InputMappings.Type.KEYSYM.getOrMakeInput(GLFW.GLFW_KEY_UNKNOWN));
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
	 * geschrieben wird die Belegung selbst (gespeichert in options.txt).
	 */
	public static dev.theredstonee.trsclient.core.module.KeySetting.Link link(final KeyBinding binding) {
		return new dev.theredstonee.trsclient.core.module.KeySetting.Link() {
			@Override
			public String get() {
				return binding.getKey().getTranslationKey();
			}

			@Override
			public void set(String keyName) {
				net.minecraft.client.util.InputMappings.Input input;
				try {
					input = net.minecraft.client.util.InputMappings.getInputByName(keyName);
				} catch (RuntimeException e) {
					input = net.minecraft.client.util.InputMappings.INPUT_INVALID;
				}
				binding.bind(input);
				KeyBinding.resetKeyBindingArrayAndHash();
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				if (mc != null && mc.gameSettings != null) mc.gameSettings.saveOptions();
			}
		};
	}
}
