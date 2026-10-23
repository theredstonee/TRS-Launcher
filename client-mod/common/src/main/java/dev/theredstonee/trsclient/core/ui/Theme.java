package dev.theredstonee.trsclient.core.ui;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Farben des TRS-Menüs. Der Launcher schreibt vor dem Start seine Oberflächen-Einstellungen nach
 * {@code config/trsclient/launcher-theme.json}; fehlt die Datei oder ist sie ungültig, gilt das
 * dunkle Standard-Thema mit Redstone-Rot. Alle Werte werden geprüft und begrenzt.
 */
public final class Theme {
	/** Datei, die der Launcher schreibt (relativ zum Config-Ordner der Instanz). */
	public static final String FILE = "trsclient/launcher-theme.json";
	/** Größere Dateien werden gar nicht erst gelesen. */
	private static final long MAX_BYTES = 8 * 1024;

	/** Gson-DTO der Launcher-Datei. */
	static final class File {
		int version;
		String theme;
		String accent;
		String accentColor;
		/** "redstone" oder "winter" (ab Launcher 0.20); fehlt/unbekannt = Redstone. */
		String design;
		/** Aktives TRS-Event für dieses Konto ({@code "halloween"}) – fehlt sonst. */
		String event;
		/** Halloween: „Flackern &amp; Rauschen“ im Launcher (fehlt = Standard an). */
		Boolean halloweenFlicker;
		/**
		 * Zeigt der Launcher den Event-Look? false = vom Team gesperrt oder im Launcher ausgeschaltet (fehlt = an, ältere
		 * Launcher). {@link #event} bleibt davon unberührt (Funktionen).
		 */
		Boolean eventLook;
	}

	/** Design „Redstone“ (Standard) und „Winternacht“. */
	public static final String REDSTONE = "redstone";
	public static final String WINTER = "winter";

	private static Theme current = new Theme("dark", "redstone", 0xFFE0281E, REDSTONE);
	/** Halloween-Variante des aktuellen Themas (zwischengespeichert, solange {@link #current} gleich bleibt). */
	private static volatile Theme spooky;

	public final String themeName;
	public final String accentName;
	/** Design: {@link #REDSTONE} oder {@link #WINTER}. */
	public final String designName;
	/** Winternacht? (Nachtblau, Eis statt Stein, Eislaternen statt Redstone-Lampen, Frost statt Staub.) */
	public final boolean winter;
	/**
	 * Halloween 2026 „Mondnacht“ (Event aktiv, siehe {@code core.halloween.Halloween}): Nacht, Pflaume, Violett und
	 * Mondsilber – liegt über Redstone und Winternacht, immer dunkel.
	 */
	public final boolean halloween;
	/** Thema, aus dem die Halloween-Variante entstand (sonst null). */
	private final Theme base;

	/** Akzentfarbe (Knöpfe, aktive Elemente). */
	public final int accent;
	/** Hellere Akzentvariante (Hover). */
	public final int accentHover;
	/** Hintergrund des Menüs. */
	public final int background;
	/** Flächen/Kacheln. */
	public final int surface;
	public final int surfaceHover;
	/** Flächen einer Ebene höher (Kopfzeile, Eingabefelder). */
	public final int surfaceHigh;
	public final int border;
	public final int text;
	public final int textDim;
	/** „Unter Strom“ – aktive Anzeige (Lampen-Bernstein). */
	public final int on;
	public final int off;
	/** Abdunkelung hinter dem Menü. */
	public final int scrim;
	/** Heller Grundton? (Licht-Thema – Texte sind dann dunkel.) */
	public final boolean light;

	// --- Redstone-Stil ---
	/** Tiefste Fläche (Mulden: Eingabefelder, Schalter-Schiene, Symbol-Nischen). */
	public final int deep;
	/** Pixel-Kante oben/links (Licht) und unten/rechts (Schatten) von Steinflächen. */
	public final int bevelLight;
	public final int bevelDark;
	/** Redstone-Staub ohne Signal und mit voller Signalstärke (in Akzentfarbe getönt). */
	public final int dustOff;
	public final int dustOn;
	/** Leuchten um aktive Elemente (Akzent, deckend – Deckkraft setzt der Aufrufer). */
	public final int glow;
	/** Redstone-Lampe: aus (Fläche/Kante), an (Fläche/heißer Kern/Kante), Leuchten. */
	public final int lampOff;
	public final int lampOffEdge;
	public final int lampOn;
	public final int lampHot;
	public final int lampOnEdge;
	public final int lampGlow;
	/** Schrift auf der Lampe: aus (hell) und an (dunkel, damit sie auf dem Licht lesbar bleibt). */
	public final int lampText;
	public final int lampTextLit;

	private Theme(String themeName, String accentName, int accent, String design) {
		this.halloween = false;
		this.base = null;
		this.themeName = themeName;
		this.accentName = accentName;
		this.designName = WINTER.equals(design) ? WINTER : REDSTONE;
		this.winter = WINTER.equals(designName);
		this.accent = accent;
		this.accentHover = ColorMath.lerp(accent, 0xFFFFFFFF, 0.22f);
		this.light = "light".equals(themeName);
		boolean oled = "oled".equals(themeName);
		if (winter) {
			// Winternacht (Launcher: [data-design="winter"]): Nachtblau, Eis statt Stein. Die Lampen sind Eislaternen –
			// eisblau unabhängig vom Akzent, wie im Launcher; Frost-Linien statt Staub in Akzentfarbe.
			this.dustOff = ColorMath.lerp(ColorMath.scaleRgb(accent, 0.42f), oled ? 0xFF070C17 : 0xFF101A2E, 0.3f);
			this.dustOn = ColorMath.lerp(accent, 0xFFFFFFFF, 0.3f);
			this.glow = accent;
			this.lampOff = 0xFF172338;
			this.lampOffEdge = 0xFF0A111F;
			this.lampOn = 0xFF8FDCFF;
			this.lampHot = 0xFFEAF9FF;
			this.lampOnEdge = 0xFF2A6E9E;
			this.lampGlow = 0xFF8FDCFF;
			this.lampText = 0xFFDBE8F8;
			this.lampTextLit = 0xFF06263B;
			if (light) {
				background = 0xFFE8EFF7;
				surface = 0xFFFFFFFF;
				surfaceHover = 0xFFE0E9F3;
				surfaceHigh = 0xFFEAF1F8;
				border = 0xFFC3D2E4;
				text = 0xFF0B1426;
				textDim = 0xFF55698B;
				on = 0xFF1C74AD;
				off = 0xFFCBD8E7;
				scrim = 0x500B1426;
				deep = 0xFFD5E1EE;
				bevelLight = 0xFFFFFFFF;
				bevelDark = 0xFFA6B9D0;
			} else {
				background = oled ? 0xFF000000 : 0xFF0D1424;
				surface = oled ? 0xFF070C17 : 0xFF15203A;
				surfaceHover = oled ? 0xFF0E1626 : 0xFF1C2A48;
				surfaceHigh = oled ? 0xFF0A111E : 0xFF18253F;
				border = oled ? 0xFF19243A : 0xFF283A5E;
				text = 0xFFEAF4FF;
				textDim = oled ? 0xFF7D8FB3 : 0xFF889BC2;
				on = 0xFF8FDCFF;
				off = oled ? 0xFF0E1626 : 0xFF1C2A48;
				scrim = oled ? 0xB0000000 : 0xA8060A14;
				deep = oled ? 0xFF02040A : 0xFF080D19;
				bevelLight = oled ? 0xFF1F2C47 : 0xFF31476F;
				bevelDark = oled ? 0xFF000000 : 0xFF070B15;
			}
			return;
		}
		// Staub: dunkles, entsättigtes Akzent-Rot ohne Signal – kräftig und etwas heller mit Signal.
		this.dustOff = ColorMath.lerp(ColorMath.scaleRgb(accent, 0.34f), 0xFF1A1414, 0.25f);
		this.dustOn = ColorMath.lerp(accent, 0xFFFFFFFF, 0.12f);
		this.glow = accent;
		this.lampOff = 0xFF3B2A1D;
		this.lampOffEdge = 0xFF22180F;
		this.lampOn = 0xFFF2B454;
		this.lampHot = 0xFFFFE7A6;
		this.lampOnEdge = 0xFF8C4E1C;
		this.lampGlow = 0xFFFFB347;
		this.lampText = 0xFFE6DCD2;
		this.lampTextLit = 0xFF2B1605;
		if (light) {
			background = 0xFFF3F3F8;
			surface = 0xFFFFFFFF;
			surfaceHover = 0xFFE9E9F2;
			surfaceHigh = 0xFFE6E6EE;
			border = 0xFFC9C9D8;
			text = 0xFF15151C;
			textDim = 0xFF5F5F75;
			on = 0xFFE0900C;
			off = 0xFFC2C2D0;
			scrim = 0x50000000;
			deep = 0xFFD9D9E0;
			bevelLight = 0xFFFFFFFF;
			bevelDark = 0xFFB4B4C2;
		} else {
			// Tiefenschiefer: kühles, fast neutrales Grau – dunkel genug, dass Redstone leuchtet.
			background = oled ? 0xFF000000 : 0xFF19191D;
			surface = oled ? 0xFF0D0D10 : 0xFF222227;
			surfaceHover = oled ? 0xFF18181C : 0xFF2B2B31;
			surfaceHigh = oled ? 0xFF131317 : 0xFF26262B;
			border = oled ? 0xFF26262C : 0xFF36363D;
			text = 0xFFEDE9E4;
			textDim = 0xFF8F8B89;
			on = 0xFFFFB84D;
			off = oled ? 0xFF1C1C21 : 0xFF2E2E34;
			scrim = 0xA80B0B0E;
			deep = oled ? 0xFF050506 : 0xFF121215;
			bevelLight = oled ? 0xFF2A2A31 : 0xFF3B3B42;
			bevelDark = oled ? 0xFF000000 : 0xFF141417;
		}
	}

	/**
	 * Halloween-Variante von {@code base} (Vertrag „Look“): Nacht #07060c, Nebelgrund #13101f, Pflaume #3a1f5c,
	 * Violett #8e6cc9/#b9a2ea, Mondsilber #cfd5e3/#f3f5fb. Lampen sind mondbeschienenes Pflaumenglas mit Silberkante,
	 * „Staub“ ist ein dünner violett-silberner Faden. Kein Orange als Fläche.
	 */
	private Theme(Theme base) {
		this.base = base;
		this.halloween = true;
		this.themeName = base.themeName;
		this.accentName = base.accentName;
		this.designName = base.designName;
		this.winter = false;
		this.light = false;
		this.accent = 0xFF8E6CC9;
		this.accentHover = 0xFFB9A2EA;
		this.dustOff = 0xFF2E2648;
		this.dustOn = 0xFFCFD5E3;
		this.glow = 0xFFB9A2EA;
		this.lampOff = 0xFF141020;
		this.lampOffEdge = 0xFF5B5280;
		this.lampOn = 0xFF4A2C78;
		this.lampHot = 0xFFCFD5E3;
		this.lampOnEdge = 0xFFF3F5FB;
		this.lampGlow = 0xFFB9A2EA;
		this.lampText = 0xFFE9E6F5;
		this.lampTextLit = 0xFFF3F5FB;
		boolean oled = "oled".equals(base.themeName);
		this.background = oled ? 0xFF000000 : 0xFF0A0812;
		this.surface = oled ? 0xFF0A0812 : 0xFF13101F;
		this.surfaceHover = oled ? 0xFF14101F : 0xFF1C1730;
		this.surfaceHigh = oled ? 0xFF100C19 : 0xFF181427;
		this.border = 0xFF3B3358;
		this.text = 0xFFEDEAF7;
		this.textDim = 0xFF9A94B4;
		this.on = 0xFFCFD5E3;
		this.off = 0xFF2A2440;
		this.scrim = 0xB007060C;
		this.deep = oled ? 0xFF000000 : 0xFF07060C;
		this.bevelLight = 0xFF4A4068;
		this.bevelDark = 0xFF040308;
	}

	/**
	 * Farbe des Redstone-Staubs bei Signalstärke {@code level} (0..15). Wie im Spiel wird der Staub
	 * mit sinkender Stärke dunkler – nie ganz aus, solange ein Signal anliegt.
	 */
	public int dust(int level) {
		if (level <= 0) return dustOff;
		float f = Math.min(15, level) / 15f;
		return ColorMath.lerp(ColorMath.lerp(dustOff, dustOn, 0.45f), dustOn, f);
	}

	/** Aktuelles Thema (nie null) – während Halloween (Look an) dessen Halloween-Variante. */
	public static Theme get() {
		Theme c = current;
		if (!dev.theredstonee.trsclient.core.halloween.Halloween.lookActive()) return c;
		Theme h = spooky;
		if (h == null || h.base != c) {
			h = new Theme(c);
			spooky = h;
		}
		return h;
	}

	/** Das eingestellte Thema ohne Halloween-Variante. */
	public static Theme plain() {
		return current;
	}

	/** Setzt das Thema direkt (Tests, Fallback). */
	public static void set(Theme theme) {
		if (theme != null) current = theme;
	}

	/** Thema aus Namen bauen (Design Redstone); unbekannte Namen → Standard. */
	public static Theme of(String themeName, String accentName, String accentHex) {
		return of(themeName, accentName, accentHex, REDSTONE);
	}

	/** Thema aus Namen bauen; unbekannte Namen → Standard, unbekanntes Design → Redstone. */
	public static Theme of(String themeName, String accentName, String accentHex, String design) {
		String t = normalize(themeName);
		String a = normalize(accentName);
		int accent = accentColor(a, accentHex);
		if (!"dark".equals(t) && !"oled".equals(t) && !"light".equals(t)) t = "dark";
		return new Theme(t, a.isEmpty() ? "redstone" : a, accent, normalizeDesign(design));
	}

	/** "winter" → Winternacht, alles andere (fehlt, ältere Launcher, Tippfehler) → Redstone. */
	public static String normalizeDesign(String design) {
		return WINTER.equals(normalize(design)) ? WINTER : REDSTONE;
	}

	/**
	 * Akzent beim Wechsel des Designs (wie der Launcher): Winternacht mit dem Redstone-Akzent bekommt Eisblau, zurück
	 * zu Redstone wird aus Eisblau wieder Redstone. Jede andere (selbst gewählte) Farbe bleibt.
	 */
	public static String accentForDesign(String design, String accent) {
		String d = normalizeDesign(design);
		if (WINTER.equals(d) && "redstone".equals(accent)) return "ice";
		if (REDSTONE.equals(d) && "ice".equals(accent)) return "redstone";
		return accent;
	}

	/**
	 * Lädt {@code <configDir>/trsclient/launcher-theme.json} und setzt das Thema.
	 * Fehler werden verschluckt – dann bleibt das Standard-Thema.
	 * @return true, wenn eine gültige Datei gelesen wurde
	 */
	public static boolean loadFrom(Path configDir) {
		if (configDir == null) return false;
		Path file = configDir.resolve("trsclient").resolve("launcher-theme.json");
		try {
			if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return false;
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				File parsed = new Gson().fromJson(reader, File.class);
				if (parsed == null) return false;
				set(of(parsed.theme, parsed.accent, parsed.accentColor, parsed.design));
				dev.theredstonee.trsclient.core.halloween.Halloween.fromLauncher(parsed.event);
				dev.theredstonee.trsclient.core.halloween.Halloween.flickerFromLauncher(parsed.halloweenFlicker);
				dev.theredstonee.trsclient.core.halloween.Halloween.lookFromLauncher(parsed.eventLook);
				return true;
			}
		} catch (IOException | RuntimeException e) {
			// JsonParseException, IllegalStateException, ClassCastException … – das Thema bleibt Standard.
			return false;
		}
	}

	/** Akzentfarbe aus dem Namen des Launchers, sonst aus dem Hex-Wert, sonst Redstone-Rot. */
	static int accentColor(String name, String hex) {
		if ("lamp".equals(name)) return 0xFFE0900C;
		if ("emerald".equals(name)) return 0xFF17A34A;
		if ("lapis".equals(name)) return 0xFF3563E9;
		if ("amethyst".equals(name)) return 0xFF9B4DDF;
		if ("ice".equals(name)) return 0xFF2B97D6;
		if ("redstone".equals(name)) return 0xFFE0281E;
		int parsed = parseHex(hex);
		return parsed != 0 ? parsed : 0xFFE0281E;
	}

	/** "#RRGGBB" → deckendes ARGB; ungültig → 0. */
	static int parseHex(String hex) {
		if (hex == null) return 0;
		String t = hex.trim();
		if (t.startsWith("#")) t = t.substring(1);
		if (t.length() != 6) return 0;
		for (int i = 0; i < 6; i++) {
			if (Character.digit(t.charAt(i), 16) < 0) return 0;
		}
		return 0xFF000000 | Integer.parseInt(t, 16);
	}

	private static String normalize(String s) {
		if (s == null) return "";
		String t = s.trim().toLowerCase(Locale.ROOT);
		if (t.length() > 24) return "";
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i);
			if (!(c >= 'a' && c <= 'z')) return "";
		}
		return t;
	}
}
