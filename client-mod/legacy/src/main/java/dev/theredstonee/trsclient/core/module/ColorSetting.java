package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.ModuleConfig;

import java.util.Locale;

/** Farbwahl aus einer festen Palette (Klick = nächste Farbe). Gespeichert als "#RRGGBB". */
public final class ColorSetting extends Setting {
	/** Markenpalette: Weiß, Redstone-Rot, Lampen-Bernstein, Hellgrau, Smaragd, Diamant, Amethyst. */
	public static final int[] PALETTE = {0xFFFFFF, 0xE0281E, 0xFFB84D, 0xB8B8C8, 0x3DDC84, 0x4DD8E0, 0xB07CFF};

	private final int defaultRgb;
	private int rgb;

	public ColorSetting(String key, String label, int defaultRgb) {
		super(key, label);
		this.defaultRgb = defaultRgb & 0xFFFFFF;
		this.rgb = this.defaultRgb;
	}

	/** Farbe als 0xRRGGBB. */
	public int rgb() {
		return rgb;
	}

	/** Farbe als deckendes ARGB. */
	public int argb() {
		return 0xFF000000 | rgb;
	}

	public void set(int rgb) {
		this.rgb = rgb & 0xFFFFFF;
	}

	/** Wechselt zur nächsten Palettenfarbe. */
	public void cycle() {
		int idx = -1;
		for (int i = 0; i < PALETTE.length; i++) {
			if (PALETTE[i] == rgb) idx = i;
		}
		rgb = PALETTE[(idx + 1) % PALETTE.length];
	}

	public static String toHex(int rgb) {
		return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
	}

	/** Parst "#RRGGBB"; bei ungültigem Wert → {@code fallback}. */
	public static int parseHex(String s, int fallback) {
		if (s == null) return fallback;
		String t = s.startsWith("#") ? s.substring(1) : s;
		if (t.length() != 6) return fallback;
		try {
			return Integer.parseInt(t, 16);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	@Override
	public void read(ModuleConfig config) {
		rgb = parseHex(config.colors.get(key()), defaultRgb);
	}

	@Override
	public void write(ModuleConfig config) {
		config.colors.put(key(), toHex(rgb));
	}

	@Override
	public void reset() {
		rgb = defaultRgb;
	}
}
