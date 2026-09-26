package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Unsichtbare Blöcke (Barriere & Co.) und Innenansicht (Dach ausblenden) der Karten. */
class RoofTest {
	static final int A = ChunkReader.AIR;
	static final int STONE = 0x707070;
	static final int GRASS = MapColors.MAP_GRASS;
	/** Voller, undurchsichtiger Block (Dach, Wand). */
	static final int PLANKS = 0x8F7748 | ChunkReader.OPAQUE;
	/** Laub: Kartenfarbe, aber nicht undurchsichtig. */
	static final int LEAVES = MapColors.MAP_PLANT;

	/** Leser mit Höhenkarte wie Minecraft (zählt auch Barrieren) und leeren Abschnitten. */
	static final class Reader implements ChunkReader {
		final Map<Long, int[]> columns = new HashMap<Long, int[]>();
		final Map<Long, Integer> tops = new HashMap<Long, Integer>();
		final Set<Integer> emptySections = new HashSet<Integer>();
		int[] fallback = {STONE, STONE, STONE, STONE, GRASS};
		int cx, cz;
		int reads;

		void column(int x, int z, int[] blocks) {
			columns.put(key(x, z), blocks);
		}

		/** Oberkante wie die Höhenkarte (z. B. über einer Barriere). */
		void top(int x, int z, int top) {
			tops.put(key(x, z), top);
		}

		static long key(int x, int z) {
			return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
		}

		private int[] col(int lx, int lz) {
			int[] c = columns.get(key((cx << 4) + lx, (cz << 4) + lz));
			return c == null ? fallback : c;
		}

		@Override
		public boolean isLoaded(int chunkX, int chunkZ) {
			return true;
		}

		@Override
		public boolean open(int chunkX, int chunkZ) {
			cx = chunkX;
			cz = chunkZ;
			return true;
		}

		@Override
		public int minY() {
			return 0;
		}

		@Override
		public int top(int lx, int lz) {
			Integer t = tops.get(key((cx << 4) + lx, (cz << 4) + lz));
			if (t != null) return t;
			int[] c = col(lx, lz);
			for (int y = c.length - 1; y >= 0; y--) {
				if ((c[y] & AIR) == 0) return y + 1;
			}
			return 0;
		}

		@Override
		public int block(int lx, int y, int lz) {
			reads++;
			int[] c = col(lx, lz);
			if (y < 0 || y >= c.length) return AIR;
			return c[y];
		}

		@Override
		public int tint(int lx, int y, int lz) {
			return -1;
		}

		@Override
		public boolean sectionEmpty(int y) {
			return emptySections.contains(y >> 4);
		}
	}

	static int[] column(int height, int... placed) {
		int[] c = new int[height];
		Arrays.fill(c, A);
		for (int i = 0; i + 1 < placed.length; i += 2) c[placed[i]] = placed[i + 1];
		return c;
	}

	// --- Barriere & unsichtbare Blöcke ---

	@Test
	void barrierCeilingHighAboveIsSkippedDownToTheGround() {
		Reader r = new Reader();
		// Lobby: Boden (Gras) auf 64, Barriere-Decke auf 200 – der Leser meldet die Barriere als Luft,
		// die Höhenkarte aber zählt sie (Oberkante 201).
		int[] lobby = column(201, 60, STONE, 61, STONE, 62, STONE, 63, STONE, 64, GRASS, 200, A);
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				r.column(x, z, lobby);
				r.top(x, z, 201);
			}
		}
		int[] px = new int[256], h = new int[256];
		assertTrue(ColumnScanner.surface(r, 0, 0, px, h));
		assertEquals(64, h[0], "Barriere wird übersprungen, der Boden gezeigt");
		assertEquals(MapColors.KNOWN | GRASS, px[0]);
		assertEquals(0, px[0] & MapColors.EMPTY);
		// Ohne Abschnitts-Info: jede Spalte liest die Luft einzeln (etwa 137 Blöcke).
		int slow = r.reads;
		// Mit leeren Abschnitten (80..191 nur Luft) geht es in großen Schritten.
		for (int s = 5; s <= 11; s++) r.emptySections.add(s);
		r.reads = 0;
		assertTrue(ColumnScanner.surface(r, 0, 0, px, h));
		assertEquals(64, h[0]);
		assertTrue(r.reads * 3 < slow, "leere Abschnitte übersprungen: " + r.reads + " statt " + slow);
	}

	@Test
	void voidWorldWithBarrierFloorIsEmptyNotBarrier() {
		Reader r = new Reader();
		// Leere-Welt: nur eine Barriere-Plattform auf 100, darunter nichts.
		r.fallback = column(101, 100, A);
		for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) r.top(x, z, 101);
		for (int s = 0; s < 6; s++) r.emptySections.add(s);
		int[] px = new int[256], h = new int[256];
		assertTrue(ColumnScanner.surface(r, 0, 0, px, h));
		assertTrue((px[0] & MapColors.EMPTY) != 0, "Barriere wird nicht gezeichnet");
		assertTrue(r.reads < 256 * 12, "Leere schnell übersprungen: " + r.reads);
	}

	@Test
	void caveViewTreatsBarriersAsAir() {
		Reader r = new Reader();
		// Höhle 10..12 mit Barriere auf 11 (Luft), Boden auf 9.
		int[] cave = new int[40];
		Arrays.fill(cave, STONE);
		cave[10] = A;
		cave[11] = A;
		cave[12] = A;
		r.fallback = cave;
		int[] px = new int[256], h = new int[256];
		assertTrue(ColumnScanner.cave(r, 0, 0, 15, px, h));
		assertEquals(9, h[0]);
	}

	// --- Dach erkennen ---

	@Test
	void roofAboveFindsOnlyOpaqueBlocksWithinRange() {
		Reader r = new Reader();
		r.column(1, 1, column(20, 4, GRASS, 9, PLANKS));
		r.column(2, 1, column(20, 4, GRASS, 8, LEAVES, 9, LEAVES));
		r.column(3, 1, column(20, 4, GRASS, 8, A, 12, PLANKS));
		r.column(4, 1, column(30, 4, GRASS, 17, PLANKS));
		// Kopf auf 6 (Füße 5).
		assertEquals(9, ColumnScanner.roofAbove(r, 1, 6, 1, 10), "Bretter-Dach");
		assertEquals(ColumnScanner.NO_CUT, ColumnScanner.roofAbove(r, 2, 6, 1, 10), "Laub ist kein Dach");
		assertEquals(12, ColumnScanner.roofAbove(r, 3, 6, 1, 10), "Barriere (Luft) zählt nicht, das Dach darüber schon");
		assertEquals(ColumnScanner.NO_CUT, ColumnScanner.roofAbove(r, 4, 6, 1, 10), "zu hoch (Kopf + 11)");
		assertEquals(17, ColumnScanner.roofAbove(r, 4, 7, 1, 10), "Kopf eins höher: in Reichweite");
	}

	@Test
	void surfaceUnderACutShowsTheInside() {
		Reader r = new Reader();
		// Haus: Boden (Gras) auf 4, Dach auf 9; Wand von 5 bis 9; draußen ein Turm bis 20.
		r.column(0, 0, column(10, 3, STONE, 4, GRASS, 9, PLANKS));
		r.column(1, 0, column(10, 3, STONE, 4, GRASS, 5, PLANKS, 6, PLANKS, 7, PLANKS, 8, PLANKS, 9, PLANKS));
		r.column(2, 0, column(21, 4, GRASS, 20, PLANKS));
		int[] px = new int[256], h = new int[256];
		assertTrue(ColumnScanner.surface(r, 0, 0, px, h));
		assertEquals(9, h[0], "ohne Schnitt: Dach");
		assertTrue(ColumnScanner.surface(r, 0, 0, 8, px, h));
		assertEquals(4, h[0], "unter dem Schnitt: Boden des Hauses");
		assertEquals(MapColors.KNOWN | GRASS, px[0]);
		assertEquals(8, h[1], "Wand auf Schnitthöhe");
		assertEquals(MapColors.KNOWN | (PLANKS & MapColors.RGB), px[1]);
		assertEquals(4, h[2], "Blöcke über dem Schnitt zählen nicht");
		assertEquals(4, h[3], "Standardspalte unverändert");
	}

	@Test
	void cutStartsAtHeadPlusTenOrJustBelowTheRoof() {
		// Füße 5, Kopf 6: ohne Dach in Reichweite Kopf + 10 = 16.
		assertEquals(16, MapEngine.chooseCut(ColumnScanner.NO_CUT, 5, ColumnScanner.NO_CUT));
		// Ungerade Höhen werden abgerundet (Füße 6 → Kopf 7 → 17 → 16).
		assertEquals(16, MapEngine.chooseCut(ColumnScanner.NO_CUT, 6, ColumnScanner.NO_CUT));
		// Dach auf 9 → knapp darunter (8).
		assertEquals(8, MapEngine.chooseCut(ColumnScanner.NO_CUT, 5, 9));
		// Dach direkt über dem Kopf (7) → 6 = Kopf.
		assertEquals(6, MapEngine.chooseCut(ColumnScanner.NO_CUT, 5, 7));
		// Dach auf 8 (Schnitt 7 → gerundet 6).
		assertEquals(6, MapEngine.chooseCut(ColumnScanner.NO_CUT, 5, 8));
		// Nie unter die Füße.
		assertEquals(7, MapEngine.chooseCut(ColumnScanner.NO_CUT, 7, 8));
		// Bisherige Höhe bleibt, solange sie passt (weniger Ebenenwechsel) …
		assertEquals(16, MapEngine.chooseCut(16, 7, ColumnScanner.NO_CUT));
		assertEquals(8, MapEngine.chooseCut(8, 6, 12));
		// … aber nicht über dem Dach und nicht unter den Füßen.
		assertEquals(10, MapEngine.chooseCut(16, 7, 11));
		assertEquals(20, MapEngine.chooseCut(8, 10, ColumnScanner.NO_CUT));
	}

	// --- Engine: Hysterese, Ebenen, Fair Play ---

	/** Haus rund um den Spieler (8, 8): Boden (Gras) auf 4, Dach aus Brettern auf 9, Füße auf 5. */
	static MapTest.FakePlatform house(Path dir) {
		MapTest.FakePlatform p = new MapTest.FakePlatform(dir);
		p.y = 5;
		p.sky = 12;
		for (int x = 4; x <= 12; x++) {
			for (int z = 4; z <= 12; z++) {
				p.reader.column(x, z, new int[]{STONE, STONE, STONE, STONE, GRASS, A, A, A, A, PLANKS});
			}
		}
		return p;
	}

	static void roofOff(MapTest.FakePlatform p) {
		for (int x = 4; x <= 12; x++) {
			for (int z = 4; z <= 12; z++) p.reader.column(x, z, MapTest.DEFAULT);
		}
	}

	@Test
	void engineHidesTheRoofWithHysteresisAndSeparateLayers(@TempDir Path dir) {
		TrsModules modules = new TrsModules();
		modules.minimap.setEnabled(true);
		assertTrue(modules.minimapHideRoof.get(), "Standard: an");
		MapEngine e = new MapEngine(modules, dir, true);
		MapTest.FakePlatform p = house(dir);
		for (int i = 0; i < 20; i++) e.tick(p);
		assertTrue(e.roofActive(), "Dach über dem Kopf → Innenansicht");
		assertFalse(e.caveActive());
		assertTrue(e.layeredView());
		MapLayer roof = e.viewLayer();
		assertTrue(roof.roof());
		assertFalse(roof.cave());
		assertEquals("roof8", roof.id);
		assertEquals(8, e.roofCut());
		assertNotSame(e.surfaceLayer(), roof);
		assertNotEquals(e.surfaceLayer().dir(), roof.dir(), "eigener Ordner (Cache-Schlüssel)");
		assertEquals(9, e.heightAt(e.surfaceLayer(), 8, 8), "Oberfläche: Dach");
		assertEquals(4, e.heightAt(roof, 8, 8), "Innenansicht: Boden");

		// Dach weg, volles Himmelslicht: erst nach ROOF_LEAVE_TICKS aus (kein Flackern).
		roofOff(p);
		p.sky = 15;
		for (int i = 0; i < MapEngine.ROOF_LEAVE_TICKS - 1; i++) {
			e.tick(p);
			assertTrue(e.roofActive(), "Hysterese, Tick " + i);
		}
		e.tick(p);
		assertFalse(e.roofActive());
		assertSame(e.surfaceLayer(), e.viewLayer());

		// Himmelslicht zwischen den Schwellen (12–13) ohne Dach: bleibt aus …
		p.sky = 13;
		for (int i = 0; i < 5; i++) e.tick(p);
		assertFalse(e.roofActive());
		// … bei wenig Himmelslicht (große Halle, Dach außer Reichweite) an, Schnitt bei Kopf + 10.
		p.sky = 10;
		e.tick(p);
		assertTrue(e.roofActive());
		assertEquals(16, e.roofCut());
		assertEquals("roof16", e.viewLayer().id);
		// Zurück auf 13: bleibt an (Hysterese), erst ab 14 und nach der Wartezeit aus.
		p.sky = 13;
		for (int i = 0; i < 20; i++) e.tick(p);
		assertTrue(e.roofActive());
		p.sky = 14;
		for (int i = 0; i < MapEngine.ROOF_LEAVE_TICKS; i++) e.tick(p);
		assertFalse(e.roofActive());

		// Gespeichert in getrennten Ebenen-Ordnern.
		e.leaveWorld();
		assertNull(e.surfaceLayer());
		Path dim = roof.dir().getParent();
		assertTrue(Files.isDirectory(dim.resolve(MapDisk.safeName("surface"))));
		assertTrue(Files.isDirectory(roof.dir()));
	}

	@Test
	void singleLogAboveTheHeadIsNoRoof(@TempDir Path dir) {
		TrsModules modules = new TrsModules();
		modules.minimap.setEnabled(true);
		MapEngine e = new MapEngine(modules, dir, true);
		MapTest.FakePlatform p = new MapTest.FakePlatform(dir);
		p.y = 5;
		p.sky = 14;
		p.reader.column(8, 8, new int[]{STONE, STONE, STONE, STONE, GRASS, A, A, A, PLANKS});
		for (int i = 0; i < 5; i++) e.tick(p);
		assertFalse(e.roofActive(), "ein einzelner Block über dem Kopf ist kein Dach");
	}

	@Test
	void roofRespectsSettingFairPlayCavesAndTheNether(@TempDir Path dir) {
		TrsModules modules = new TrsModules();
		modules.minimap.setEnabled(true);
		MapEngine e = new MapEngine(modules, dir, true);
		MapTest.FakePlatform p = house(dir);
		e.tick(p);
		assertTrue(e.roofActive());
		// Einstellung aus.
		modules.minimapHideRoof.set(false);
		e.tick(p);
		assertFalse(e.roofActive());
		assertSame(e.surfaceLayer(), e.viewLayer());
		modules.minimapHideRoof.set(true);
		// Fair Play: wie die Höhlenansicht verboten.
		modules.minimapFairPlay.set(true);
		e.tick(p);
		assertFalse(e.roofActive(), "Fair Play → keine Innenansicht");
		assertFalse(e.caveActive());
		modules.minimapFairPlay.set(false);
		e.tick(p);
		assertTrue(e.roofActive());
		// Höhle (kaum Himmelslicht): die Höhlenansicht hat Vorrang.
		p.sky = 0;
		e.tick(p);
		assertTrue(e.caveActive());
		assertFalse(e.roofActive());
		assertTrue(e.viewLayer().cave());
		// Höhlenansicht aus, trotzdem unter Tage: keine Innenansicht (sonst Gestein-Schnitt).
		modules.minimapCaveMode.set(TrsModules.CaveMode.OFF);
		e.tick(p);
		assertFalse(e.caveActive());
		assertFalse(e.roofActive());
		// Nether: immer Höhlenansicht, nie Innenansicht.
		modules.minimapCaveMode.set(TrsModules.CaveMode.AUTO);
		p.sky = 15;
		p.dim = "minecraft:the_nether";
		e.tick(p);
		assertTrue(e.caveActive());
		assertFalse(e.roofActive());
		// End (kein Himmelslicht): nur ein echtes Dach zählt.
		p.dim = "minecraft:the_end";
		p.sky = 0;
		e.tick(p);
		assertFalse(e.caveActive());
		assertTrue(e.roofActive(), "Dach im End");
		roofOff(p);
		for (int i = 0; i < MapEngine.ROOF_LEAVE_TICKS; i++) e.tick(p);
		assertFalse(e.roofActive(), "End ohne Dach: Himmelslicht 0 zählt nicht");
	}
}
