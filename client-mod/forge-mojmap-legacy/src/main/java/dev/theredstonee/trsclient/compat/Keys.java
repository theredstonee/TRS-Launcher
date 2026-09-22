package dev.theredstonee.trsclient.compat;

import com.mojang.blaze3d.platform.InputConstants;
import dev.theredstonee.trsclient.core.ui.UiKey;

/**
 * Tastenhilfen für das TRS-Menü. Tastennamen ("key.keyboard.v") sind in allen Versionen gleich
 * und stehen deshalb in der Config; die GLFW-Zahlenwerte dahinter nicht.
 */
public final class Keys {
	/** Keine Taste. */
	public static final int UNBOUND = InputConstants.UNKNOWN.getValue();

	private Keys() {
	}

	/** Tastencode zum Namen ("key.keyboard.v"); unbekannt → {@link #UNBOUND}. */
	public static int code(String keyName) {
		try {
			return InputConstants.getKey(keyName).getValue();
		} catch (RuntimeException e) {
			return UNBOUND;
		}
	}

	/** Name einer Taste ("key.keyboard.v"). */
	public static String name(int code) {
		try {
			return InputConstants.Type.KEYSYM.getOrCreate(code).getName();
		} catch (RuntimeException e) {
			return "key.keyboard.unknown";
		}
	}

	/**
	 * Anzeigename einer Taste ("V", "Leertaste"). Bis 1.15 kennt InputConstants.Key keinen
	 * Anzeigenamen – dort ist der Tastenname selbst der Sprachschlüssel.
	 */
	public static String display(String keyName) {
		try {
			//? if >=1.16 {
			return InputConstants.getKey(keyName).getDisplayName().getString();
			//?} else
			/*return net.minecraft.client.resources.language.I18n.get(keyName);*/
		} catch (RuntimeException e) {
			return "?";
		}
	}

	/** Ist die Taste gerade gedrückt? */
	public static boolean isDown(String keyName) {
		int code = code(keyName);
		return code != UNBOUND && InputConstants.isKeyDown(Mc.window().getWindow(), code);
	}

	/** Logische Taste für die Oberfläche (Escape, Enter, Pfeile …). */
	public static UiKey ui(int code) {
		if (code == code("key.keyboard.escape")) return UiKey.ESCAPE;
		if (code == code("key.keyboard.enter") || code == code("key.keyboard.keypad.enter")) return UiKey.ENTER;
		if (code == code("key.keyboard.backspace")) return UiKey.BACKSPACE;
		if (code == code("key.keyboard.delete")) return UiKey.DELETE;
		if (code == code("key.keyboard.tab")) return UiKey.TAB;
		if (code == code("key.keyboard.left")) return UiKey.LEFT;
		if (code == code("key.keyboard.right")) return UiKey.RIGHT;
		if (code == code("key.keyboard.up")) return UiKey.UP;
		if (code == code("key.keyboard.down")) return UiKey.DOWN;
		if (code == code("key.keyboard.home")) return UiKey.HOME;
		if (code == code("key.keyboard.end")) return UiKey.END;
		return UiKey.NONE;
	}
}
