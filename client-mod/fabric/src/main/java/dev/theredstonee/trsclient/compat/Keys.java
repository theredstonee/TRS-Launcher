package dev.theredstonee.trsclient.compat;

import com.mojang.blaze3d.platform.InputConstants;
import dev.theredstonee.trsclient.core.ui.UiKey;

/**
 * Tasten-/Maus-Konstanten und Umrechnungen. Ab 1.17 kommen die Konstanten aus InputConstants
 * (ab 26.3 gibt es kein GLFW mehr im Klassenpfad), davor fehlen sie dort – dann die festen
 * GLFW-Werte. Tastennamen ("key.keyboard.v") sind in allen Versionen gleich und werden deshalb
 * in der Config gespeichert; die Zahlenwerte dahinter sind es nicht.
 */
public final class Keys {
	//? if >=1.17 {
	public static final int KEY_RSHIFT = InputConstants.KEY_RSHIFT;
	public static final int KEY_C = InputConstants.KEY_C;
	public static final int KEY_LALT = InputConstants.KEY_LALT;
	public static final int KEY_B = InputConstants.KEY_B;
	public static final int KEY_N = InputConstants.KEY_N;
	public static final int PRESS = InputConstants.PRESS;
	public static final int MOUSE_LEFT = InputConstants.MOUSE_BUTTON_LEFT;
	public static final int MOUSE_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;
	//?} else {
	/*public static final int KEY_RSHIFT = 344;
	public static final int KEY_C = 67;
	public static final int KEY_LALT = 342;
	public static final int KEY_B = 66;
	public static final int KEY_N = 78;
	public static final int PRESS = 1;
	public static final int MOUSE_LEFT = 0;
	public static final int MOUSE_RIGHT = 1;
	*///?}

	// Tastatur-Eingabetyp: KEYSYM bis 26.2, KEYBOARD ab 26.3.
	//? if >=26.3 {
	/*public static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYBOARD;
	*///?} else
	public static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYSYM;

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

	/** Name einer Taste dieser Version ("key.keyboard.v"). */
	public static String name(int code) {
		try {
			return KEYBOARD.getOrCreate(code).getName();
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
			/*return Mc.translated(keyName);*/
		} catch (RuntimeException e) {
			return "?";
		}
	}

	/** Ist die Taste gerade gedrückt? */
	public static boolean isDown(String keyName) {
		int code = code(keyName);
		if (code == UNBOUND) return false;
		//? if >=26.3 {
		/*return InputConstants.isKeyDown(code);
		*///?} elif >=1.21.9 {
		/*return InputConstants.isKeyDown(Mc.window(), code);
		*///?} else
		return InputConstants.isKeyDown(Mc.window().getWindow(), code);
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
