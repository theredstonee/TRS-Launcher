package dev.theredstonee.trsclient.core.cinematic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kamera-Fahrten je Welt/Server und Dimension in {@code config/trsclient-cinematic.json}. Schlüssel wie bei den
 * Wegpunkten ({@code sp:<Weltordner>} bzw. {@code mp:<adresse>}) plus {@code |<dimension>} – eine Fahrt im Nether
 * bleibt im Nether.
 */
public final class CinematicStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Type TYPE = new TypeToken<LinkedHashMap<String, List<Point>>>() {
	}.getType();
	/** Höchstens so viele Keyframes je Fahrt. */
	public static final int MAX_KEYFRAMES = 64;
	/** Höchstens so viele Welten in der Datei (älteste fliegen raus). */
	public static final int MAX_WORLDS = 200;

	/** Gespeicherte Form eines Keyframes (Gson). */
	static final class Point {
		double x;
		double y;
		double z;
		float yaw;
		float pitch;
		float fov;
	}

	private final Path file;
	private final Map<String, List<Keyframe>> paths = new LinkedHashMap<>();
	private boolean dirty;

	public CinematicStore(Path file) {
		this.file = file;
	}

	/** Schlüssel einer Fahrt: Welt plus Dimension. */
	public static String key(String worldKey, String dimension) {
		return worldKey + "|" + (dimension == null ? "?" : dimension);
	}

	/** Lädt die Datei; fehlt oder ist sie kaputt, geht es mit leeren Fahrten weiter. */
	public void load() {
		paths.clear();
		if (file == null || !Files.exists(file)) return;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Map<String, List<Point>> data = GSON.fromJson(reader, TYPE);
			if (data == null) return;
			for (Map.Entry<String, List<Point>> e : data.entrySet()) {
				if (e.getKey() == null || e.getKey().length() > 300 || e.getValue() == null) continue;
				List<Keyframe> list = new ArrayList<>();
				for (Point p : e.getValue()) {
					if (p == null) continue;
					Keyframe k = new Keyframe(p.x, p.y, p.z, p.yaw, p.pitch, p.fov);
					if (k.valid() && list.size() < MAX_KEYFRAMES) list.add(k.normalized());
				}
				if (!list.isEmpty()) paths.put(e.getKey(), list);
			}
		} catch (JsonParseException | IllegalStateException | ClassCastException | IOException e) {
			paths.clear();
		}
	}

	public boolean dirty() {
		return dirty;
	}

	/** Schreibt die Datei atomar (nur wenn sich etwas geändert hat). */
	public void saveIfDirty() throws IOException {
		if (dirty) save();
	}

	public void save() throws IOException {
		if (file == null) {
			dirty = false;
			return;
		}
		Path dir = file.toAbsolutePath().getParent();
		if (dir != null) Files.createDirectories(dir);
		Map<String, List<Point>> data = new LinkedHashMap<>();
		for (Map.Entry<String, List<Keyframe>> e : paths.entrySet()) {
			List<Point> list = new ArrayList<>();
			for (Keyframe k : e.getValue()) {
				Point p = new Point();
				p.x = k.x;
				p.y = k.y;
				p.z = k.z;
				p.yaw = k.yaw;
				p.pitch = k.pitch;
				p.fov = k.fov;
				list.add(p);
			}
			data.put(e.getKey(), list);
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(data, TYPE, writer);
		}
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
		dirty = false;
	}

	/** Keyframes einer Fahrt (unveränderliche Sicht, leer = keine). */
	public List<Keyframe> get(String key) {
		List<Keyframe> list = paths.get(key);
		return list == null ? Collections.<Keyframe>emptyList() : Collections.unmodifiableList(list);
	}

	/** Keyframe anhängen; false = Grenze erreicht. */
	public boolean add(String key, Keyframe k) {
		List<Keyframe> list = paths.get(key);
		if (list == null) {
			list = new ArrayList<>();
			paths.put(key, list);
			trim(key);
		}
		if (list.size() >= MAX_KEYFRAMES) return false;
		list.add(k.normalized());
		dirty = true;
		return true;
	}

	/** Letzten Keyframe entfernen; false = es gab keinen. */
	public boolean removeLast(String key) {
		List<Keyframe> list = paths.get(key);
		if (list == null || list.isEmpty()) return false;
		list.remove(list.size() - 1);
		if (list.isEmpty()) paths.remove(key);
		dirty = true;
		return true;
	}

	/** Ganze Fahrt löschen; false = es gab keine. */
	public boolean clear(String key) {
		if (paths.remove(key) == null) return false;
		dirty = true;
		return true;
	}

	/** Zu viele Welten: die ältesten (zuerst angelegten) entfernen, nie {@code keep}. */
	private void trim(String keep) {
		Iterator<String> it = paths.keySet().iterator();
		while (paths.size() > MAX_WORLDS && it.hasNext()) {
			String k = it.next();
			if (!k.equals(keep)) it.remove();
		}
	}
}
