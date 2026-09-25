package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.config.KeyDefaults;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MapTest {
	// --- Farben ---

	@Test
	void tintUsesBiomeColorAndIgnoresWhite() {
		int grass = MapColors.tint(MapColors.MAP_GRASS, 0x91BD59);
		assertEquals(MapColors.mul(0x91BD59, 0.76f), grass);
		assertEquals(0x707070, MapColors.tint(0x707070, -1), "ohne Tönung bleibt die Kartenfarbe");
		assertEquals(0x707070, MapColors.tint(0x707070, 0xFFFFFF), "weiße Tönung zählt nicht");
		int leaves = MapColors.tint(MapColors.MAP_PLANT, 0x59AE30);
		assertTrue(brightness(leaves) < brightness(0x59AE30));
	}

	@Test
	void deepWaterIsDarkerAndHidesTheFloor() {
		int sand = 0xF7E9A3;
		int shallow = MapColors.water(sand, 0x3F76E4, 1);
		int deep = MapColors.water(sand, 0x3F76E4, 20);
		assertTrue(brightness(deep) < brightness(shallow));
		// Flaches Wasser lässt den (hellen) Grund durchscheinen → heller als reines Wasser gleicher Tiefe.
		assertTrue(brightness(shallow) > brightness(MapColors.water(0, 0x3F76E4, 1)));
		// Tiefes Wasser: fast unabhängig vom Grund.
		int deepDark = MapColors.water(0x101010, 0x3F76E4, 20);
		assertTrue(Math.abs(brightness(deep) - brightness(deepDark)) < 40);
	}

	@Test
	void reliefIsMonotonicAndSaturated() {
		assertEquals(1f, MapColors.relief(0), 1e-6);
		float prev = 0f;
		for (int s = -20; s <= 20; s++) {
			float f = MapColors.relief(s);
			assertTrue(f > prev, "steigt mit der Steigung");
			assertTrue(f > 0.69f && f < 1.31f, "gesättigt: " + f);
			prev = f;
		}
		assertTrue(MapColors.heightTone(200) > 1f && MapColors.heightTone(0) < 1f);
		assertTrue(MapColors.heightTone(10_000) <= 1.12f + 1e-6);
	}

	@Test
	void composeHandlesUnknownWaterWallAndCave() {
		assertEquals(0, MapColors.compose(0x123456, 64, 64, 64, Integer.MIN_VALUE), "unerkundet = durchsichtig");
		int land = MapColors.KNOWN | 0x808080;
		int lit = MapColors.compose(land, 70, 60, 60, Integer.MIN_VALUE);
		int shadow = MapColors.compose(land, 60, 70, 70, Integer.MIN_VALUE);
		assertTrue(brightness(lit) > brightness(shadow), "Hang nach Nordwesten ist heller");
		int water = MapColors.KNOWN | MapColors.WATER | 0x3050A0;
		assertEquals(MapColors.compose(water, 62, 50, 50, Integer.MIN_VALUE), MapColors.compose(water, 62, 70, 70, Integer.MIN_VALUE),
				"Wasser ist flach (keine Relief-Schattierung)");
		assertEquals(0xFF, MapColors.compose(MapColors.KNOWN | MapColors.WALL, 40, 40, 40, 40) >>> 24);
		int nearFloor = MapColors.compose(land, 38, 38, 38, 40);
		int deepFloor = MapColors.compose(land, 5, 5, 5, 40);
		assertTrue(brightness(deepFloor) < brightness(nearFloor), "tiefer Höhlenboden ist dunkler");
		assertEquals(0, MapColors.compose(MapColors.KNOWN | MapColors.EMPTY, 0, 0, 0, Integer.MIN_VALUE), "Leere oben durchsichtig");
	}

	private static int brightness(int rgb) {
		return ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
	}

	// --- Abtasten ---

	/** Kleine Test-Welt: je Spalte eine Liste (y → Blockinfo), Tönung je Farbe. */
	static final class FakeReader implements ChunkReader {
		final java.util.Map<Long, int[]> columns = new java.util.HashMap<Long, int[]>();
		int minY = 0;
		int openCount;
		boolean loaded = true;

		void column(int x, int z, int[] blocksFromMinY) {
			columns.put(((long) x << 32) ^ (z & 0xFFFFFFFFL), blocksFromMinY);
		}

		int cx, cz;

		@Override
		public boolean isLoaded(int chunkX, int chunkZ) {
			return loaded;
		}

		@Override
		public boolean open(int chunkX, int chunkZ) {
			cx = chunkX;
			cz = chunkZ;
			openCount++;
			return loaded;
		}

		@Override
		public int minY() {
			return minY;
		}

		private int[] col(int lx, int lz) {
			int x = (cx << 4) + lx, z = (cz << 4) + lz;
			int[] c = columns.get(((long) x << 32) ^ (z & 0xFFFFFFFFL));
			return c == null ? DEFAULT : c;
		}

		@Override
		public int top(int lx, int lz) {
			int[] c = col(lx, lz);
			for (int y = c.length - 1; y >= 0; y--) {
				if ((c[y] & AIR) == 0) return y + 1 + minY;
			}
			return minY;
		}

		@Override
		public int block(int lx, int y, int lz) {
			int[] c = col(lx, lz);
			int i = y - minY;
			if (i < 0 || i >= c.length) return AIR;
			return c[i];
		}

		@Override
		public int tint(int lx, int y, int lz) {
			int b = block(lx, y, lz) & MapColors.RGB;
			if (b == MapColors.MAP_GRASS) return 0x91BD59;
			if (b == MapColors.MAP_WATER) return 0x3F76E4;
			return -1;
		}
	}

	static final int STONE = 0x707070;
	static final int SAND = 0xF7E9A3;
	static final int A = ChunkReader.AIR;
	/** Standardspalte: Stein bis 3, Gras auf 4, Luft darüber. */
	static final int[] DEFAULT = {STONE, STONE, STONE, STONE, MapColors.MAP_GRASS, A, A, A};

	@Test
	void surfaceFindsTopBlockWaterAndGlass() {
		FakeReader r = new FakeReader();
		// Glas (keine Kartenfarbe, kein Luftblock) über Stein.
		r.column(1, 0, new int[]{STONE, STONE, 0, A});
		// Wasser: Sand auf 1, Wasser 2..4.
		r.column(2, 0, new int[]{STONE, SAND, MapColors.MAP_WATER, MapColors.MAP_WATER, MapColors.MAP_WATER, A});
		int[] px = new int[256], h = new int[256];
		assertTrue(ColumnScanner.surface(r, 0, 0, px, h));
		assertEquals(4, h[0]);
		assertEquals(MapColors.KNOWN | MapColors.tint(MapColors.MAP_GRASS, 0x91BD59), px[0], "Gras mit Biomfarbe");
		assertEquals(1, h[1], "Glas wird übersprungen");
		assertEquals(MapColors.KNOWN | STONE, px[1]);
		assertEquals(4, h[2], "Wasserspiegel ist die Höhe");
		assertTrue((px[2] & MapColors.WATER) != 0);
		assertEquals(MapColors.KNOWN | MapColors.WATER | MapColors.water(SAND, 0x3F76E4, 3), px[2]);
		r.loaded = false;
		assertFalse(ColumnScanner.surface(r, 0, 0, px, h), "nicht geladen");
	}

	@Test
	void caveViewSkipsTheRoofAndFindsTheFloor() {
		FakeReader r = new FakeReader();
		int[] cave = new int[40];
		Arrays.fill(cave, STONE);
		// Höhle 10..12 (Luft), Boden auf 9, Decke ab 13, Oberfläche bei 39.
		cave[10] = A;
		cave[11] = A;
		cave[12] = A;
		r.column(0, 0, cave);
		int[] solid = new int[40];
		Arrays.fill(solid, STONE);
		r.column(1, 0, solid);
		int[] px = new int[256], h = new int[256];
		assertTrue(ColumnScanner.cave(r, 0, 0, 15, px, h));
		assertEquals(9, h[0], "Boden der Höhle");
		assertEquals(MapColors.KNOWN | STONE, px[0]);
		assertTrue((px[1] & MapColors.WALL) != 0, "geschlossene Spalte = Wand");
		// Standardspalte (Oberfläche unter der Startebene): Gras wird gefunden.
		assertEquals(4, h[2]);
	}

	// --- Bereiche, Kodierung, Platte ---

	@Test
	void regionCodecRoundTrip() throws IOException {
		MapRegion r = new MapRegion(-3, 7);
		int[] px = new int[256], h = new int[256];
		for (int i = 0; i < 256; i++) {
			px[i] = MapColors.KNOWN | (i * 0x010203);
			h[i] = -60 + i;
		}
		r.writeChunk(-17, 60, px, h, 1);
		int[] summary = new int[MapRegion.SUMMARY * MapRegion.SUMMARY];
		summary[5] = 0xFF112233;
		byte[] data = RegionCodec.encode(r.rx, r.rz, r.pixels, r.heights, summary);
		RegionCodec.Decoded d = RegionCodec.decode(new ByteArrayInputStream(data));
		assertEquals(-3, d.rx);
		assertEquals(7, d.rz);
		assertArrayEquals(r.pixels, d.pixels);
		assertArrayEquals(r.heights, d.heights);
		assertArrayEquals(summary, d.summary);
		assertArrayEquals(summary, RegionCodec.readSummary(new ByteArrayInputStream(data)));
		assertTrue(data.length < 20_000, "kompakt: " + data.length);
		byte[] broken = Arrays.copyOf(data, data.length / 2);
		assertThrows(IOException.class, () -> RegionCodec.decode(new ByteArrayInputStream(broken)));
		assertThrows(IOException.class, () -> RegionCodec.decode(new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5})));
	}

	@Test
	void writeChunkPlacesDataAndCountsVersions() {
		MapRegion r = new MapRegion(0, 0);
		int[] px = new int[256], h = new int[256];
		Arrays.fill(px, MapColors.KNOWN | 0xABCDEF);
		Arrays.fill(h, 70);
		r.writeChunk(9, 10, px, h, 5); // lokal (1, 2) → Blöcke 16..31 × 32..47
		assertEquals(MapColors.KNOWN | 0xABCDEF, r.pixel(16, 32));
		assertEquals(70, r.height(31, 47));
		assertEquals(0, r.pixel(15, 32));
		assertEquals(1, r.version);
		r.writeChunk(9, 10, px, h, 6);
		assertEquals(1, r.version, "unverändert → keine neue Version");
		assertTrue(r.dirty());
	}

	@Test
	void diskNamesAndRegionFiles(@TempDir Path dir) {
		String a = MapDisk.safeName("mp:play.example.com:25565");
		String b = MapDisk.safeName("mp:play.example.com:25566");
		assertNotEquals(a, b);
		assertTrue(a.matches("[a-z0-9._-]+"), a);
		assertTrue(MapDisk.safeName("sp:../../etc").matches("[a-z0-9._-]+"));
		assertEquals(MapRegion.key(-4, 12), MapDisk.parseRegionFile("r.-4.12.trsm"));
		assertEquals(Long.MIN_VALUE, MapDisk.parseRegionFile("r.x.1.trsm"));
		assertEquals(Long.MIN_VALUE, MapDisk.parseRegionFile("other.txt"));
		MapDisk disk = new MapDisk(dir, true);
		Path layer = disk.layerDir("sp:Welt", "minecraft:overworld", "surface");
		assertTrue(layer.startsWith(dir));
	}

	@Test
	void layerSavesLoadsAndMerges(@TempDir Path dir) {
		MapDisk disk = new MapDisk(dir, true);
		Path ld = disk.layerDir("sp:test", "minecraft:overworld", "surface");
		MapLayer layer = new MapLayer("surface", 1, Integer.MIN_VALUE, disk, ld);
		int[] px = new int[256], h = new int[256];
		Arrays.fill(px, MapColors.KNOWN | 0x336699);
		Arrays.fill(h, 64);
		layer.forWrite(0, 0, 1000).writeChunk(2, 3, px, h, 1000);
		layer.maintain(1000 + 5_000, 20_000, MapCompose.INSTANCE, 4);
		assertFalse(Files.exists(MapDisk.regionFile(ld, 0, 0)), "zu früh gespeichert");
		layer.maintain(1000 + 21_000, 20_000, MapCompose.INSTANCE, 4);
		assertTrue(Files.exists(MapDisk.regionFile(ld, 0, 0)));
		layer.close(MapCompose.INSTANCE);

		// Neue Ebene: kennt den Bereich von der Platte, lädt ihn beim Anzeigen.
		MapLayer again = new MapLayer("surface", 2, Integer.MIN_VALUE, disk, ld);
		assertTrue(again.knownKeys().contains(MapRegion.key(0, 0)));
		MapRegion loaded = again.get(0, 0, 1);
		if (loaded == null) loaded = again.get(0, 0, 2);
		assertNotNull(loaded);
		assertEquals(MapColors.KNOWN | 0x336699, loaded.pixel(32, 48));
		assertFalse(loaded.dirty());
		assertNotNull(again.summary(0, 0, MapCompose.INSTANCE));

		// Schreiben vor dem Laden: gespeicherte Pixel werden nur dort ergänzt, wo nichts Neues steht.
		MapRegion r = new MapRegion(0, 0);
		int[] other = new int[256];
		Arrays.fill(other, MapColors.KNOWN | 0xFF0000);
		r.writeChunk(2, 3, other, h, 5);
		int[] fromDisk = new int[MapRegion.AREA];
		Arrays.fill(fromDisk, MapColors.KNOWN | 0x00FF00);
		assertTrue(r.mergeFrom(fromDisk, new short[MapRegion.AREA], 6));
		assertEquals(MapColors.KNOWN | 0xFF0000, r.pixel(32, 48), "neuere Daten bleiben");
		assertEquals(MapColors.KNOWN | 0x00FF00, r.pixel(0, 0), "Lücken aus der Platte");
	}

	@Test
	void layerUnloadsOldestRegionsAfterSaving(@TempDir Path dir) {
		MapDisk disk = new MapDisk(dir, true);
		Path ld = disk.layerDir("sp:t", "d", "surface");
		MapLayer layer = new MapLayer("surface", 1, Integer.MIN_VALUE, disk, ld);
		layer.setMaxRegions(16);
		int[] px = new int[256], h = new int[256];
		Arrays.fill(px, MapColors.KNOWN | 0x404040);
		for (int i = 0; i < 20; i++) {
			layer.forWrite(i, 0, i).writeChunk(i * 8, 0, px, h, i);
		}
		layer.maintain(1_000_000, Long.MAX_VALUE, MapCompose.INSTANCE, 0);
		assertEquals(16, layer.loadedCount());
		assertNull(layer.peek(0, 0), "älteste entladen");
		assertTrue(Files.exists(MapDisk.regionFile(ld, 0, 0)), "vor dem Entladen gespeichert");
	}

	@Test
	void diskLimitDeletesOldestButKeepsOpenLayers(@TempDir Path dir) throws IOException {
		Path keep = dir.resolve("w").resolve("d").resolve("surface");
		Path other = dir.resolve("w2").resolve("d").resolve("surface");
		Files.createDirectories(keep);
		Files.createDirectories(other);
		for (int i = 0; i < 5; i++) {
			Path a = other.resolve("r." + i + ".0.trsm");
			Files.write(a, new byte[1000]);
			Files.setLastModifiedTime(a, FileTime.fromMillis(1000L + i));
			Path b = keep.resolve("r." + i + ".0.trsm");
			Files.write(b, new byte[1000]);
			Files.setLastModifiedTime(b, FileTime.fromMillis(10L + i));
		}
		assertEquals(10_000, MapDisk.totalSize(dir));
		int deleted = MapDisk.trim(dir, 6000, Collections.singletonList(keep));
		assertTrue(deleted >= 4, "gelöscht: " + deleted);
		for (int i = 0; i < 5; i++) assertTrue(Files.exists(keep.resolve("r." + i + ".0.trsm")), "offene Ebene bleibt");
		assertFalse(Files.exists(other.resolve("r.0.0.trsm")), "älteste zuerst");
		assertEquals(0, MapDisk.trim(dir, 1_000_000, Collections.<Path>emptyList()));
	}

	@Test
	void summaryAveragesAndMarksPartialAreas() {
		int[] argb = new int[MapRegion.AREA];
		for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) argb[z * MapRegion.SIZE + x] = 0xFF204060;
		argb[MapRegion.SIZE * 4 + 4] = 0xFFFFFFFF; // ein einzelnes Pixel im nächsten Feld
		int[] s = MapCompose.summaryOf(argb);
		assertEquals(0xFF204060, s[0]);
		assertEquals(0xA0FFFFFF, s[MapRegion.SUMMARY + 1], "wenig erkundet = halb durchsichtig");
		assertEquals(0, s[2]);
	}

	// --- Fair Play ---

	@Test
	void fairPlayParsesXaeroServerCodes() {
		char s = FairPlay.SECTION;
		FairPlay fp = new FairPlay();
		assertTrue(fp.caveAllowed(false));
		assertTrue(fp.radarThroughWalls());
		assertFalse(fp.onServerText("Hallo Welt"));
		assertTrue(fp.onServerText("Willkommen " + codes(s, "fairxaero")));
		assertTrue(fp.serverFair());
		assertFalse(fp.caveAllowed(false));
		assertFalse(fp.caveAllowed(true));
		assertFalse(fp.radarThroughWalls());
		fp.onServerText(codes(s, "xaeromm" + "netherisfair"));
		assertTrue(fp.caveAllowed(true), "Server erlaubt Höhlen im Nether");
		assertFalse(fp.caveAllowed(false));
		fp.onServerText(codes(s, "nominimap"));
		assertFalse(fp.minimapAllowed());
		fp.onServerText(codes(s, "resetxaero"));
		assertFalse(fp.serverFair());
		assertTrue(fp.minimapAllowed());
		fp.onServerText(s + "a" + s + "l" + codes(s, "fairxaero"));
		assertTrue(fp.serverFair(), "normale Farbcodes davor stören nicht");
		fp.resetServer();
		assertFalse(fp.active());
	}

	@Test
	void manualFairPlayIsStrictEverywhere() {
		char s = FairPlay.SECTION;
		FairPlay fp = new FairPlay();
		fp.setManual(true);
		assertTrue(fp.active());
		assertFalse(fp.caveAllowed(false));
		assertFalse(fp.radarThroughWalls());
		fp.onServerText(codes(s, "xaerowm" + "netherisfair"));
		assertFalse(fp.caveAllowed(true), "eigener Schalter bleibt streng, auch wenn der Server den Nether erlaubt");
		fp.setManual(false);
		assertTrue(fp.caveAllowed(true));
	}

	private static String codes(char s, String letters) {
		StringBuilder sb = new StringBuilder();
		for (char c : letters.toCharArray()) sb.append(s).append(c);
		return sb.toString();
	}

	// --- Formen ---

	@Test
	void circleBandsStayInsideAndCoverTheInnerDisc() {
		float[] out = new float[4 * 512];
		for (float radius : new float[]{20f, 56f, 128f}) {
			for (float tol : new float[]{1f, 2.5f, MapSprites.ringIn(radius) - 0.5f}) {
				int n = MapShapes.circle(radius, tol, out);
				// Anzahl ≈ Radius / Toleranz – mit dem Rahmen der Minimap (ringIn) höchstens ~25.
				assertTrue(n > 0 && n <= radius / tol + 3, "Streifen: " + n);
				if (tol >= MapSprites.ringIn(radius) - 0.5f) assertTrue(n <= 26, "Streifen mit Rahmen: " + n);
				for (int i = 0; i < n; i++) {
					float y0 = out[i * 4], y1 = out[i * 4 + 1], x0 = out[i * 4 + 2], x1 = out[i * 4 + 3];
					assertTrue(y1 > y0);
					for (float y : new float[]{y0, y1}) {
						assertTrue(x0 * x0 + y * y <= radius * radius + 0.01f, "Ecke innerhalb");
						assertTrue(x1 * x1 + y * y <= radius * radius + 0.01f);
					}
				}
				float inner = radius - tol;
				for (float a = 0; a < 6.28f; a += 0.05f) {
					for (float d = 0; d < inner - 0.01f; d += inner / 7f) {
						float px = (float) Math.cos(a) * d, py = (float) Math.sin(a) * d;
						assertTrue(covered(out, n, px, py), "Punkt " + px + "," + py + " bedeckt (r=" + radius + ", t=" + tol + ")");
					}
				}
			}
		}
	}

	@Test
	void rotatedSquareBandsStayInside() {
		float[] out = new float[4 * 512];
		float half = 50f;
		int n = MapShapes.rotatedSquare(half, 0.6f, 2f, out);
		assertTrue(n > 10);
		float cos = (float) Math.cos(-0.6f), sin = (float) Math.sin(-0.6f);
		for (int i = 0; i < n; i++) {
			for (float y : new float[]{out[i * 4], out[i * 4 + 1]}) {
				for (float x : new float[]{out[i * 4 + 2], out[i * 4 + 3]}) {
					// Zurückdrehen: muss im Quadrat liegen.
					float qx = x * cos - y * sin, qy = x * sin + y * cos;
					assertTrue(Math.abs(qx) <= half + 0.01f && Math.abs(qy) <= half + 0.01f);
				}
			}
		}
	}

	private static boolean covered(float[] out, int n, float x, float y) {
		for (int i = 0; i < n; i++) {
			if (y >= out[i * 4] - 1e-4f && y <= out[i * 4 + 1] + 1e-4f && x >= out[i * 4 + 2] - 1e-4f && x <= out[i * 4 + 3] + 1e-4f) {
				return true;
			}
		}
		return false;
	}

	// --- Engine: Abtasten, Höhle, Fair Play, Wegpunkte, Tod ---

	static final class FakePlatform implements MapPlatform {
		final FakeReader reader = new FakeReader();
		final WaypointStore store;
		String world = "sp:Testwelt";
		String dim = "minecraft:overworld";
		double x = 8, y = 64, z = 8;
		int sky = 15;
		boolean lastLos;
		int saves;
		List<double[]> mobs = new ArrayList<double[]>();

		FakePlatform(Path dir) {
			store = new WaypointStore(dir.resolve("wp.json"));
		}

		@Override
		public boolean inWorld() {
			return true;
		}

		@Override
		public String worldKey() {
			return world;
		}

		@Override
		public String dimension() {
			return dim;
		}

		@Override
		public double x() {
			return x;
		}

		@Override
		public double y() {
			return y;
		}

		@Override
		public double z() {
			return z;
		}

		@Override
		public float yaw() {
			return 0;
		}

		@Override
		public ChunkReader reader() {
			return reader;
		}

		@Override
		public int renderDistance() {
			return 2;
		}

		@Override
		public int skyLight() {
			return sky;
		}

		@Override
		public void entities(EntitySink sink, double radius, boolean lineOfSightOnly) {
			lastLos = lineOfSightOnly;
			for (double[] m : mobs) {
				MapEntity e = sink.add();
				if (e == null) return;
				e.set(MapEntity.HOSTILE, m[0], m[1], m[0], 64, m[1], 0);
			}
		}

		@Override
		public String biome() {
			return "Ebene";
		}

		@Override
		public long dayTime() {
			return 6000;
		}

		@Override
		public double guiScale() {
			return 2;
		}

		@Override
		public WaypointStore waypoints() {
			return store;
		}

		@Override
		public String waypointWorldKey() {
			return world;
		}

		@Override
		public void waypointsChanged() {
			saves++;
		}

		@Override
		public String serverMotd() {
			return null;
		}
	}

	@Test
	void engineSamplesAroundThePlayerWithinBudget(@TempDir Path dir) {
		TrsModules modules = new TrsModules();
		modules.minimap.setEnabled(true);
		MapEngine e = new MapEngine(modules, dir, true);
		FakePlatform p = new FakePlatform(dir);
		for (int i = 0; i < 30; i++) e.tick(p);
		assertTrue(e.inWorld());
		assertFalse(e.caveActive());
		assertTrue(e.sampledTotal() >= 25, "5×5 Chunks (Sichtweite 2) abgetastet: " + e.sampledTotal());
		assertEquals(4, e.heightAt(e.surfaceLayer(), 3, 3));
		assertEquals("Ebene", e.biome());
		assertEquals(6000, e.dayTime());
		e.leaveWorld();
		assertFalse(e.inWorld());
		// Beim Verlassen gespeichert.
		assertTrue(Files.isDirectory(dir.resolve("trsclient").resolve("maps")));
	}

	@Test
	void caveViewTurnsOnUndergroundUnlessFairPlay(@TempDir Path dir) {
		TrsModules modules = new TrsModules();
		modules.minimap.setEnabled(true);
		MapEngine e = new MapEngine(modules, dir, true);
		FakePlatform p = new FakePlatform(dir);
		p.sky = 0;
		p.y = 20;
		e.tick(p);
		assertTrue(e.caveActive(), "unter Tage → Höhlenansicht");
		assertTrue(e.viewLayer().cave());
		modules.minimapFairPlay.set(true);
		e.tick(p);
		assertFalse(e.caveActive(), "Fair Play → keine Höhlenansicht");
		assertEquals(e.surfaceLayer(), e.viewLayer());
		modules.minimapFairPlay.set(false);
		modules.minimapCaveMode.set(TrsModules.CaveMode.OFF);
		e.tick(p);
		assertFalse(e.caveActive(), "abgeschaltet");
		modules.minimapCaveMode.set(TrsModules.CaveMode.AUTO);
		p.sky = 15;
		p.dim = "minecraft:the_nether";
		e.tick(p);
		assertTrue(e.caveActive(), "im Nether immer Höhlenansicht");
		assertTrue(e.nether());
	}

	@Test
	void entitiesUseLineOfSightOnlyWithFairPlay(@TempDir Path dir) {
		TrsModules modules = new TrsModules();
		modules.minimap.setEnabled(true);
		modules.minimapHostile.set(true);
		MapEngine e = new MapEngine(modules, dir, true);
		FakePlatform p = new FakePlatform(dir);
		p.mobs.add(new double[]{20, 30});
		e.tick(p);
		assertEquals(1, e.entityCount());
		assertFalse(p.lastLos);
		modules.minimapFairPlay.set(true);
		e.tick(p);
		assertTrue(p.lastLos, "Fair Play: nur, was der Spieler sieht");
		e.onServerText(FairPlay.SECTION + "f" + FairPlay.SECTION + "a" + FairPlay.SECTION + "i" + FairPlay.SECTION + "r"
				+ FairPlay.SECTION + "x" + FairPlay.SECTION + "a" + FairPlay.SECTION + "e" + FairPlay.SECTION + "r" + FairPlay.SECTION + "o");
		modules.minimapFairPlay.set(false);
		e.tick(p);
		assertTrue(p.lastLos, "Server verlangt Fair Play");
		// Ohne eingeschaltete Anzeigen werden keine Kreaturen gesammelt.
		modules.minimapHostile.set(false);
		modules.minimapPlayers.set(false);
		e.tick(p);
		assertEquals(0, e.entityCount());
	}

	@Test
	void waypointsAndDeathPointFromTheMap(@TempDir Path dir) {
		TrsModules modules = new TrsModules();
		modules.worldMap.setEnabled(true);
		MapEngine e = new MapEngine(modules, dir, true);
		FakePlatform p = new FakePlatform(dir);
		e.tick(p);
		Waypoint w = e.addWaypoint("Basis", 100, 70, -20, 0x3DDC84);
		assertNotNull(w);
		assertEquals(1, p.saves);
		assertEquals(Collections.singletonList(w), e.waypoints());
		assertEquals("minecraft:overworld", w.dimension);
		// Todespunkt (vom Loader gesetzt) erscheint, nur der letzte bleibt.
		p.store.setDeath(p.world, 1, 2, 3, "minecraft:overworld", 0xE0281E);
		p.store.setDeath(p.world, 4, 5, 6, "minecraft:overworld", 0xE0281E);
		int deaths = 0;
		for (Waypoint x : e.waypoints()) if (x.death) deaths++;
		assertEquals(1, deaths);
		// Andere Dimension: nicht auf dieser Karte.
		p.store.add(p.world, new Waypoint("Nether", 0, 0, 0, "minecraft:the_nether", 0xFFFFFF));
		assertEquals(2, e.waypoints().size());
		w.name = "Zuhause";
		e.waypointEdited();
		assertEquals(2, p.saves);
		e.removeWaypoint(w);
		assertEquals(3, p.saves);
		assertEquals(1, e.waypoints().size());
	}

	@Test
	void worldMapKeyConflictOnlyForDefault() {
		assertTrue(KeyDefaults.conflicts("key.keyboard.m", "key.keyboard.m", Arrays.asList("key.keyboard.w", "key.keyboard.m")));
		assertFalse(KeyDefaults.conflicts("key.keyboard.m", "key.keyboard.m", Arrays.asList("key.keyboard.w")));
		assertFalse(KeyDefaults.conflicts("key.keyboard.j", "key.keyboard.m", Arrays.asList("key.keyboard.j")),
				"selbst gewählte Taste bleibt");
		KeyDefaults k = new KeyDefaults();
		assertTrue(k.needsWorldMapKeyCheck());
		k.markWorldMapKeyChecked();
		assertFalse(k.needsWorldMapKeyCheck());
	}

	@Test
	void clockFormatsDayTime() {
		assertEquals("06:00", MinimapRenderer.clock(0));
		assertEquals("12:00", MinimapRenderer.clock(6000));
		assertEquals("00:00", MinimapRenderer.clock(18000));
		assertEquals("18:30", MinimapRenderer.clock(12500));
		assertEquals("06:00", MinimapRenderer.clock(24000 * 5));
	}

	@Test
	void netherIsDetectedForAllVersions() {
		assertTrue(MapEngine.isNether("minecraft:the_nether"));
		assertTrue(MapEngine.isNether("dim-1"));
		assertFalse(MapEngine.isNether("minecraft:overworld"));
		assertFalse(MapEngine.isNether("dim0"));
		assertEquals("Nether", WorldMapUi.dimensionName("minecraft:the_nether").isEmpty() ? "" : "Nether");
	}
}
