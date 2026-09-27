package dev.theredstonee.trsclient.core.circuit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Eingeblendete Vorlagen je Welt bzw. Server (Schlüssel wie bei den Wegpunkten: {@code sp:<welt>}/{@code mp:<adresse>}
 * plus Dimension), damit sie nach dem Neuverbinden noch da sind – und die zuletzt bestätigte Position je Welt
 * („An gespeicherter Position“). Datei {@code config/trsclient/circuits.json}, nur lokal.
 */
public final class CircuitStore {
	/** Höchstens so viele Welten merken (älteste zuerst vergessen). */
	static final int MAX_WORLDS = 200;

	/** Eine Vorlage in einer Welt. */
	public static final class Entry {
		public String circuit;
		public int x;
		public int y;
		public int z;
		public int rotation;
		public boolean mirror;
		public int layer = -1;
		public boolean hidden;
	}

	private final Path file;
	private final Map<String, Entry> active = new LinkedHashMap<String, Entry>();
	private final Map<String, Entry> anchors = new LinkedHashMap<String, Entry>();

	public CircuitStore(Path file) {
		this.file = file;
	}

	public void load() {
		active.clear();
		anchors.clear();
		if (file == null || !Files.isRegularFile(file)) return;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject root = new JsonParser().parse(r).getAsJsonObject();
			read(root.getAsJsonObject("worlds"), active);
			read(root.getAsJsonObject("anchors"), anchors);
		} catch (IOException | RuntimeException e) {
			active.clear();
			anchors.clear();
		}
	}

	private static void read(JsonObject o, Map<String, Entry> into) {
		if (o == null) return;
		Gson gson = new Gson();
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			if (into.size() >= MAX_WORLDS) break;
			try {
				Entry entry = gson.fromJson(e.getValue(), Entry.class);
				if (entry != null && e.getKey().length() <= 300) into.put(e.getKey(), sanitize(entry));
			} catch (RuntimeException ignored) {
				// kaputten Eintrag überspringen
			}
		}
	}

	private static Entry sanitize(Entry e) {
		e.rotation = ((e.rotation % 4) + 4) % 4;
		if (e.layer < -1 || e.layer >= Circuit.MAX_SIZE) e.layer = -1;
		return e;
	}

	public void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			root.add("worlds", gson.toJsonTree(active));
			root.add("anchors", gson.toJsonTree(anchors));
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				gson.toJson(root, w);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException ignored) {
			// nur Komfort – beim nächsten Mal erneut
		}
	}

	public Entry active(String world) {
		return world == null ? null : active.get(world);
	}

	public void setActive(String world, Entry entry) {
		if (world == null) return;
		active.remove(world);
		if (entry != null) {
			active.put(world, entry);
			trim(active);
		}
	}

	public Entry anchor(String world) {
		return world == null ? null : anchors.get(world);
	}

	public void setAnchor(String world, Entry entry) {
		if (world == null || entry == null) return;
		anchors.remove(world);
		anchors.put(world, entry);
		trim(anchors);
	}

	private static void trim(Map<String, Entry> map) {
		while (map.size() > MAX_WORLDS) map.remove(map.keySet().iterator().next());
	}
}
