package dev.theredstonee.trsclient.core.waypoint;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wegpunkte je Welt/Server in {@code config/trsclient-waypoints.json}.
 * Schlüssel ist die Welt ({@code sp:<Weltordner>} bzw. {@code mp:<adresse>}),
 * die Dimension steht am Wegpunkt – so bleiben Nether-Punkte in der Oberwelt unsichtbar.
 *
 * <p>Sets und das aktuelle Ziel je Welt stehen in einer zweiten Datei daneben ({@code trsclient-waypoint-sets.json}):
 * Das Format der Wegpunkt-Datei bleibt dadurch dasselbe. Alte Dateien laden ohne Änderung, ihre Wegpunkte gehören dann
 * zum Standard-Set, und ältere TRS-Client-Versionen können die Datei weiter lesen.
 */
public final class WaypointStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Type TYPE = new TypeToken<Map<String, List<Waypoint>>>() {
	}.getType();
	private static final Type META_TYPE = new TypeToken<Map<String, WorldMeta>>() {
	}.getType();
	/** Sicherheitsgrenze pro Welt. */
	public static final int MAX_PER_WORLD = 200;
	/** Standard-Set: alles ohne eigene Zuordnung (auch alle alten Wegpunkte). */
	public static final String SET_DEFAULT = "default";
	/** Festes Set der automatischen Todespunkte. */
	public static final String SET_DEATHS = "deaths";
	/** Höchstens so viele eigene Sets je Welt. */
	public static final int MAX_SETS = 32;
	/** Längster Set-Name. */
	public static final int MAX_SET_NAME = 24;
	/** Grenzen für „Todespunkte behalten“. */
	public static final int MIN_DEATHS = 1;
	public static final int MAX_DEATHS = 20;

	/** Sets und Ziel einer Welt (Gson-DTO). */
	private static final class WorldMeta {
		List<WaypointSet> sets = new ArrayList<WaypointSet>();
		/** Kennung des aktuellen Ziels, null = keins. */
		String target;
		/** Ziel automatisch gesetzt (Weg zurück zum Todespunkt)? */
		boolean targetAuto;
		/** Zuletzt gewähltes Set für neue Wegpunkte. */
		String lastSet;
		/** Ausgeblendete Sets (nicht gespeichert – steht an den Sets). */
		transient Set<String> hidden;
	}

	private final Path file;
	private final Path metaFile;
	private final Map<String, List<Waypoint>> worlds = new LinkedHashMap<>();
	private final Map<String, WorldMeta> metas = new LinkedHashMap<>();
	private boolean dirty;

	public WaypointStore(Path file) {
		this.file = file;
		Path name = file.getFileName();
		String base = name == null ? "waypoints.json" : name.toString();
		String side = base.endsWith("waypoints.json") ? base.substring(0, base.length() - "waypoints.json".length()) + "waypoint-sets.json"
				: base + ".sets.json";
		this.metaFile = file.resolveSibling(side);
	}

	public Path file() {
		return file;
	}

	/** Datei mit Sets und Zielen. */
	public Path metaFile() {
		return metaFile;
	}

	/** Weltschlüssel für einen Server. */
	public static String serverKey(String address) {
		return "mp:" + (address == null ? "?" : address.toLowerCase(java.util.Locale.ROOT));
	}

	/** Weltschlüssel für eine Einzelspielerwelt. */
	public static String singleplayerKey(String levelId) {
		return "sp:" + (levelId == null ? "?" : levelId);
	}

	/** Lädt die Datei; fehlt oder ist sie kaputt, wird mit einer leeren Liste weitergemacht. */
	public void load() {
		worlds.clear();
		metas.clear();
		loadMeta();
		if (!Files.exists(file)) return;
		boolean repaired = false;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Map<String, List<Waypoint>> data = GSON.fromJson(reader, TYPE);
			if (data != null) {
				for (Map.Entry<String, List<Waypoint>> e : data.entrySet()) {
					if (e.getKey() == null || e.getValue() == null) continue;
					List<Waypoint> list = new ArrayList<>();
					java.util.Set<String> ids = new java.util.HashSet<>();
					for (Waypoint w : e.getValue()) {
						if (w == null) continue;
						// Alte Dateien (vor dem Live-Teilen) haben keine Kennung; doppelte gibt es nach Handarbeit.
						if (!Waypoint.validId(w.id) || !ids.add(w.id)) {
							w.id = null;
							repaired = true;
						}
						list.add(w.normalized());
						ids.add(w.id);
					}
					worlds.put(e.getKey(), list);
					adoptUnknownSets(e.getKey(), list);
				}
			}
		} catch (JsonParseException | IllegalStateException | ClassCastException | IOException e) {
			worlds.clear();
			return;
		}
		if (repaired) {
			// Neue Kennungen gleich festhalten – sonst bekäme ein geteilter Wegpunkt bei jedem Start eine andere.
			dirty = true;
			try {
				save();
			} catch (IOException ignored) {
				// beim nächsten Speichern
			}
		}
	}

	private void loadMeta() {
		if (!Files.exists(metaFile)) return;
		try (Reader reader = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) {
			Map<String, WorldMeta> data = GSON.fromJson(reader, META_TYPE);
			if (data == null) return;
			for (Map.Entry<String, WorldMeta> e : data.entrySet()) {
				if (e.getKey() == null || e.getValue() == null) continue;
				WorldMeta m = e.getValue();
				List<WaypointSet> clean = new ArrayList<WaypointSet>();
				Set<String> seen = new HashSet<String>();
				if (m.sets != null) {
					for (WaypointSet s : m.sets) {
						if (s == null || !Waypoint.validId(s.id) || !seen.add(s.id)) continue;
						if (clean.size() >= MAX_SETS) break;
						clean.add(s);
					}
				}
				m.sets = clean;
				metas.put(e.getKey(), m);
			}
		} catch (JsonParseException | IllegalStateException | ClassCastException | IOException e) {
			// Eine kaputte Set-Datei kostet nur die Sets – die Wegpunkte selbst stehen in der anderen Datei.
			metas.clear();
		}
	}

	/** Wegpunkte mit unbekanntem Set (Datei von Hand bearbeitet, Set-Datei verloren) behalten ihre Gruppe. */
	private void adoptUnknownSets(String worldKey, List<Waypoint> list) {
		for (Waypoint w : list) {
			if (w.set == null || w.set.isEmpty() || SET_DEFAULT.equals(w.set) || SET_DEATHS.equals(w.set)) continue;
			if (!Waypoint.validId(w.set)) {
				w.set = "";
				continue;
			}
			WorldMeta m = meta(worldKey);
			if (find(m, w.set) == null && m.sets.size() < MAX_SETS) m.sets.add(new WaypointSet(w.set, w.set));
		}
	}

	private WorldMeta meta(String worldKey) {
		WorldMeta m = metas.get(worldKey);
		if (m == null) {
			m = new WorldMeta();
			metas.put(worldKey, m);
		}
		return m;
	}

	private static WaypointSet find(WorldMeta m, String id) {
		if (m == null || id == null) return null;
		for (WaypointSet s : m.sets) if (id.equals(s.id)) return s;
		return null;
	}

	/** Schreibt die Datei atomar (nur wenn etwas geändert wurde). */
	public void saveIfDirty() throws IOException {
		if (!dirty) return;
		save();
	}

	public void save() throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		if (dir != null) Files.createDirectories(dir);
		// Vorübergehende Wegpunkte (Notiz-Koordinaten) bleiben nur im Speicher.
		Map<String, List<Waypoint>> kept = new LinkedHashMap<>();
		for (Map.Entry<String, List<Waypoint>> e : worlds.entrySet()) {
			List<Waypoint> list = new ArrayList<>();
			for (Waypoint w : e.getValue()) {
				if (!w.temporary) list.add(w);
			}
			if (!list.isEmpty() || e.getValue().isEmpty()) kept.put(e.getKey(), list);
		}
		write(file, kept, TYPE);
		if (metas.isEmpty()) {
			Files.deleteIfExists(metaFile);
		} else {
			write(metaFile, metas, META_TYPE);
		}
		dirty = false;
	}

	private static void write(Path target, Object data, Type type) throws IOException {
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(data, type, writer);
		}
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Alle Wegpunkte einer Welt (unveränderliche Sicht). */
	public List<Waypoint> all(String worldKey) {
		List<Waypoint> list = worlds.get(worldKey);
		return list == null ? Collections.<Waypoint>emptyList() : Collections.unmodifiableList(list);
	}

	/** Sichtbare Wegpunkte einer Welt in dieser Dimension (eigener Schalter und Schalter des Sets). */
	public List<Waypoint> visible(String worldKey, String dimension) {
		List<Waypoint> out = new ArrayList<>();
		for (Waypoint w : all(worldKey)) {
			if (shown(worldKey, w) && w.inDimension(dimension)) out.add(w);
		}
		return out;
	}

	/** Wird der Wegpunkt angezeigt (eingeblendet und sein Set auch)? */
	public boolean shown(String worldKey, Waypoint w) {
		return w != null && w.visible && setVisible(worldKey, w.setId());
	}

	/** Fügt einen Wegpunkt hinzu (ältester fällt raus, wenn die Grenze erreicht ist; Todespunkte zuerst). */
	public Waypoint add(String worldKey, Waypoint waypoint) {
		List<Waypoint> list = worlds.get(worldKey);
		if (list == null) {
			list = new ArrayList<>();
			worlds.put(worldKey, list);
		}
		if (waypoint.created == 0) waypoint.created = System.currentTimeMillis();
		list.add(waypoint.normalized());
		while (list.size() > MAX_PER_WORLD) removeOldest(worldKey, list);
		dirty = true;
		return waypoint;
	}

	private void removeOldest(String worldKey, List<Waypoint> list) {
		int victim = 0;
		for (int i = 0; i < list.size() - 1; i++) {
			if (list.get(i).death) {
				victim = i;
				break;
			}
		}
		clearTargetIf(worldKey, list.remove(victim));
	}

	/** Legt einen Todespunkt an und entfernt den vorherigen (nur der letzte Tod zählt). */
	public Waypoint setDeath(String worldKey, int x, int y, int z, String dimension, int color) {
		return addDeath(worldKey, x, y, z, dimension, color, 1);
	}

	/**
	 * Legt einen Todespunkt an und behält insgesamt {@code keep} (die ältesten fallen weg).
	 *
	 * @return der neue Todespunkt
	 */
	public Waypoint addDeath(String worldKey, int x, int y, int z, String dimension, int color, int keep) {
		int limit = Math.max(MIN_DEATHS, Math.min(MAX_DEATHS, keep));
		List<Waypoint> list = worlds.get(worldKey);
		if (list != null) {
			int count = 0;
			for (Waypoint w : list) if (w.death) count++;
			for (int i = 0; i < list.size() && count >= limit; ) {
				if (list.get(i).death) {
					clearTargetIf(worldKey, list.remove(i));
					count--;
				} else {
					i++;
				}
			}
		}
		Waypoint death = new Waypoint(dev.theredstonee.trsclient.core.i18n.I18n.tr("waypoint.deathName"), x, y, z, dimension, color);
		death.death = true;
		return add(worldKey, death);
	}

	/** Der jüngste Todespunkt der Welt oder null. */
	public Waypoint lastDeath(String worldKey) {
		List<Waypoint> list = all(worldKey);
		for (int i = list.size() - 1; i >= 0; i--) {
			if (list.get(i).death) return list.get(i);
		}
		return null;
	}

	public boolean remove(String worldKey, Waypoint waypoint) {
		List<Waypoint> list = worlds.get(worldKey);
		if (list == null || !list.remove(waypoint)) return false;
		clearTargetIf(worldKey, waypoint);
		dirty = true;
		return true;
	}

	/** Wegpunkt mit dieser Kennung in der Welt oder null. */
	public Waypoint byId(String worldKey, String id) {
		if (id == null) return null;
		for (Waypoint w : all(worldKey)) {
			if (id.equals(w.id)) return w;
		}
		return null;
	}

	/** Liegen für die Welt überhaupt Wegpunkte vor (auch vorübergehende)? */
	public boolean hasWorld(String worldKey) {
		List<Waypoint> list = worlds.get(worldKey);
		return list != null && !list.isEmpty();
	}

	/** Meldet eine Änderung an einem Wegpunkt (Name/Farbe/sichtbar). */
	public void touch() {
		dirty = true;
	}

	public boolean dirty() {
		return dirty;
	}

	public int worldCount() {
		return worlds.size();
	}

	// --- Sets ---

	/** Sets der Welt: immer zuerst das Standard-Set, dann die eigenen, zuletzt die Todespunkte (sobald es welche gibt). */
	public List<WaypointSet> sets(String worldKey) {
		WorldMeta m = metas.get(worldKey);
		List<WaypointSet> out = new ArrayList<WaypointSet>();
		WaypointSet def = m == null ? null : find(m, SET_DEFAULT);
		out.add(def != null ? def : new WaypointSet(SET_DEFAULT, ""));
		if (m != null) {
			for (WaypointSet s : m.sets) {
				if (!s.builtIn()) out.add(s);
			}
		}
		WaypointSet deaths = m == null ? null : find(m, SET_DEATHS);
		boolean anyDeath = false;
		for (Waypoint w : all(worldKey)) {
			if (SET_DEATHS.equals(w.setId())) {
				anyDeath = true;
				break;
			}
		}
		if (deaths != null || anyDeath) out.add(deaths != null ? deaths : new WaypointSet(SET_DEATHS, ""));
		return out;
	}

	/** Eigenes Set anlegen; gibt es den Namen schon, das vorhandene. null = kein Name oder Grenze erreicht. */
	public WaypointSet addSet(String worldKey, String name) {
		String clean = cleanSetName(name);
		if (clean.isEmpty()) return null;
		for (WaypointSet s : sets(worldKey)) {
			if (clean.equalsIgnoreCase(s.label())) return s;
		}
		WorldMeta m = meta(worldKey);
		int own = 0;
		for (WaypointSet s : m.sets) if (!s.builtIn()) own++;
		if (own >= MAX_SETS - 2) return null;
		WaypointSet set = new WaypointSet(Waypoint.newId(), clean);
		m.sets.add(set);
		dirty = true;
		return set;
	}

	/** Eigenes Set umbenennen (feste Sets nicht). */
	public boolean renameSet(String worldKey, String id, String name) {
		String clean = cleanSetName(name);
		WaypointSet s = find(metas.get(worldKey), id);
		if (clean.isEmpty() || s == null || s.builtIn()) return false;
		s.name = clean;
		dirty = true;
		return true;
	}

	/** Eigenes Set löschen; seine Wegpunkte bleiben und wandern ins Standard-Set. */
	public boolean removeSet(String worldKey, String id) {
		WorldMeta m = metas.get(worldKey);
		WaypointSet s = find(m, id);
		if (s == null || s.builtIn()) return false;
		m.sets.remove(s);
		m.hidden = null;
		for (Waypoint w : all(worldKey)) {
			if (id.equals(w.set)) w.set = "";
		}
		if (id.equals(m.lastSet)) m.lastSet = null;
		dirty = true;
		return true;
	}

	/** Set angezeigt? (Unbekannte Sets gelten als sichtbar.) */
	public boolean setVisible(String worldKey, String id) {
		WorldMeta m = metas.get(worldKey);
		if (m == null || m.sets.isEmpty()) return true;
		if (m.hidden == null) {
			m.hidden = new HashSet<String>();
			for (WaypointSet s : m.sets) if (!s.visible) m.hidden.add(s.id);
		}
		return !m.hidden.contains(id);
	}

	/** Set ein-/ausblenden. */
	public void setSetVisible(String worldKey, String id, boolean visible) {
		if (id == null) return;
		WorldMeta m = meta(worldKey);
		WaypointSet s = find(m, id);
		if (s == null) {
			if (!SET_DEFAULT.equals(id) && !SET_DEATHS.equals(id)) return;
			s = new WaypointSet(id, "");
			m.sets.add(s);
		}
		s.visible = visible;
		m.hidden = null;
		dirty = true;
	}

	/** Set für den nächsten neuen Wegpunkt. */
	public String lastSet(String worldKey) {
		WorldMeta m = metas.get(worldKey);
		if (m == null || m.lastSet == null) return SET_DEFAULT;
		return SET_DEFAULT.equals(m.lastSet) || find(m, m.lastSet) != null ? m.lastSet : SET_DEFAULT;
	}

	public void rememberSet(String worldKey, String id) {
		if (id == null || id.equals(lastSet(worldKey))) return;
		meta(worldKey).lastSet = id;
		dirty = true;
	}

	/** Set-Kennung für das Feld {@link Waypoint#set}: das Standard-Set steht leer in der Datei. */
	public static String fieldValue(String setId) {
		return setId == null || SET_DEFAULT.equals(setId) ? "" : setId;
	}

	private static String cleanSetName(String name) {
		if (name == null) return "";
		String clean = name.replace((char) 0xA7, ' ').trim().replaceAll("\\s+", " ");
		return clean.length() > MAX_SET_NAME ? clean.substring(0, MAX_SET_NAME).trim() : clean;
	}

	// --- Ziel ---

	/** Das aktuelle Ziel der Welt oder null. */
	public Waypoint target(String worldKey) {
		WorldMeta m = metas.get(worldKey);
		return m == null ? null : byId(worldKey, m.target);
	}

	/** Ziel setzen (null = keins); {@code auto}: automatisch nach einem Tod gesetzt. */
	public void setTarget(String worldKey, Waypoint waypoint, boolean auto) {
		String id = waypoint == null ? null : waypoint.id;
		WorldMeta m = id == null ? metas.get(worldKey) : meta(worldKey);
		if (m == null) return;
		m.target = id;
		m.targetAuto = id != null && auto;
		dirty = true;
	}

	/** Wurde das aktuelle Ziel automatisch gesetzt? */
	public boolean targetAuto(String worldKey) {
		WorldMeta m = metas.get(worldKey);
		return m != null && m.target != null && m.targetAuto;
	}

	private void clearTargetIf(String worldKey, Waypoint removed) {
		WorldMeta m = metas.get(worldKey);
		if (m != null && removed != null && removed.id != null && removed.id.equals(m.target)) {
			m.target = null;
			m.targetAuto = false;
		}
	}
}
