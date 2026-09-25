package dev.theredstonee.trsclient.core.perf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Eingebaute Optimierungen: freie Leistungs-Mods, die der TRS Client (Fabric) als Jar-in-Jar mitbringt. Die Liste
 * schreibt der Build nach {@code assets/trsclient/bundled-mods.json}; fehlt sie (Forge, alte Versionen), gibt es
 * das Modul „Eingebaute Optimierungen“ nicht. Abgeschaltet wird beim nächsten Start über den TRS Launcher (Fabric
 * kann eingebettete Mods nur beim Laden weglassen).
 */
public final class BundledMods {
	/** Eine eingebaute Mod. */
	public static final class Entry {
		public final String id;
		public final String name;
		/** Version laut fabric.mod.json der eingebetteten Datei. */
		public final String version;
		public final String license;

		Entry(String id, String name, String version, String license) {
			this.id = id;
			this.name = name;
			this.version = version;
			this.license = license;
		}
	}

	/** Welche Fassung einer Mod gerade läuft (Loader). */
	public interface Loaded {
		/** Version der geladenen Mod mit dieser ID oder null. */
		String version(String id);
	}

	/** Zustand einer eingebauten Mod in dieser Sitzung. */
	public enum State {
		/** Unsere Fassung läuft. */
		ACTIVE,
		/** Eine andere Fassung läuft (vom Spieler oder vom Launcher-Preset) – Fabric nimmt die neueste. */
		OTHER_VERSION,
		/** Läuft nicht (abgeschaltet). */
		OFF
	}

	private static final String RESOURCE = "/assets/trsclient/bundled-mods.json";
	private static volatile BundledMods instance;
	private static volatile Loaded loaded;

	private final List<Entry> entries;

	private BundledMods(List<Entry> entries) {
		this.entries = Collections.unmodifiableList(entries);
	}

	/** Liste aus dem eigenen Jar (einmal gelesen). */
	public static BundledMods get() {
		BundledMods b = instance;
		if (b == null) {
			b = EMPTY;
			InputStream in = BundledMods.class.getResourceAsStream(RESOURCE);
			if (in != null) {
				try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
					b = parse(r);
				} catch (Exception e) {
					b = EMPTY;
				}
			}
			instance = b;
		}
		return b;
	}

	private static final BundledMods EMPTY = new BundledMods(new ArrayList<Entry>());

	/** Loader meldet, wie er geladene Mods nachschlägt (nur Fabric). */
	public static void setLoaded(Loaded l) {
		loaded = l;
	}

	static BundledMods parse(Reader r) {
		List<Entry> out = new ArrayList<Entry>();
		JsonElement root = new JsonParser().parse(r);
		if (root != null && root.isJsonArray()) {
			JsonArray arr = root.getAsJsonArray();
			for (int i = 0; i < arr.size() && i < 32; i++) {
				if (!arr.get(i).isJsonObject()) continue;
				JsonObject o = arr.get(i).getAsJsonObject();
				String id = str(o, "id");
				if (id.isEmpty()) continue;
				out.add(new Entry(id, str(o, "name").isEmpty() ? id : str(o, "name"), str(o, "version"), str(o, "license")));
			}
		}
		return new BundledMods(out);
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}

	public boolean available() {
		return !entries.isEmpty();
	}

	public List<Entry> entries() {
		return entries;
	}

	/** Läuft unsere Fassung, eine andere oder keine? */
	public State state(Entry e) {
		Loaded l = loaded;
		String v = l == null ? null : l.version(e.id);
		if (v == null) return State.OFF;
		return v.equals(e.version) ? State.ACTIVE : State.OTHER_VERSION;
	}

	/** Die Version, die gerade läuft (oder null). */
	public String runningVersion(Entry e) {
		Loaded l = loaded;
		return l == null ? null : l.version(e.id);
	}

	/** Für Tests. */
	static BundledMods of(Reader r) {
		return parse(r);
	}
}
