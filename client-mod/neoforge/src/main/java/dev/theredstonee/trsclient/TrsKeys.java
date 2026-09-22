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
	/** Wegpunkt an der eigenen Position anlegen. */
	public static KeyMapping waypointAdd;
	/** Wegpunkt-Liste öffnen. */
	public static KeyMapping waypointList;
	/** Vier frei belegbare Tasten, die je einen Text senden (Standard: unbelegt). */
	public static final KeyMapping[] textHotkeys = new KeyMapping[4];

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
		zoom = register(event, new KeyMapping("key.trsclient.zoom", KEYBOARD, InputConstants.KEY_C, CATEGORY));
		// Standardmäßig unbelegt – Fullbright lässt sich auch im Menü schalten.
		fullbright = register(event, new KeyMapping("key.trsclient.fullbright", KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
		freelook = register(event, new KeyMapping("key.trsclient.freelook", KEYBOARD, InputConstants.KEY_LALT, CATEGORY));
		waypointAdd = register(event, new KeyMapping("key.trsclient.waypointAdd", KEYBOARD, InputConstants.KEY_B, CATEGORY));
		waypointList = register(event, new KeyMapping("key.trsclient.waypointList", KEYBOARD, InputConstants.KEY_N, CATEGORY));
		// Standardmäßig unbelegt – Text-Hotkeys senden erst, wenn man sie selbst belegt.
		for (int i = 0; i < textHotkeys.length; i++) {
			textHotkeys[i] = register(event, new KeyMapping("key.trsclient.text" + (i + 1), KEYBOARD,
					InputConstants.UNKNOWN.getValue(), CATEGORY));
		}
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
