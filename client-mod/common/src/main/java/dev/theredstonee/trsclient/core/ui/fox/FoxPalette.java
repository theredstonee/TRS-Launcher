package dev.theredstonee.trsclient.core.ui.fox;

import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.hex;
import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.mix;

/**
 * Farben der Fuchsbau-Landschaft (Port von {@code app/utils/fox/palette.ts}). Feste, abgestimmte Töne je Farbschema:
 * warme Abenddämmerung (Dunkel), späte Dämmerung (OLED) und goldene Stunde (Hell). Die Welt bekommt ein
 * Umgebungslicht (Dämmerung tönt alles rosa-violett, die goldene Stunde warm) – nur die Laternen folgen dem Akzent,
 * Feuer und Sonne bleiben warm. Alle Farben deckend ARGB.
 */
public final class FoxPalette {
	public static final int DARK = 0;
	public static final int OLED = 1;
	public static final int LIGHT = 2;

	private static final int WHITE = 0xFFFFFFFF;

	public final int variant;
	/** Himmel von oben nach unten (Bänder, dazwischen gerastert). */
	public final int[] sky;
	/** Dunst am Horizont – ferne Ebenen werden darin eingefärbt. */
	public final int haze;
	public final double hazeScale;
	public final int star;
	public final int starDim;
	public final int sun;
	public final int sunCore;
	public final int halo;
	public final int cloudLit;
	public final int cloud;
	public final int cloudUnder;
	public final int hillBack;
	public final int hillBackRim;
	public final int hill;
	public final int hillRim;
	public final int forestFar;
	public final int forestMid;
	/** Wiese: im Licht → tief im Schatten. */
	public final int[] meadow;
	public final int[] grass;
	public final int[] dirt;
	public final int deep;
	public final int[] spruce;
	public final int[] spruceLog;
	public final int[] cherry;
	public final int[] cherryLog;
	public final int[] berryLeaf;
	public final int berry;
	public final int berryHi;
	/** Saum, den die tiefe Sonne auf Oberkanten legt. */
	public final int rim;
	/** Loch im Bau (Eingang → tief). */
	public final int[] hole;
	public final int[] root;
	/** Lagerfeuer: Stämme, Glut, Flammen (innen → außen). */
	public final int[] log;
	public final int ember;
	public final int[] flame;
	public final int smoke;
	public final int spark;
	public final int fly;
	public final int flyGlow;
	/** Fallende Blätter (warm) und Kirschblüten. */
	public final int[] leaves;
	public final int[] petals;
	public final int[] flowers;
	/** Umgebungslicht (Faktor je Kanal) – tönt den Fuchs wie die Welt. */
	public final int ambient;
	public final int[] fence;
	public final int lanternFrame;
	public final int lanternOff;
	public final int lanternOn;
	public final int lanternCore;
	public final int glowAccent;
	public final int glowFire;
	public final int glowSun;
	public final int glowFly;

	/** Farbe im Umgebungslicht (je Kanal multipliziert). */
	public static int lit(int c, int amb) {
		int r = Math.min(255, (int) Math.round(((c >> 16) & 255) * ((amb >> 16) & 255) / 255.0));
		int g = Math.min(255, (int) Math.round(((c >> 8) & 255) * ((amb >> 8) & 255) / 255.0));
		int b = Math.min(255, (int) Math.round((c & 255) * (amb & 255) / 255.0));
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static int[] lits(int amb, String... hs) {
		int[] out = new int[hs.length];
		for (int i = 0; i < hs.length; i++) out[i] = lit(hex(hs[i]), amb);
		return out;
	}

	private static int[] hexes(String... hs) {
		int[] out = new int[hs.length];
		for (int i = 0; i < hs.length; i++) out[i] = hex(hs[i]);
		return out;
	}

	/**
	 * @param variant {@link #DARK}, {@link #OLED} oder {@link #LIGHT}
	 * @param a300 helle Akzentstufe, {@code a400} mittlere, {@code a500} kräftige (Launcher-Variablen redstone-300…500)
	 */
	public FoxPalette(int variant, int a300, int a400, int a500) {
		this.variant = variant;
		boolean light = variant == LIGHT;
		boolean oled = variant == OLED;
		int amb = hex(light ? "#fff4e0" : oled ? "#9a7c92" : "#e6b8c0");
		ambient = amb;
		// Minecraft-Grundfarben (Tageslicht) – mit dem Umgebungslicht getönt.
		grass = lits(amb, "#4c8030", "#5f9a3c", "#74b048");
		dirt = lits(amb, "#6e4630", "#8a5a3a", "#9a6a44");
		spruce = lits(amb, "#16291f", "#1f3a2e", "#2f5a40", "#3f7050");
		spruceLog = lits(amb, "#2c1c0e", "#3b2614", "#4e3420");
		cherry = lits(amb, "#c95a8c", "#e07aa8", "#f3a6c8", "#ffc6dc");
		cherryLog = lits(amb, "#2a1622", "#3b2230", "#5a3440");
		berryLeaf = lits(amb, "#244a2a", "#2f6a3a", "#3f8a4a");
		log = lits(amb, "#2c1c0e", "#3b2614", "#6a4a2c");
		fence = lits(amb, "#4e3420", "#6b4a2e");
		root = lits(amb, "#4e3420", "#2c1c0e");
		berry = hex("#c8102e");
		berryHi = hex("#ff5a6a");
		ember = hex("#ff8a2a");
		flame = hexes("#fff2a8", "#ffb340", "#ff6a1a");
		spark = hex("#ffd27a");
		fly = hex("#fff4a8");
		flyGlow = hex("#ffe27a");
		leaves = hexes("#e07a3a", "#f0a050", "#b4502a", "#e8b04a");
		petals = hexes("#f3a6c8", "#ffc6dc");
		flowers = hexes("#ff8ac0", "#ffd84a", "#f4efe8");
		glowFire = hex("#ff9a40");
		glowFly = hex("#ffe27a");
		if (light) {
			sky = hexes("#8fb2dc", "#a6bfe2", "#bfc9e2", "#d8cfdc", "#ecd3cc", "#f8d8bc", "#ffdcaa", "#ffe4b0", "#fff0cc");
			haze = hex("#f6dcc4");
			hazeScale = 0.62;
			star = WHITE;
			starDim = WHITE;
			sun = hex("#fff2c4");
			sunCore = WHITE;
			halo = hex("#fff0b8");
			cloudLit = WHITE;
			cloud = hex("#fff0e4");
			cloudUnder = hex("#ecc4b4");
			hillBack = hex("#c6b0c6");
			hillBackRim = hex("#e2d0d8");
			hill = hex("#a898b4");
			hillRim = hex("#c8b6c6");
			forestFar = hex("#8c94a8");
			forestMid = hex("#6a7a80");
			meadow = hexes("#b4c46a", "#94b052", "#7a9846", "#5f7c3c");
			deep = hex("#5a3c2a");
			rim = hex("#fff0b0");
			hole = hexes("#4a2e1e", "#1e120c");
			smoke = hex("#c8bcb8");
		} else if (oled) {
			sky = hexes("#000000", "#07040b", "#100816", "#1a0c20", "#281028", "#3a1628", "#4e1e28", "#682a28", "#86402c");
			haze = hex("#3e1c2c");
			hazeScale = 1;
			star = hex("#ffe8d8");
			starDim = hex("#6e5064");
			sun = hex("#f0a058");
			sunCore = hex("#ffd098");
			halo = hex("#c86040");
			cloudLit = hex("#c88a74");
			cloud = hex("#7a4048");
			cloudUnder = hex("#4a2236");
			hillBack = hex("#4a2236");
			hillBackRim = hex("#6a3040");
			hill = hex("#341a2c");
			hillRim = hex("#4c2434");
			forestFar = hex("#2a1424");
			forestMid = hex("#1c0e1a");
			meadow = hexes("#3e3a26", "#2a2a1e", "#1a1816", "#0e0a0c");
			deep = hex("#070407");
			rim = hex("#e88a50");
			hole = hexes("#1c100c", "#050302");
			smoke = hex("#4a3a44");
		} else {
			sky = hexes("#2a1a3a", "#3e2044", "#56284e", "#7a3a5a", "#9a4a5c", "#c05e5a", "#e0785a", "#f09a64", "#ffc27a");
			haze = hex("#d07a82");
			hazeScale = 1;
			star = hex("#ffe8d8");
			starDim = hex("#9a7088");
			sun = hex("#ffd27a");
			sunCore = hex("#fff2c0");
			halo = hex("#ffb070");
			cloudLit = hex("#ffe2c4");
			cloud = hex("#f4ac92");
			cloudUnder = hex("#c8687a");
			hillBack = hex("#b4607a");
			hillBackRim = hex("#d88a8a");
			hill = hex("#8a4566");
			hillRim = hex("#b0647a");
			forestFar = hex("#7a3a5a");
			forestMid = hex("#4e2440");
			meadow = hexes("#8a7a46", "#5a5634", "#3c3a2c", "#261c22");
			deep = hex("#170d14");
			rim = hex("#ffb878");
			hole = hexes("#3a2418", "#0e0806");
			smoke = hex("#8a7884");
		}
		// Im hellen Theme ist `redstone-300` dunkler Text – fürs Licht die helle Stufe nehmen.
		int accentHi = light ? mix(a400, WHITE, 0.45) : a300;
		lanternFrame = light ? hex("#3a2a26") : hex("#1e1416");
		lanternOff = mix(a500, spruce[0], light ? 0.6 : 0.72);
		lanternOn = mix(a400, WHITE, 0.15);
		lanternCore = mix(accentHi, WHITE, 0.55);
		glowAccent = a400;
		glowSun = halo;
	}

	/** Variante aus dem Namen des Farbschemas ("dark", "oled", "light"). */
	public static int variantOf(String themeName) {
		if ("light".equals(themeName)) return LIGHT;
		if ("oled".equals(themeName)) return OLED;
		return DARK;
	}

	/**
	 * Palette zu Farbschema und Akzent. Für Fuchsorange (Standard im Fuchsbau) die genauen Launcher-Stufen, sonst aus
	 * dem Akzent aufgehellt.
	 */
	public static FoxPalette of(String themeName, String accentName, int accent) {
		int a300;
		int a400;
		int a500;
		if ("fox".equals(accentName)) {
			a300 = hex("#ffc89a");
			a400 = hex("#ff9a52");
			a500 = hex("#e8702a");
		} else {
			a500 = 0xFF000000 | accent;
			a400 = mix(a500, WHITE, 0.38);
			a300 = mix(a500, WHITE, 0.62);
		}
		return new FoxPalette(variantOf(themeName), a300, a400, a500);
	}
}
