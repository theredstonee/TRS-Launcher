package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.*;

/** Weltkarte 2: Zoom zur Maus, Schwung, Dimensionen, Export, Wegpunkt-Liste, Lade-Reihenfolge. */
class WorldMap2Test {
	private static final long MS = 1_000_000L;

	// --- Zoom zur Maus ---

	@Test
	void zoomKeepsThePointUnderTheMouseDuringTheWholeAnimation() {
		MapCamera cam = new MapCamera();
		cam.setLimits(0.01f, 16f);
		cam.center(100, 200);
		cam.setScaleNow(2f);
		double ox = 50, oy = -30;
		double wx = cam.worldX(ox), wz = cam.worldZ(oy);
		assertEquals(125, wx, 1e-9);
		assertEquals(185, wz, 1e-9);
		cam.zoomTo(8f, ox, oy);
		for (int i = 0; i < 120; i++) {
			cam.update(1 / 60f);
			assertEquals(wx, cam.worldX(ox), 1e-6, "Punkt unter der Maus bleibt (Bild " + i + ")");
			assertEquals(wz, cam.worldZ(oy), 1e-6);
		}
		assertEquals(8f, cam.scale(), 1e-6f);
		assertFalse(cam.moving());
	}

	@Test
	void zoomingAgainDuringTheAnimationDoesNotJump() {
		MapCamera cam = new MapCamera();
		cam.setLimits(0.01f, 16f);
		cam.center(0, 0);
		cam.setScaleNow(1f);
		cam.zoomBy(MapCamera.wheelFactor(3), -80, 40);
		cam.update(1 / 60f);
		cam.update(1 / 60f);
		// Zweite Raste an anderer Stelle: der Punkt dort bleibt ab jetzt stehen, kein Sprung.
		double wx = cam.worldX(120), wz = cam.worldZ(-10);
		cam.zoomBy(MapCamera.wheelFactor(1), 120, -10);
		for (int i = 0; i < 60; i++) {
			cam.update(1 / 144f);
			assertEquals(wx, cam.worldX(120), 1e-6);
			assertEquals(wz, cam.worldZ(-10), 1e-6);
		}
		assertEquals(Math.pow(1.25, 4), cam.targetScale(), 1e-5);
	}

	@Test
	void zoomIsClampedAndLogarithmicallySymmetric() {
		MapCamera cam = new MapCamera();
		cam.setLimits(0.5f, 4f);
		cam.setScaleNow(1f);
		cam.zoomBy(100, 0, 0);
		assertEquals(4f, cam.targetScale(), 1e-6f);
		cam.zoomBy(1e-4, 0, 0);
		assertEquals(0.5f, cam.targetScale(), 1e-6f);
		// Rein wie raus gleich schnell: nach gleicher Zeit gleicher Anteil (in log).
		MapCamera in = new MapCamera(), out = new MapCamera();
		in.setLimits(0.01f, 100f);
		out.setLimits(0.01f, 100f);
		in.setScaleNow(1f);
		out.setScaleNow(1f);
		in.zoomTo(4f, 0, 0);
		out.zoomTo(0.25f, 0, 0);
		in.update(0.05f);
		out.update(0.05f);
		assertEquals(Math.log(in.scale()), -Math.log(out.scale()), 1e-4);
	}

	// --- Schwung ---

	@Test
	void flingGlidesWithTheReleaseSpeedAndStops() {
		MapCamera cam = new MapCamera();
		cam.setLimits(0.01f, 16f);
		cam.center(0, 0);
		cam.setScaleNow(2f);
		long t = 1_000 * MS;
		cam.beginDrag(0, 0, t);
		for (int i = 1; i <= 10; i++) cam.dragTo(i * 10, 0, t + i * 10 * MS); // 1000 GUI-px/s nach rechts
		assertEquals(-50, cam.centerX(), 1e-9, "Karte folgt der Maus 1:1 (100 px bei Zoom 2 = 50 Blöcke)");
		cam.endDrag(t + 105 * MS, true);
		assertEquals(1000, cam.velocityX(), 1.0);
		double expected = cam.centerX() - cam.remainingGlide() / cam.scale();
		for (int i = 0; i < 600 && cam.moving(); i++) cam.update(1 / 60f);
		assertFalse(cam.moving(), "Schwung endet");
		// Gleitweg ≈ v/F (bis auf den Rest unter der Mindestgeschwindigkeit).
		assertEquals(expected, cam.centerX(), MapCamera.MIN_SPEED / MapCamera.FRICTION / cam.scale() + 1e-6);
		assertEquals(0, cam.centerZ(), 1e-9);
	}

	@Test
	void flingIsFrameRateIndependent() {
		double[] ends = new double[2];
		float[] dts = {1 / 30f, 1 / 240f};
		for (int k = 0; k < 2; k++) {
			MapCamera cam = new MapCamera();
			cam.setLimits(0.01f, 16f);
			cam.setScaleNow(1f);
			long t = 0;
			cam.beginDrag(0, 0, t);
			for (int i = 1; i <= 6; i++) cam.dragTo(0, i * 15, t + i * 15 * MS);
			cam.endDrag(t + 92 * MS, true);
			for (int i = 0; i < 3000 && cam.moving(); i++) cam.update(dts[k]);
			ends[k] = cam.centerZ();
		}
		assertEquals(ends[0], ends[1], 1.5, "30 und 240 Bilder/s gleiten gleich weit");
	}

	@Test
	void noFlingWhenTheMouseStoodStillOrInertiaIsOff() {
		MapCamera cam = new MapCamera();
		cam.setLimits(0.01f, 16f);
		cam.setScaleNow(1f);
		cam.beginDrag(0, 0, 0);
		for (int i = 1; i <= 5; i++) cam.dragTo(i * 20, 0, i * 10 * MS);
		cam.endDrag(50 * MS + 250 * MS, true);
		assertFalse(cam.moving(), "Maus stand vor dem Loslassen still");
		cam.beginDrag(0, 0, 0);
		for (int i = 1; i <= 5; i++) cam.dragTo(i * 20, 0, i * 10 * MS);
		cam.endDrag(55 * MS, false);
		assertFalse(cam.moving(), "Schwung ausgeschaltet");
		// Ein Ruck wird gedeckelt.
		cam.beginDrag(0, 0, 0);
		cam.dragTo(5000, 0, 5 * MS);
		cam.endDrag(6 * MS, true);
		assertEquals(MapCamera.MAX_SPEED, Math.hypot(cam.velocityX(), cam.velocityY()), 1e-6);
	}

	// --- Dimensionen ---

	@Test
	void netherOverworldConversion() {
		assertEquals(8.0, MapDimensions.factor("minecraft:the_nether", "minecraft:overworld"), 1e-12);
		assertEquals(0.125, MapDimensions.factor("minecraft:overworld", "minecraft:the_nether"), 1e-12);
		assertEquals(8.0, MapDimensions.factor("dim-1", "dim0"), 1e-12);
		assertEquals(1.0, MapDimensions.factor("minecraft:overworld", "minecraft:overworld"), 1e-12);
		assertTrue(Double.isNaN(MapDimensions.factor("minecraft:the_end", "minecraft:overworld")));
		assertTrue(Double.isNaN(MapDimensions.factor("mymod:moon", "minecraft:overworld")));
		assertEquals(800, MapDimensions.convert(100, "minecraft:the_nether", "minecraft:overworld"));
		assertEquals(-800, MapDimensions.convert(-100, "minecraft:the_nether", "minecraft:overworld"));
		assertEquals(100, MapDimensions.convert(807, "minecraft:overworld", "minecraft:the_nether"));
		assertEquals(-1, MapDimensions.convert(-1, "minecraft:overworld", "minecraft:the_nether"), "abrunden wie Minecraft");
		assertEquals(-2, MapDimensions.convert(-9, "minecraft:overworld", "minecraft:the_nether"));
		assertEquals("minecraft:overworld", MapDimensions.counterpart("minecraft:the_nether"));
		assertEquals("dim-1", MapDimensions.counterpart("dim0"));
		assertNull(MapDimensions.counterpart("minecraft:the_end"));
	}

	@Test
	void folderNamesDecodeBackToDimensionIds() {
		for (String id : new String[]{"minecraft:the_nether", "minecraft:overworld", "dim-1", "dim1", "cave-1", "cave7", "surface",
				"twilightforest:twilight_forest"}) {
			assertEquals(id, MapDimensions.decodeDirName(MapDisk.safeName(id)), id);
		}
		assertNull(MapDimensions.decodeDirName("minecraft_the_nether-00000000"), "falscher Prüfwert");
		assertNull(MapDimensions.decodeDirName("kein-name"));
	}

	@Test
	void savedDimensionsAreFoundEvenWhenNotThere(@TempDir Path dir) throws IOException {
		TrsModules modules = new TrsModules();
		modules.worldMap.setEnabled(true);
		MapEngine e = new MapEngine(modules, dir, true);
		MapTest.FakePlatform p = new MapTest.FakePlatform(dir);
		e.tick(p);
		p.dim = "minecraft:the_nether";
		p.sky = 0;
		for (int i = 0; i < 3; i++) e.tick(p);
		p.dim = "minecraft:overworld";
		p.sky = 15;
		e.tick(p); // Nether schließen → speichern
		e.shutdown();
		List<MapDimensions.DimInfo> dims = MapDimensions.scan(e.disk().worldDir(p.world));
		List<String> ids = new ArrayList<String>();
		for (MapDimensions.DimInfo d : dims) ids.add(d.id);
		assertEquals(Arrays.asList("minecraft:overworld", "minecraft:the_nether"), ids, "Reihenfolge Oberwelt, Nether");
		assertTrue(Files.isRegularFile(dims.get(1).dir.resolve(MapDisk.DIMENSION_FILE)));
		MapDimensions.LayerInfo li = MapDimensions.defaultLayer(dims.get(1));
		assertNotNull(li);
		assertTrue(li.cave(), "Nether: Höhlenschicht statt Bedrock-Decke");
		assertEquals(li.caveBand() * MapEngine.CAVE_BAND + MapEngine.CAVE_BAND + 3, li.caveRef());
		// Eine Ebene davon lässt sich von außen öffnen und liefert ihre Bereiche.
		MapLayer view = new MapLayer(li.id, 99, li.caveRef(), e.disk(), li.dir);
		assertFalse(view.knownKeys().isEmpty());
	}

	@Test
	void defaultLayerPrefersSurfaceOutsideTheNether() {
		List<MapDimensions.LayerInfo> ls = new ArrayList<MapDimensions.LayerInfo>();
		ls.add(new MapDimensions.LayerInfo("surface", null, 4));
		ls.add(new MapDimensions.LayerInfo("cave5", null, 9));
		ls.add(new MapDimensions.LayerInfo("cave2", null, 30));
		assertEquals("surface", MapDimensions.defaultLayer(new MapDimensions.DimInfo("minecraft:overworld", null, ls)).id);
		assertEquals("cave2", MapDimensions.defaultLayer(new MapDimensions.DimInfo("minecraft:the_nether", null, ls)).id);
		assertEquals(16, MapDimensions.bandBottom(2));
	}

	// --- Wegpunkt-Liste ---

	@Test
	void waypointListSearchesFiltersAndSortsByDistance() {
		List<Waypoint> all = new ArrayList<Waypoint>();
		Waypoint base = new Waypoint("Basis Nord", 100, 64, -100, "minecraft:overworld", 0xFF0000);
		Waypoint mine = new Waypoint("Mine", 10, 12, 10, "minecraft:overworld", 0x00FF00);
		Waypoint portal = new Waypoint("Portal", 20, 70, 0, "minecraft:the_nether", 0x0000FF);
		Waypoint end = new Waypoint("Stadt", 1000, 60, 0, "minecraft:the_end", 0xFFFFFF);
		Waypoint any = new Waypoint("Überall", 0, 64, 5, "", 0xFFFFFF);
		all.addAll(Arrays.asList(base, mine, portal, end, any));
		String ow = "minecraft:overworld";

		List<WaypointFilter.Row> rows = WaypointFilter.apply(all, "", WaypointFilter.ALL, ow, 0, 0);
		assertEquals(5, rows.size());
		assertSame(any, rows.get(0).waypoint, "am nächsten zuerst");
		assertSame(mine, rows.get(1).waypoint);
		assertSame(base, rows.get(2).waypoint, "Basis (141) vor dem Portal (Nether 20 → 164 Oberwelt)");
		assertSame(portal, rows.get(3).waypoint);
		assertTrue(rows.get(3).converted);
		assertEquals(Math.hypot(20.5 * 8, 0.5 * 8), rows.get(3).distance, 1e-9);
		assertSame(end, rows.get(4).waypoint, "End ohne Entfernung ans Ende");
		assertTrue(Double.isNaN(rows.get(4).distance));

		// Suche: alle Wörter, egal ob groß/klein; Koordinaten.
		assertEquals(1, WaypointFilter.apply(all, "basis NORD", WaypointFilter.ALL, ow, 0, 0).size());
		assertEquals(0, WaypointFilter.apply(all, "basis süd", WaypointFilter.ALL, ow, 0, 0).size());
		assertSame(base, WaypointFilter.apply(all, "-100", WaypointFilter.ALL, ow, 0, 0).get(0).waypoint);
		// Dimension: Wegpunkte ohne Dimension gelten überall.
		List<WaypointFilter.Row> nether = WaypointFilter.apply(all, "", "minecraft:the_nether", ow, 0, 0);
		assertEquals(2, nether.size());
		assertEquals(Arrays.asList(WaypointFilter.ALL, ow, "minecraft:the_nether", "minecraft:the_end"),
				WaypointFilter.dimensions(all, ow));
		assertEquals("—", WaypointFilter.distanceText(Double.NaN));
		assertEquals("12.4k", WaypointFilter.distanceText(12_400));
		assertEquals("850", WaypointFilter.distanceText(849.6));
	}

	// --- Export ---

	@Test
	void exportPlanScalesByPowersOfTwoWithinTheLimit() {
		MapExport.Plan p = MapExport.plan(0, 0, 1000, 500, 16384);
		assertEquals(1, p.k);
		assertEquals(1000, p.width);
		assertEquals(500, p.height);
		assertFalse(p.summaries());
		p = MapExport.plan(-5000, -100, 5000, 100, 4096);
		assertEquals(4, p.k, "10000 Blöcke in 4096 px → 4 Blöcke je Pixel");
		assertTrue(p.width <= 4096);
		assertEquals(0, p.bx0 % 4);
		assertEquals(0, p.bz1 % 4);
		assertTrue(p.bx0 <= -5000 && p.bx1 >= 5000);
		assertTrue(p.summaries());
		p = MapExport.plan(3, 3, 3 + 20_000, 3 + 3, 8192);
		assertEquals(4, p.k);
		assertEquals(0, p.bx0);
		assertNull(MapExport.plan(0, 0, 0, 10, 1024), "leer");
		assertNull(MapExport.plan(0, 0, 10_000_000, 10, 8192), "selbst mit 128 Blöcken je Pixel zu groß");
	}

	@Test
	void averageWeightsColorsByOpacity() {
		int[] src = {0xFF000000 | 0xFF0000, 0, 0xFF000000 | 0x0000FF, 0};
		int c = MapExport.average(src, 2, 0, 0, 2, 2);
		assertEquals(0x80, c >>> 24, "halb erkundet = halb deckend");
		assertEquals(0x7F, c >> 16 & 0xFF);
		assertEquals(0x7F, c & 0xFF);
		assertEquals(0, MapExport.average(new int[4], 2, 0, 0, 2, 2));
		assertEquals(0xFF123456, MapExport.average(new int[]{0xFF123456}, 1, 0, 0, 1, 1));
	}

	@Test
	void fileNameIsSafeAndDescriptive() {
		java.util.Date when = new java.util.Date(0);
		String n = MapExport.fileName("mp:play.example.com:25565", "minecraft:the_nether", null, when);
		assertTrue(n.startsWith("trs-map_play.example.com_25565_the_nether_"), n);
		assertTrue(n.endsWith(".png"));
		assertTrue(n.matches("[A-Za-z0-9._-]+"), n);
		MapLayer cave = new MapLayer("cave4", 1, 4 * MapEngine.CAVE_BAND + MapEngine.CAVE_BAND + 3, null, null);
		assertTrue(MapExport.fileName("sp:Neue Welt/..", "dim-1", cave, when).startsWith("trs-map_Neue_Welt_dim-1_y32_"));
	}

	@Test
	void exportWritesAPngFromMemoryAndDisk(@TempDir Path dir) throws IOException {
		MapDisk disk = new MapDisk(dir.resolve("maps"), true);
		Path ld = disk.layerDir("sp:test", "minecraft:overworld", "surface");
		// Bereich (0,0) auf der Platte, Bereich (1,0) nur im Speicher.
		MapLayer writer = new MapLayer("surface", 1, Integer.MIN_VALUE, disk, ld);
		fill(writer.forWrite(0, 0, 1), 0x336699);
		writer.close(MapCompose.INSTANCE);
		MapLayer layer = new MapLayer("surface", 2, Integer.MIN_VALUE, disk, ld);
		fill(layer.forWrite(1, 0, 1), 0xCC3333);
		// Nur ein Chunk erkundet in (1,0)? Nein – ganz, aber eine Ecke bleibt leer:
		layer.peek(1, 0).pixels[MapRegion.AREA - 1] = 0;

		final MapExport[] done = new MapExport[1];
		MapExport.StartResult r = MapExport.start(layer, "sp:test", "minecraft:overworld", null, 8192, dir.resolve("shots"), disk,
				false, job -> done[0] = job);
		assertEquals(MapExport.StartResult.STARTED, r);
		disk.drain();
		assertNotNull(done[0]);
		assertEquals(MapExport.State.DONE, done[0].state(), done[0].error());
		assertTrue(Files.isRegularFile(done[0].file()));
		assertFalse(Files.exists(done[0].file().resolveSibling(done[0].file().getFileName() + ".tmp")));
		BufferedImage img = ImageIO.read(done[0].file().toFile());
		assertEquals(256, img.getWidth());
		assertEquals(128, img.getHeight());
		int left = img.getRGB(10, 10), right = img.getRGB(200, 10);
		assertEquals(0xFF, left >>> 24);
		assertTrue((left & 0xFF) > (left >> 16 & 0xFF), "Platte: blau");
		assertTrue((right >> 16 & 0xFF) > (right & 0xFF), "Speicher: rot");
		assertEquals(0, img.getRGB(255, 127) >>> 24, "unerkundet = durchsichtig");

		// Verkleinert (Übersichten) und sichtbarer Ausschnitt.
		r = MapExport.start(layer, "sp:test", "minecraft:overworld", null, 64, dir.resolve("shots"), disk, false, job -> done[0] = job);
		assertEquals(MapExport.StartResult.STARTED, r);
		disk.drain();
		img = ImageIO.read(done[0].file().toFile());
		assertEquals(64, img.getWidth());
		assertEquals(32, img.getHeight());
		assertEquals(4, done[0].plan().k);
		r = MapExport.start(layer, "sp:test", "minecraft:overworld", new int[]{100, 10, 150, 30}, 8192, dir.resolve("shots"), disk,
				false, job -> done[0] = job);
		disk.drain();
		img = ImageIO.read(done[0].file().toFile());
		assertEquals(50, img.getWidth());
		assertEquals(20, img.getHeight());
		assertTrue((img.getRGB(5, 5) & 0xFF) > (img.getRGB(5, 5) >> 16 & 0xFF), "x 105 liegt noch im blauen Bereich");
		assertTrue((img.getRGB(45, 5) >> 16 & 0xFF) > (img.getRGB(45, 5) & 0xFF), "x 145 im roten");

		MapLayer empty = new MapLayer("surface", 3, Integer.MIN_VALUE, null, null);
		assertEquals(MapExport.StartResult.EMPTY, MapExport.start(empty, "sp:x", "d", null, 8192, dir, null, false, null));
	}

	private static void fill(MapRegion r, int rgb) {
		int[] px = new int[256], h = new int[256];
		Arrays.fill(px, MapColors.KNOWN | rgb);
		Arrays.fill(h, 64);
		for (int cz = 0; cz < 8; cz++) {
			for (int cx = 0; cx < 8; cx++) r.writeChunk(r.rx * 8 + cx, r.rz * 8 + cz, px, h, 1);
		}
	}

	@Test
	void pngWriterStreamsRowsAndCleansUp(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("a.png");
		try (MapPng png = new MapPng(f, 3, 2)) {
			png.writeRow(new int[]{0xFFFF0000, 0x8000FF00, 0}, 0);
			png.writeRow(new int[]{9, 9, 0xFF0000FF, 0xFFFFFFFF, 0xFF000000}, 2);
			png.finish();
		}
		BufferedImage img = ImageIO.read(f.toFile());
		assertEquals(0xFFFF0000, img.getRGB(0, 0));
		assertEquals(0x80, img.getRGB(1, 0) >>> 24);
		assertEquals(0, img.getRGB(2, 0) >>> 24);
		assertEquals(0xFF0000FF, img.getRGB(0, 1));
		Path g = dir.resolve("b.png");
		try (MapPng png = new MapPng(g, 2, 2)) {
			png.writeRow(new int[]{0, 0}, 0);
			// abgebrochen – keine halbe Datei
		}
		assertFalse(Files.exists(g));
		assertFalse(Files.exists(dir.resolve("b.png.tmp")));
	}

	// --- Laden nach Sichtbarkeit ---

	@Test
	void diskRunsWritesFirstThenNewestFrameLoadsThenExport() {
		PriorityQueue<MapDisk.Task> q = new PriorityQueue<MapDisk.Task>();
		MapDisk.Task oldLoad = new MapDisk.Task(MapDisk.PRIO_LOAD, 1, 1, () -> { });
		MapDisk.Task newLoadA = new MapDisk.Task(MapDisk.PRIO_LOAD, 5, 2, () -> { });
		MapDisk.Task newLoadB = new MapDisk.Task(MapDisk.PRIO_LOAD, 5, 3, () -> { });
		MapDisk.Task save = new MapDisk.Task(MapDisk.PRIO_WRITE, 0, 4, () -> { });
		MapDisk.Task export = new MapDisk.Task(MapDisk.PRIO_EXPORT, 9, 5, () -> { });
		q.addAll(Arrays.asList(export, oldLoad, newLoadB, save, newLoadA));
		assertSame(save, q.poll());
		assertSame(newLoadA, q.poll(), "neuestes Bild zuerst, darin Mitte (zuerst angefragt) zuerst");
		assertSame(newLoadB, q.poll());
		assertSame(oldLoad, q.poll());
		assertSame(export, q.poll());
	}

	@Test
	void tilesAreRequestedFromTheCenterOutwards() {
		WorldMapTiles t = new WorldMapTiles();
		int n = t.centerOut(-3, -2, 3, 2, 0.5 - 0.5, 0.5 - 0.5);
		assertEquals(35, n);
		long[] order = t.orderForTests();
		assertEquals(MapRegion.key(0, 0), order[0]);
		double prev = -1;
		for (int i = 0; i < n; i++) {
			int x = MapRegion.keyX(order[i]), z = MapRegion.keyZ(order[i]);
			double d = x * x + z * z;
			assertTrue(d >= prev, "aufsteigender Abstand");
			prev = d;
		}
	}

	/** Leinwand nur zum Messen: jedes Zeichen 6 Pixel. */
	private static final dev.theredstonee.trsclient.core.ui.Canvas MEASURE = new dev.theredstonee.trsclient.core.ui.Canvas() {
		public void fill(int x1, int y1, int x2, int y2, int argb) { }
		public void text(String text, int x, int y, int argb, boolean shadow) { }
		public int textWidth(String text) { return text.length() * 6; }
		public int lineHeight() { return 9; }
		public String clip(String text, int maxWidth) { return text.length() * 6 <= maxWidth ? text : text.substring(0, Math.max(0, maxWidth / 6)); }
		public void flush() { }
		public void scissor(int x1, int y1, int x2, int y2) { }
		public void noScissor() { }
		public void raise(float z) { }
		public void push() { }
		public void translate(float x, float y) { }
		public void scale(float factor) { }
		public void pop() { }
	};

	@Test
	void cursorCoordinatesStayCompleteInEveryShorterVariant(@TempDir Path dir) {
		TrsModules modules = new TrsModules();
		modules.worldMap.setEnabled(true);
		MapEngine e = new MapEngine(modules, dir, true);
		MapTest.FakePlatform p = new MapTest.FakePlatform(dir);
		p.dim = "minecraft:the_nether";
		e.tick(p);
		WorldMapUi ui = new WorldMapUi(e, new WorldMapUi.Host() {
			public void closeScreen() { }
			public void playClick() { }
			public boolean isMapKey(int rawKey) { return false; }
		});
		List<String> v = ui.coordinateTexts(4, 38, -2);
		assertTrue(v.size() >= 3);
		for (String s : v) {
			assertTrue(s.contains("4") && s.contains("-2"), s);
			assertTrue(s.contains("32") && s.contains("-16"), "Oberwelt X und Z immer dabei: " + s);
		}
		for (int i = 1; i < v.size(); i++) assertTrue(v.get(i).length() < v.get(i - 1).length(), "wird kürzer");
		modules.worldMapNetherCoords.set(false);
		for (String s : ui.coordinateTexts(4, 38, -2)) assertFalse(s.contains("32"), s);
		assertEquals("X 1 Z 2", WorldMapUi.tight("X 1   Z 2"));
	}

	@Test
	void longFileNamesAreShortenedInTheMiddle() {
		String name = "trs-map_trs-maps-1.21.11_overworld_2026-09-28_12.30.05.png";
		String s = WorldMapUi.middleEllipsis(MEASURE, name, 30 * 6);
		assertTrue(s.length() <= 30, s);
		assertTrue(s.startsWith("trs-map_") && s.endsWith(".png") && s.contains("…"), s);
		assertEquals(name, WorldMapUi.middleEllipsis(MEASURE, name, 1000));
	}

	@Test
	void tileStampsChangeOnlyForTheTouchedTile() {
		MapLayer layer = new MapLayer("surface", 1, Integer.MIN_VALUE, null, null);
		long a = layer.tileStamp(1, 0, 0), b = layer.tileStamp(1, 1, 0), m = layer.tileStamp(2, 0, 0);
		layer.forWrite(3, 4, 1); // Kachel (0,0) bzw. Weitkachel (0,0)
		assertNotEquals(a, layer.tileStamp(1, 0, 0));
		assertEquals(b, layer.tileStamp(1, 1, 0), "Nachbarkachel bleibt");
		assertNotEquals(m, layer.tileStamp(2, 0, 0));
		long before = layer.tileStamp(1, 1, 0);
		layer.summaryChanged();
		assertNotEquals(before, layer.tileStamp(1, 1, 0), "unbekannte Änderung: alle neu");
		assertTrue(layer.known(MapRegion.key(3, 4)));
		assertFalse(layer.known(MapRegion.key(4, 4)));
	}
}
