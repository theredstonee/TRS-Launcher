package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.Friends;
import dev.theredstonee.trsclient.core.online.FriendsView;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.online.Uuids;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Herz der Karten (Minimap + Weltkarte), für alle Minecraft-Versionen gleich: erkennt Welt/Dimension, tastet
 * geladene Chunks mit Zeitbudget je Tick ab (fehlende zuerst, von innen nach außen, danach Auffrischen in der
 * Nähe), wählt Oberfläche oder Höhlenschnitt, speichert erkundete Bereiche, merkt sich Spieler/Kreaturen je Tick
 * und liefert Position/Drehung flüssig zwischen den Ticks. Die Version liefert nur {@link MapPlatform}.
 */
public final class MapEngine {
	/** Höhlenschnitt in Schichten dieser Höhe. */
	static final int CAVE_BAND = 8;
	/** So viele Höhlenebenen bleiben geöffnet. */
	static final int MAX_CAVE_LAYERS = 4;
	/** Zeitbudget fürs Abtasten je Tick (ns). */
	static final long SAMPLE_BUDGET_NS = 600_000L;
	static final int MAX_ENTITIES = 256;

	private static MapEngine instance;

	private final TrsModules modules;
	private final MapDisk disk;
	private final MapTextures textures = new MapTextures();
	private final MapSprites sprites = new MapSprites();
	private final FairPlay fairPlay = new FairPlay();
	private final int[] chunkPixels = new int[256];
	private final int[] chunkHeights = new int[256];

	private String worldKey = "";
	private String dimension = "";
	private MapLayer surface;
	private final List<MapLayer> caves = new ArrayList<MapLayer>();
	private MapLayer cave;
	private int layerSerial;
	private final ChunkStamps surfaceStamps = new ChunkStamps();
	private final ChunkStamps caveStamps = new ChunkStamps();
	private boolean caveActive;
	private boolean underground;
	private int refreshCursor;
	private boolean motdChecked;

	// Spielerposition: letzter und aktueller Tick → flüssig dazwischen.
	private double prevX, prevZ, curX, curZ, curY;
	private long tickNanos;
	private boolean hasPos;

	// Kreaturen/Spieler (Momentaufnahme je Tick).
	private final List<MapEntity> pool = new ArrayList<MapEntity>();
	private int entityCount;
	private final MapPlatform.EntitySink sink = () -> {
		if (entityCount >= MAX_ENTITIES) return null;
		if (entityCount == pool.size()) pool.add(new MapEntity());
		return pool.get(entityCount++);
	};

	private String biome = "";
	private long dayTime = -1;
	private int slowTick;
	private long lastMaintain;
	private long lastLimit;
	private boolean worldMapOpen;
	private MapPlatform platform;

	// Messwerte (µs, gleitender Mittelwert).
	private float tickMicros;
	private float drawMicros;
	private int sampledTotal;

	// Freunde (Menge der UUIDs je Stand der Liste).
	private Object friendsView;
	private final Set<String> friendIds = new HashSet<String>();

	public MapEngine(TrsModules modules, Path configDir, boolean synchronousDisk) {
		this.modules = modules;
		Path root = configDir == null ? null : configDir.resolve("trsclient").resolve("maps");
		this.disk = root == null ? null : new MapDisk(root, synchronousDisk);
	}

	/** Einmal beim Start (je Loader). */
	public static MapEngine init(TrsModules modules, Path configDir) {
		if (instance == null) {
			instance = new MapEngine(modules, configDir, false);
			// Der eine Fair-Play-Schalter auf der Weltkarten-Seite (auf der Minimap-Seite ist er eine Einstellung).
			dev.theredstonee.trsclient.core.ui.menu.ModulePanel.Registry.set(modules.worldMap, new MapPanel(instance, true));
			dev.theredstonee.trsclient.core.ui.menu.ModulePanel.Registry.set(modules.minimap, new MapPanel(instance, false));
		}
		return instance;
	}

	/** null, solange nicht initialisiert. */
	public static MapEngine get() {
		return instance;
	}

	public TrsModules modules() {
		return modules;
	}

	public MapTextures textures() {
		return textures;
	}

	public MapSprites sprites() {
		return sprites;
	}

	public FairPlay fairPlay() {
		return fairPlay;
	}

	public MapPlatform platform() {
		return platform;
	}

	/** Läuft die Karte überhaupt (Minimap oder Weltkarte an)? */
	public boolean enabled() {
		return modules.minimap.isEnabled() || modules.worldMap.isEnabled();
	}

	public boolean inWorld() {
		return surface != null && platform != null;
	}

	public String dimension() {
		return dimension;
	}

	public boolean nether() {
		return isNether(dimension);
	}

	/** Nether erkennen – Mojmap-Kennung oder Legacy-Nummer. */
	public static boolean isNether(String dim) {
		if (dim == null) return false;
		String d = dim.toLowerCase(Locale.ROOT);
		return d.contains("nether") || d.equals("dim-1") || d.equals("-1");
	}

	/** End erkennen (dort kein Himmelslicht). */
	public static boolean isEnd(String dim) {
		if (dim == null) return false;
		String d = dim.toLowerCase(Locale.ROOT);
		return d.contains("the_end") || d.endsWith(":end") || d.equals("dim1");
	}

	/** Ebene, die die Minimap gerade zeigt. */
	public MapLayer viewLayer() {
		return caveActive && cave != null ? cave : surface;
	}

	public MapLayer surfaceLayer() {
		return surface;
	}

	public MapLayer caveLayer() {
		return caveActive ? cave : null;
	}

	public boolean caveActive() {
		return caveActive && cave != null;
	}

	public boolean underground() {
		return underground;
	}

	public void setWorldMapOpen(boolean open) {
		worldMapOpen = open;
	}

	public String biome() {
		return biome;
	}

	public long dayTime() {
		return dayTime;
	}

	public float tickMicros() {
		return tickMicros;
	}

	public float drawMicros() {
		return drawMicros;
	}

	public int sampledTotal() {
		return sampledTotal;
	}

	/** Zeichenzeit der Minimap mitteln (vom Renderer). */
	public void recordDraw(long nanos) {
		float us = nanos / 1000f;
		drawMicros = drawMicros == 0 ? us : drawMicros + (us - drawMicros) * 0.05f;
	}

	// --- Spielerposition flüssig ---

	/** Anteil des laufenden Ticks (0..1) nach Uhrzeit. */
	public float alpha() {
		if (!hasPos) return 1f;
		float a = (System.nanoTime() - tickNanos) / 50_000_000f;
		return a < 0f ? 0f : (a > 1f ? 1f : a);
	}

	public double playerX() {
		return prevX + (curX - prevX) * alpha();
	}

	public double playerZ() {
		return prevZ + (curZ - prevZ) * alpha();
	}

	public double playerY() {
		return curY;
	}

	/** Blickrichtung (je Bild aktuell). */
	public float yaw() {
		return platform == null ? 0f : platform.yaw();
	}

	// --- Kreaturen ---

	public int entityCount() {
		return entityCount;
	}

	public MapEntity entity(int i) {
		return pool.get(i);
	}

	/** Server-Text (Chat, MOTD) auf Fair-Play-Codes prüfen. */
	public void onServerText(String text) {
		fairPlay.onServerText(text);
	}

	// --- Wegpunkte ---

	/** Wegpunkte der aktuellen Welt und Dimension (alle, auch ausgeblendete). */
	public List<Waypoint> waypoints() {
		if (platform == null) return Collections.emptyList();
		WaypointStore store = platform.waypoints();
		String key = platform.waypointWorldKey();
		if (store == null || key == null || key.isEmpty()) return Collections.emptyList();
		List<Waypoint> all = store.all(key);
		List<Waypoint> out = new ArrayList<Waypoint>(all.size());
		for (Waypoint w : all) {
			if (w.inDimension(dimension)) out.add(w);
		}
		return out;
	}

	/** Neuer Wegpunkt (von der Karte). */
	public Waypoint addWaypoint(String name, int x, int y, int z, int color) {
		if (platform == null) return null;
		WaypointStore store = platform.waypoints();
		String key = platform.waypointWorldKey();
		if (store == null || key == null || key.isEmpty()) return null;
		Waypoint w = new Waypoint(name, x, y, z, dimension, color);
		store.add(key, w);
		platform.waypointsChanged();
		return w;
	}

	public void removeWaypoint(Waypoint w) {
		if (platform == null || w == null) return;
		WaypointStore store = platform.waypoints();
		if (store == null) return;
		store.remove(platform.waypointWorldKey(), w);
		platform.waypointsChanged();
	}

	/** Nach dem Bearbeiten eines Wegpunkts. */
	public void waypointEdited() {
		if (platform == null) return;
		WaypointStore store = platform.waypoints();
		if (store != null) store.touch();
		platform.waypointsChanged();
	}

	/** Höhe an einer Blockposition aus den Kartendaten (Oberfläche), {@link Integer#MIN_VALUE} = unbekannt. */
	public int heightAt(MapLayer layer, int bx, int bz) {
		if (layer == null) return Integer.MIN_VALUE;
		MapRegion r = layer.peek(bx >> MapRegion.SHIFT, bz >> MapRegion.SHIFT);
		if (r == null) return Integer.MIN_VALUE;
		int lx = bx & (MapRegion.SIZE - 1), lz = bz & (MapRegion.SIZE - 1);
		if (!MapColors.known(r.pixel(lx, lz))) return Integer.MIN_VALUE;
		return r.height(lx, lz);
	}

	// --- Freunde ---

	/** Ist die UUID ein TRS-Freund? (Liste im Hintergrund; ohne Anmeldung nie.) */
	public boolean isFriend(UUID uuid) {
		if (uuid == null) return false;
		TrsOnline online = TrsOnline.current();
		if (online == null) return false;
		Friends friends = online.friends();
		if (friends == null) return false;
		friends.want(Friends.Interest.BACKGROUND, false);
		Friends.Snapshot snap = friends.snapshot();
		FriendsView view = snap == null ? null : snap.view;
		if (view != friendsView) {
			friendsView = view;
			friendIds.clear();
			if (view != null && view.friends != null) {
				for (FriendsView.Friend f : view.friends) {
					if (f.uuid != null) friendIds.add(f.uuid.toLowerCase(Locale.ROOT));
				}
			}
		}
		return !friendIds.isEmpty() && friendIds.contains(Uuids.of(uuid));
	}

	// --- Tick ---

	/** Einmal je Client-Tick (Ende). */
	public void tick(MapPlatform p) {
		long t0 = System.nanoTime();
		this.platform = p;
		if (disk != null) disk.drain();
		fairPlay.setManual(modules.minimapFairPlay.get());
		if (!enabled() || p == null || !p.inWorld()) {
			if (surface != null) leaveWorld();
			return;
		}
		String wk = p.worldKey();
		if (wk == null || wk.isEmpty()) return;
		if (!wk.equals(worldKey)) {
			leaveWorld();
			worldKey = wk;
			fairPlay.resetServer();
			motdChecked = false;
		}
		String dim = p.dimension() == null ? "?" : p.dimension();
		if (surface == null || !dim.equals(dimension)) openDimension(dim);
		if (!motdChecked) {
			motdChecked = true;
			fairPlay.onServerText(p.serverMotd());
		}
		long now = System.currentTimeMillis();

		// Position (für flüssige Bewegung zwischen den Ticks).
		double x = p.x(), z = p.z();
		if (!hasPos || Math.abs(x - curX) > 64 || Math.abs(z - curZ) > 64) {
			prevX = x;
			prevZ = z;
		} else {
			prevX = curX;
			prevZ = curZ;
		}
		curX = x;
		curZ = z;
		curY = p.y();
		tickNanos = System.nanoTime();
		hasPos = true;

		// Oberfläche oder Höhle?
		boolean nether = isNether(dim);
		int sky = p.skyLight();
		if (isEnd(dim)) {
			// Das End hat kein Himmelslicht – dort gibt es keine Höhlen im Sinne der Karte.
			underground = false;
		} else if (underground) {
			if (sky >= 8) underground = false;
		} else if (sky <= 2) {
			underground = true;
		}
		boolean want = modules.minimapCaveMode.get() == TrsModules.CaveMode.AUTO && (nether || underground);
		caveActive = want && fairPlay.caveAllowed(nether);
		if (caveActive) selectCave((int) Math.floor(curY));

		// Abtasten mit Zeitbudget: Höhle zuerst (die sieht man gerade), dann Oberfläche.
		ChunkReader reader = p.reader();
		if (reader != null) {
			long deadline = System.nanoTime() + SAMPLE_BUDGET_NS;
			int radius = Math.max(2, Math.min(12, p.renderDistance()));
			try {
				if (caveActive && cave != null) {
					sample(reader, cave, caveStamps, true, cave.caveRef, Math.min(radius, 8), deadline, now);
				}
				sample(reader, surface, surfaceStamps, false, 0, radius, deadline, now);
			} catch (RuntimeException e) {
				// Ein Chunk im Umbau darf die Karte nicht anhalten.
			}
		}

		// Spieler/Kreaturen.
		entityCount = 0;
		if (wantsEntities()) {
			try {
				p.entities(sink, 192, !fairPlay.radarThroughWalls());
			} catch (RuntimeException e) {
				entityCount = 0;
			}
			for (int i = 0; i < entityCount; i++) {
				MapEntity e = pool.get(i);
				e.friend = e.type == MapEntity.PLAYER && isFriend(e.uuid);
			}
		}

		// Seltenes: Biom, Zeit, Speichern, Aufräumen.
		if (++slowTick >= 10) {
			slowTick = 0;
			try {
				biome = p.biome();
				dayTime = p.dayTime();
			} catch (RuntimeException e) {
				biome = "";
			}
		}
		if (now - lastMaintain >= 1000) {
			lastMaintain = now;
			surface.maintain(now, 20_000, MapCompose.INSTANCE, 2);
			for (MapLayer l : caves) l.maintain(now, 20_000, MapCompose.INSTANCE, 1);
			textures.trim(now);
		}
		if (disk != null && now - lastLimit >= 300_000) {
			lastLimit = now;
			List<Path> keep = new ArrayList<Path>();
			if (surface.dir() != null) keep.add(surface.dir());
			for (MapLayer l : caves) if (l.dir() != null) keep.add(l.dir());
			disk.enforceLimit((long) modules.worldMapCache.get() * 1024L * 1024L, keep);
		}
		float us = (System.nanoTime() - t0) / 1000f;
		tickMicros = tickMicros == 0 ? us : tickMicros + (us - tickMicros) * 0.05f;
	}

	private boolean wantsEntities() {
		boolean mini = modules.minimap.isEnabled()
				&& (modules.minimapPlayers.get() || modules.minimapHostile.get() || modules.minimapPassive.get());
		boolean world = worldMapOpen
				&& (modules.worldMapPlayers.get() || modules.worldMapHostile.get() || modules.worldMapPassive.get());
		return mini || world;
	}

	private void openDimension(String dim) {
		closeLayers();
		dimension = dim;
		Path dir = disk == null ? null : disk.layerDir(worldKey, dim, "surface");
		surface = new MapLayer("surface", ++layerSerial, Integer.MIN_VALUE, disk, dir);
		surfaceStamps.clear();
		caveStamps.clear();
		refreshCursor = 0;
		hasPos = false;
		lastLimit = System.currentTimeMillis() - 290_000; // Grenze kurz nach dem Betreten prüfen
	}

	private void selectCave(int y) {
		int band = Math.floorDiv(y, CAVE_BAND);
		String id = "cave" + band;
		if (cave != null && cave.id.equals(id)) return;
		MapLayer found = null;
		for (MapLayer l : caves) {
			if (l.id.equals(id)) found = l;
		}
		if (found == null) {
			// Startebene knapp über dem Kopf: Band-Oberkante + 3.
			int yStart = band * CAVE_BAND + CAVE_BAND + 3;
			Path dir = disk == null ? null : disk.layerDir(worldKey, dimension, id);
			found = new MapLayer(id, ++layerSerial, yStart, disk, dir);
			found.setMaxRegions(96);
			caves.add(found);
			while (caves.size() > MAX_CAVE_LAYERS) {
				MapLayer old = caves.remove(0);
				if (old == cave) continue;
				textures.releaseLayer(old);
				old.close(MapCompose.INSTANCE);
			}
		} else {
			caves.remove(found);
			caves.add(found);
		}
		cave = found;
		caveStamps.clear();
	}

	/**
	 * Tastet Chunks rund um den Spieler ab: erst fehlende (Ringe von innen nach außen), dann das Auffrischen
	 * (nahe Chunks alle 2 s, weitere alle 30 s) – bis das Zeitbudget verbraucht ist.
	 */
	private void sample(ChunkReader r, MapLayer layer, ChunkStamps stamps, boolean caveMode, int yStart, int radius,
			long deadline, long now) {
		int pcx = (int) Math.floor(curX) >> 4;
		int pcz = (int) Math.floor(curZ) >> 4;
		for (int ring = 0; ring <= radius; ring++) {
			for (int dz = -ring; dz <= ring; dz++) {
				boolean edgeRow = dz == -ring || dz == ring;
				for (int dx = -ring; dx <= ring; dx += edgeRow ? 1 : 2 * ring) {
					int cx = pcx + dx, cz = pcz + dz;
					long key = ChunkStamps.key(cx, cz);
					if (stamps.get(key) >= 0) continue;
					if (!r.isLoaded(cx, cz)) continue;
					scan(r, layer, stamps, caveMode, yStart, cx, cz, key, now);
					if (System.nanoTime() > deadline) return;
				}
			}
		}
		// Auffrischen: reihum durch das Quadrat, nahe Chunks häufiger.
		int side = 2 * radius + 1;
		int total = side * side;
		for (int n = 0; n < 24 && System.nanoTime() < deadline; n++) {
			refreshCursor = (refreshCursor + 1) % total;
			int dx = refreshCursor % side - radius;
			int dz = refreshCursor / side - radius;
			int cx = pcx + dx, cz = pcz + dz;
			long key = ChunkStamps.key(cx, cz);
			long last = stamps.get(key);
			if (last < 0) continue;
			int dist = Math.max(Math.abs(dx), Math.abs(dz));
			long maxAge = dist <= 2 ? 2_000 : (dist <= 5 ? 8_000 : 30_000);
			if (now - last < maxAge || !r.isLoaded(cx, cz)) continue;
			scan(r, layer, stamps, caveMode, yStart, cx, cz, key, now);
		}
	}

	private void scan(ChunkReader r, MapLayer layer, ChunkStamps stamps, boolean caveMode, int yStart, int cx, int cz,
			long key, long now) {
		boolean ok = caveMode ? ColumnScanner.cave(r, cx, cz, yStart, chunkPixels, chunkHeights)
				: ColumnScanner.surface(r, cx, cz, chunkPixels, chunkHeights);
		stamps.put(key, now);
		if (!ok) return;
		layer.forWrite(cx >> 3, cz >> 3, now).writeChunk(cx, cz, chunkPixels, chunkHeights, now);
		sampledTotal++;
	}

	private void closeLayers() {
		if (surface != null) {
			textures.releaseLayer(surface);
			surface.close(MapCompose.INSTANCE);
		}
		for (MapLayer l : caves) {
			textures.releaseLayer(l);
			l.close(MapCompose.INSTANCE);
		}
		caves.clear();
		surface = null;
		cave = null;
		caveActive = false;
	}

	/** Welt verlassen: alles speichern, Texturen freigeben. */
	public void leaveWorld() {
		closeLayers();
		worldKey = "";
		dimension = "";
		hasPos = false;
		entityCount = 0;
		fairPlay.resetServer();
	}

	/** Spiel beendet: speichern und kurz warten, bis die Platte fertig ist. */
	public void shutdown() {
		leaveWorld();
		if (disk != null) disk.flush(3000);
	}

	/** Nur für Tests: Speicher leeren und Platte abarbeiten. */
	void drainForTests() {
		if (disk != null) disk.drain();
	}
}
