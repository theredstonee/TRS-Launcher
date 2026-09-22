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
	}

	private static Theme current = new Theme("dark", "redstone", 0xFFE0281E);

	public final String themeName;
	public final String accentName;

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

	private Theme(String themeName, String accentName, int accent) {
		this.themeName = themeName;
		this.accentName = accentName;
		this.accent = accent;
		this.accentHover = ColorMath.lerp(accent, 0xFFFFFFFF, 0.22f);
		this.light = "light".equals(themeName);
		boolean oled = "oled".equals(themeName);
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
		} else {
			background = oled ? 0xFF000000 : 0xFF17171E;
			surface = oled ? 0xFF0C0C10 : 0xFF1D1D26;
			surfaceHover = oled ? 0xFF17171D : 0xFF262631;
			surfaceHigh = oled ? 0xFF121218 : 0xFF232330;
			border = oled ? 0xFF24242E : 0xFF2E2E3B;
			text = 0xFFECECF1;
			textDim = 0xFF8C8C9E;
			on = 0xFFFFB84D;
			off = 0xFF3A3A48;
			scrim = 0xA00C0C11;
		}
	}

	/** Aktuelles Thema (nie null). */
	public static Theme get() {
		return current;
	}

	/** Setzt das Thema direkt (Tests, Fallback). */
	public static void set(Theme theme) {
		if (theme != null) current = theme;
	}

	/** Thema aus Namen bauen; unbekannte Namen → Standard. */
	public static Theme of(String themeName, String accentName, String accentHex) {
		String t = normalize(themeName);
		String a = normalize(accentName);
		int accent = accentColor(a, accentHex);
		if (!"dark".equals(t) && !"oled".equals(t) && !"light".equals(t)) t = "dark";
		return new Theme(t, a.isEmpty() ? "redstone" : a, accent);
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
				set(of(parsed.theme, parsed.accent, parsed.accentColor));
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
