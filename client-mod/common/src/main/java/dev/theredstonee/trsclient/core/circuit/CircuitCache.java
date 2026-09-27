package dev.theredstonee.trsclient.core.circuit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Zwischenspeicher der Schaltungen vom Server unter {@code config/trsclient/circuits/}: {@code index.json} (ETag,
 * Version, Einträge mit rev) und je Schaltung {@code <id>.json} (genau die Antwort des Servers). Alles atomar
 * geschrieben, beim Lesen streng geprüft – kaputte oder unpassende Dateien werden übersprungen.
 */
public final class CircuitCache {
	static final Pattern ID = Pattern.compile("[a-z0-9_]{1,48}");
	static final Pattern REV = Pattern.compile("[A-Za-z0-9._-]{1,64}");
	/** Höchstens so viele Schaltungen. */
	public static final int MAX_ENTRIES = 200;
	/** Höchstens so groß darf eine Schaltung sein. */
	public static final int MAX_CIRCUIT_BYTES = 256 * 1024;
	/** Höchstens so groß darf der Index sein. */
	public static final int MAX_INDEX_BYTES = 256 * 1024;

	/** Ein Eintrag des Server-Index. */
	public static final class Entry {
		public String id;
		public String rev;
		public String updatedAt;
		public String minVersion;
		public String maxVersion;

		boolean valid() {
			return id != null && ID.matcher(id).matches() && rev != null && REV.matcher(rev).matches()
					&& (updatedAt == null || updatedAt.length() <= 40)
					&& (minVersion == null || minVersion.length() <= 16) && (maxVersion == null || maxVersion.length() <= 16);
		}
	}

	/** Gespeicherter Index. */
	public static final class Index {
		/** ETag der letzten 200-Antwort (für If-None-Match). */
		public String etag;
		public String version;
		public List<Entry> circuits = new ArrayList<Entry>();
	}

	private final Path dir;

	public CircuitCache(Path dir) {
		this.dir = dir;
	}

	public Path dir() {
		return dir;
	}

	/** Gespeicherter Index oder null. */
	public Index readIndex() {
		Path f = dir.resolve("index.json");
		try {
			if (!Files.isRegularFile(f) || Files.size(f) > MAX_INDEX_BYTES) return null;
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				Index index = new Gson().fromJson(r, Index.class);
				if (index == null) return null;
				List<Entry> ok = new ArrayList<Entry>();
				Set<String> seen = new HashSet<String>();
				if (index.circuits != null) {
					for (Entry e : index.circuits) {
						if (e != null && e.valid() && seen.add(e.id) && ok.size() < MAX_ENTRIES) ok.add(e);
					}
				}
				index.circuits = ok;
				if (index.etag != null && index.etag.length() > 200) index.etag = null;
				return index;
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	public void writeIndex(Index index) throws IOException {
		write(dir.resolve("index.json"), new GsonBuilder().setPrettyPrinting().create().toJson(index));
	}

	/** Schaltung aus dem Cache (roh) oder null. */
	public JsonObject readCircuit(String id) {
		if (id == null || !ID.matcher(id).matches()) return null;
		Path f = dir.resolve(id + ".json");
		try {
			if (!Files.isRegularFile(f) || Files.size(f) > MAX_CIRCUIT_BYTES) return null;
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				return new JsonParser().parse(r).getAsJsonObject();
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	public boolean hasCircuit(String id) {
		return id != null && ID.matcher(id).matches() && Files.isRegularFile(dir.resolve(id + ".json"));
	}

	public void writeCircuit(String id, String json) throws IOException {
		if (!ID.matcher(id).matches()) throw new IOException("ungültige id");
		write(dir.resolve(id + ".json"), json);
	}

	/** Alle Schaltungs-Dateien löschen, die nicht in {@code keep} stehen. */
	public int removeOthers(Set<String> keep) {
		int removed = 0;
		if (!Files.isDirectory(dir)) return 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
			for (Path f : files) {
				String name = f.getFileName().toString();
				if (name.equals("index.json")) continue;
				String id = name.substring(0, name.length() - 5);
				if (!keep.contains(id)) {
					Files.deleteIfExists(f);
					removed++;
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// nächster Start
		}
		return removed;
	}

	private void write(Path file, String text) throws IOException {
		Files.createDirectories(dir);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			w.write(text);
		}
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Bibliothek aus dem Cache (Reihenfolge wie im Index; ungültige Einträge fehlen). */
	public CircuitLibrary load(BlockCatalog catalog) {
		Index index = readIndex();
		List<Circuit> list = new ArrayList<Circuit>();
		if (index != null) {
			for (Entry e : index.circuits) {
				JsonObject o = readCircuit(e.id);
				if (o == null) continue;
				try {
					Circuit c = Circuit.parse(o, catalog);
					if (!c.id.equals(e.id)) continue;
					list.add(CircuitLibrary.prepare(c).withVersions(e.minVersion, e.maxVersion));
				} catch (RuntimeException ignored) {
					// ungültig → nicht anzeigen
				}
			}
		}
		return CircuitLibrary.of(list, index != null);
	}
}
