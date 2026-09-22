package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.ui.UiKey;
import net.minecraft.client.settings.GameSettings;
import org.lwjgl.input.Keyboard;

import java.util.Locale;

/**
 * Tasten unter LWJGL 2 (Minecraft 1.8.9–1.12.2). Gespeichert werden die versionsneutralen
 * Vanilla-Namen ("key.keyboard.v"), damit die Config zwischen allen Minecraft-Versionen passt;
 * hier werden sie in LWJGL-Tastencodes übersetzt.
 */
public final class Keys {
	/** Keine Taste. */
	public static final int UNBOUND = Keyboard.KEY_NONE;

	private Keys() {
	}

	/** Tastencode zum Namen ("key.keyboard.v"); unbekannt → {@link #UNBOUND}. */
	public static int code(String keyName) {
		if (keyName == null || !keyName.startsWith("key.keyboard.")) return UNBOUND;
		String name = keyName.substring("key.keyboard.".length());
		String lwjgl = toLwjgl(name);
		int code = Keyboard.getKeyIndex(lwjgl);
		return code == Keyboard.KEY_NONE ? UNBOUND : code;
	}

	/** Name einer Taste ("key.keyboard.v"). */
	public static String name(int code) {
		String lwjgl = Keyboard.getKeyName(code);
		if (lwjgl == null) return "key.keyboard.unknown";
		return "key.keyboard." + fromLwjgl(lwjgl);
	}

	/** Anzeigename einer Taste ("V", "Leertaste"). */
	public static String display(String keyName) {
		int code = code(keyName);
		if (code == UNBOUND) return "—";
		String name = GameSettings.getKeyDisplayString(code);
		return name == null ? "?" : name;
	}

	/** Ist die Taste gerade gedrückt? */
	public static boolean isDown(String keyName) {
		int code = code(keyName);
		return code != UNBOUND && Keyboard.isKeyDown(code);
	}

	/** Logische Taste für die Oberfläche (LWJGL-2-Codes sind feste Zahlen). */
	public static UiKey ui(int code) {
		switch (code) {
			case Keyboard.KEY_ESCAPE: return UiKey.ESCAPE;
			case Keyboard.KEY_RETURN:
			case Keyboard.KEY_NUMPADENTER: return UiKey.ENTER;
			case Keyboard.KEY_BACK: return UiKey.BACKSPACE;
			case Keyboard.KEY_DELETE: return UiKey.DELETE;
			case Keyboard.KEY_TAB: return UiKey.TAB;
			case Keyboard.KEY_LEFT: return UiKey.LEFT;
			case Keyboard.KEY_RIGHT: return UiKey.RIGHT;
			case Keyboard.KEY_UP: return UiKey.UP;
			case Keyboard.KEY_DOWN: return UiKey.DOWN;
			case Keyboard.KEY_HOME: return UiKey.HOME;
			case Keyboard.KEY_END: return UiKey.END;
			default: return UiKey.NONE;
		}
	}

	/** Vanilla-Name → LWJGL-Name ("left.shift" → "LSHIFT", "v" → "V"). */
	private static String toLwjgl(String name) {
		if (name.startsWith("keypad.")) {
			String rest = name.substring("keypad.".length());
			if ("enter".equals(rest)) return "NUMPADENTER";
			if ("add".equals(rest)) return "ADD";
			if ("subtract".equals(rest)) return "SUBTRACT";
			if ("multiply".equals(rest)) return "MULTIPLY";
			if ("divide".equals(rest)) return "DIVIDE";
			if ("decimal".equals(rest)) return "DECIMAL";
			return "NUMPAD" + rest;
		}
		if ("left.shift".equals(name)) return "LSHIFT";
		if ("right.shift".equals(name)) return "RSHIFT";
		if ("left.control".equals(name)) return "LCONTROL";
		if ("right.control".equals(name)) return "RCONTROL";
		if ("left.alt".equals(name)) return "LMENU";
		if ("right.alt".equals(name)) return "RMENU";
		if ("enter".equals(name)) return "RETURN";
		if ("backspace".equals(name)) return "BACK";
		if ("caps.lock".equals(name)) return "CAPITAL";
		if ("num.lock".equals(name)) return "NUMLOCK";
		if ("scroll.lock".equals(name)) return "SCROLL";
		if ("page.up".equals(name)) return "PRIOR";
		if ("page.down".equals(name)) return "NEXT";
		if ("apostrophe".equals(name)) return "APOSTROPHE";
		if ("left.bracket".equals(name)) return "LBRACKET";
		if ("right.bracket".equals(name)) return "RBRACKET";
		if ("world.1".equals(name) || "world.2".equals(name)) return "";
		return name.toUpperCase(Locale.ROOT);
	}

	/** LWJGL-Name → Vanilla-Name ("LSHIFT" → "left.shift"). */
	private static String fromLwjgl(String name) {
		if ("LSHIFT".equals(name)) return "left.shift";
		if ("RSHIFT".equals(name)) return "right.shift";
		if ("LCONTROL".equals(name)) return "left.control";
		if ("RCONTROL".equals(name)) return "right.control";
		if ("LMENU".equals(name)) return "left.alt";
		if ("RMENU".equals(name)) return "right.alt";
		if ("RETURN".equals(name)) return "enter";
		if ("BACK".equals(name)) return "backspace";
		if ("CAPITAL".equals(name)) return "caps.lock";
		if ("NUMLOCK".equals(name)) return "num.lock";
		if ("SCROLL".equals(name)) return "scroll.lock";
		if ("PRIOR".equals(name)) return "page.up";
		if ("NEXT".equals(name)) return "page.down";
		if ("NUMPADENTER".equals(name)) return "keypad.enter";
		if ("ADD".equals(name)) return "keypad.add";
		if ("SUBTRACT".equals(name)) return "keypad.subtract";
		if ("MULTIPLY".equals(name)) return "keypad.multiply";
		if ("DIVIDE".equals(name)) return "keypad.divide";
		if ("DECIMAL".equals(name)) return "keypad.decimal";
		if ("LBRACKET".equals(name)) return "left.bracket";
		if ("RBRACKET".equals(name)) return "right.bracket";
		if (name.startsWith("NUMPAD")) return "keypad." + name.substring("NUMPAD".length());
		return name.toLowerCase(Locale.ROOT);
	}
}
