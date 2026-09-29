package dev.theredstonee.trsclient.core.util;

import dev.theredstonee.trsclient.core.i18n.I18n;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Links in die Dokumentation auf der Website ({@code /docs/<sprache>/<bereich>/<seite>}, ersetzt das GitHub-Wiki) –
 * in der Sprache des Clients, sofern es die Docs darin gibt (en, de, es), sonst Englisch.
 */
public final class Docs {
	/** Wurzel der Docs. */
	public static final String BASE = "https://trs-launcher.theredstonee.de/docs";
	/** Sprachen, in denen es die Docs gibt. */
	public static final List<String> LANGUAGES = Collections.unmodifiableList(Arrays.asList("en", "de", "es"));

	/** Überblick über den TRS Client (Ziel des Eintrags „Hilfe“ im TRS-Menü). */
	public static final String CLIENT_OVERVIEW = "client/overview";

	private Docs() {
	}

	/** Docs-Sprache zu einer Client-Sprache ({@code "de"}, {@code "es"}, sonst {@code "en"} – auch für {@code "pt-BR"}). */
	public static String language(String code) {
		if (code == null) return "en";
		String base = code.toLowerCase(Locale.ROOT);
		int dash = base.indexOf('-');
		if (dash < 0) dash = base.indexOf('_');
		if (dash >= 0) base = base.substring(0, dash);
		return LANGUAGES.contains(base) ? base : "en";
	}

	/**
	 * Adresse einer Seite ({@code "client/overview"}); {@code null} oder eine ungültige Seite = Startseite der Sprache
	 * ({@code /docs/de/}). Erlaubt sind nur Kleinbuchstaben, Ziffern, Bindestriche und einzelne {@code /}.
	 */
	public static String url(String page, String languageCode) {
		String lang = language(languageCode);
		if (!validPage(page)) return BASE + "/" + lang + "/";
		return BASE + "/" + lang + "/" + page;
	}

	/** {@link #url(String, String)} in der aktiven Client-Sprache. */
	public static String url(String page) {
		return url(page, I18n.code());
	}

	/** Seite im Standardbrowser öffnen; false = kein Browser gefunden. */
	public static boolean open(String page) {
		return Links.open(url(page));
	}

	static boolean validPage(String page) {
		if (page == null || page.isEmpty() || page.length() > 100) return false;
		if (page.charAt(0) == '/' || page.charAt(page.length() - 1) == '/') return false;
		char prev = '/';
		for (int i = 0; i < page.length(); i++) {
			char c = page.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '/';
			if (!ok || (c == '/' && prev == '/')) return false;
			prev = c;
		}
		return true;
	}
}
