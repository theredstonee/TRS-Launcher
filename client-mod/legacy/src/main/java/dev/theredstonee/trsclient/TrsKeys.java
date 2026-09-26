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
	/** Emote-Rad (halten, Standard G – in keiner Vanilla-Version belegt). */
	public static KeyBinding emoteWheel;
	/** Schaltet das Redstone-Signal-Overlay (F6; in 1.8.9 ist F6 "Stream starten" → dort F8). */
	public static KeyBinding redstoneOverlay;
	/** Clip der letzten Sekunden speichern (F9 – in keiner Vanilla-Version belegt). */
	public static KeyBinding saveClip;
	/** Aufnahme starten/stoppen (F10 – in keiner Vanilla-Version belegt). */
	public static KeyBinding toggleRecording;
	/** Öffnet die Garderobe (standardmäßig unbelegt). */
	public static KeyBinding wardrobe;
	/** Öffnet die Weltkarte (M – in keiner Vanilla-Version belegt; bei Doppelbelegung einmalig freigegeben). */
	public static KeyBinding worldMap;
	/** Öffnet den Sozial-Bildschirm (standardmäßig unbelegt). */
	public static KeyBinding social;
	/** Schnellantwort/-aktion zum neuesten Sozial-Toast (Y; wirkt nur, solange ein Toast sichtbar ist). */
	public static KeyBinding quickReply;

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
		emoteWheel = register(new KeyBinding("key.trsclient.emoteWheel", Keyboard.KEY_G, CATEGORY));
		//? if >=1.9 {
		/*redstoneOverlay = register(new KeyBinding("key.trsclient.redstoneOverlay", Keyboard.KEY_F6, CATEGORY));
		*///?} else
		redstoneOverlay = register(new KeyBinding("key.trsclient.redstoneOverlay", Keyboard.KEY_F8, CATEGORY));
		saveClip = register(new KeyBinding("key.trsclient.saveClip", Keyboard.KEY_F9, CATEGORY));
		toggleRecording = register(new KeyBinding("key.trsclient.toggleRecording", Keyboard.KEY_F10, CATEGORY));
		wardrobe = register(new KeyBinding("key.trsclient.wardrobe", Keyboard.KEY_NONE, CATEGORY));
		worldMap = register(new KeyBinding("key.trsclient.worldMap", Keyboard.KEY_M, CATEGORY));
		social = register(new KeyBinding("key.trsclient.social", Keyboard.KEY_NONE, CATEGORY));
		quickReply = register(new KeyBinding("key.trsclient.quickReply", Keyboard.KEY_Y, CATEGORY));
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
	 * ebenfalls M, wird die TRS-Taste freigegeben (im TRS-Menü/in den Steuerungen neu belegbar).
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
	 * Verbindung einer Modul-Tasteneinstellung (TRS-Menü) mit einer Vanilla-Tastenbelegung: gelesen
	 * und geschrieben wird die Belegung selbst (gespeichert in options.txt). LWJGL 2: Tasten > 0,
	 * Maustasten = Nummer - 100.
	 */
	public static dev.theredstonee.trsclient.core.module.KeySetting.Link link(final KeyBinding binding) {
		return new dev.theredstonee.trsclient.core.module.KeySetting.Link() {
			@Override
			public String get() {
				return nameOf(binding.getKeyCode());
			}

			@Override
			public void set(String keyName) {
				binding.setKeyCode(codeOf(keyName));
				KeyBinding.resetKeyBindingArrayAndHash();
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
				if (mc != null && mc.gameSettings != null) mc.gameSettings.saveOptions();
			}
		};
	}

	/** LWJGL-2-Code (Maustasten negativ) → Vanilla-Tastenname. */
	public static String nameOf(int code) {
		if (code == Keyboard.KEY_NONE) return dev.theredstonee.trsclient.core.module.KeySetting.NONE;
		if (code < 0) {
			int button = code + 100;
			if (button == 0) return "key.mouse.left";
			if (button == 1) return "key.mouse.right";
			if (button == 2) return "key.mouse.middle";
			return "key.mouse." + (button + 1);
		}
		return dev.theredstonee.trsclient.compat.Keys.name(code);
	}

	/** Vanilla-Tastenname → LWJGL-2-Code (Maustasten negativ). */
	public static int codeOf(String keyName) {
		if (keyName != null && keyName.startsWith("key.mouse.")) {
			String b = keyName.substring("key.mouse.".length());
			int button;
			if ("left".equals(b)) button = 0;
			else if ("right".equals(b)) button = 1;
			else if ("middle".equals(b)) button = 2;
			else {
				try {
					button = Integer.parseInt(b) - 1;
				} catch (NumberFormatException e) {
					button = -1;
				}
			}
			return button >= 0 ? button - 100 : Keyboard.KEY_NONE;
		}
		return dev.theredstonee.trsclient.compat.Keys.code(keyName);
	}
}
