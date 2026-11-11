package dev.theredstonee.trsclient.core.input;

import dev.theredstonee.trsclient.core.module.KeySetting;

import java.util.HashMap;
import java.util.Map;

/**
 * Erkennt für Modul-Tastenbelegungen ({@link KeySetting}) den Moment des Drückens.
 * Minecraft liefert nur "ist gedrückt"; hier wird daraus ein einmaliges Ereignis je Druck.
 */
public final class KeyPresses {
	/** Fragt die Minecraft-Version, ob eine Taste gerade gedrückt ist. */
	public interface Down {
		boolean isDown(String keyName);
	}

	/** Tastenstatus der laufenden Minecraft-Version, für das Belegen von Kombis im Menü. */
	private static volatile Down shared;

	private final Down down;
	private final Map<String, Boolean> held = new HashMap<String, Boolean>();

	public KeyPresses(Down down) {
		this.down = down;
		if (down != null) shared = down;
	}

	/** null, solange noch keine Version ihren Tastenstatus gemeldet hat. */
	public static Down shared() {
		return shared;
	}

	/** True genau einmal, wenn die Taste gerade gedrückt wurde (nicht, solange sie gehalten wird). */
	public boolean pressed(KeySetting key) {
		if (key == null || !key.isBound()) return false;
		String name = key.get();
		boolean isDown = down.isDown(KeyCombo.main(name));
		Boolean was = held.get(name);
		held.put(name, isDown ? Boolean.TRUE : Boolean.FALSE);
		return isDown && (was == null || !was.booleanValue()) && KeyCombo.modifiersMatch(name, down);
	}

	/** Alle Tasten als losgelassen merken (z. B. wenn ein Bildschirm offen ist). */
	public void releaseAll() {
		held.clear();
	}
}
