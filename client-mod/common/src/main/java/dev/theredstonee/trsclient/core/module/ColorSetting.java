package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.ModuleConfig;
import dev.theredstonee.trsclient.core.ui.ColorMath;

import java.util.Locale;

/**
 * Farbe mit optionaler Deckkraft (Alpha) und Chroma (Regenbogen, läuft mit der Zeit durch).
 * Gespeichert als "#RRGGBB" (deckend) bzw. "#AARRGGBB"; Chroma als Flag {@code <key>.chroma}.
 * Im Menü öffnet ein Klick den Farbwähler (Farbton/Sättigung/Helligkeit, Alpha, Chroma).
 */
public final class ColorSetting extends Setting {
	/** Markenpalette: Weiß, Redstone-Rot, Lampen-Bernstein, Hellgrau, Smaragd, Diamant, Amethyst. */
	public static final int[] PALETTE = {0xFFFFFF, 0xE0281E, 0xFFB84D, 0xB8B8C8, 0x3DDC84, 0x4DD8E0, 0xB07CFF};

	private final int defaultArgb;
	private final boolean defaultChroma;
	private final boolean alphaEditable;
	private int argb;
	private boolean chroma;

	/** Deckende Farbe (Alpha nicht einstellbar), z. B. {@code 0xFFFFFF}. */
	public ColorSetting(String key, String label, int defaultRgb) {
		this(key, label, 0xFF000000 | defaultRgb, false);
	}

	/**
	 * Farbe mit Alpha, z. B. {@code 0x90FFFFFF}.
	 * @param alphaEditable Deckkraft im Farbwähler einstellbar
	 */
	public ColorSetting(String key, String label, int defaultArgb, boolean alphaEditable) {
		super(key, label);
		this.alphaEditable = alphaEditable;
		this.defaultArgb = alphaEditable ? defaultArgb : (0xFF000000 | defaultArgb);
		this.defaultChroma = false;
		this.argb = this.defaultArgb;
	}

	/** Gespeicherte Farbe als 0xRRGGBB (ohne Chroma). */
	public int rgb() {
		return argb & 0xFFFFFF;
	}

	/** Deckkraft 0..255. */
	public int alpha() {
		return argb >>> 24;
	}

	/** Gespeicherte Farbe als ARGB (ohne Chroma). */
	public int storedArgb() {
		return argb;
	}

	/** Aktuelle Anzeigefarbe als ARGB – bei Chroma die Regenbogenfarbe dieses Moments. */
	public int argb() {
		if (!chroma) return argb;
		return (argb & 0xFF000000) | ColorMath.chroma(System.currentTimeMillis(), 0);
	}

	/** Wie {@link #argb()}, mit Versatz (z. B. je Zeichen/Zeile für eine Regenbogen-Welle). */
	public int argb(long offsetMillis) {
		if (!chroma) return argb;
		return (argb & 0xFF000000) | ColorMath.chroma(System.currentTimeMillis(), offsetMillis);
	}

	/** Setzt die Farbe (0xRRGGBB), die Deckkraft bleibt. */
	public void set(int rgb) {
		this.argb = (argb & 0xFF000000) | (rgb & 0xFFFFFF);
	}

	/** Setzt Farbe inkl. Deckkraft (bei nicht einstellbarem Alpha immer deckend). */
	public void setArgb(int argb) {
		this.argb = alphaEditable ? argb : (0xFF000000 | argb);
	}

	public boolean alphaEditable() {
		return alphaEditable;
	}

	public boolean chroma() {
		return chroma;
	}

	public void setChroma(boolean chroma) {
		this.chroma = chroma;
	}

	/** Wechselt zur nächsten Palettenfarbe (Deckkraft bleibt, Chroma aus). */
	public void cycle() {
		int idx = -1;
		for (int i = 0; i < PALETTE.length; i++) {
			if (PALETTE[i] == rgb()) idx = i;
		}
		set(PALETTE[(idx + 1) % PALETTE.length]);
		chroma = false;
	}

	public static String toHex(int rgb) {
		return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
	}

	/** "#RRGGBB" bei voller Deckkraft, sonst "#AARRGGBB". */
	public static String toHexArgb(int argb) {
		if ((argb >>> 24) == 0xFF) return toHex(argb);
		return String.format(Locale.ROOT, "#%08X", argb);
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

	/** Parst "#RRGGBB" (deckend) oder "#AARRGGBB"; bei ungültigem Wert → {@code fallback}. */
	public static int parseHexArgb(String s, int fallback) {
		if (s == null) return fallback;
		String t = s.trim();
		if (t.startsWith("#")) t = t.substring(1);
		if (t.length() != 6 && t.length() != 8) return fallback;
		for (int i = 0; i < t.length(); i++) {
			if (Character.digit(t.charAt(i), 16) < 0) return fallback;
		}
		long v = Long.parseLong(t, 16);
		return t.length() == 6 ? (int) (0xFF000000L | v) : (int) v;
	}

	@Override
	public void read(ModuleConfig config) {
		setArgb(parseHexArgb(config.colors.get(key()), defaultArgb));
		Boolean c = config.flags.get(key() + ".chroma");
		chroma = c != null ? c : defaultChroma;
	}

	@Override
	public void write(ModuleConfig config) {
		config.colors.put(key(), toHexArgb(argb));
		if (chroma) config.flags.put(key() + ".chroma", true);
	}

	@Override
	public void reset() {
		argb = defaultArgb;
		chroma = defaultChroma;
	}
}
