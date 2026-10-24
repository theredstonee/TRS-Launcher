package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Karte v3 ({@link MapPreview}): Weltkarte als Bild-Pyramide – in jeder Zoomstufe so fein, dass ein Texel höchstens
 * einen Bildschirmpixel groß ist (nichts verschwimmt, wo es darauf ankommt):
 * <ul>
 *   <li>ab 1 Bildschirmpixel je Block: Bereichs-Texturen (1 Texel je Block), auch bei vielen Bereichen auf einmal;</li>
 *   <li>darunter Stufe L ({@value #MIN_LEVEL}…{@value #MAX_LEVEL}): Kacheln aus 2^L × 2^L Bereichen, ein Texel je
 *   2^(L−1) Blöcke – Stufe 2 aus den 64×64-Übersichten, ab Stufe 3 aus den 32×32-Übersichten.</li>
 * </ul>
 * Was gespeichert ist, aber noch lädt, bekommt einen dezenten Platzhalter (unerkundet bleibt dunkel); fehlen Bereichs-
 * Texturen noch, liegt die nächstgröbere Stufe darunter.
 */
final class WorldMapPyramid {
	static final int MIN_LEVEL = 2;
	static final int MAX_LEVEL = 6;
	/** Farbe „wird geladen“: etwas heller als der Hintergrund der Weltkarte (0xFF0B0A0E = unerkundet). */
	static final int LOADING = 0xFF1B1922;

	private MapLayer tilesOf;
	private int tilesGeneration = Integer.MIN_VALUE;
	private long tilesAt;
	@SuppressWarnings("unchecked")
	private final Set<Long>[] tiles = new Set[MAX_LEVEL + 1];
	private final WorldMapTiles order;
	private final List<MapRegion> ready = new ArrayList<MapRegion>();
	private final List<TextureRef> readyTex = new ArrayList<TextureRef>();
	private final List<Long> waiting = new ArrayList<Long>();

	/** Statistik: gezeichnete Stufe (0 = Bereiche) und fehlende Teile im letzten Bild. */
	int lastLevel;
	int lastMissing;

	WorldMapPyramid(WorldMapTiles order) {
		this.order = order;
		for (int l = 0; l < tiles.length; l++) tiles[l] = new HashSet<Long>();
	}

	/**
	 * Stufe für einen Zoom: 0 = Bereichs-Texturen, sonst die gröbste Stufe, deren Texel noch höchstens einen
	 * Bildschirmpixel groß ist (Stufe 2 auch knapp unter 1 Pixel je Block, wenn zu viele Bereiche sichtbar sind).
	 */
	static int levelFor(float screenPx, int regionCount, int detailCap) {
		if (screenPx >= 1f && regionCount <= detailCap) return 0;
		double blocksPerPx = 1.0 / Math.max(1e-6f, screenPx);
		int l = (int) Math.floor(Math.log(blocksPerPx) / Math.log(2) + 1e-6) + 1;
		return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, l));
	}

	/** Blöcke je Texel einer Stufe. */
	static int blocksPerTexel(int level) {
		return level <= 0 ? 1 : 1 << (level - 1);
	}

	/** So viele Bereiche dürfen höchstens einzeln (1 Texel je Block) gezeichnet werden. */
	static int detailCap(MapLayer layer) {
		return Math.max(96, Math.min(480, layer.maxRegions() * 3 / 4));
	}

	void draw(Canvas c, MapEngine e, MapLayer layer, double cx, double cz, double wx0, double wz0, double wx1, double wz1,
			float screenPx, long now) {
		refreshTiles(layer, now);
		int rx0 = floor(wx0) >> MapRegion.SHIFT, rx1 = floor(wx1) >> MapRegion.SHIFT;
		int rz0 = floor(wz0) >> MapRegion.SHIFT, rz1 = floor(wz1) >> MapRegion.SHIFT;
		int regionCount = (rx1 - rx0 + 1) * (rz1 - rz0 + 1);
		int level = levelFor(screenPx, regionCount, detailCap(layer));
		lastLevel = level;
		if (level == 0) {
			drawRegions(c, e, layer, cx, cz, wx0, wz0, wx1, wz1, rx0, rz0, rx1, rz1, now);
		} else {
			lastMissing = drawLevel(c, e, layer, level, cx, cz, wx0, wz0, wx1, wz1, now, true);
		}
	}

	private void drawRegions(Canvas c, MapEngine e, MapLayer layer, double cx, double cz, double wx0, double wz0,
			double wx1, double wz1, int rx0, int rz0, int rx1, int rz1, long now) {
		int n = order.centerOut(rx0, rz0, rx1, rz1, cx / MapRegion.SIZE - 0.5, cz / MapRegion.SIZE - 0.5);
		long[] keys = order.orderForTests();
		// Genug Texturen für alles Sichtbare (plus Rand), sonst würden sie im Kreis aus- und eingelagert.
		e.textures().setMaxRegionTextures(Math.max(448, n + n / 3 + 32));
		ready.clear();
		readyTex.clear();
		waiting.clear();
		MapTextures tex = e.textures();
		for (int i = 0; i < n; i++) {
			long key = keys[i];
			if (!layer.known(key)) continue;
			MapRegion r = layer.get(MapRegion.keyX(key), MapRegion.keyZ(key), now);
			TextureRef ref = r == null ? null : tex.region(layer, r, now, 250);
			if (ref == null) {
				waiting.add(key);
				continue;
			}
			ready.add(r);
			readyTex.add(ref);
		}
		lastMissing = waiting.size();
		if (!waiting.isEmpty()) {
			// Platzhalter für Geladenes-in-Arbeit, darüber die nächstgröbere Stufe (meist schon da).
			for (int i = 0; i < waiting.size(); i++) {
				long key = waiting.get(i);
				placeholder(c, (double) MapRegion.keyX(key) * MapRegion.SIZE - cx, (double) MapRegion.keyZ(key) * MapRegion.SIZE - cz,
						MapRegion.SIZE, MapRegion.SIZE);
			}
			drawLevel(c, e, layer, MIN_LEVEL, cx, cz, wx0, wz0, wx1, wz1, now, false);
		}
		for (int i = 0; i < ready.size(); i++) {
			MapRegion r = ready.get(i);
			c.push();
			c.translate((float) ((double) r.rx * MapRegion.SIZE - cx), (float) ((double) r.rz * MapRegion.SIZE - cz));
			MapDraw.image(c, readyTex.get(i), 0, 0, MapRegion.SIZE, MapRegion.SIZE, MapRegion.SIZE, MapRegion.SIZE, 0xFFFFFFFF);
			c.pop();
		}
	}

	/** Zeichnet die Kacheln einer Stufe; liefert die Anzahl der noch fehlenden. */
	private int drawLevel(Canvas c, MapEngine e, MapLayer layer, int level, double cx, double cz, double wx0, double wz0,
			double wx1, double wz1, long now, boolean placeholders) {
		int span = MapRegion.SIZE << level;
		int tx0 = Math.floorDiv(floor(wx0), span), tx1 = Math.floorDiv(floor(wx1), span);
		int tz0 = Math.floorDiv(floor(wz0), span), tz1 = Math.floorDiv(floor(wz1), span);
		int n = order.centerOut(tx0, tz0, tx1, tz1, cx / span - 0.5, cz / span - 0.5);
		long[] keys = order.orderForTests();
		e.textures().setMaxLevelTextures(n * 2 + 32);
		Set<Long> known = tiles[level];
		int missing = 0;
		for (int i = 0; i < n; i++) {
			long key = keys[i];
			if (!known.contains(key)) continue;
			int tx = MapRegion.keyX(key), tz = MapRegion.keyZ(key);
			TextureRef ref = e.textures().levelTile(layer, level, tx, tz, now);
			if (ref == null) {
				missing++;
				if (placeholders) placeholder(c, (double) tx * span - cx, (double) tz * span - cz, span, span);
				continue;
			}
			c.push();
			c.translate((float) ((double) tx * span - cx), (float) ((double) tz * span - cz));
			int px = MapTextures.SUPER_PIXELS;
			MapDraw.image(c, ref, 0, 0, px, px, span, span, 0xFFFFFFFF);
			c.pop();
		}
		return missing;
	}

	/** Dezenter Platzhalter „wird geladen“ für ein Welt-Rechteck (relativ zur Bildmitte, in Blöcken). */
	static void placeholder(Canvas c, double x, double z, float w, float h) {
		c.push();
		c.translate((float) x, (float) z);
		c.scale(w / 16f, h / 16f);
		c.fill(0, 0, 16, 16, LOADING);
		c.pop();
	}

	private void refreshTiles(MapLayer layer, long now) {
		if (layer == tilesOf && layer.generation() == tilesGeneration && now - tilesAt < 2000) return;
		if (layer == tilesOf && now - tilesAt < 500) return;
		tilesOf = layer;
		tilesGeneration = layer.generation();
		tilesAt = now;
		for (int l = MIN_LEVEL; l <= MAX_LEVEL; l++) tiles[l].clear();
		for (long key : layer.knownKeys()) {
			int rx = MapRegion.keyX(key), rz = MapRegion.keyZ(key);
			for (int l = MIN_LEVEL; l <= MAX_LEVEL; l++) tiles[l].add(MapRegion.key(rx >> l, rz >> l));
		}
	}

	private static int floor(double v) {
		return (int) Math.floor(v);
	}
}
