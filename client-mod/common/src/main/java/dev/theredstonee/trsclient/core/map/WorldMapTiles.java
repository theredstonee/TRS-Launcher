package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Zeichnet eine Kartenebene auf der Weltkarte in drei Stufen, je nach Zoom:
 * <ul>
 *   <li>nah (ab 1 Bildschirmpixel je Block): Bereichs-Texturen, ein Texel je Block – solange noch nicht alle da sind,
 *   liegt die Übersicht darunter (nie schwarze Löcher beim Schieben);</li>
 *   <li>mittel: Übersichtskacheln (8×8 Bereiche, ein Texel je 4 Blöcke);</li>
 *   <li>weit (unter ¼ Bildschirmpixel je Block): Weitkacheln (64×64 Bereiche, ein Texel je 32 Blöcke).</li>
 * </ul>
 * Alles wird von der Bildmitte nach außen angefragt: Hochlade-Budget und Ladeaufträge gehen zuerst an das, was man
 * ansieht ({@link MapDisk#nextFrame()} sorgt dafür, dass ältere Aufträge warten).
 */
final class WorldMapTiles {
	/** Ab diesem Zoom (Bildschirmpixel je Block) die vollen Bereichs-Texturen. */
	static final float DETAIL = 1f;
	/** Darunter Weitkacheln statt Übersichtskacheln. */
	static final float FAR = 0.25f;
	/** Mehr Bereiche auf einmal werden nicht einzeln gezeichnet (sonst Ein-/Auslagern im Kreis). */
	static final int MAX_DETAIL_REGIONS = 160;

	// Welche Kacheln überhaupt Bereiche enthalten (neu bestimmt, wenn sich die Ebene ändert – höchstens alle 500 ms).
	private MapLayer tilesOf;
	private int tilesGeneration = Integer.MIN_VALUE;
	private long tilesAt;
	private final Set<Long> tiles1 = new HashSet<Long>();
	private final Set<Long> tiles2 = new HashSet<Long>();

	// Wiederverwendete Listen (kein Müll je Bild).
	private long[] order = new long[256];
	private final List<MapRegion> ready = new ArrayList<MapRegion>();
	private final List<TextureRef> readyTex = new ArrayList<TextureRef>();

	/** Statistik fürs Autotest-Log: gezeichnete Stufe (0 = Bereiche, 1 = Übersicht, 2 = Weitkacheln). */
	int lastLevel;
	int lastMissing;

	/**
	 * Zeichnet {@code layer} für den Welt-Ausschnitt [wx0,wx1]×[wz0,wz1]. Die Leinwand ist so verschoben/skaliert,
	 * dass (0,0) der Weltpunkt ({@code cx}, {@code cz}) ist und eine Einheit ein Block.
	 */
	void draw(Canvas c, MapEngine e, MapLayer layer, double cx, double cz, double wx0, double wz0, double wx1, double wz1,
			float screenPx, long now) {
		refreshTiles(layer, now);
		int rx0 = floor(wx0) >> MapRegion.SHIFT, rx1 = floor(wx1) >> MapRegion.SHIFT;
		int rz0 = floor(wz0) >> MapRegion.SHIFT, rz1 = floor(wz1) >> MapRegion.SHIFT;
		int regionCount = (rx1 - rx0 + 1) * (rz1 - rz0 + 1);
		if (screenPx >= DETAIL && regionCount <= MAX_DETAIL_REGIONS) {
			lastLevel = 0;
			drawRegions(c, e, layer, cx, cz, wx0, wz0, wx1, wz1, rx0, rz0, rx1, rz1, now);
		} else if (screenPx >= FAR) {
			lastLevel = 1;
			drawTiles(c, e, layer, 1, cx, cz, wx0, wz0, wx1, wz1, now);
		} else {
			lastLevel = 2;
			drawTiles(c, e, layer, 2, cx, cz, wx0, wz0, wx1, wz1, now);
		}
	}

	private void drawRegions(Canvas c, MapEngine e, MapLayer layer, double cx, double cz, double wx0, double wz0,
			double wx1, double wz1, int rx0, int rz0, int rx1, int rz1, long now) {
		// Erst anfragen/hochladen (Mitte zuerst), dann zeichnen: fehlt noch etwas, liegt die Übersicht darunter.
		int n = centerOut(rx0, rz0, rx1, rz1, cx / MapRegion.SIZE - 0.5, cz / MapRegion.SIZE - 0.5);
		ready.clear();
		readyTex.clear();
		int missing = 0;
		MapTextures tex = e.textures();
		for (int i = 0; i < n; i++) {
			long key = order[i];
			if (!layer.known(key)) continue;
			MapRegion r = layer.get(MapRegion.keyX(key), MapRegion.keyZ(key), now);
			TextureRef ref = r == null ? null : tex.region(layer, r, now, 250);
			if (ref == null) {
				missing++;
				continue;
			}
			ready.add(r);
			readyTex.add(ref);
		}
		lastMissing = missing;
		if (missing > 0) drawTiles(c, e, layer, 1, cx, cz, wx0, wz0, wx1, wz1, now);
		for (int i = 0; i < ready.size(); i++) {
			MapRegion r = ready.get(i);
			c.push();
			c.translate((float) ((double) r.rx * MapRegion.SIZE - cx), (float) ((double) r.rz * MapRegion.SIZE - cz));
			c.image(readyTex.get(i), 0, 0, MapRegion.SIZE, MapRegion.SIZE, 0xFFFFFFFF);
			c.pop();
		}
	}

	private void drawTiles(Canvas c, MapEngine e, MapLayer layer, int level, double cx, double cz, double wx0, double wz0,
			double wx1, double wz1, long now) {
		int regions = level == 2 ? MapTextures.MEGA : MapTextures.SUPER;
		int span = regions * MapRegion.SIZE;
		int tx0 = Math.floorDiv(floor(wx0), span), tx1 = Math.floorDiv(floor(wx1), span);
		int tz0 = Math.floorDiv(floor(wz0), span), tz1 = Math.floorDiv(floor(wz1), span);
		int n = centerOut(tx0, tz0, tx1, tz1, cx / span - 0.5, cz / span - 0.5);
		if (level == 1) e.textures().setMaxSuperTextures(n + n / 2 + 16);
		Set<Long> known = level == 2 ? tiles2 : tiles1;
		float k = (float) span / MapTextures.SUPER_PIXELS;
		int missing = 0;
		for (int i = 0; i < n; i++) {
			long key = order[i];
			if (!known.contains(key)) continue;
			int tx = MapRegion.keyX(key), tz = MapRegion.keyZ(key);
			TextureRef ref = level == 2 ? e.textures().megaTile(layer, tx, tz, now) : e.textures().superTile(layer, tx, tz, now);
			if (ref == null) {
				missing++;
				continue;
			}
			c.push();
			c.translate((float) ((double) tx * span - cx), (float) ((double) tz * span - cz));
			c.scale(k, k);
			c.image(ref, 0, 0, MapTextures.SUPER_PIXELS, MapTextures.SUPER_PIXELS, 0xFFFFFFFF);
			c.pop();
		}
		if (level != 0) lastMissing = missing;
	}

	/**
	 * Schreibt alle Zellen des Rechtecks in {@link #order}, sortiert nach Abstand zur Mitte ({@code mx}, {@code mz} in
	 * Zellen). Liefert die Anzahl.
	 */
	int centerOut(int x0, int z0, int x1, int z1, double mx, double mz) {
		int w = x1 - x0 + 1, h = z1 - z0 + 1;
		int n = Math.max(0, w) * Math.max(0, h);
		if (n > 4096) n = 4096; // Sicherheitsgrenze (so weit zoomt die Karte nicht heraus)
		if (order.length < n) order = new long[Integer.highestOneBit(n) * 2];
		// Abstand (quadratisch, auf 1/16 Zelle) in die oberen Bits, Index in die unteren – dann einfach sortieren.
		int i = 0;
		for (int z = z0; z <= z1 && i < n; z++) {
			for (int x = x0; x <= x1 && i < n; x++) {
				double dx = x - mx, dz = z - mz;
				long d = Math.min((long) ((dx * dx + dz * dz) * 16), (1L << 30) - 1);
				order[i++] = d << 32 | ((long) (z - z0) << 16) | (x - x0);
			}
		}
		Arrays.sort(order, 0, i);
		for (int j = 0; j < i; j++) {
			int x = x0 + (int) (order[j] & 0xFFFF), z = z0 + (int) (order[j] >> 16 & 0xFFFF);
			order[j] = MapRegion.key(x, z);
		}
		return i;
	}

	/** Liste der Zellen nach {@link #centerOut} (nur für Tests). */
	long[] orderForTests() {
		return order;
	}

	private void refreshTiles(MapLayer layer, long now) {
		// Neu abgetastete Bereiche ändern die Generation nicht immer – darum spätestens alle 2 s neu.
		if (layer == tilesOf && layer.generation() == tilesGeneration && now - tilesAt < 2000) return;
		if (layer == tilesOf && now - tilesAt < 500) return;
		tilesOf = layer;
		tilesGeneration = layer.generation();
		tilesAt = now;
		tiles1.clear();
		tiles2.clear();
		for (long key : layer.knownKeys()) {
			int rx = MapRegion.keyX(key), rz = MapRegion.keyZ(key);
			tiles1.add(MapRegion.key(Math.floorDiv(rx, MapTextures.SUPER), Math.floorDiv(rz, MapTextures.SUPER)));
			tiles2.add(MapRegion.key(Math.floorDiv(rx, MapTextures.MEGA), Math.floorDiv(rz, MapTextures.MEGA)));
		}
	}

	private static int floor(double v) {
		return (int) Math.floor(v);
	}
}
