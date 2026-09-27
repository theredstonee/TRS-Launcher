package dev.theredstonee.trsclient.core.tooltip;

/**
 * Kartenfarben von Minecraft: ein Byte der Karte = Grundfarbe × 4 + Helligkeit. Die Grundfarben sind in allen Versionen
 * gleich nummeriert (1.8.9 kennt die ersten 36, 1.12 bis 51, 1.16 bis 58, ab 1.17 alle 62); die Tabelle hier ist die
 * von Vanilla ({@code MapColor}) – so braucht die Vorschau keine versionsabhängigen Farb-APIs (die liefern je nach
 * Version ABGR oder ARGB).
 */
public final class MapPalette {
	private static final int[] BASE = {
			0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00,
			0xFFFFFF, 0xA4A8B8, 0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33,
			0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99,
			0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919, 0xFAEE4D, 0x5CDBD5,
			0x4A80FF, 0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A,
			0xBA8524, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C,
			0x4C3223, 0x4C522A, 0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86,
			0x3A8E8C, 0x562C3E, 0x14B485, 0x646464, 0xD8AF93, 0x7FA796};
	/** Helligkeitsstufen (Index = Byte &amp; 3): niedrig, normal, hoch, am niedrigsten. */
	private static final int[] BRIGHTNESS = {180, 220, 255, 135};
	private static final int[] ARGB = new int[256];

	static {
		for (int packed = 0; packed < 256; packed++) {
			int base = packed >> 2;
			if (base == 0 || base >= BASE.length) {
				ARGB[packed] = 0; // durchsichtig (Vanilla: „kein Block“)
				continue;
			}
			int m = BRIGHTNESS[packed & 3];
			int c = BASE[base];
			int r = (c >> 16 & 0xFF) * m / 255, g = (c >> 8 & 0xFF) * m / 255, b = (c & 0xFF) * m / 255;
			ARGB[packed] = 0xFF000000 | r << 16 | g << 8 | b;
		}
	}

	private MapPalette() {
	}

	/** ARGB eines Karten-Bytes (0 = durchsichtig). */
	public static int argb(byte packed) {
		return ARGB[packed & 0xFF];
	}

	/**
	 * Wandelt die Kartendaten (128×128 Bytes) in ARGB-Pixel um; durchsichtige Stellen bekommen {@code paper}
	 * (Papierfarbe der Vorschau).
	 */
	public static int[] toArgb(byte[] colors, int paper, int[] out) {
		int n = colors == null ? 0 : Math.min(colors.length, 128 * 128);
		if (out == null || out.length < 128 * 128) out = new int[128 * 128];
		for (int i = 0; i < 128 * 128; i++) {
			int c = i < n ? ARGB[colors[i] & 0xFF] : 0;
			out[i] = c == 0 ? paper : c;
		}
		return out;
	}

	/** Schneller Fingerabdruck der Kartendaten (für „hat sich die Karte geändert?“). */
	public static long hash(byte[] colors) {
		if (colors == null) return 0;
		long h = 1125899906842597L;
		for (byte b : colors) h = 31 * h + b;
		return h;
	}
}
