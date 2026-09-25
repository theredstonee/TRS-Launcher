package dev.theredstonee.trsclient.core.i18n;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Übersetzungen des TRS Clients (versionsunabhängig, Java 8).
 *
 * <p>Je Sprache liegt eine flache UTF-8-JSON-Datei {@code assets/trsclient/i18n/<code>.json} in der Jar.
 * Die Sprache kommt aus {@code config/trsclient/launcher-theme.json} ({@code "language"}, schreibt der
 * Launcher vor jedem Start); fehlt sie, gilt die in Minecraft gewählte Sprache ({@code options.txt},
 * {@code lang:de_de}), sonst Englisch. Fehlende Schlüssel fallen auf Englisch zurück, dann auf den
 * Schlüssel selbst.
 *
 * <p>Nachschlagen ist eine einzige HashMap-Abfrage auf einer fertig zusammengeführten Tabelle –
 * keine Allokation je Bild. Platzhalter heißen {@code {0}}, {@code {1}} … (kein MessageFormat,
 * damit Apostrophe wie im Französischen unverändert bleiben).
 */
public final class I18n {
	/** Standard- und Rückfallsprache. */
	public static final String FALLBACK = "en";
	/** Alle Sprachen – genau wie im Launcher. */
	public static final List<String> LANGUAGES = Collections.unmodifiableList(
			java.util.Arrays.asList("en", "de", "es", "fr", "pl", "pt-BR", "tr", "nl"));
	/** Maschinell übersetzt (Beta, wie im Launcher). */
	public static final List<String> BETA = Collections.unmodifiableList(
			java.util.Arrays.asList("fr", "pl", "pt-BR", "tr", "nl"));

	private static final String RESOURCE_DIR = "/assets/trsclient/i18n/";
	private static final long MAX_BYTES = 8 * 1024;

	private static final Map<String, Map<String, String>> RAW = new HashMap<String, Map<String, String>>();
	private static volatile Map<String, String> table;
	private static volatile String code = FALLBACK;
	/** Zählt jeden Sprachwechsel – Aufrufer mit eigenem Zwischenspeicher vergleichen darauf. */
	private static volatile int generation;

	private static Path configDir;
	private static long themeStamp = Long.MIN_VALUE;
	private static long optionsStamp = Long.MIN_VALUE;

	private I18n() {
	}

	// --- Nachschlagen ---

	/** Übersetzung zu {@code key}; fehlt sie überall, der Schlüssel selbst. */
	public static String tr(String key) {
		String v = table().get(key);
		return v != null ? v : key;
	}

	/** Übersetzung mit Platzhaltern {@code {0}}, {@code {1}} …. */
	public static String tr(String key, Object... args) {
		return format(tr(key), args);
	}

	/** Übersetzung oder – fehlt der Schlüssel auch im Englischen – {@code fallback}. */
	public static String trOr(String key, String fallback) {
		String v = table().get(key);
		return v != null ? v : fallback;
	}

	/** Gibt es den Schlüssel (in der aktiven Sprache oder im Englischen)? */
	public static boolean has(String key) {
		return table().containsKey(key);
	}

	/** Aktive Sprache ("en", "de", "pt-BR", …). */
	public static String code() {
		return code;
	}

	/** Zähler, der bei jedem Sprachwechsel steigt. */
	public static int generation() {
		return generation;
	}

	/** Java-Locale der aktiven Sprache (Dezimaltrennzeichen, Groß-/Kleinschreibung). */
	public static Locale locale() {
		return Locale.forLanguageTag(code);
	}

	/** Ersetzt {@code {0}}, {@code {1}} … durch die Argumente; unbekannte Platzhalter bleiben stehen. */
	public static String format(String pattern, Object... args) {
		if (args == null || args.length == 0 || pattern.indexOf('{') < 0) return pattern;
		StringBuilder out = new StringBuilder(pattern.length() + 16);
		int n = pattern.length();
		for (int i = 0; i < n; i++) {
			char ch = pattern.charAt(i);
			if (ch == '{' && i + 2 < n && pattern.charAt(i + 2) == '}') {
				int idx = pattern.charAt(i + 1) - '0';
				if (idx >= 0 && idx <= 9 && idx < args.length) {
					out.append(args[idx]);
					i += 2;
					continue;
				}
			}
			out.append(ch);
		}
		return out.toString();
	}

	// --- Sprache wählen ---

	/**
	 * Merkt sich den Config-Ordner der Instanz und wählt die Sprache (beim Start aufrufen).
	 * Fehler werden verschluckt – dann bleibt Englisch.
	 */
	public static synchronized void init(Path configDirectory) {
		configDir = configDirectory;
		themeStamp = Long.MIN_VALUE;
		optionsStamp = Long.MIN_VALUE;
		refresh();
	}

	/** Config-Ordner der Instanz (aus {@link #init}) oder null – auch für andere Teile, die ihn beim Start nicht bekommen. */
	public static synchronized Path configDir() {
		return configDir;
	}

	/**
	 * Liest die Sprache neu, wenn sich launcher-theme.json oder options.txt geändert haben
	 * (beim Öffnen von Menü und Startbildschirm – kostet nur zwei Zeitstempel-Abfragen).
	 */
	public static synchronized void refresh() {
		if (configDir == null) return;
		Path theme = configDir.resolve("trsclient").resolve("launcher-theme.json");
		Path options = configDir.getParent() != null ? configDir.getParent().resolve("options.txt") : null;
		long ts = stamp(theme);
		long os = stamp(options);
		if (ts == themeStamp && os == optionsStamp) return;
		themeStamp = ts;
		optionsStamp = os;
		use(resolve(readLauncherLanguage(theme), readMinecraftLanguage(options)));
	}

	/** Setzt die Sprache direkt (Tests, Rückfall); unbekannte Codes → Englisch. */
	public static synchronized void use(String language) {
		String c = supported(language);
		if (c == null) c = FALLBACK;
		if (c.equals(code) && table != null) return;
		Map<String, String> merged = new HashMap<String, String>(raw(FALLBACK));
		if (!FALLBACK.equals(c)) merged.putAll(raw(c));
		table = merged;
		code = c;
		generation++;
	}

	/** Sprache des Launchers vor der von Minecraft, sonst Englisch. */
	public static String resolve(String launcherLanguage, String minecraftLocale) {
		String c = supported(launcherLanguage);
		if (c != null) return c;
		c = fromMinecraft(minecraftLocale);
		return c != null ? c : FALLBACK;
	}

	/** Launcher-Code ("de", "pt-BR", auch "pt_br") → unterstützter Code oder null. */
	public static String supported(String language) {
		if (language == null) return null;
		String l = language.trim().replace('_', '-');
		for (int i = 0; i < LANGUAGES.size(); i++) {
			if (LANGUAGES.get(i).equalsIgnoreCase(l)) return LANGUAGES.get(i);
		}
		return null;
	}

	/**
	 * Minecraft-Sprachcode ("de_de", "es_mx", ab 1.8 auch "de_DE") → unterstützter Code oder null.
	 * Alle Varianten einer Sprache (de_at, es_ar, fr_ca, nl_be …) nehmen dieselbe Übersetzung.
	 */
	public static String fromMinecraft(String locale) {
		if (locale == null) return null;
		String l = locale.trim().toLowerCase(Locale.ROOT).replace('-', '_');
		if (l.isEmpty()) return null;
		if (l.equals("pt_br") || l.equals("pt_pt")) return "pt-BR";
		int us = l.indexOf('_');
		String lang = us < 0 ? l : l.substring(0, us);
		if (lang.equals("en") || lang.equals("de") || lang.equals("es") || lang.equals("fr")
				|| lang.equals("pl") || lang.equals("tr") || lang.equals("nl")) return lang;
		return null;
	}

	// --- Dateien ---

	/** Rohe Tabelle einer Sprache aus der Jar (leer, wenn es sie nicht gibt). */
	public static synchronized Map<String, String> raw(String language) {
		Map<String, String> cached = RAW.get(language);
		if (cached != null) return cached;
		Map<String, String> loaded = load(language);
		RAW.put(language, loaded);
		return loaded;
	}

	private static Map<String, String> load(String language) {
		InputStream in = open(RESOURCE_DIR + language + ".json");
		if (in == null) return Collections.emptyMap();
		try (Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			Type type = new TypeToken<LinkedHashMap<String, String>>() {
			}.getType();
			Map<String, String> parsed = new Gson().fromJson(reader, type);
			if (parsed == null) return Collections.emptyMap();
			parsed.remove("_meta");
			return Collections.unmodifiableMap(parsed);
		} catch (IOException | RuntimeException e) {
			return Collections.emptyMap();
		}
	}

	/** Ressource über die eigene Klasse, notfalls über den Kontext-Classloader (Forge/LaunchWrapper). */
	private static InputStream open(String path) {
		InputStream in = I18n.class.getResourceAsStream(path);
		if (in != null) return in;
		ClassLoader own = I18n.class.getClassLoader();
		if (own != null) {
			in = own.getResourceAsStream(path.substring(1));
			if (in != null) return in;
		}
		ClassLoader ctx = Thread.currentThread().getContextClassLoader();
		return ctx != null ? ctx.getResourceAsStream(path.substring(1)) : null;
	}

	private static Map<String, String> table() {
		Map<String, String> t = table;
		if (t == null) {
			use(code);
			t = table;
		}
		return t;
	}

	private static long stamp(Path file) {
		try {
			if (file == null || !Files.isRegularFile(file)) return -1;
			return Files.getLastModifiedTime(file).toMillis() ^ (Files.size(file) << 40);
		} catch (IOException | RuntimeException e) {
			return -1;
		}
	}

	/** DTO: nur das Sprachfeld von launcher-theme.json. */
	static final class ThemeFile {
		String language;
	}

	static String readLauncherLanguage(Path file) {
		try {
			if (file == null || !Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return null;
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				ThemeFile parsed = new Gson().fromJson(reader, ThemeFile.class);
				return parsed == null ? null : parsed.language;
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** {@code lang:de_de} aus options.txt (nur die ersten 64 KB, die Zeile steht weit oben). */
	static String readMinecraftLanguage(Path options) {
		try {
			if (options == null || !Files.isRegularFile(options)) return null;
			// ISO-8859-1: die Zeile ist ASCII, kaputte Bytes weiter unten (Servernamen …) stören so nicht.
			try (BufferedReader reader = Files.newBufferedReader(options, StandardCharsets.ISO_8859_1)) {
				String line;
				int read = 0;
				while ((line = reader.readLine()) != null && read < 64 * 1024) {
					read += line.length() + 1;
					if (line.startsWith("lang:")) return line.substring(5).trim();
				}
			}
		} catch (IOException | RuntimeException e) {
			// options.txt kaputt oder gesperrt – dann eben Englisch.
		}
		return null;
	}
}
