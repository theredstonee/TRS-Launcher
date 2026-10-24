package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Karte v3 (Vorschau): Schalter, Dateiformat 2, Schattierung, Pyramide, Laden aus den bisherigen Karten. */
class MapV3Test {
	@AfterEach
	void reset() {
		MapPreview.setForTests(null);
	}

	static int brightness(int rgb) {
		return ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
	}

	// --- Schalter ---

	@Test
	void switchComesFromPropertyOrBuildList() {
		assertFalse(MapPreview.decide(null, null), "normale Builds: aus");
		assertTrue(MapPreview.decide(null, "map-v3\n"), "Vorschau-Build");
		assertTrue(MapPreview.decide("map-v3", null), "System-Eigenschaft");
		assertFalse(MapPreview.decide("-map-v3", "map-v3"), "Eigenschaft schaltet einen Vorschau-Build aus");
		assertFalse(MapPreview.decide("!map-v3", "map-v3"));
		assertTrue(MapPreview.decide("other, map-v3", null));
		assertFalse(MapPreview.decide("other", null));
		assertTrue(MapPreview.decide("", "other\nmap-v3"), "leere Eigenschaft zählt nicht");
	}

	// --- Dateiformat ---

	private static MapRegion sampleRegion() {
		MapRegion r = new MapRegion(4, -2);
		for (int i = 0; i < MapRegion.AREA; i++) {
			boolean water = i % 7 == 0;
			r.pixels[i] = MapColors.KNOWN | (water ? MapColors.WATER : 0) | (i * 2654435 & 0xFFFFFF);
			r.heights[i] = (short) (60 + (i % 13));
		}
		r.ensureWater();
		for (int i = 0; i < MapRegion.AREA; i += 7) {
			r.depth[i] = (byte) (1 + i % 33);
			r.floor[i] = 0xC2B280 ^ (i & 0xFF);
		}
		return r;
	}

	@Test
	void format2RoundTripsWaterAndSummary64() throws IOException {
		MapRegion r = sampleRegion();
		int[] out = new int[MapRegion.AREA];
		MapShade.compose(null, r, out);
		int[] s32 = MapCompose.summaryOf(out), s64 = MapShade.summary64Of(out);
		byte[] data = RegionCodec.encodeV3(r.rx, r.rz, r.pixels, r.heights, r.depth, r.floor, s32, s64);
		RegionCodec.Decoded d = RegionCodec.decode(new ByteArrayInputStream(data));
		assertEquals(RegionCodec.VERSION_V3, d.version);
		assertArrayEquals(r.pixels, d.pixels);
		assertArrayEquals(r.heights, d.heights);
		assertArrayEquals(r.depth, d.depth);
		assertArrayEquals(r.floor, d.floor);
		assertArrayEquals(s32, d.summary);
		assertArrayEquals(s64, d.summary64);
		// Nur die Übersichten lesen geht in beiden Fassungen.
		assertArrayEquals(s32, RegionCodec.readSummary(new ByteArrayInputStream(data)));
		assertArrayEquals(s64, RegionCodec.readSummary64(new ByteArrayInputStream(data)));
		// Ohne Wasser: keine Wasser-Ebenen gespeichert.
		MapRegion dry = new MapRegion(0, 0);
		dry.pixels[5] = MapColors.KNOWN | 0x707070;
		RegionCodec.Decoded dd = RegionCodec.decode(new ByteArrayInputStream(
				RegionCodec.encodeV3(0, 0, dry.pixels, dry.heights, null, null, null, null)));
		assertNull(dd.depth);
		assertNull(dd.summary64);
	}

	@Test
	void oldFilesStillReadAndHaveNoSummary64() throws IOException {
		MapRegion r = sampleRegion();
		byte[] v1 = RegionCodec.encode(r.rx, r.rz, r.pixels, r.heights, null);
		RegionCodec.Decoded d = RegionCodec.decode(new ByteArrayInputStream(v1));
		assertEquals(RegionCodec.VERSION, d.version);
		assertArrayEquals(r.pixels, d.pixels);
		assertNull(d.depth);
		assertNull(RegionCodec.readSummary64(new ByteArrayInputStream(v1)));
	}

	// --- Schattierung ---

	/** Bereich mit einer Rampe nach Osten (Höhe steigt mit x) bzw. fällt. */
	private static MapRegion ramp(int dir) {
		MapRegion r = new MapRegion(0, 0);
		for (int z = 0; z < MapRegion.SIZE; z++) {
			for (int x = 0; x < MapRegion.SIZE; x++) {
				int i = z * MapRegion.SIZE + x;
				r.pixels[i] = MapColors.KNOWN | 0x7F7F7F;
				r.heights[i] = (short) (64 + dir * (x / 2));
			}
		}
		return r;
	}

	@Test
	void reliefLightsSlopesFacingTopLeftAndShadesTheOthers() {
		int[] up = new int[MapRegion.AREA], down = new int[MapRegion.AREA], flat = new int[MapRegion.AREA];
		MapShade.compose(null, ramp(1), up);
		MapShade.compose(null, ramp(-1), down);
		MapShade.compose(null, ramp(0), flat);
		int i = 40 * MapRegion.SIZE + 41;
		// Höhe steigt nach Osten → Hang zeigt nach Westen (zum Licht links oben) → heller.
		assertTrue(brightness(up[i]) > brightness(flat[i]), "zum Licht geneigt ist heller");
		assertTrue(brightness(down[i]) < brightness(flat[i]), "vom Licht weg ist dunkler");
		// Deutlich kräftiger als das alte Relief bei einer 1-Block-Stufe.
		float oldStep = MapColors.relief(1);
		float newStep = MapShade.relief(MapShade.slope(1, 0, 1, 0, 1));
		assertTrue(newStep - 1f > (oldStep - 1f) * 1.2f, "stärker: " + newStep + " vs " + oldStep);
		// Gesättigt: auch an Klippen nie schwarz/weiß.
		for (int s = -60; s <= 60; s += 3) {
			float f = MapShade.relief(s);
			assertTrue(f > 0.45f && f < 1.45f, "gesättigt: " + f);
		}
		// Einzelne Mauer (1 Block breit): oben hell, Schattenseite dunkel – nicht flach.
		assertTrue(MapShade.slope(66, 64, 64, 64, 64) > 0f);
		assertTrue(MapShade.slope(64, 64, 64, 66, 64) < 0f);
	}

	@Test
	void waterDepthIsShadedAtComposeTimeOverALongRange() {
		int sand = 0xF7E9A3, water = MapShade.waterColor(0x3F76E4);
		int prev = Integer.MAX_VALUE;
		for (int d = 1; d <= 40; d += 3) {
			int c = MapShade.water(water, sand, d, 1f, 1f);
			assertTrue(brightness(c) < prev, "tiefer = dunkler (" + d + ")");
			prev = brightness(c);
		}
		// Flaches Wasser zeigt den Grund, die Farbe ist noch nach 10 Blöcken nicht ganz deckend.
		assertTrue(brightness(MapShade.water(water, sand, 1, 1f, 1f)) > brightness(MapShade.water(water, 0x202020, 1, 1f, 1f)) + 60);
		assertTrue(MapShade.waterAlpha(8) < 0.88f, "längerer Verlauf als bisher (alt: 0,92 nach 8 Blöcken, neu " + MapShade.waterAlpha(8) + ")");
		assertTrue(MapShade.waterAlpha(12) < 0.75f, "Grund bis über 10 Blöcke erkennbar (" + MapShade.waterAlpha(12) + ")");
		assertTrue(MapShade.waterAlpha(40) > 0.93f);
		// Grund nicht gefunden = tief: reine Wasserfarbe, abgedunkelt.
		assertEquals(MapColors.mul(water, MapShade.waterDark(40)), MapShade.water(water, 0, 40, 1f, 1f));
	}

	@Test
	void composeUsesStoredDepthAndKeepsOldWaterAsItWas() {
		MapRegion r = new MapRegion(0, 0);
		Arrays.fill(r.heights, (short) 62);
		for (int i = 0; i < MapRegion.AREA; i++) r.pixels[i] = MapColors.KNOWN | MapColors.WATER | 0x3A6BD0;
		int[] old = new int[MapRegion.AREA];
		MapShade.compose(null, r, old);
		// Ohne Tiefe (alte Karte): Farbe bleibt fast gleich.
		assertTrue(Math.abs(brightness(old[100] & 0xFFFFFF) - brightness(0x3A6BD0)) < 30);
		r.ensureWater();
		for (int i = 0; i < MapRegion.AREA; i++) {
			r.depth[i] = (byte) (i % MapRegion.SIZE < 64 ? 2 : 30);
			r.floor[i] = 0xF7E9A3;
		}
		int[] out = new int[MapRegion.AREA];
		MapShade.compose(null, r, out);
		assertTrue(brightness(out[10]) > brightness(out[100]) + 60, "flach (Sand sichtbar) heller als tief");
	}

	@Test
	void reliefCrossesRegionBordersWhenTheNeighbourIsLoaded() {
		MapLayer layer = new MapLayer("surface", 1, Integer.MIN_VALUE, null, null);
		MapRegion a = layer.forWrite(0, 0, 0), west = layer.forWrite(-1, 0, 0);
		for (int i = 0; i < MapRegion.AREA; i++) {
			a.pixels[i] = MapColors.KNOWN | 0x7F7F7F;
			a.heights[i] = 70;
			west.pixels[i] = MapColors.KNOWN | 0x7F7F7F;
			west.heights[i] = 60;
		}
		int[] with = new int[MapRegion.AREA];
		MapShade.compose(layer, a, with);
		MapLayer alone = new MapLayer("surface", 2, Integer.MIN_VALUE, null, null);
		MapRegion b = alone.forWrite(0, 0, 0);
		b.copyFrom(a);
		int[] without = new int[MapRegion.AREA];
		MapShade.compose(alone, b, without);
		int edge = 50 * MapRegion.SIZE;
		assertTrue(brightness(with[edge]) > brightness(without[edge]), "Kante zum tieferen Nachbarn ist beleuchtet");
		assertEquals(with[edge + 5], without[edge + 5], "innen gleich");
		// Neuer Nachbar → anderer Nachbar-Stand → Rand wird neu schattiert.
		long before = MapTextures.neighbourStamp(alone, b);
		alone.forWrite(-1, 0, 0);
		assertNotEquals(before, MapTextures.neighbourStamp(alone, b));
	}

	@Test
	void seabedTurnsBluishNotMuddyAndFoliageSandAreCalibrated() {
		int water = MapShade.waterColor(0x3F76E4);
		int sand = 0xD8CDA1;
		int seen = MapShade.absorb(sand, water, 4);
		assertTrue((seen & 0xFF) > ((seen >> 16) & 0xFF) + 15, "Sand unter Wasser bläulich");
		int deeper = MapShade.absorb(sand, water, 16);
		assertTrue(((deeper >> 16) & 0xFF) < ((seen >> 16) & 0xFF), "tiefer = mehr Rot geschluckt");
		// Laub: dunkler und satter.
		int leaf = 0x6A9A2E;
		int f = MapShade.foliage(leaf);
		assertTrue(brightness(f) < brightness(leaf));
		// Sand: heller und weniger gelb; Schnee bleibt unberührt.
		int lifted = MapShade.lift(sand);
		assertTrue(brightness(lifted) > brightness(sand));
		assertTrue(((lifted >> 16) & 0xFF) - (lifted & 0xFF) < ((sand >> 16) & 0xFF) - (sand & 0xFF), "weniger gelb");
		assertEquals(0xF8FAFF, MapShade.lift(0xF8FAFF));
		assertEquals(0x507030, MapShade.lift(0x507030), "dunkle Farben unverändert");
		// Senke zwischen Baumkronen: dunkler; ebene Fläche nicht.
		assertTrue(MapShade.occlusion(64, 70, 70, 64, 64) < 1f);
		assertEquals(1f, MapShade.occlusion(64, 64, 65, 64, 64), 1e-6);
	}

	@Test
	void livelierTintThanBefore() {
		int grass = 0x91BD59;
		assertTrue(brightness(MapShade.tint(MapColors.MAP_GRASS, grass)) > brightness(MapColors.tint(MapColors.MAP_GRASS, grass)));
		assertTrue(brightness(MapShade.tint(MapColors.MAP_PLANT, 0x59AE30)) > brightness(MapColors.tint(MapColors.MAP_PLANT, 0x59AE30)));
		assertEquals(0x707070, MapShade.tint(0x707070, -1));
		assertEquals(0x707070, MapShade.tint(0x707070, 0xFFFFFF));
	}

	@Test
	void scannerKeepsDepthAndFloorSeparateInV3() {
		MapTest.FakeReader r = new MapTest.FakeReader();
		int w = MapColors.MAP_WATER;
		r.column(2, 0, new int[]{MapTest.STONE, MapTest.SAND, w, w, w, w, w, ChunkReader.AIR});
		int[] px = new int[256], h = new int[256], d = new int[256], f = new int[256];
		assertTrue(ColumnScanner.surface(r, 0, 0, ColumnScanner.NO_CUT, false, px, h, d, f));
		assertEquals(6, h[2]);
		assertEquals(5, d[2], "Tiefe");
		assertEquals(MapTest.SAND, f[2], "Grund ungeschattet");
		assertEquals(MapColors.KNOWN | MapColors.WATER | MapShade.waterColor(0x3F76E4), px[2], "Oberfläche ungemischt");
		assertEquals(0, d[0], "Land ohne Tiefe");
	}

	// --- Weltkarte: Pyramide ---

	@Test
	void pyramidKeepsTexelsAtMostOneScreenPixel() {
		assertEquals(0, WorldMapPyramid.levelFor(1f, 100, 400), "ab 1 px/Block: Bereiche");
		assertEquals(2, WorldMapPyramid.levelFor(1f, 600, 400), "zu viele Bereiche: Stufe 2");
		assertEquals(2, WorldMapPyramid.levelFor(0.5f, 900, 400));
		assertEquals(2, WorldMapPyramid.levelFor(0.3f, 900, 400));
		assertEquals(3, WorldMapPyramid.levelFor(0.25f, 900, 400));
		assertEquals(4, WorldMapPyramid.levelFor(0.125f, 900, 400));
		assertEquals(5, WorldMapPyramid.levelFor(1f / 16f, 900, 400));
		assertEquals(6, WorldMapPyramid.levelFor(1f / 32f, 900, 400));
		for (float px = 0.04f; px < 1f; px *= 1.17f) {
			int l = WorldMapPyramid.levelFor(px, 10_000, 400);
			assertTrue(WorldMapPyramid.blocksPerTexel(l) * px <= 1.0001f || l == WorldMapPyramid.MIN_LEVEL,
					"Texel ≤ 1 Bildschirmpixel bei " + px);
		}
	}

	// --- Platte: getrennter Ordner, bisherige Karten nur lesen ---

	@Test
	void v3ReadsOldMapsButWritesOnlyItsOwnFolder(@TempDir Path dir) throws IOException {
		Path oldRoot = dir.resolve("maps"), newRoot = dir.resolve("maps-v3");
		MapDisk oldDisk = new MapDisk(oldRoot, true);
		Path oldLayer = oldDisk.layerDir("sp:Welt", "minecraft:overworld", "surface");
		MapLayer old = new MapLayer("surface", 1, Integer.MIN_VALUE, oldDisk, oldLayer);
		int[] px = new int[256], h = new int[256];
		Arrays.fill(px, MapColors.KNOWN | 0x557733);
		Arrays.fill(h, 70);
		old.forWrite(0, 0, 1).writeChunk(1, 1, px, h, 1);
		old.close(MapCompose.INSTANCE);
		Path oldFile = MapDisk.regionFile(oldLayer, 0, 0);
		assertTrue(Files.isRegularFile(oldFile));
		byte[] oldBytes = Files.readAllBytes(oldFile);

		MapPreview.setForTests(true);
		MapDisk disk = new MapDisk(newRoot, true);
		disk.enableV3(oldRoot);
		Path newLayer = disk.layerDir("sp:Welt", "minecraft:overworld", "surface");
		assertEquals(oldLayer.toAbsolutePath().normalize(), disk.legacyDirOf(newLayer).toAbsolutePath().normalize());
		MapLayer layer = new MapLayer("surface", 2, Integer.MIN_VALUE, disk, newLayer);
		assertTrue(layer.listed());
		long key = MapRegion.key(0, 0);
		assertTrue(layer.known(key) && layer.legacyOnly(key), "alter Bereich bekannt");
		layer.get(0, 0, 5);
		MapRegion r = layer.get(0, 0, 5);
		assertNotNull(r, "aus den bisherigen Karten geladen");
		assertEquals(70, r.height(16, 16));
		// Neu abtasten (mit Wasser) und speichern → nur im neuen Ordner, alte Datei unverändert.
		int[] d = new int[256], f = new int[256];
		Arrays.fill(d, 3);
		Arrays.fill(f, 0xF7E9A3);
		int[] wpx = new int[256];
		Arrays.fill(wpx, MapColors.KNOWN | MapColors.WATER | 0x3F76E4);
		layer.forWrite(0, 0, 6).writeChunk(2, 2, wpx, h, d, f, 6);
		layer.close(MapCompose.INSTANCE);
		assertArrayEquals(oldBytes, Files.readAllBytes(oldFile), "bisherige Karte nie beschrieben");
		Path newFile = MapDisk.regionFile(newLayer, 0, 0);
		RegionCodec.Decoded dec = RegionCodec.decode(Files.newInputStream(newFile));
		assertEquals(RegionCodec.VERSION_V3, dec.version);
		assertEquals(3, dec.depth[(32 + 1) * MapRegion.SIZE + 33] & 0xFF);
		assertEquals(70, dec.heights[16 * MapRegion.SIZE + 16], "alter Inhalt übernommen");
		assertNotNull(dec.summary64);

		// Zurück auf die bisherige Fassung: liest ihren Ordner wie zuvor.
		MapPreview.setForTests(false);
		MapLayer back = new MapLayer("surface", 3, Integer.MIN_VALUE, new MapDisk(oldRoot, true), oldLayer);
		back.get(0, 0, 7);
		MapRegion br = back.get(0, 0, 7);
		assertNotNull(br);
		assertEquals(MapColors.KNOWN | 0x557733, br.pixel(16, 16));
	}

	@Test
	void dimensionListMergesOldMaps(@TempDir Path dir) throws IOException {
		MapDisk oldDisk = new MapDisk(dir.resolve("maps"), true);
		oldDisk.rememberDimension("sp:Welt", "minecraft:the_nether");
		Path cave = oldDisk.layerDir("sp:Welt", "minecraft:the_nether", "cave4");
		Files.createDirectories(cave);
		Files.write(MapDisk.regionFile(cave, 0, 0), new byte[] {1});
		MapDisk disk = new MapDisk(dir.resolve("maps-v3"), true);
		disk.enableV3(dir.resolve("maps"));
		Path world = disk.worldDir("sp:Welt");
		List<MapDimensions.DimInfo> dims = MapDimensions.scan(world, disk.legacyDirOf(world));
		assertEquals(1, dims.size());
		assertEquals("minecraft:the_nether", dims.get(0).id);
		assertTrue(dims.get(0).layers.get(0).dir.startsWith(world), "Ordner im neuen Speicher");
		assertTrue(MapDimensions.scan(world).isEmpty(), "ohne bisherige Karten nichts");
	}

	@Test
	void v3TrimDeletesCaveLayersBeforeSurfaces(@TempDir Path dir) throws IOException {
		Path surface = dir.resolve("w").resolve("d").resolve("surface-1");
		Path cave = dir.resolve("w").resolve("d").resolve("cave3-1");
		Files.createDirectories(surface);
		Files.createDirectories(cave);
		List<Path> files = new ArrayList<Path>();
		for (int i = 0; i < 4; i++) {
			Path s = MapDisk.regionFile(surface, i, 0), c = MapDisk.regionFile(cave, i, 0);
			Files.write(s, new byte[1000]);
			Files.write(c, new byte[1000]);
			// Höhlen neuer als die Oberfläche – trotzdem zuerst weg.
			Files.setLastModifiedTime(s, FileTime.fromMillis(1000 + i));
			Files.setLastModifiedTime(c, FileTime.fromMillis(9000 + i));
			files.add(s);
		}
		int deleted = MapDisk.trimV3(dir, 6000, new ArrayList<Path>());
		assertTrue(deleted >= 2);
		for (Path s : files) assertTrue(Files.exists(s), "Oberfläche bleibt, solange Höhlen reichen");
	}

	@Test
	void memoryAndDiskLimitsScale() {
		assertEquals(256, MapEngine.adaptiveMaxRegions(256L << 20));
		assertTrue(MapEngine.adaptiveMaxRegions(4L << 30) > 256);
		assertEquals(1024, MapEngine.adaptiveMaxRegions(64L << 30));
		assertEquals(1024L << 20, MapEngine.v3DiskLimitBytes(256), "Standard 256 MB → 1 GB");
		assertEquals(8192L << 20, MapEngine.v3DiskLimitBytes(4096));
	}

	@Test
	void engineScansTheWholeRenderDistanceInV3(@TempDir Path dir) {
		MapPreview.setForTests(true);
		TrsModules modules = new TrsModules();
		modules.minimap.setEnabled(true);
		MapEngine e = new MapEngine(modules, dir, true);
		assertTrue(e.v3());
		MapTest.FakePlatform p = new MapTest.FakePlatform(dir);
		p.renderDistance = 16;
		for (int i = 0; i < 400 && (e.sampledTotal() < 33 * 33 || e.backlog()); i++) {
			e.tick(p);
			e.frame(false);
		}
		assertTrue(e.sampledTotal() >= 33 * 33, "33×33 Chunks (Sichtweite 16, früher höchstens 25×25): " + e.sampledTotal());
		e.leaveWorld();
		assertTrue(Files.isDirectory(dir.resolve("trsclient").resolve("maps-v3")), "eigener Ordner");
		assertFalse(Files.exists(dir.resolve("trsclient").resolve("maps").resolve("unused")));
	}
}
