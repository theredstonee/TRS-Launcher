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
