package dev.theredstonee.trsclient.core.minimap;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimapTest {
	/** Testquelle: jeder Block bekommt eine Farbe aus seinen Koordinaten, Höhe = x. */
	private static final class FakeSource implements MinimapCache.Source {
		final Set<Long> loaded = new HashSet<>();
		int fills;

		FakeSource(int radius) {
			for (int cx = -radius; cx <= radius; cx++) {
				for (int cz = -radius; cz <= radius; cz++) loaded.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
			}
		}

		@Override
		public boolean isLoaded(int chunkX, int chunkZ) {
			return loaded.contains(((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL));
		}

		@Override
		public boolean fill(int chunkX, int chunkZ, int[] colors, int[] heights) {
			fills++;
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					int wx = (chunkX << 4) + x;
					int wz = (chunkZ << 4) + z;
					colors[z * 16 + x] = 0x010101 * (Math.floorMod(wx + wz, 200) + 20);
					heights[z * 16 + x] = 64 + Math.floorMod(wx, 3);
				}
			}
			return true;
		}
	}

	@Test
	void cacheReadsOnlyWithinBudget() {
		MinimapCache cache = new MinimapCache();
		FakeSource source = new FakeSource(3);
		assertEquals(2, cache.update(source, 0, 0, 3, 1000, 5000, 2));
		assertEquals(2, cache.size());
		assertTrue(cache.hasChunk(0, 0), "die Mitte zuerst");
		cache.update(source, 0, 0, 3, 1100, 5000, 100);
		assertEquals(49, cache.size(), "7×7 Chunks im Radius 3");
		assertFalse(cache.hasChunk(9, 9));
	}

	@Test
	void cacheSkipsUnloadedChunksAndRefreshesOldOnes() {
		MinimapCache cache = new MinimapCache();
		FakeSource source = new FakeSource(0); // nur Chunk 0,0 ist geladen
		cache.update(source, 0, 0, 2, 0, 5000, 10);
		assertEquals(1, cache.size());
		int before = source.fills;
		// Nichts veraltet → keine neuen Lesevorgänge
		cache.update(source, 0, 0, 2, 100, 5000, 10);
		assertEquals(before, source.fills);
		// Nach der Höchstdauer wird der Chunk aufgefrischt
		cache.update(source, 0, 0, 2, 10_000, 5000, 10);
		assertEquals(before + 1, source.fills);
	}

	@Test
	void unknownAreaStaysDark() {
		MinimapCache cache = new MinimapCache();
		MinimapGrid grid = new MinimapGrid();
		grid.build(cache, 0, 0, 8, 8, 1, 0);
		assertEquals(8, grid.cols());
		assertEquals(MinimapGrid.UNKNOWN, grid.cell(0, 0));
	}

	@Test
	void gridSamplesTheRightBlocks() {
		MinimapCache cache = new MinimapCache();
		FakeSource source = new FakeSource(2);
		cache.update(source, 0, 0, 2, 0, 0, 100);
		MinimapGrid grid = new MinimapGrid();
		grid.build(cache, 0.5, 0.5, 9, 9, 1, 0);
		// Mitte des Gitters = Block (0,0)
		int expected = MinimapGrid.shade(cache.color(0, 0), cache.height(0, 0) - cache.height(0, -1));
		assertEquals(expected, grid.cell(4, 4));
		// Norden (kleineres z) liegt oben
		int north = MinimapGrid.shade(cache.color(0, -1), cache.height(0, -1) - cache.height(0, -2));
		assertEquals(north, grid.cell(4, 3));
	}

	@Test
	void rotationPutsTheViewUp() {
		MinimapCache cache = new MinimapCache();
		FakeSource source = new FakeSource(2);
		cache.update(source, 0, 0, 2, 0, 0, 100);
		MinimapGrid grid = new MinimapGrid();
		// yaw 0 = Blick nach Süden (+Z); gedreht muss Süden oben liegen
		grid.build(cache, 0.5, 0.5, 9, 9, 1, 0 + 180);
		int south = MinimapGrid.shade(cache.color(0, 1), cache.height(0, 1) - cache.height(0, 0));
		assertEquals(south, grid.cell(4, 3));
	}

	@Test
	void toCellMatchesTheGrid() {
		double[] out = new double[2];
		MinimapGrid.toCell(0.5, 0.5, 9, 9, 1, 0, 0.5, 0.5, out);
		assertEquals(4.5, out[0], 1e-9);
		assertEquals(4.5, out[1], 1e-9);
		// 4 Blöcke nach Süden (+Z) = 4 Zellen nach unten
		MinimapGrid.toCell(0.5, 0.5, 9, 9, 1, 0, 0.5, 4.5, out);
		assertEquals(8.5, out[1], 1e-9);
		// gedreht (Blick nach Süden) liegt derselbe Punkt oben
		MinimapGrid.toCell(0.5, 0.5, 9, 9, 1, 180, 0.5, 4.5, out);
		assertEquals(0.5, out[1], 1e-9);
		MinimapGrid.clamp(out, 9, 9, 1);
		assertEquals(1.0, out[1], 1e-9);
	}

	@Test
	void shadeFollowsHeightDifference() {
		int base = 0x808080;
		assertTrue(MinimapGrid.shade(base, 1) > MinimapGrid.shade(base, 0));
		assertTrue(MinimapGrid.shade(base, 0) > MinimapGrid.shade(base, -1));
		assertEquals(base, MinimapGrid.shade(base, 1));
	}
}
