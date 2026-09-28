package dev.theredstonee.trsclient.core.bugreport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.util.Links;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Anfrage und Antwort von {@code POST /v1/issues} (API.md §28) – reine Funktionen: Grenzen prüfen, JSON bauen,
 * Antwort lesen, Link prüfen. Die Grenzen entsprechen dem Server; was zu lang ist, wird hier schon gekürzt, damit
 * eine Meldung nie an einer Kleinigkeit scheitert.
 */
public final class BugReportBody {
	public static final int TITLE_MIN = 5;
	public static final int TITLE_MAX = 120;
	/** Mindestens ein paar Worte – leere Meldungen helfen niemandem. */
	public static final int DESCRIPTION_MIN = 10;
	public static final int DESCRIPTION_MAX = 8000;
	public static final int MAX_ATTACHMENTS = 6;
	public static final int MAX_MODS = 300;
	public static final int MAX_MOD_LENGTH = 100;
	public static final int MAX_LOG = 20000;
	public static final int MAX_META_STRING = 32;
	/** Nur Links auf die TRS-Website öffnen („Im Browser öffnen“). */
	public static final String SITE = "https://trs-launcher.theredstonee.de/";

	private static final Pattern UPLOAD_ID = Pattern.compile("[A-Za-z0-9_-]{22}");
	private static final Pattern META = Pattern.compile("[0-9A-Za-z._+ -]{1,32}");

	private BugReportBody() {
	}

	/** Technische Angaben (null = nicht mitschicken). */
	public static final class Meta {
		public String modVersion;
		public String mcVersion;
		public String loader;
		public List<String> mods;
		public String log;
	}

	/** Angelegtes Issue. {@code url} nur, wenn sie auf die TRS-Website zeigt – sonst null. */
	public static final class Created {
		public final int number;
		public final String url;

		public Created(int number, String url) {
			this.number = number;
			this.url = url;
		}
	}

	// --- Prüfen ---

	/** Fehler-Schlüssel (i18n) für den Titel oder null. */
	public static String titleError(String title) {
		int n = title == null ? 0 : title.trim().length();
		if (n < TITLE_MIN) return "bugreport.error.titleShort";
		if (n > TITLE_MAX) return "bugreport.error.titleLong";
		return null;
	}

	/** Fehler-Schlüssel (i18n) für die Beschreibung oder null. */
	public static String descriptionError(String description) {
		int n = description == null ? 0 : description.trim().length();
		if (n < DESCRIPTION_MIN) return "bugreport.error.descriptionShort";
		if (n > DESCRIPTION_MAX) return "bugreport.error.descriptionLong";
		return null;
	}

	public static boolean validUploadId(String id) {
		return id != null && UPLOAD_ID.matcher(id).matches();
	}

	/**
	 * Darf „Im Browser öffnen“ diesen Link öffnen? Nur https auf der TRS-Website, ohne Leer-/Steuerzeichen (siehe
	 * {@link Links#allowed}) und ohne Zugangsdaten im Host-Teil.
	 */
	public static boolean siteLink(String url) {
		if (url == null || !url.startsWith(SITE) || url.length() > 300 || !Links.allowed(url)) return false;
		String rest = url.substring(SITE.length());
		return !rest.contains("@") && !rest.startsWith("/") && !rest.contains("..");
	}

	// --- Anfrage ---

	/**
	 * Körper für {@code POST /v1/issues}: {@code {type:"bug", area:"client", title, description, attachments, meta}}.
	 * Titel/Beschreibung werden getrimmt und auf die Höchstlänge gekürzt; Anhänge höchstens 6 gültige IDs;
	 * {@code meta} enthält nur die gesetzten Felder.
	 */
	public static JsonObject build(String title, String description, List<String> uploadIds, Meta meta) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "bug");
		o.addProperty("area", "client");
		o.addProperty("title", clip(title == null ? "" : title.trim(), TITLE_MAX));
		o.addProperty("description", clip(description == null ? "" : description.trim(), DESCRIPTION_MAX));
		JsonArray att = new JsonArray();
		if (uploadIds != null) {
			for (String id : uploadIds) {
				if (validUploadId(id) && att.size() < MAX_ATTACHMENTS) att.add(new com.google.gson.JsonPrimitive(id));
			}
		}
		o.add("attachments", att);
		JsonObject m = new JsonObject();
		if (meta != null) {
			putMeta(m, "modVersion", meta.modVersion);
			putMeta(m, "mcVersion", meta.mcVersion);
			putMeta(m, "loader", meta.loader);
			if (meta.mods != null) {
				JsonArray mods = new JsonArray();
				for (String mod : meta.mods) {
					if (mod == null || mod.trim().isEmpty()) continue;
					if (mods.size() >= MAX_MODS) break;
					mods.add(new com.google.gson.JsonPrimitive(clip(mod.trim(), MAX_MOD_LENGTH)));
				}
				m.add("mods", mods);
			}
			if (meta.log != null) m.addProperty("log", LogScrubber.tail(meta.log, Integer.MAX_VALUE, MAX_LOG));
		}
		o.add("meta", m);
		return o;
	}

	/** Harmlose Kurzangabe (Version, Loader) – sonst gekürzt auf die erlaubten Zeichen. */
	static String metaString(String value) {
		if (value == null) return null;
		String t = value.trim();
		if (META.matcher(t).matches()) return t;
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < t.length() && b.length() < MAX_META_STRING; i++) {
			char c = t.charAt(i);
			if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '.' || c == '_' || c == '+'
					|| c == ' ' || c == '-') {
				b.append(c);
			}
		}
		String out = b.toString().trim();
		return out.isEmpty() ? null : out;
	}

	private static void putMeta(JsonObject m, String key, String value) {
		String v = metaString(value);
		if (v != null) m.addProperty(key, v);
	}

	/** Auf {@code max} UTF-16-Zeichen kürzen, ohne ein Zeichenpaar (Emoji) zu zerschneiden. */
	static String clip(String s, int max) {
		if (s.length() <= max) return s;
		int end = max;
		if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
		return s.substring(0, end);
	}

	// --- Antworten ---

	/** {@code {"upload":{"id":"…"}}} → ID oder null (fehlt/ungültig). */
	public static String uploadId(String json) {
		try {
			JsonObject root = new JsonParser().parse(json).getAsJsonObject();
			JsonElement up = root.get("upload");
			JsonElement id = up != null && up.isJsonObject() ? up.getAsJsonObject().get("id") : null;
			String s = id == null || id.isJsonNull() ? null : id.getAsString();
			return validUploadId(s) ? s : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** {@code {"issue":{"number":123,"url":"…"}}} → Ergebnis oder null (keine gültige Nummer). */
	public static Created created(String json) {
		try {
			JsonObject root = new JsonParser().parse(json).getAsJsonObject();
			JsonElement issue = root.get("issue");
			if (issue == null || !issue.isJsonObject()) return null;
			JsonObject o = issue.getAsJsonObject();
			JsonElement num = o.get("number");
			if (num == null || !num.isJsonPrimitive() || !num.getAsJsonPrimitive().isNumber()) return null;
			long n = num.getAsLong();
			if (n <= 0 || n > Integer.MAX_VALUE) return null;
			JsonElement u = o.get("url");
			String url = u == null || u.isJsonNull() || !u.isJsonPrimitive() ? null : u.getAsString();
			return new Created((int) n, siteLink(url) ? url : null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	// --- Mod-Liste ---

	/** Sortiert, ohne Doppelte, höchstens {@link #MAX_MODS} Einträge je höchstens {@link #MAX_MOD_LENGTH} Zeichen. */
	public static List<String> cleanMods(List<String> names) {
		if (names == null) return Collections.emptyList();
		List<String> out = new ArrayList<String>();
		java.util.Set<String> seen = new java.util.HashSet<String>();
		List<String> sorted = new ArrayList<String>();
		for (String n : names) {
			if (n != null) sorted.add(n);
		}
		Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
		for (String n : sorted) {
			String t = clip(n.trim(), MAX_MOD_LENGTH);
			if (t.isEmpty() || !seen.add(t.toLowerCase(java.util.Locale.ROOT))) continue;
			out.add(t);
			if (out.size() >= MAX_MODS) break;
		}
		return out;
	}
}
