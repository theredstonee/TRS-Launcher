package dev.theredstonee.trsclient.core.hosting.share;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.theredstonee.trsclient.core.social.SafeText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Was ein Host mit seiner Welt teilt (API.md §21.10): Mod-Liste und Resource Pack – nur Metadaten. Die Dateien gehen
 * nie über TRS, sondern direkt vom Host an angenommene Gäste ({@link FileChannel}).
 *
 * <p>Alles, was aus der API (also von einem fremden Host) kommt, läuft durch {@link #parse}: gleiche Regeln wie der
 * Server (Längen, Muster, Grenzen), Unbekanntes wird verworfen. Unveränderlich.
 */
public final class SharedContent {
	public static final int MAX_MODS = 300;
	public static final long MAX_HOST_FILE = 64L * 1024 * 1024;
	public static final long MAX_HOST_TOTAL = 512L * 1024 * 1024;
	public static final long MAX_STORE_FILE = 512L * 1024 * 1024;
	public static final long MAX_PACK = 250L * 1024 * 1024;

	static final Pattern JAR_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._+()\\[\\]{}'!,&~@#$%=-]*\\.jar");
	static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
	static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	static final Pattern SHA512 = Pattern.compile("[0-9a-f]{128}");
	static final Pattern MODRINTH_ID = Pattern.compile("[A-Za-z0-9]{8}");
	static final Pattern CF_ID = Pattern.compile("[0-9]{1,10}");

	/** Woher ein Gast eine Mod bekommt. */
	public enum Source {
		MODRINTH("modrinth"), CURSEFORGE("curseforge"),
		/** Direkt vom Host (nicht geprüft). */
		HOST("host"),
		/** Nicht im Store und nicht übertragen: „musst du selbst besorgen“. */
		MANUAL("manual");

		public final String id;

		Source(String id) {
			this.id = id;
		}

		public boolean store() {
			return this == MODRINTH || this == CURSEFORGE;
		}

		public static Source of(String id) {
			for (Source s : values()) if (s.id.equals(id)) return s;
			return null;
		}
	}

	/** Eine geteilte Mod. */
	public static final class Mod {
		public final String name;
		public final String version;
		public final String file;
		public final long size;
		public final boolean required;
		public final Source source;
		/** Modrinth-Projekt / CurseForge-Mod (nur Store). */
		public final String projectId;
		/** Modrinth-Version / CurseForge-Datei (nur Store). */
		public final String fileId;
		public final String sha1;
		public final String sha512;
		public final String sha256;
		/** CurseForge-Fingerprint oder -1. */
		public final long fingerprint;

		public Mod(String name, String version, String file, long size, boolean required, Source source, String projectId,
				String fileId, String sha1, String sha512, String sha256, long fingerprint) {
			this.name = name;
			this.version = version == null ? "" : version;
			this.file = file;
			this.size = size;
			this.required = required;
			this.source = source;
			this.projectId = projectId;
			this.fileId = fileId;
			this.sha1 = sha1;
			this.sha512 = sha512;
			this.sha256 = sha256;
			this.fingerprint = fingerprint;
		}

		/** null = gültig, sonst Grund (für Tests/Log). */
		public String problem() {
			if (name == null || name.trim().isEmpty() || name.length() > 64 || !clean(name)) return "name";
			if (version.length() > 64 || !clean(version)) return "version";
			if (!validFileName(file)) return "file";
			if (source == null) return "source";
			if (size < 1 || size > (source == Source.HOST ? MAX_HOST_FILE : MAX_STORE_FILE)) return "size";
			if (sha1 == null || !SHA1.matcher(sha1).matches()) return "sha1";
			if (sha512 != null && !SHA512.matcher(sha512).matches()) return "sha512";
			if (sha256 != null && !SHA256.matcher(sha256).matches()) return "sha256";
			if (fingerprint < -1 || fingerprint > 0xFFFFFFFFL) return "fingerprint";
			switch (source) {
				case MODRINTH:
					if (projectId == null || !MODRINTH_ID.matcher(projectId).matches()) return "projectId";
					if (fileId == null || !MODRINTH_ID.matcher(fileId).matches()) return "fileId";
					if (sha512 == null) return "sha512";
					break;
				case CURSEFORGE:
					if (projectId == null || !CF_ID.matcher(projectId).matches()) return "projectId";
					if (fileId == null || !CF_ID.matcher(fileId).matches()) return "fileId";
					break;
				case HOST:
					if (sha256 == null) return "sha256";
					// fallthrough
				default:
					if (projectId != null || fileId != null) return "projectId";
			}
			return null;
		}

		JsonObject json() {
			JsonObject o = new JsonObject();
			o.addProperty("name", name);
			o.addProperty("version", version);
			o.addProperty("file", file);
			o.addProperty("size", size);
			o.addProperty("required", required);
			o.addProperty("source", source.id);
			if (projectId != null) o.addProperty("projectId", projectId);
			if (fileId != null) o.addProperty("fileId", fileId);
			o.addProperty("sha1", sha1);
			if (sha512 != null) o.addProperty("sha512", sha512);
			if (sha256 != null) o.addProperty("sha256", sha256);
			if (fingerprint >= 0) o.addProperty("fingerprint", fingerprint);
			return o;
		}
	}

	/** Das geteilte Resource Pack (kommt direkt vom Host). */
	public static final class Pack {
		public final String name;
		public final long size;
		public final String sha1;
		public final String sha256;

		public Pack(String name, long size, String sha1, String sha256) {
			this.name = name;
			this.size = size;
			this.sha1 = sha1;
			this.sha256 = sha256;
		}

		public String problem() {
			if (name == null || name.trim().isEmpty() || name.length() > 64 || !clean(name)) return "name";
			if (size < 1 || size > MAX_PACK) return "size";
			if (sha1 == null || !SHA1.matcher(sha1).matches()) return "sha1";
			if (sha256 == null || !SHA256.matcher(sha256).matches()) return "sha256";
			return null;
		}

		JsonObject json() {
			JsonObject o = new JsonObject();
			o.addProperty("name", name);
			o.addProperty("size", size);
			o.addProperty("sha1", sha1);
			o.addProperty("sha256", sha256);
			return o;
		}
	}

	/** Kurzform aus jeder Raum-Ansicht ({@code content}). */
	public static final class Summary {
		public final int mods;
		public final int required;
		public final int fromHost;
		public final int manual;
		/** Name des Packs oder null. */
		public final String packName;
		public final long packSize;
		public final String packSha1;

		public Summary(int mods, int required, int fromHost, int manual, String packName, long packSize, String packSha1) {
			this.mods = mods;
			this.required = required;
			this.fromHost = fromHost;
			this.manual = manual;
			this.packName = packName;
			this.packSize = packSize;
			this.packSha1 = packSha1;
		}

		public boolean hasPack() {
			return packName != null;
		}

		/** Aus der API ({@code room.content}); kaputt/leer → null. */
		public static Summary parse(JsonElement e) {
			if (e == null || !e.isJsonObject()) return null;
			JsonObject o = e.getAsJsonObject();
			int mods = clampInt(o.get("mods"), MAX_MODS);
			int required = Math.min(mods, clampInt(o.get("required"), MAX_MODS));
			int fromHost = Math.min(mods, clampInt(o.get("fromHost"), MAX_MODS));
			int manual = Math.min(mods, clampInt(o.get("manual"), MAX_MODS));
			String pn = null;
			long ps = 0;
			String psha = null;
			JsonElement p = o.get("pack");
			if (p != null && p.isJsonObject()) {
				JsonObject po = p.getAsJsonObject();
				String n = str(po, "name");
				String sha = str(po, "sha1");
				long size = num(po.get("size"));
				if (n != null && sha != null && SHA1.matcher(sha).matches() && size > 0 && size <= MAX_PACK) {
					pn = shown(n);
					ps = size;
					psha = sha;
				}
			}
			if (mods == 0 && pn == null) return null;
			return new Summary(mods, required, fromHost, manual, pn, ps, psha);
		}
	}

	public static final SharedContent EMPTY = new SharedContent(Collections.<Mod>emptyList(), null);

	public final List<Mod> mods;
	public final Pack pack;

	public SharedContent(List<Mod> mods, Pack pack) {
		this.mods = Collections.unmodifiableList(new ArrayList<Mod>(mods));
		this.pack = pack;
	}

	public boolean isEmpty() {
		return mods.isEmpty() && pack == null;
	}

	public int required() {
		int n = 0;
		for (Mod m : mods) if (m.required) n++;
		return n;
	}

	public long hostTotal() {
		long t = 0;
		for (Mod m : mods) if (m.source == Source.HOST) t += m.size;
		return t;
	}

	public Mod bySha1(String sha1) {
		for (Mod m : mods) if (m.sha1.equals(sha1)) return m;
		return null;
	}

	/** Gleiche Regeln wie die API: null = gültig, sonst Grund. */
	public String problem() {
		if (mods.size() > MAX_MODS) return "too_many_mods";
		Set<String> seen = new HashSet<String>();
		for (Mod m : mods) {
			String p = m.problem();
			if (p != null) return "mod." + p;
			if (!seen.add(m.sha1)) return "duplicate";
		}
		if (hostTotal() > MAX_HOST_TOTAL) return "host_total";
		if (pack != null && pack.problem() != null) return "pack." + pack.problem();
		return null;
	}

	/** Körper für {@code PUT /v1/hosting/rooms/{id}/content}. */
	public String json() {
		JsonObject o = new JsonObject();
		JsonArray a = new JsonArray();
		for (Mod m : mods) a.add(m.json());
		o.add("mods", a);
		if (pack != null) o.add("pack", pack.json());
		else o.add("pack", com.google.gson.JsonNull.INSTANCE);
		return o.toString();
	}

	/**
	 * Antwort von {@code GET …/content} (oder ein gespeicherter Stand) prüfen. Ungültige Einträge fallen weg – ein
	 * fremder Host kann dem Gast so keine kaputten Namen/Pfade unterschieben. null = gar nicht lesbar.
	 */
	public static SharedContent parse(JsonElement e) {
		if (e == null || !e.isJsonObject()) return null;
		JsonObject o = e.getAsJsonObject();
		List<Mod> mods = new ArrayList<Mod>();
		Set<String> seen = new HashSet<String>();
		long hostTotal = 0;
		JsonElement ma = o.get("mods");
		if (ma != null && ma.isJsonArray()) {
			for (JsonElement me : ma.getAsJsonArray()) {
				if (mods.size() >= MAX_MODS) break;
				Mod m = mod(me);
				if (m == null || m.problem() != null || !seen.add(m.sha1)) continue;
				if (m.source == Source.HOST) {
					if (hostTotal + m.size > MAX_HOST_TOTAL) continue;
					hostTotal += m.size;
				}
				mods.add(m);
			}
		}
		Pack pack = null;
		JsonElement pe = o.get("pack");
		if (pe != null && pe.isJsonObject()) {
			JsonObject po = pe.getAsJsonObject();
			String n = str(po, "name");
			Pack p = new Pack(n == null ? null : shown(n), num(po.get("size")), lower(str(po, "sha1")), lower(str(po, "sha256")));
			if (p.problem() == null) pack = p;
		}
		return new SharedContent(mods, pack);
	}

	private static Mod mod(JsonElement e) {
		if (e == null || !e.isJsonObject()) return null;
		JsonObject o = e.getAsJsonObject();
		Source source = Source.of(str(o, "source"));
		String name = str(o, "name");
		String version = str(o, "version");
		JsonElement r = o.get("required");
		boolean required = r != null && r.isJsonPrimitive() && r.getAsJsonPrimitive().isBoolean() && r.getAsBoolean();
		long fp = o.has("fingerprint") ? num(o.get("fingerprint")) : -1;
		if (fp == 0 && !o.has("fingerprint")) fp = -1;
		return new Mod(name == null ? null : shown(name), version == null ? "" : shown(version), str(o, "file"), num(o.get("size")),
				required, source, str(o, "projectId"), str(o, "fileId"), lower(str(o, "sha1")), lower(str(o, "sha512")),
				lower(str(o, "sha256")), fp);
	}

	// --- Hilfen ---

	/** Reiner Dateiname einer Mod (kein Pfad, kein „..“, endet auf .jar). */
	public static boolean validFileName(String f) {
		return f != null && f.length() >= 5 && f.length() <= 128 && JAR_NAME.matcher(f).matches() && !f.contains("..");
	}

	/** Anzeigetext: eine Zeile, ohne Steuer-/Formatzeichen, höchstens 64 Zeichen. */
	public static String shown(String raw) {
		String s = SafeText.line(raw, 64);
		if (s == null) return "";
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);
			if (cleanPoint(cp)) b.appendCodePoint(cp);
		}
		return b.toString().replaceAll("\\s+", " ").trim();
	}

	/** Wie die API ({@code [\p{Cc}\p{Cf}\p{Co}\p{Cn}]} verboten) – plus kein §. */
	static boolean clean(String s) {
		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);
			if (!cleanPoint(cp)) return false;
		}
		return true;
	}

	private static boolean cleanPoint(int cp) {
		int type = Character.getType(cp);
		return type != Character.CONTROL && type != Character.FORMAT && type != Character.PRIVATE_USE
				&& type != Character.UNASSIGNED && type != Character.SURROGATE && cp != 0xA7;
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
	}

	private static String lower(String s) {
		return s == null ? null : s.toLowerCase(Locale.ROOT);
	}

	private static long num(JsonElement e) {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return 0;
		try {
			double d = e.getAsDouble();
			return d != Math.floor(d) || d < 0 || d > 1e15 ? 0 : (long) d;
		} catch (RuntimeException ex) {
			return 0;
		}
	}

	private static int clampInt(JsonElement e, int max) {
		return (int) Math.max(0, Math.min(max, num(e)));
	}
}
