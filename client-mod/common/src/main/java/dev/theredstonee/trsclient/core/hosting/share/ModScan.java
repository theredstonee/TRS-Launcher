package dev.theredstonee.trsclient.core.hosting.share;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Mods einer Instanz einlesen – unabhängig von Loader und Version direkt aus dem {@code mods}-Ordner: Name, Version,
 * Umgebung (Client/Server/beides), Abhängigkeiten und ob die Mod eigene Inhalte (Blöcke, Items, Rezepte …) mitbringt.
 * Dazu SHA-1, SHA-512 und SHA-256 in einem Durchgang. Ergebnisse werden je Datei (Pfad + Größe + Zeit) gemerkt.
 *
 * <p>Blockierend (Hashes über ganze Dateien) – nur aus Hintergrund-Threads.
 */
public final class ModScan {
	/** Höchstens so viele JAR-Dateien werden gelesen (mehr geht ohnehin nicht in die Liste). */
	public static final int MAX_FILES = 400;
	/** Größte gelesene Metadaten-Datei in der JAR. */
	static final int MAX_META = 256 * 1024;

	/** Umgebung laut Metadaten. */
	public enum Env {
		BOTH, CLIENT, SERVER, UNKNOWN
	}

	/** Eine Mod-Datei. */
	public static final class LocalMod {
		public final Path path;
		public final String file;
		public final long size;
		public final long modified;
		public final String sha1;
		public final String sha512;
		public final String sha256;
		/** Mod-ID (fabric/quilt/forge) oder null. */
		public final String id;
		public final String name;
		public final String version;
		public final Env env;
		/** Bringt eigene Blöcke/Items/Rezepte/Welt-Inhalte mit (Blockstates bzw. Daten eines eigenen Namensraums). */
		public final boolean content;
		/** Pflicht-Abhängigkeiten (Mod-IDs). */
		public final Set<String> depends;

		LocalMod(Path path, String file, long size, long modified, String[] hashes, Meta meta) {
			this.path = path;
			this.file = file;
			this.size = size;
			this.modified = modified;
			this.sha1 = hashes[0];
			this.sha512 = hashes[1];
			this.sha256 = hashes[2];
			this.id = meta.id;
			this.name = meta.name == null || meta.name.isEmpty() ? stripJar(file) : meta.name;
			this.version = meta.version == null ? "" : meta.version;
			this.env = meta.env;
			this.content = meta.content;
			this.depends = Collections.unmodifiableSet(meta.depends);
		}

		/** Nur auf dem Client nötig (Umgebung „client“ bzw. Forge {@code clientSideOnly}/Anzeige-Test ohne Server). */
		public boolean clientOnly() {
			return env == Env.CLIENT;
		}
	}

	/** Gelesene Metadaten. */
	static final class Meta {
		String id;
		String name;
		String version;
		Env env = Env.UNKNOWN;
		boolean content;
		final Set<String> depends = new LinkedHashSet<String>();
	}

	private static final Map<String, LocalMod> CACHE = new ConcurrentHashMap<String, LocalMod>();

	private ModScan() {
	}

	/**
	 * Alle {@code *.jar} im Ordner (nicht rekursiv, keine versteckten/deaktivierten, keine Verknüpfungen), nach Name
	 * sortiert. Fehlt der Ordner → leer.
	 */
	public static List<LocalMod> scan(Path modsDir) {
		List<Path> files = new ArrayList<Path>();
		if (modsDir == null || !Files.isDirectory(modsDir)) return Collections.emptyList();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
			for (Path p : ds) {
				String n = p.getFileName().toString();
				if (n.startsWith(".") || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
				files.add(p);
				if (files.size() >= MAX_FILES) break;
			}
		} catch (IOException e) {
			return Collections.emptyList();
		}
		Collections.sort(files, new Comparator<Path>() {
			@Override
			public int compare(Path a, Path b) {
				return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
			}
		});
		List<LocalMod> out = new ArrayList<LocalMod>();
		for (Path p : files) {
			LocalMod m = read(p);
			if (m != null) out.add(m);
		}
		return out;
	}

	/** Eine Datei einlesen (gemerkt je Pfad + Größe + Änderungszeit); unlesbar → null. */
	public static LocalMod read(Path p) {
		try {
			BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (!a.isRegularFile()) return null;
			String key = p.toAbsolutePath().normalize() + "|" + a.size() + "|" + a.lastModifiedTime().toMillis();
			LocalMod cached = CACHE.get(key);
			if (cached != null) return cached;
			String[] hashes = hashes(p);
			Meta meta = meta(p);
			LocalMod m = new LocalMod(p, p.getFileName().toString(), a.size(), a.lastModifiedTime().toMillis(), hashes, meta);
			if (CACHE.size() > 2000) CACHE.clear();
			CACHE.put(key, m);
			return m;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** SHA-1, SHA-512, SHA-256 (Kleinbuchstaben-Hex) in einem Durchgang. */
	public static String[] hashes(Path p) throws IOException {
		MessageDigest s1 = digest("SHA-1");
		MessageDigest s512 = digest("SHA-512");
		MessageDigest s256 = digest("SHA-256");
		try (InputStream in = Files.newInputStream(p)) {
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0) {
				s1.update(buf, 0, n);
				s512.update(buf, 0, n);
				s256.update(buf, 0, n);
			}
		}
		return new String[] { hex(s1.digest()), hex(s512.digest()), hex(s256.digest()) };
	}

	static MessageDigest digest(String alg) {
		try {
			return MessageDigest.getInstance(alg);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(alg, e);
		}
	}

	public static String hex(byte[] b) {
		char[] d = "0123456789abcdef".toCharArray();
		char[] out = new char[b.length * 2];
		for (int i = 0; i < b.length; i++) {
			out[i * 2] = d[(b[i] >> 4) & 0xF];
			out[i * 2 + 1] = d[b[i] & 0xF];
		}
		return new String(out);
	}

	static String stripJar(String file) {
		return file.toLowerCase(Locale.ROOT).endsWith(".jar") ? file.substring(0, file.length() - 4) : file;
	}

	// --- Metadaten ---

	/** Liest fabric.mod.json / quilt.mod.json / (neoforge.)mods.toml / mcmod.info und schaut nach Inhalten. */
	static Meta meta(Path p) {
		Meta m = new Meta();
		try (ZipFile zip = new ZipFile(p.toFile())) {
			String fabric = text(zip, "fabric.mod.json");
			String quilt = fabric == null ? text(zip, "quilt.mod.json") : null;
			String neo = fabric == null && quilt == null ? text(zip, "META-INF/neoforge.mods.toml") : null;
			String forge = fabric == null && quilt == null && neo == null ? text(zip, "META-INF/mods.toml") : null;
			if (fabric != null) fabric(m, fabric);
			else if (quilt != null) quilt(m, quilt);
			else if (neo != null || forge != null) toml(m, neo != null ? neo : forge, manifestVersion(zip));
			else {
				String info = text(zip, "mcmod.info");
				if (info != null) mcmodInfo(m, info);
			}
			m.content = hasContent(zip);
		} catch (IOException | RuntimeException e) {
			// keine lesbare JAR: nur Name aus der Datei
		}
		m.name = m.name == null ? null : SharedContent.shown(m.name);
		m.version = m.version == null ? null : SharedContent.shown(m.version);
		return m;
	}

	private static String text(ZipFile zip, String name) throws IOException {
		ZipEntry e = zip.getEntry(name);
		if (e == null || e.isDirectory()) return null;
		try (InputStream in = zip.getInputStream(e)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) {
				if (out.size() + n > MAX_META) return null;
				out.write(buf, 0, n);
			}
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private static String manifestVersion(ZipFile zip) throws IOException {
		String mf = text(zip, "META-INF/MANIFEST.MF");
		if (mf == null) return null;
		for (String line : mf.split("\r?\n")) {
			if (line.startsWith("Implementation-Version:")) return line.substring("Implementation-Version:".length()).trim();
		}
		return null;
	}

	/**
	 * Eigene Inhalte: Blockstates eines eigenen Namensraums (neue Blöcke) oder Daten mit Rezepten, Beute-Tabellen bzw.
	 * Weltgenerierung (neue Items/Welt-Inhalte). Reine Client-Mods haben beides nicht.
	 */
	static boolean hasContent(ZipFile zip) {
		Enumeration<? extends ZipEntry> en = zip.entries();
		int seen = 0;
		while (en.hasMoreElements() && seen++ < 50_000) {
			String n = en.nextElement().getName();
			String[] parts = n.split("/");
			if (parts.length < 4) continue;
			String ns = parts[1];
			if (ns.equals("minecraft") || ns.isEmpty()) continue;
			if (parts[0].equals("assets") && parts[2].equals("blockstates")) return true;
			if (parts[0].equals("data") && (parts[2].equals("recipe") || parts[2].equals("recipes") || parts[2].equals("loot_table")
					|| parts[2].equals("loot_tables") || parts[2].equals("worldgen") || parts[2].equals("dimension"))) return true;
		}
		return false;
	}

	@SuppressWarnings("deprecation")
	private static JsonObject json(String s) {
		JsonElement e = new JsonParser().parse(s);
		return e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	static void fabric(Meta m, String raw) {
		JsonObject o = json(raw);
		if (o == null) return;
		m.id = modId(str(o, "id"));
		m.name = str(o, "name");
		m.version = str(o, "version");
		String env = str(o, "environment");
		m.env = env == null || env.equals("*") ? Env.BOTH : env.equals("client") ? Env.CLIENT : env.equals("server") ? Env.SERVER
				: Env.UNKNOWN;
		JsonElement deps = o.get("depends");
		if (deps != null && deps.isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : deps.getAsJsonObject().entrySet()) addDep(m, e.getKey());
		}
	}

	static void quilt(Meta m, String raw) {
		JsonObject o = json(raw);
		JsonElement ql = o == null ? null : o.get("quilt_loader");
		if (ql == null || !ql.isJsonObject()) return;
		JsonObject q = ql.getAsJsonObject();
		m.id = modId(str(q, "id"));
		m.version = str(q, "version");
		JsonElement md = q.get("metadata");
		if (md != null && md.isJsonObject()) m.name = str(md.getAsJsonObject(), "name");
		JsonElement mc = o.get("minecraft");
		String env = mc != null && mc.isJsonObject() ? str(mc.getAsJsonObject(), "environment") : null;
		m.env = env == null || env.equals("*") ? Env.BOTH : env.equals("client") ? Env.CLIENT
				: env.equals("dedicated_server") ? Env.SERVER : Env.UNKNOWN;
		JsonElement deps = q.get("depends");
		if (deps != null && deps.isJsonArray()) {
			for (JsonElement d : deps.getAsJsonArray()) {
				if (d.isJsonPrimitive()) addDep(m, d.getAsString());
				else if (d.isJsonObject()) addDep(m, str(d.getAsJsonObject(), "id"));
			}
		}
	}

	private static final Pattern TOML_KV = Pattern.compile("^\\s*([A-Za-z_]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|(true|false))\\s*(#.*)?$");

	/**
	 * Sehr kleiner TOML-Leser für mods.toml/neoforge.mods.toml: erster {@code [[mods]]}-Block (modId, displayName,
	 * version, clientSideOnly, displayTest) und {@code [[dependencies.x]]} mit {@code mandatory=true} bzw.
	 * {@code type="required"}.
	 */
	static void toml(Meta m, String raw, String manifestVersion) {
		String section = "";
		boolean firstMod = false;
		boolean seenMod = false;
		String depId = null;
		boolean depRequired = false;
		boolean depClientSide = false;
		boolean clientOnly = false;
		for (String line : raw.split("\r?\n")) {
			String t = line.trim();
			if (t.startsWith("[")) {
				if (depId != null && depRequired && !depClientSide) addDep(m, depId);
				depId = null;
				depRequired = false;
				depClientSide = false;
				section = t;
				if (t.equals("[[mods]]")) {
					firstMod = !seenMod;
					seenMod = true;
				} else firstMod = false;
				continue;
			}
			Matcher k = TOML_KV.matcher(line);
			if (!k.matches()) continue;
			String key = k.group(1);
			String val = k.group(3) != null ? k.group(3) : k.group(4) != null ? k.group(4) : k.group(5);
			if (firstMod) {
				if (key.equals("modId")) m.id = modId(val);
				else if (key.equals("displayName")) m.name = val;
				else if (key.equals("version")) m.version = val.contains("${") ? manifestVersion : val;
				else if (key.equals("clientSideOnly") && "true".equals(val)) clientOnly = true;
				else if (key.equals("displayTest") && (val.equals("IGNORE_ALL_VERSION") || val.equals("IGNORE_SERVER_VERSION"))) clientOnly = true;
			} else if (section.startsWith("[[dependencies.")) {
				if (key.equals("modId")) depId = val;
				else if (key.equals("mandatory") && "true".equals(val)) depRequired = true;
				else if (key.equals("type") && "required".equals(val)) depRequired = true;
				else if (key.equals("side") && "CLIENT".equals(val)) depClientSide = true;
			}
		}
		if (depId != null && depRequired && !depClientSide) addDep(m, depId);
		m.env = clientOnly ? Env.CLIENT : m.id != null ? Env.BOTH : Env.UNKNOWN;
	}

	/** Forge ≤ 1.12 / 1.7.10: {@code mcmod.info} (Liste oder {@code {modList:[…]}}). */
	@SuppressWarnings("deprecation")
	static void mcmodInfo(Meta m, String raw) {
		JsonElement e;
		try {
			e = new JsonParser().parse(raw);
		} catch (RuntimeException ex) {
			return;
		}
		JsonArray list = e.isJsonArray() ? e.getAsJsonArray()
				: e.isJsonObject() && e.getAsJsonObject().has("modList") && e.getAsJsonObject().get("modList").isJsonArray()
						? e.getAsJsonObject().getAsJsonArray("modList") : null;
		if (list == null || list.size() == 0 || !list.get(0).isJsonObject()) return;
		JsonObject o = list.get(0).getAsJsonObject();
		m.id = modId(str(o, "modid"));
		m.name = str(o, "name");
		m.version = str(o, "version");
		m.env = Env.BOTH;
	}

	private static void addDep(Meta m, String id) {
		String d = modId(id);
		if (d == null || d.equals("minecraft") || d.equals("java") || d.equals("fabricloader") || d.equals("quilt_loader")
				|| d.equals("forge") || d.equals("neoforge") || d.equals(m.id) || m.depends.size() >= 64) return;
		m.depends.add(d);
	}

	private static String modId(String id) {
		return id != null && id.matches("[a-z][a-z0-9_.-]{0,63}") ? id : null;
	}
}
