package dev.theredstonee.trsclient.core.ui.halloween;

/**
 * Farben von Halloween 2026 „Nacht der langen Schatten“ (Vertrag „Look“): Licht kommt vom Mond – Silber für Kanten,
 * Glanz und Fortschritt; Orange nur als Kürbislicht, nie als Fläche.
 */
public final class HalloweenPalette {
	/** Nacht (Hintergrund). */
	public static final int NIGHT = 0xFF07060C;
	/** Nebelgrund (Flächen). */
	public static final int FOG_GROUND = 0xFF13101F;
	/** Pflaume (Knöpfe, aktive Flächen). */
	public static final int PLUM = 0xFF3A1F5C;
	/** Violett und helles Violett (Akzente, Untertitel). */
	public static final int VIOLET = 0xFF8E6CC9;
	public static final int VIOLET_LIGHT = 0xFFB9A2EA;
	/** Mondsilber und helles Silber (Kanten, Schrift, Fortschritt). */
	public static final int SILVER = 0xFFCFD5E3;
	public static final int SILVER_LIGHT = 0xFFF3F5FB;
	/** Kürbislicht – nur als Licht. */
	public static final int PUMPKIN_LIGHT = 0xFFE08A3C;
	/** Blut (Tropfen, Augen). */
	public static final int BLOOD = 0xFF7A0F18;
	public static final int BLOOD_LIGHT = 0xFFC9424A;
	/** Seelenblau (Irrlichter). */
	public static final int SOUL = 0xFF9FD6E6;

	/** Knopf (Vanilla-Knöpfe, Titel-Knöpfe): dunkles Pflaumen-Glas, Kante Silber-Violett (wie {@code .mcb}). */
	public static final int BUTTON = 0xB8100C1C;
	public static final int BUTTON_OPAQUE = 0xFF120E20;
	public static final int BUTTON_HOVER = 0xC03A1F5C;
	public static final int BUTTON_EDGE = 0xFF5B5280;
	public static final int BUTTON_OFF = 0xC00B0912;
	/** Gedämpfte Schrift (Fußzeile, Hinweise). */
	public static final int MUTED = 0xFF9A94B4;
	public static final int MUTED_LIGHT = 0xFFB6AFCF;
	/** Linien/Ränder der Karten (wie {@code #3b3358}). */
	public static final int LINE = 0xFF3B3358;
	/** Untertitel / Splash. */
	public static final int SPLASH = 0xFFD9D0FF;

	private HalloweenPalette() {
	}
}
