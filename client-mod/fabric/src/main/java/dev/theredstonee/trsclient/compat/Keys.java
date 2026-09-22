package dev.theredstonee.trsclient.compat;

//? if >=1.17
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Tasten-/Maus-Konstanten. Ab 1.17 aus InputConstants (ab 26.3 gibt es kein GLFW mehr im Klassenpfad),
 * davor fehlen die Konstanten dort – dann die festen GLFW-Werte.
 */
public final class Keys {
	//? if >=1.17 {
	public static final int KEY_RSHIFT = InputConstants.KEY_RSHIFT;
	public static final int KEY_C = InputConstants.KEY_C;
	public static final int KEY_LALT = InputConstants.KEY_LALT;
	public static final int PRESS = InputConstants.PRESS;
	public static final int MOUSE_LEFT = InputConstants.MOUSE_BUTTON_LEFT;
	public static final int MOUSE_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;
	//?} else {
	/*public static final int KEY_RSHIFT = 344;
	public static final int KEY_C = 67;
	public static final int KEY_LALT = 342;
	public static final int PRESS = 1;
	public static final int MOUSE_LEFT = 0;
	public static final int MOUSE_RIGHT = 1;
	*///?}

	private Keys() {
	}
}
