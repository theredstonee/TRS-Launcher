package dev.theredstonee.trsclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;

/** Tastenbelegungen – erscheinen in den Steuerungs-Optionen unter "TRS Client". */
public final class TrsKeys {
	// Kategorie: bis 1.21.8 ein Übersetzungsschlüssel, ab 1.21.9 ein registrierter Datensatz
	// (Anzeigename dann über "key.category.trsclient.main").
	//? if >=1.21.9 {
	/*public static KeyMapping.Category CATEGORY;
	*///?} else
	public static final String CATEGORY = "key.categories.trsclient";

	public static KeyMapping menu;
	public static KeyMapping zoom;
	public static KeyMapping fullbright;
	public static KeyMapping freelook;
	/** Wechselt das HUD-Profil (standardmäßig unbelegt). */
	public static KeyMapping hudProfile;
	/** Emote-Rad (halten, Standard G – in keiner Vanilla-Version belegt). */
	public static KeyMapping emoteWheel;
	/** Schaltet das Redstone-Signal-Overlay (F6 – in keiner Vanilla-Version ab 1.9 belegt). */
	public static KeyMapping redstoneOverlay;
	/** Clip der letzten Sekunden speichern (F9 – in keiner Vanilla-Version belegt). */
	public static KeyMapping saveClip;
	/** Aufnahme starten/stoppen (F10 – in keiner Vanilla-Version belegt). */
	public static KeyMapping toggleRecording;

	private TrsKeys() {
	}

	// Tastatur-Eingabetyp: KEYSYM bis 26.2, KEYBOARD ab 26.3.
	//? if >=26.3 {
	/*private static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYBOARD;
	*///?} else
	private static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYSYM;

	/** Legt die Tastenbelegungen an (Mod-Konstruktor); eingetragen werden sie über {@link #register}. */
	static void create() {
		//? if >=1.21.9
		/*CATEGORY = KeyMapping.Category.register(TrsClient.id("main"));*/
		menu = new KeyMapping("key.trsclient.menu", KEYBOARD, InputConstants.KEY_RSHIFT, CATEGORY);
		// Zoom liegt auf V: C ist ab Minecraft 1.12 mit "Hotbar speichern" belegt.
		zoom = new KeyMapping("key.trsclient.zoom", KEYBOARD, dev.theredstonee.trsclient.compat.Keys.code("key.keyboard.v"), CATEGORY);
		// Standardmäßig unbelegt – Fullbright lässt sich auch im Menü schalten.
		fullbright = new KeyMapping("key.trsclient.fullbright", KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY);
		freelook = new KeyMapping("key.trsclient.freelook", KEYBOARD, InputConstants.KEY_LALT, CATEGORY);
		// Standardmäßig unbelegt – Profile lassen sich auch im Menü wechseln.
		hudProfile = new KeyMapping("key.trsclient.hudProfile", KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY);
		emoteWheel = new KeyMapping("key.trsclient.emoteWheel", KEYBOARD, dev.theredstonee.trsclient.compat.Keys.code("key.keyboard.g"), CATEGORY);
		redstoneOverlay = new KeyMapping("key.trsclient.redstoneOverlay", KEYBOARD, InputConstants.KEY_F6, CATEGORY);
		saveClip = new KeyMapping("key.trsclient.saveClip", KEYBOARD, InputConstants.KEY_F9, CATEGORY);
		toggleRecording = new KeyMapping("key.trsclient.toggleRecording", KEYBOARD, InputConstants.KEY_F10, CATEGORY);
	}

	/**
	 * Stellt den alten Zoom-Standard C auf V um – aber nur, wenn die Taste noch auf C liegt,
	 * also nie geändert wurde.
	 * @return true, wenn umgestellt wurde
	 */
	public static boolean migrateZoomKey() {
		if (zoom == null) return false;
		if (boundKey(zoom) != dev.theredstonee.trsclient.compat.Keys.code("key.keyboard.c")) return false;
		zoom.setKey(InputConstants.getKey("key.keyboard.v"));
		KeyMapping.resetMapping();
		return true;
	}

	/** Forge-Event (Mod-Bus): Tasten in die Steuerungs-Optionen eintragen. */
	static void register(RegisterKeyMappingsEvent event) {
		if (menu == null) return;
		event.register(menu);
		event.register(zoom);
		event.register(fullbright);
		event.register(freelook);
		event.register(hudProfile);
		event.register(emoteWheel);
		event.register(redstoneOverlay);
		event.register(saveClip);
		event.register(toggleRecording);
	}

	/** Aktuell belegte Taste (Code) einer Tastenbelegung. */
	public static int boundKey(KeyMapping mapping) {
		return mapping.getKey().getValue();
	}

	/**
	 * Verbindung einer Modul-Tasteneinstellung (TRS-Menü) mit einer Vanilla-Tastenbelegung: gelesen und
	 * geschrieben wird die Belegung selbst (gespeichert in options.txt). Die Belegung wird erst beim
	 * Aufruf geholt, weil sie je nach Loader erst später angelegt wird.
	 */
	public static dev.theredstonee.trsclient.core.module.KeySetting.Link link(final java.util.function.Supplier<KeyMapping> mapping) {
		return new dev.theredstonee.trsclient.core.module.KeySetting.Link() {
			@Override
			public String get() {
				KeyMapping m = mapping.get();
				return m == null ? dev.theredstonee.trsclient.core.module.KeySetting.NONE : m.saveString();
			}

			@Override
			public void set(String keyName) {
				KeyMapping m = mapping.get();
				if (m == null) return;
				m.setKey(InputConstants.getKey(keyName));
				KeyMapping.resetMapping();
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				if (mc != null && mc.options != null) mc.options.save();
			}
		};
	}
}
