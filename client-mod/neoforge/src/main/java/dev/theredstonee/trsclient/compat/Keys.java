package dev.theredstonee.trsclient.compat;

import com.mojang.blaze3d.platform.InputConstants;
import dev.theredstonee.trsclient.core.ui.UiKey;

/**
 * Tastenhilfen für das TRS-Menü. Tastennamen ("key.keyboard.v") sind in allen Versionen gleich
 * und stehen deshalb in der Config; die Zahlenwerte dahinter nicht (GLFW bis 26.2, danach anders).
 */
public final class Keys {
	// Tastatur-Eingabetyp: KEYSYM bis 26.2, KEYBOARD ab 26.3.
	//? if >=26.3 {
	/*public static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYBOARD;
	*///?} else
	public static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYSYM;

	/** Keine Taste. */
	public static final int UNBOUND = InputConstants.UNKNOWN.getValue();

	private Keys() {
	}

	/** Linke/rechte Maustaste dieser Version (ab 26.3 SDL-Zählung: 1 und 3). */
	public static final int MOUSE_LEFT = InputConstants.MOUSE_BUTTON_LEFT;
	public static final int MOUSE_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;

	/**
	 * Maustaste in der Zählung der TRS-Oberfläche: 0 links, 1 rechts, 2 Mitte. Bis 26.2 ist das
	 * GLFWs Zählung; ab 26.3 (SDL) zählt Minecraft ab 1 und die rechte Taste ist 3 – ohne diese
	 * Umrechnung wäre jeder Linksklick in einem TRS-Bildschirm ein Rechtsklick.
	 */
	public static int uiButton(int button) {
		if (button == MOUSE_LEFT) return 0;
		if (button == MOUSE_RIGHT) return 1;
		return button;
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

	/** Anzeigename einer Taste ("V", "Leertaste"). */
	public static String display(String keyName) {
		try {
			return InputConstants.getKey(keyName).getDisplayName().getString();
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
