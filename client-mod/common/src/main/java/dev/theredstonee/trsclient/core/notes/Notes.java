package dev.theredstonee.trsclient.core.notes;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MapPlatform;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Notizen je Welt – der Dienst des laufenden Spiels: Notizbücher ({@link NotesStore}), aktuelle Welt und Position
 * (über die {@link MapPlatform} der Karte, die es in jeder Version gibt), Koordinaten auf der Weltkarte zeigen bzw.
 * als (vorübergehenden) Wegpunkt setzen. Die Bäume rufen nur {@link #init}, {@link #tick} und öffnen das Menü.
 *
 * <p>Alles aus dem Spiel-Thread.
 */
public final class Notes {
	/** Vorübergehender Wegpunkt verschwindet in diesem Abstand (Blöcke). */
	static final double REACHED = 4.0;
	/** Farbe der Notiz-Wegpunkte (Lapis). */
	public static final int COLOR = 0x3D7BFF;
	/** Nach so langer Ruhe wird gespeichert. */
	static final long SAVE_DELAY_MS = 1500L;

	private static volatile Notes instance;
	private static volatile boolean openRequested;

	private final TrsModules modules;
	private final NotesStore store;
	private final Consumer<String> log;
	private Waypoint temporary;
	private String temporaryWorld;
	private long lastSaveError;
	private long nextSaveAt;

	Notes(TrsModules modules, NotesStore store, Consumer<String> log) {
		this.modules = modules;
		this.store = store;
		this.log = log != null ? log : new Consumer<String>() {
			@Override
			public void accept(String s) {
			}
		};
	}

	/** Beim Start: Notizen aus {@code <configDir>/trsclient/notes} laden. */
	public static Notes init(TrsModules modules, Path configDir, Consumer<String> log) {
		Path dir = configDir == null ? null : configDir.resolve("trsclient").resolve("notes");
		Notes n = new Notes(modules, new NotesStore(dir).load(System.currentTimeMillis()), log);
		instance = n;
		ModulePanel.Registry.set(modules.notes.notes, new NotesModulePanel());
		return n;
	}

	/** Für Tests: Dienst mit eigenem Speicher. */
	public static Notes forTest(TrsModules modules, NotesStore store) {
		Notes n = new Notes(modules, store, null);
		instance = n;
		return n;
	}

	/** Der Dienst des laufenden Spiels oder null (vor dem Start). */
	public static Notes get() {
		return instance;
	}

	public NotesStore store() {
		return store;
	}

	public TrsModules modules() {
		return modules;
	}

	// --- Menü öffnen (Taste, Knopf auf der Modulseite) ---

	/** Beim nächsten Zeichnen des TRS-Menüs die Notizen zeigen. */
	public static void requestOpen() {
		openRequested = true;
	}

	public static boolean takeOpenRequest() {
		boolean r = openRequested;
		openRequested = false;
		return r;
	}

	// --- Welt ---

	private static MapPlatform platform() {
		MapEngine e = MapEngine.get();
		return e == null ? null : e.platform();
	}

	public boolean inWorld() {
		MapPlatform p = platform();
		return p != null && p.inWorld();
	}

	/** Welt/Server, in dem der Spieler gerade ist (null = keine Welt). */
	public NoteWorld currentWorld() {
		MapPlatform p = platform();
		if (p == null || !p.inWorld()) return null;
		return NoteWorld.fromWaypointKey(p.waypointWorldKey());
	}

	/** Notizbuch der aktuellen Welt (null = keine Welt). */
	public NoteBook currentBook() {
		return store.book(currentWorld());
	}

	/** Blockposition des Spielers {x, y, z} oder null. */
	public int[] position() {
		MapPlatform p = platform();
		if (p == null || !p.inWorld()) return null;
		return new int[] {floor(p.x()), floor(p.y()), floor(p.z())};
	}

	// --- Koordinaten ---

	/** Ergebnis einer Koordinaten-Aktion: Meldung (übersetzt) und ob es geklappt hat. */
	public static final class Outcome {
		public final boolean ok;
		public final String message;

		Outcome(boolean ok, String message) {
			this.ok = ok;
			this.message = message;
		}
	}

	private Outcome check(NoteWorld world) {
		MapPlatform p = platform();
		if (p == null || !p.inWorld()) return new Outcome(false, I18n.tr("notes.coord.noWorld"));
		NoteWorld here = NoteWorld.fromWaypointKey(p.waypointWorldKey());
		if (here == null || world == null || !here.equals(world)) {
			return new Outcome(false, I18n.tr("notes.coord.otherWorld", world == null ? "?" : world.label()));
		}
		return null;
	}

	/** Weltkarte öffnen und auf die Stelle zentrieren (ersetzt das Menü). */
	public Outcome showOnMap(NoteWorld world, String name, int x, int z) {
		Outcome bad = check(world);
		if (bad != null) return bad;
		MapEngine e = MapEngine.get();
		if (e == null || !modules.worldMap.isEnabled()) return new Outcome(false, I18n.tr("waypoint.share.mapOff"));
		WorldMapUi.requestFocus(name(name), x, z, COLOR);
		MapPlatform p = platform();
		if (p != null && p.openWorldMap()) return new Outcome(true, null);
		return new Outcome(false, I18n.tr("waypoint.share.unsupported"));
	}

	/**
	 * Vorübergehender Wegpunkt (Lichtsäule, Minimap, Weltkarte) – wird nie gespeichert, ersetzt den vorherigen und
	 * verschwindet beim Erreichen.
	 */
	public Outcome temporaryWaypoint(NoteWorld world, String name, int x, int y, int z) {
		Outcome bad = check(world);
		if (bad != null) return bad;
		MapPlatform p = platform();
		WaypointStore wps = p.waypoints();
		String key = p.waypointWorldKey();
		if (wps == null || key == null || key.isEmpty()) return new Outcome(false, I18n.tr("waypoint.share.unsupported"));
		clearTemporary(p);
		Waypoint w = new Waypoint(name(name), x, y, z, p.dimension(), COLOR);
		w.temporary = true;
		wps.add(key, w);
		temporary = w;
		temporaryWorld = key;
		return new Outcome(true, I18n.tr("notes.coord.temporary", w.name));
	}

	/** Als dauerhaften Wegpunkt der aktuellen Welt und Dimension speichern. */
	public Outcome saveWaypoint(NoteWorld world, String name, int x, int y, int z) {
		Outcome bad = check(world);
		if (bad != null) return bad;
		MapPlatform p = platform();
		WaypointStore wps = p.waypoints();
		String key = p.waypointWorldKey();
		if (wps == null || key == null || key.isEmpty()) return new Outcome(false, I18n.tr("waypoint.share.unsupported"));
		Waypoint w = new Waypoint(name(name), x, y, z, p.dimension(), COLOR);
		for (Waypoint e : wps.all(key)) {
			if (!e.temporary && e.x == x && e.y == y && e.z == z && e.name.equals(w.name)) {
				return new Outcome(false, I18n.tr("waypoint.share.already", w.name));
			}
		}
		wps.add(key, w);
		p.waypointsChanged();
		return new Outcome(true, I18n.tr("waypoint.share.added", w.name));
	}

	/** Name des Wegpunkts: Titel der Notiz (höchstens 32 Zeichen), sonst „Notiz“. */
	static String name(String title) {
		String t = title == null ? "" : Note.clean(title, 32, false).trim();
		return t.isEmpty() ? I18n.tr("notes.waypointName") : t;
	}

	/** Gerade gesetzter vorübergehender Wegpunkt (Selbsttest) oder null. */
	public Waypoint temporary() {
		return temporary;
	}

	private void clearTemporary(MapPlatform p) {
		if (temporary == null) return;
		WaypointStore wps = p == null ? null : p.waypoints();
		if (wps != null && temporaryWorld != null) wps.remove(temporaryWorld, temporary);
		temporary = null;
		temporaryWorld = null;
	}

	// --- Tick ---

	/** Einmal je Client-Tick: vorübergehenden Wegpunkt aufräumen, Änderungen verzögert speichern. */
	public void tick(long now) {
		MapPlatform p = platform();
		if (temporary != null) {
			boolean gone = p == null || !p.inWorld() || !temporaryWorld.equals(p.waypointWorldKey());
			if (!gone && temporary.inDimension(p.dimension())) {
				double dx = temporary.x + 0.5 - p.x(), dz = temporary.z + 0.5 - p.z();
				if (Math.sqrt(dx * dx + dz * dz) <= REACHED && Math.abs(temporary.y - p.y()) < 12) {
					p.message(I18n.tr("notes.coord.reached", temporary.name));
					gone = true;
				}
			}
			if (gone) clearTemporary(p);
		}
		if (store.dirty() && now - store.changedAt() >= SAVE_DELAY_MS && now >= nextSaveAt) save(now);
	}

	/** Jetzt speichern (Menü geschlossen, Spiel beendet). */
	public void save() {
		save(System.currentTimeMillis());
	}

	private void save(long now) {
		String err = store.saveQuietly();
		// Nach einem Fehler (Platte voll, Datei gesperrt) nicht jeden Tick neu versuchen.
		nextSaveAt = err == null ? 0 : now + 10_000L;
		if (err != null && now - lastSaveError > 60_000L) {
			lastSaveError = now;
			log.accept("TRS-Notizen: nicht gespeichert: " + err);
		}
	}

	private static int floor(double v) {
		return (int) Math.floor(v);
	}
}
