package dev.theredstonee.trsclient.core.perf;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealBenchTest {
	@Test
	void pathIsDeterministicAndSmooth() {
		double[] prev = null;
		for (int t = 0; t < RealBench.PATH_TICKS; t++) {
			double[] p = RealBench.pose(t, 100.5, 70, -40.5);
			assertArrayEquals(p, RealBench.pose(t, 100.5, 70, -40.5), 0.0, "gleiche Tick-Nummer, gleiche Kamera");
			assertArrayEquals(p, RealBench.pose(t + RealBench.PATH_TICKS, 100.5, 70, -40.5), 1e-9, "Pfad wiederholt sich");
			assertTrue(p[3] >= -180 && p[3] < 180, "Yaw im Minecraft-Bereich: " + p[3]);
			assertTrue(p[4] >= 0 && p[4] <= 35, "leicht nach unten: " + p[4]);
			if (prev != null) {
				double step = Math.hypot(p[0] - prev[0], p[2] - prev[2]);
				assertTrue(step < 0.5, "keine Sprünge (Tick " + t + "): " + step);
				assertTrue(Math.abs(p[1] - prev[1]) <= 0.1, "Höhe ohne Sprung (Tick " + t + ")");
			}
			prev = p;
		}
	}

	@Test
	void orbitLooksAtTheCentre() {
		// Tick 0: östlich der Mitte → Blick nach Westen (Yaw 90).
		double[] p = RealBench.pose(0, 0, 64, 0);
		assertEquals(36, p[0], 1e-9);
		assertEquals(0, p[2], 1e-9);
		assertEquals(76, p[1], 1e-9);
		assertEquals(90, p[3], 1e-9);
		// Viertelkreis: südlich der Mitte → Blick nach Norden (Yaw ±180).
		p = RealBench.pose(150, 0, 64, 0);
		assertEquals(180, Math.abs(p[3]), 1e-6);
		assertEquals(0, RealBench.yaw(0, 1), 1e-9, "Süden");
		assertEquals(-90, RealBench.yaw(1, 0), 1e-9, "Osten");
	}

	@Test
	void cameraStaysAboveTheTerrainAlongThePath() {
		double[] ground = new double[RealBench.PATH_TICKS];
		java.util.Arrays.fill(ground, 64);
		for (int i = 100; i < 140; i++) ground[i] = 100; // Berg am Rand des Rundflugs
		double[] h = RealBench.smoothHeights(ground, 64);
		for (int i = 0; i < ground.length; i++) {
			assertTrue(h[i] >= ground[i] + RealBench.CLEARANCE, "Tick " + i + ": " + h[i]);
			assertTrue(h[i] >= RealBench.pose(i, 0, 64, 0)[1], "nie tiefer als der Grundpfad");
		}
		// Rechtzeitig steigen: schon LOOK Ticks vor dem Berg oben.
		assertTrue(h[100 - RealBench.LOOK] >= 100 + RealBench.CLEARANCE);
		for (int i = 1; i < h.length; i++) {
			assertTrue(Math.abs(h[i] - h[i - 1]) <= RealBench.MAX_SLOPE + 1e-9, "kein Sprung bei " + i + ": " + h[i - 1] + " → " + h[i]);
		}
		// Rundkurs: Ende und Anfang passen zusammen.
		double[] end = RealBench.pose(RealBench.PATH_TICKS - 1, 0, 64, 0);
		double[] start = RealBench.pose(0, 0, 64, 0);
		assertTrue(Math.hypot(end[0] - start[0], end[2] - start[2]) < 0.5);
		for (int i = 101; i < 140; i++) assertTrue(Math.abs(h[i] - h[i - 1]) <= RealBench.MAX_SLOPE + 1e-9, "Berg: " + i);
	}

	@Test
	void runsSetupWarmupAndMeasureThenFinishes() {
		final List<String> log = new ArrayList<String>();
		final int[] placed = {0};
		final double[] at = {0, 0, 0};
		final boolean[] finished = {false};
		final int[] ground = {Integer.MIN_VALUE};
		RealBench.Game g = new RealBench.Game() {
			@Override
			public boolean ready() {
				return true;
			}

			@Override
			public void command(String command) {
				log.add("/" + command);
			}

			@Override
			public int groundY(int x, int z) {
				// Hügel östlich der Mitte (x > 30): die Kamera muss darüber hinweg.
				return ground[0] == Integer.MIN_VALUE ? ground[0] : (x > 30 ? 95 : ground[0]);
			}

			@Override
			public void place(double x, double y, double z, float yaw, float pitch) {
				placed[0]++;
				at[0] = x;
				at[1] = y;
				at[2] = z;
			}

			@Override
			public double[] position() {
				return at.clone();
			}

			@Override
			public void shot(String name) {
				log.add("shot " + name);
			}

			@Override
			public void log(String line) {
				log.add(line);
			}

			@Override
			public int width() {
				return 1904;
			}

			@Override
			public int height() {
				return 1001;
			}

			@Override
			public String setup() {
				return "Test";
			}

			@Override
			public void profile(boolean on) {
				log.add("profile " + on);
			}

			@Override
			public void maximize() {
				log.add("maximize");
			}

			@Override
			public void finish() {
				finished[0] = true;
			}
		};
		FrameStats stats = new FrameStats();
		RealBench bench = new RealBench(10, 20, "trs", stats);
		long now = 0;
		int ticks = 0;
		while (bench.tick(g, now)) {
			if (ticks == 5) ground[0] = 70; // Boden erst nach ein paar Ticks geladen
			if (ticks > 3000) break;
			now += 50_000_000L;
			for (int f = 0; f < 3; f++) stats.frame(now + f);
			ticks++;
		}
		assertTrue(finished[0], "beendet");
		assertTrue(log.contains("maximize"));
		assertTrue(log.contains("/gamerule doMobSpawning false"));
		assertTrue(log.contains("/gamemode creative @a"), "fliegen können");
		boolean valid = false;
		for (String line : log) valid |= line.startsWith("[RealBench] Pfad \"trs\"") && line.endsWith("gültig");
		assertTrue(valid, log.toString());
		assertTrue(log.contains("profile true") && log.contains("profile false"));
		String result = null;
		for (String line : log) if (line.startsWith("[RealBench] Ergebnis \"trs\"")) result = line;
		assertFalse(result == null, log.toString());
		assertTrue(result.contains("Bilder in 20.0 s"), result);
		assertTrue(placed[0] > 2 * RealBench.DEFAULT_LENGTH);
	}
}
