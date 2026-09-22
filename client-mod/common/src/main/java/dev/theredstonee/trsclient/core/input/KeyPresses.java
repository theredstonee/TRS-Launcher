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

	private final Down down;
	private final Map<String, Boolean> held = new HashMap<String, Boolean>();

	public KeyPresses(Down down) {
		this.down = down;
	}

	/** True genau einmal, wenn die Taste gerade gedrückt wurde (nicht, solange sie gehalten wird). */
	public boolean pressed(KeySetting key) {
		if (key == null || !key.isBound()) return false;
		String name = key.get();
		boolean isDown = down.isDown(name);
		Boolean was = held.get(name);
		held.put(name, isDown ? Boolean.TRUE : Boolean.FALSE);
		return isDown && (was == null || !was.booleanValue());
	}

	/** Alle Tasten als losgelassen merken (z. B. wenn ein Bildschirm offen ist). */
	public void releaseAll() {
		held.clear();
	}
}
