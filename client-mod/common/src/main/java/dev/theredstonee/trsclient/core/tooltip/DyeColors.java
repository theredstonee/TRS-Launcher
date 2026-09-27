package dev.theredstonee.trsclient.core.tooltip;

import java.util.Locale;

/** Farben der 16 Farbstoffe (Vanilla-Werte) für die Shulker-Vorschau – per Name, damit jede Version sie liefern kann. */
public final class DyeColors {
	/** Ungefärbte Shulker-Kiste (lila-grau wie die Vanilla-Textur). */
	public static final int SHULKER = 0xFF976997;

	private static final String[] NAMES = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
			"light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
	private static final int[] RGB = {0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
			0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21};

	private DyeColors() {
	}

	/**
	 * ARGB eines Farbstoffs ({@code "red"}, {@code "light_blue"}, alt {@code "silver"} = hellgrau); unbekannt oder null
	 * = ungefärbte Shulker-Kiste.
	 */
	public static int argb(String name) {
		if (name == null) return SHULKER;
		String n = name.trim().toLowerCase(Locale.ROOT);
		if (n.equals("silver")) n = "light_gray";
		if (n.equals("lightblue")) n = "light_blue";
		for (int i = 0; i < NAMES.length; i++) {
			if (NAMES[i].equals(n)) return 0xFF000000 | RGB[i];
		}
		return SHULKER;
	}
}
