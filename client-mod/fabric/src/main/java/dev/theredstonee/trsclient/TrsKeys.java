package dev.theredstonee.trsclient;

import com.mojang.blaze3d.platform.InputConstants;
import dev.theredstonee.trsclient.compat.Keys;
import net.minecraft.client.KeyMapping;
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
*///?} else
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

/** Tastenbelegungen – erscheinen in den Steuerungs-Optionen unter "TRS Client". */
public final class TrsKeys {
	// Kategorie: bis 1.21.8 ein Übersetzungsschlüssel, ab 1.21.9 ein registrierter Datensatz
	// (Anzeigename dann über "key.category.trsclient.main").
	//? if >=1.21.9 {
	/*public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(TrsClient.id("main"));
	*///?} else
	public static final String CATEGORY = "key.categories.trsclient";

	public static KeyMapping menu;
	public static KeyMapping zoom;
	public static KeyMapping fullbright;
	public static KeyMapping freelook;
	/** Wechselt das HUD-Profil (standardmäßig unbelegt). */
	public static KeyMapping hudProfile;

	private TrsKeys() {
	}

	// Tastatur-Eingabetyp: KEYSYM bis 26.2, KEYBOARD ab 26.3.
	//? if >=26.3 {
	/*private static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYBOARD;
	*///?} else
	private static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYSYM;

	static void register() {
		menu = register(new KeyMapping("key.trsclient.menu", KEYBOARD, Keys.KEY_RSHIFT, CATEGORY));
		// Zoom liegt auf V: C ist ab Minecraft 1.12 mit "Hotbar speichern" belegt.
		zoom = register(new KeyMapping("key.trsclient.zoom", KEYBOARD, Keys.code("key.keyboard.v"), CATEGORY));
		// Standardmäßig unbelegt – Fullbright lässt sich auch im Menü schalten.
		fullbright = register(new KeyMapping("key.trsclient.fullbright", KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
		freelook = register(new KeyMapping("key.trsclient.freelook", KEYBOARD, Keys.KEY_LALT, CATEGORY));
		// Standardmäßig unbelegt – Profile lassen sich auch im Menü wechseln.
		hudProfile = register(new KeyMapping("key.trsclient.hudProfile", KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
	}

	/**
	 * Stellt den alten Zoom-Standard C auf V um – aber nur, wenn die Taste nie geändert wurde
	 * (sie liegt also noch auf C). Danach werden die Steuerungen gespeichert.
	 * @return true, wenn umgestellt wurde
	 */
	public static boolean migrateZoomKey() {
		if (zoom == null) return false;
		if (boundKey(zoom) != Keys.code("key.keyboard.c")) return false;
		zoom.setKey(InputConstants.getKey("key.keyboard.v"));
		KeyMapping.resetMapping();
		return true;
	}

	/**
	 * Verbindung einer Modul-Tasteneinstellung (TRS-Menü) mit einer Vanilla-Tastenbelegung:
	 * gelesen/geschrieben wird die Belegung selbst, gespeichert in Minecrafts options.txt.
	 */
	public static dev.theredstonee.trsclient.core.module.KeySetting.Link link(final KeyMapping mapping) {
		return new dev.theredstonee.trsclient.core.module.KeySetting.Link() {
			@Override
			public String get() {
				return mapping.saveString();
			}

			@Override
			public void set(String keyName) {
				mapping.setKey(InputConstants.getKey(keyName));
				KeyMapping.resetMapping();
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				if (mc != null && mc.options != null) mc.options.save();
			}
		};
	}

	/** Aktuell belegte Taste (Code) einer Tastenbelegung. */
	public static int boundKey(KeyMapping mapping) {
		//? if >=26.1 {
		/*return KeyMappingHelper.getBoundKeyOf(mapping).getValue();
		*///?} else
		return KeyBindingHelper.getBoundKeyOf(mapping).getValue();
	}

	private static KeyMapping register(KeyMapping mapping) {
		//? if >=26.1 {
		/*return KeyMappingHelper.registerKeyMapping(mapping);
		*///?} else
		return KeyBindingHelper.registerKeyBinding(mapping);
	}
}
