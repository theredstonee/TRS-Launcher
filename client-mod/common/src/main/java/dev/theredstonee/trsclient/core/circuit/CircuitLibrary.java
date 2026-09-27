package dev.theredstonee.trsclient.core.circuit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
 * Die mitgelieferten Schaltungen ({@code assets/trsclient/circuits/}): {@code blocks.json} (Block-Katalog),
 * {@code index.json} (Reihenfolge) und je Schaltung {@code <id>.json}. Einmal geladen, danach nur gelesen.
 * Alle Schaltungen sind eigene Entwürfe bzw. allgemein bekannte Standardbauweisen.
 */
public final class CircuitLibrary {
	public static final String DIR = "/assets/trsclient/circuits/";

	private static volatile CircuitLibrary instance;

	private final BlockCatalog catalog;
	private final List<Circuit> circuits;
	private final Map<String, Circuit> byId = new HashMap<String, Circuit>();
	/** Schaltungen, die nicht geladen werden konnten (id → Grund) – nur für Tests/Log. */
	private final Map<String, String> errors = new HashMap<String, String>();

	private CircuitLibrary(BlockCatalog catalog, List<Circuit> circuits) {
		this.catalog = catalog;
		this.circuits = Collections.unmodifiableList(circuits);
		for (Circuit c : circuits) byId.put(c.id, c);
	}

	/** Die Bibliothek (beim ersten Aufruf geladen; Fehler → leere Bibliothek statt Absturz). */
	public static CircuitLibrary get() {
		CircuitLibrary l = instance;
		if (l == null) {
			synchronized (CircuitLibrary.class) {
				l = instance;
				if (l == null) {
					try {
						l = load(false);
					} catch (RuntimeException e) {
						l = new CircuitLibrary(new BlockCatalog(), new ArrayList<Circuit>());
					}
					instance = l;
				}
			}
		}
		return l;
	}

	/**
	 * Lädt alles aus den Ressourcen.
	 *
	 * @param strict Fehler in einer Schaltung werfen (Tests) statt sie zu überspringen
	 */
	public static CircuitLibrary load(boolean strict) {
		JsonObject blocks = read("blocks.json");
		if (blocks == null) throw new IllegalStateException("circuits/blocks.json fehlt");
		BlockCatalog catalog = BlockCatalog.parse(blocks);
		JsonObject index = read("index.json");
		if (index == null) throw new IllegalStateException("circuits/index.json fehlt");
		List<Circuit> list = new ArrayList<Circuit>();
		Map<String, String> errors = new HashMap<String, String>();
		for (JsonElement e : index.getAsJsonArray("circuits")) {
			String id = e.getAsString();
			try {
				JsonObject o = read(id + ".json");
				if (o == null) throw new IllegalArgumentException(id + ": Datei fehlt");
				Circuit c = Circuit.parse(o, catalog);
				if (!c.id.equals(id)) throw new IllegalArgumentException(id + ": id passt nicht zum Dateinamen");
				list.add(c);
			} catch (RuntimeException ex) {
				if (strict) throw ex;
				errors.put(id, String.valueOf(ex.getMessage()));
			}
		}
		CircuitLibrary lib = new CircuitLibrary(catalog, list);
		lib.errors.putAll(errors);
		return lib;
	}

	static JsonObject read(String name) {
		InputStream in = open(DIR + name);
		if (in == null) return null;
		try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			return new JsonParser().parse(r).getAsJsonObject();
		} catch (IOException e) {
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
		return catalog;
	}

	public List<Circuit> all() {
		return circuits;
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
