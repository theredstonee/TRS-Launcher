package dev.theredstonee.trsclient.core.circuit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.circuit.sim.CircuitSimTest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Die Schaltungs-Bibliothek. Die Schaltungen stecken nicht im Mod: sie kommen von der TRS API
 * ({@link CircuitSync}, bei jedem Start kurz geprüft) und liegen im {@link CircuitCache}. Im Mod ist nur der
 * Block-Katalog ({@code assets/trsclient/circuits/blocks.json}) – damit wird jede Schaltung streng geprüft.
 * Vor dem ersten Download (oder ohne Internet und ohne Cache) ist die Bibliothek leer.
 */
public final class CircuitLibrary {
	public static final String CATALOG = "/assets/trsclient/circuits/blocks.json";

	private static volatile BlockCatalog catalog;
	private static volatile CircuitLibrary current = new CircuitLibrary(new ArrayList<Circuit>(), false);

	private final List<Circuit> circuits;
	private final Map<String, Circuit> byId = new HashMap<String, Circuit>();
	/** Aus dem Cache bzw. vom Server (false = noch nie geladen). */
	private final boolean loaded;
	private final Map<String, String> errors = new HashMap<String, String>();

	private CircuitLibrary(List<Circuit> circuits, boolean loaded) {
		this.circuits = Collections.unmodifiableList(circuits);
		this.loaded = loaded;
		for (Circuit c : circuits) byId.put(c.id, c);
	}

	/** Die aktuelle Bibliothek (nie null; leer bis zum ersten Laden). */
	public static CircuitLibrary get() {
		return current;
	}

	/** Neue Bibliothek veröffentlichen (Cache geladen bzw. Download fertig). */
	public static void publish(CircuitLibrary library) {
		if (library != null) current = library;
	}

	/** Bibliothek aus fertigen Schaltungen. */
	public static CircuitLibrary of(List<Circuit> circuits, boolean loaded) {
		return new CircuitLibrary(new ArrayList<Circuit>(circuits), loaded);
	}

	/** Block-Katalog des Mods (einmal geladen). */
	public static BlockCatalog blockCatalog() {
		BlockCatalog c = catalog;
		if (c == null) {
			synchronized (CircuitLibrary.class) {
				c = catalog;
				if (c == null) {
					JsonObject o = read(CATALOG);
					c = o == null ? new BlockCatalog() : BlockCatalog.parse(o);
					catalog = c;
				}
			}
		}
		return c;
	}

	/** Schaltung vorbereiten: Tests in der Simulation laufen lassen (nur bestanden → „geprüft“). */
	static Circuit prepare(Circuit c) {
		if (c.tests.size() > 0) {
			try {
				c.setSimulated(CircuitSimTest.run(c).isEmpty());
			} catch (RuntimeException e) {
				c.setSimulated(false);
			}
		}
		return c;
	}

	/**
	 * Lädt Schaltungen aus dem Classpath (Tests; die mitgelieferten Quelldaten liegen in {@code <repo>/data/circuits}
	 * und sind nur im Test-Classpath): {@code <dir>/index.json} + je Schaltung {@code <dir>/<id>.json}.
	 *
	 * @param strict Fehler werfen statt überspringen
	 */
	public static CircuitLibrary loadResources(String dir, boolean strict) {
		BlockCatalog cat = blockCatalog();
		JsonObject index = read(dir + "index.json");
		if (index == null) throw new IllegalStateException(dir + "index.json fehlt");
		List<Circuit> list = new ArrayList<Circuit>();
		Map<String, String> errors = new HashMap<String, String>();
		for (JsonElement e : index.getAsJsonArray("circuits")) {
			String id = e.getAsString();
			try {
				JsonObject o = read(dir + id + ".json");
				if (o == null) throw new IllegalArgumentException(id + ": Datei fehlt");
				Circuit c = Circuit.parse(o, cat);
				if (!c.id.equals(id)) throw new IllegalArgumentException(id + ": id passt nicht zum Dateinamen");
				list.add(prepare(c));
			} catch (RuntimeException ex) {
				if (strict) throw ex;
				errors.put(id, String.valueOf(ex.getMessage()));
			}
		}
		CircuitLibrary lib = new CircuitLibrary(list, true);
		lib.errors.putAll(errors);
		return lib;
	}

	/** Die mitgelieferten Quelldaten (nur im Test-Classpath). */
	public static CircuitLibrary load(boolean strict) {
		return loadResources("/circuits/", strict);
	}

	static JsonObject read(String path) {
		InputStream in = open(path);
		if (in == null) return null;
		try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			return new JsonParser().parse(r).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Ressource über die eigene Klasse, notfalls über den Kontext-Classloader (Forge/LaunchWrapper). */
	static InputStream open(String path) {
		InputStream in = CircuitLibrary.class.getResourceAsStream(path);
		if (in != null) return in;
		ClassLoader own = CircuitLibrary.class.getClassLoader();
		if (own != null) {
			in = own.getResourceAsStream(path.substring(1));
			if (in != null) return in;
		}
		ClassLoader ctx = Thread.currentThread().getContextClassLoader();
		return ctx != null ? ctx.getResourceAsStream(path.substring(1)) : null;
	}

	public BlockCatalog catalog() {
		return blockCatalog();
	}

	public List<Circuit> all() {
		return circuits;
	}

	public boolean isEmpty() {
		return circuits.isEmpty();
	}

	/** Schon einmal geladen (Cache vorhanden bzw. Download fertig)? */
	public boolean loaded() {
		return loaded;
	}

	public Circuit byId(String id) {
		return id == null ? null : byId.get(id);
	}

	public Map<String, String> errors() {
		return Collections.unmodifiableMap(errors);
	}

	/**
	 * Suche + Filter für die Liste.
	 *
	 * @param query      Suchtext (Name, Erklärung, Blöcke) oder leer
	 * @param category   Kategorie oder null = alle
	 * @param minecraft  nur Schaltungen, die in dieser Version laufen (null = alle)
	 */
	public List<Circuit> filter(String query, Circuit.Category category, String minecraft, CircuitTexts texts) {
		String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<Circuit> out = new ArrayList<Circuit>();
		for (Circuit c : circuits) {
			if (category != null && c.category != category) continue;
			if (minecraft != null && !c.runsIn(minecraft)) continue;
			if (!q.isEmpty() && !matches(c, q, texts)) continue;
			out.add(c);
		}
		return out;
	}

	private static boolean matches(Circuit c, String q, CircuitTexts texts) {
		if (c.id.replace('_', ' ').contains(q)) return true;
		if (texts != null) {
			if (texts.name(c).toLowerCase(Locale.ROOT).contains(q)) return true;
			if (texts.desc(c).toLowerCase(Locale.ROOT).contains(q)) return true;
			if (texts.category(c.category).toLowerCase(Locale.ROOT).contains(q)) return true;
			for (Circuit.Material m : c.materials()) {
				if (texts.block(m.def).toLowerCase(Locale.ROOT).contains(q)) return true;
			}
		}
		return false;
	}
}
