package dev.theredstonee.trsclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** Tastenbelegungen – erscheinen in den Steuerungs-Optionen unter "TRS Client". */
public final class TrsKeys {
	// Kategorie: bis 1.21.8 ein Übersetzungsschlüssel, ab 1.21.9 ein Datensatz, den NeoForge über
	// RegisterKeyMappingsEvent#registerCategory registriert (Anzeigename "key.category.trsclient.main").
	//? if >=1.21.9 {
	/*public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(TrsClient.id("main"));
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

	private TrsKeys() {
	}

	// Tastatur-Eingabetyp: KEYSYM bis 26.2, KEYBOARD ab 26.3.
	//? if >=26.3 {
	/*private static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYBOARD;
	*///?} else
	private static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYSYM;

	static void register(RegisterKeyMappingsEvent event) {
		//? if >=1.21.9
		//event.registerCategory(CATEGORY);
		menu = register(event, new KeyMapping("key.trsclient.menu", KEYBOARD, InputConstants.KEY_RSHIFT, CATEGORY));
		// Zoom liegt auf V: C ist ab Minecraft 1.12 mit "Hotbar speichern" belegt.
		zoom = register(event, new KeyMapping("key.trsclient.zoom", KEYBOARD, dev.theredstonee.trsclient.compat.Keys.code("key.keyboard.v"), CATEGORY));
		// Standardmäßig unbelegt – Fullbright lässt sich auch im Menü schalten.
		fullbright = register(event, new KeyMapping("key.trsclient.fullbright", KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
		freelook = register(event, new KeyMapping("key.trsclient.freelook", KEYBOARD, InputConstants.KEY_LALT, CATEGORY));
		// Standardmäßig unbelegt – Profile lassen sich auch im Menü wechseln.
		hudProfile = register(event, new KeyMapping("key.trsclient.hudProfile", KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
		emoteWheel = register(event, new KeyMapping("key.trsclient.emoteWheel", KEYBOARD, dev.theredstonee.trsclient.compat.Keys.code("key.keyboard.g"), CATEGORY));
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

	/** Aktuell belegte Taste (Code) einer Tastenbelegung (NeoForge: KeyMapping#getKey). */
	public static int boundKey(KeyMapping mapping) {
		return mapping.getKey().getValue();
	}

	private static KeyMapping register(RegisterKeyMappingsEvent event, KeyMapping mapping) {
		event.register(mapping);
		return mapping;
	}
}
