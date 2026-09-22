package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.ui.UiKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.util.InputMappings;

/**
 * Tastenhilfen für das TRS-Menü unter 1.13.2 (GLFW). Gespeichert werden die versionsneutralen
 * Vanilla-Namen ("key.keyboard.v").
 */
public final class Keys {
	/** Keine Taste. */
	public static final int UNBOUND = InputMappings.INPUT_INVALID.getKeyCode();

	private Keys() {
	}

	/** Tastencode zum Namen ("key.keyboard.v"); unbekannt → {@link #UNBOUND}. */
	public static int code(String keyName) {
		try {
			return InputMappings.getInputByName(keyName).getKeyCode();
		} catch (RuntimeException e) {
			return UNBOUND;
		}
	}

	/** Name einer Taste ("key.keyboard.v"). */
	public static String name(int code) {
		try {
			return InputMappings.Type.KEYSYM.getOrMakeInput(code).getTranslationKey();
		} catch (RuntimeException e) {
			return "key.keyboard.unknown";
		}
	}

	/** Anzeigename einer Taste ("V", "Leertaste"). */
	public static String display(String keyName) {
		int code = code(keyName);
		if (code == UNBOUND) return "—";
		return net.minecraft.client.resources.I18n.format(keyName);
	}

	/** Ist die Taste gerade gedrückt? */
	public static boolean isDown(String keyName) {
		int code = code(keyName);
		return code != UNBOUND && InputMappings.isKeyDown(code);
	}

	/** Logische Taste für die Oberfläche. */
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
