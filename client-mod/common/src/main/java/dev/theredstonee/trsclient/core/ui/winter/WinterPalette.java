package dev.theredstonee.trsclient.core.ui.winter;

import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.hex;
import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.mix;

/**
 * Farben der Winterlandschaft (Port von {@code app/utils/winter/palette.ts}). Die Landschaft hat feste, abgestimmte
 * Töne je Farbschema (Nacht, OLED-Nacht, heller Wintertag) – nur Polarlicht-Schein und Eislaternen folgen der
 * Akzentfarbe, das Hüttenfenster bleibt warm. Alle Farben deckend ARGB.
 */
public final class WinterPalette {
	public static final int DARK = 0;
	public static final int OLED = 1;
	public static final int LIGHT = 2;

	/** Echte Polarlichter: helle Unterkante, Sauerstoff-Grün, dunkleres Grün, violette Oberkante. */
	static final int[] AURORA = {hex("#c8ffd9"), hex("#3dff86"), hex("#14c25a"), hex("#b45cff")};
	private static final int WHITE = 0xFFFFFFFF;

	public final int variant;
	/** Himmel von oben nach unten (Bänder, dazwischen gerastert). */
	public final int[] sky;
	public final int haze;
	public final double hazeScale;
	public final int star;
	public final int starDim;
	public final int moon;
	public final int moonShade;
	public final int moonCrater;
	public final int halo;
	public final int cloud;
	public final int cloudShade;
	public final int rock;
	public final int rockShade;
	public final int snowLit;
	public final int snow;
	public final int snowShade;
	public final int snowDeep;
	public final int sparkle;
	public final int pineLit;
	public final int pine;
	public final int pineDark;
	public final int trunk;
	public final int wood;
	public final int woodDark;
	public final int windowOn;
	public final int windowFrame;
	public final int smoke;
	public final int flake;
	public final int flakeFar;
	public final int lanternOff;
	public final int lanternFrame;
	public final int lanternOn;
	public final int lanternCore;
	public final int[] aurora = AURORA;
	public final int glowAccent;
	public final int glowWarm;
	public final int glowMoon;

	/**
	 * @param variant {@link #DARK}, {@link #OLED} oder {@link #LIGHT}
	 * @param a300 helle Akzentstufe, {@code a400} mittlere, {@code a500} kräftige (Launcher-Variablen redstone-300…500)
	 */
	public WinterPalette(int variant, int a300, int a400, int a500) {
		this.variant = variant;
		boolean light = variant == LIGHT;
		boolean oled = variant == OLED;
		if (light) {
			sky = new int[]{hex("#9fc9ea"), hex("#b6d7f0"), hex("#cbe3f5"), hex("#ddedf9"), hex("#ecf5fc")};
			haze = hex("#dcecf8");
			star = WHITE;
			starDim = WHITE;
			moon = hex("#fffbea");
			moonShade = hex("#fff1c4");
			moonCrater = hex("#ffe9b0");
			halo = WHITE;
			cloud = WHITE;
			cloudShade = hex("#d9e7f4");
			rock = hex("#7d93b3");
			rockShade = hex("#5f7698");
			snowLit = WHITE;
			snow = hex("#eef5fc");
			snowShade = hex("#cfdff0");
			snowDeep = hex("#b3c8e2");
			sparkle = WHITE;
			pineLit = hex("#3f7c6c");
			pine = hex("#2c5f55");
			pineDark = hex("#1d443f");
			trunk = hex("#5a4038");
			wood = hex("#8a5d42");
			woodDark = hex("#5e3d2c");
			windowOn = hex("#ffcf7a");
			windowFrame = hex("#4a3226");
			smoke = hex("#c9d6e4");
			flake = WHITE;
			flakeFar = hex("#f4f9ff");
		} else {
			sky = oled
					? new int[]{hex("#000000"), hex("#01030a"), hex("#030812"), hex("#06101f"), hex("#0b182c")}
					: new int[]{hex("#060b18"), hex("#0a1224"), hex("#0f1a32"), hex("#152442"), hex("#1d3154")};
			haze = hex(oled ? "#0d1a2e" : "#24385c");
			star = hex("#f2f8ff");
			starDim = hex(oled ? "#5d6f8f" : "#7f93b8");
			moon = hex("#eef4ff");
			moonShade = hex("#c3d0e6");
			moonCrater = hex("#a9b8d3");
			halo = hex("#9fc2ff");
			cloud = hex("#3a4d72");
			cloudShade = hex("#2a3a5a");
			rock = hex(oled ? "#18233a" : "#25365a");
			rockShade = hex(oled ? "#0f1729" : "#1a2744");
			snowLit = hex(oled ? "#c9dcf5" : "#e3efff");
			snow = hex(oled ? "#8ea7cc" : "#aac2e4");
			snowShade = hex(oled ? "#54698f" : "#7690bd");
			snowDeep = hex(oled ? "#2f3f5f" : "#4d6491");
			sparkle = WHITE;
			pineLit = hex(oled ? "#1b3a41" : "#24505a");
			pine = hex(oled ? "#10262d" : "#173640");
			pineDark = hex(oled ? "#08151b" : "#0e222b");
			trunk = hex(oled ? "#1d1517" : "#2c2025");
			wood = hex(oled ? "#3b2a26" : "#553a30");
			woodDark = hex(oled ? "#241917" : "#36241f");
			windowOn = hex("#ffc66b");
			windowFrame = hex(oled ? "#1a1210" : "#2a1c17");
			smoke = hex(oled ? "#2a3550" : "#465a80");
			flake = hex("#f4f9ff");
			flakeFar = hex(oled ? "#6d82a6" : "#9fb4d6");
		}
		hazeScale = light ? 0.62 : 1;
		// Feuerschein bleibt warm.
		int l300 = hex("#ffd48a");
		int l400 = hex("#ffb84d");
		int accentHi = light ? mix(a400, WHITE, 0.45) : a300;
		lanternOff = mix(a500, light ? snowDeep : pineDark, light ? 0.7 : 0.78);
		lanternFrame = light ? hex("#3c4a60") : hex("#151d2c");
		lanternOn = mix(a400, WHITE, 0.2);
		lanternCore = mix(accentHi, WHITE, 0.55);
		glowAccent = a400;
		glowWarm = light ? l400 : mix(l400, l300, 0.3);
		glowMoon = halo;
	}

	/** Variante aus dem Namen des Farbschemas ("dark", "oled", "light"). */
	public static int variantOf(String themeName) {
		if ("light".equals(themeName)) return LIGHT;
		if ("oled".equals(themeName)) return OLED;
		return DARK;
	}

	/**
	 * Palette zu Farbschema und Akzent. Für Eisblau (Standard im Winter) die genauen Launcher-Stufen, sonst aus dem
	 * Akzent aufgehellt.
	 */
	public static WinterPalette of(String themeName, String accentName, int accent) {
		int a300;
		int a400;
		int a500;
		if ("ice".equals(accentName)) {
			a300 = hex("#b8e7ff");
			a400 = hex("#7fd3ff");
			a500 = hex("#2b97d6");
		} else {
			a500 = 0xFF000000 | accent;
			a400 = mix(a500, WHITE, 0.38);
			a300 = mix(a500, WHITE, 0.62);
		}
		return new WinterPalette(variantOf(themeName), a300, a400, a500);
	}
}
