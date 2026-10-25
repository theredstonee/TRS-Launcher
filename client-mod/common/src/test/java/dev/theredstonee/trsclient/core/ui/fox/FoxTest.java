package dev.theredstonee.trsclient.core.ui.fox;

import dev.theredstonee.trsclient.core.intro.LookPicker;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.winter.Snow;
import dev.theredstonee.trsclient.core.ui.winter.WinterMath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Design „Fuchsbau“: Theme-Achse, Akzent, Launcher-Datei, Landschaft (dieselben Zahlen wie {@code app/utils/fox} im
 * Launcher – Werte mit dem Launcher-Code erzeugt), Bewegung, Fuchs und Malen.
 */
class FoxTest {
	@AfterEach
	void reset() {
		Theme.set(Theme.of("dark", "redstone", null));
		dev.theredstonee.trsclient.core.halloween.Halloween.reset();
	}

	// --- Design im Theme ---

	@Test
	void designParsing() {
		assertTrue(Theme.of("dark", "fox", null, "fox").fox);
		assertFalse(Theme.of("dark", "fox", null, "fox").winter);
		assertEquals(Theme.FOX, Theme.of("dark", "fox", null, " Fox ").designName);
		assertEquals(Theme.FOX, Theme.normalizeDesign("fox"));
		assertEquals(Theme.WINTER, Theme.normalizeDesign("winter"));
		assertEquals(Theme.REDSTONE, Theme.normalizeDesign("foxden"));
		assertEquals(Theme.REDSTONE, Theme.normalizeDesign(null));
		assertFalse(Theme.of("dark", "redstone", null).fox, "ohne Design: Redstone");
		assertFalse(Theme.of("dark", "ice", null, "winter").fox);
		assertEquals(0xFFE8702A, Theme.of("dark", "fox", null, "fox").accent);
		assertEquals(0xFFE8702A, Theme.of("dark", "fox", "#000000").accent, "Fuchsorange ist ein bekannter Akzent");
	}

	@Test
	void foxPaletteIsWarmDusk() {
		Theme f = Theme.of("dark", "fox", null, "fox");
		// Launcher: base-950 / base-850 / base-50 im Design „fox“.
		assertEquals(0xFF1A1119, f.background);
		assertEquals(0xFF281A27, f.surface);
		assertEquals(0xFFFFF3EA, f.text);
		assertEquals(0xFFFFC362, f.lampOn, "Laternen leuchten golden wie Glut, nicht Bernstein oder Eis");
		assertEquals(0xFFFFC362, f.on);
		assertEquals(0xFF000000, Theme.of("oled", "fox", null, "fox").background);
		Theme day = Theme.of("light", "fox", null, "fox");
		assertTrue(day.light);
		assertEquals(0xFFF6EBE0, day.background);
		assertEquals(0xFF1E130F, day.text);
		// Andere Akzente bleiben frei wählbar; die Glut der Äste folgt dem Akzent.
		Theme green = Theme.of("dark", "emerald", null, "fox");
		assertEquals(0xFF17A34A, green.accent);
		assertNotEquals(f.dustOn, green.dustOn);
	}

	@Test
	void accentFollowsDesignLikeTheLauncher() {
		assertEquals("fox", Theme.accentForDesign("fox", "redstone"));
		assertEquals("fox", Theme.accentForDesign("fox", "ice"));
		assertEquals("redstone", Theme.accentForDesign("redstone", "fox"));
		assertEquals("ice", Theme.accentForDesign("winter", "fox"));
		assertEquals("ice", Theme.accentForDesign("winter", "redstone"));
		assertEquals("redstone", Theme.accentForDesign("redstone", "ice"));
		assertEquals("emerald", Theme.accentForDesign("fox", "emerald"));
		assertEquals("lapis", Theme.accentForDesign("redstone", "lapis"));
		assertEquals("fox", Theme.designAccent("fox"));
	}

	@Test
	void pickerOffersTheThirdDesignAndAccent() {
		assertTrue(Arrays.asList(LookPicker.DESIGNS).contains(Theme.FOX));
		assertTrue(Arrays.asList(LookPicker.ACCENTS).contains("fox"));
	}

	@Test
	void launcherFileCarriesTheDesign(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("trsclient").resolve("launcher-theme.json");
		Files.createDirectories(file.getParent());
		Files.write(file, "{\"version\":1,\"theme\":\"dark\",\"accent\":\"fox\",\"accentColor\":\"#E8702A\",\"design\":\"fox\"}"
				.getBytes(StandardCharsets.UTF_8));
		assertTrue(Theme.loadFrom(dir));
		assertTrue(Theme.get().fox);
		assertEquals("fox", Theme.get().accentName);
		assertEquals(0xFFE8702A, Theme.get().accent);
	}

	@Test
	void halloweenLiesOverTheFoxDen(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("trsclient").resolve("launcher-theme.json");
		Files.createDirectories(file.getParent());
		Files.write(file, "{\"version\":1,\"theme\":\"dark\",\"accent\":\"fox\",\"design\":\"fox\",\"event\":\"halloween\"}"
				.getBytes(StandardCharsets.UTF_8));
		assertTrue(Theme.loadFrom(dir));
		assertTrue(Theme.get().halloween);
		assertFalse(Theme.get().fox, "Halloween-Look statt Fuchsbau");
		assertEquals(Theme.FOX, Theme.get().designName, "das eingestellte Design bleibt");
		assertTrue(Theme.plain().fox);
	}

	// --- Landschaft (gleich wie im Launcher) ---

	private static int sum(short[] a) {
		int s = 0;
		for (short v : a) s += v;
		return s;
	}

	/** Prüfsumme über alle Bäume (wie im Erzeuger-Skript für den Launcher-Code). */
	private static long[] treeSums(FoxLandscape L) {
		long tsum = 0;
		long seeds = 0;
		for (FoxLandscape.Tree t : L.trees) {
			tsum += t.x * 3 + t.base * 5 + t.bs * 7 + t.tiers * 11 + t.layer * 13 + (t.kind == FoxLandscape.CHERRY ? 17 : 0) + (t.flip ? 19 : 0);
			seeds = (seeds + t.seed) % 1000000007L;
		}
		return new long[]{tsum, seeds};
	}

	@Test
	void landscapeMatchesTheLauncher() {
		FoxLandscape L = FoxLandscape.build(427, 240, FoxScene.SEED, true);
		assertEquals(132, L.horizon);
		assertEquals(29, L.hillAmp);
		assertEquals(8, L.bs);
		assertEquals(49900, sum(L.hillsBack));
		assertEquals(52753, sum(L.hills));
		assertEquals(57463, sum(L.forest));
		assertEquals(59455, sum(L.meadow));
		assertEquals(87418, sum(L.ground));
		assertEquals(86, L.trees.size());
		assertArrayEquals(new long[]{123736, 202695045}, treeSums(L));
		assertEquals(22, L.stars.size());
		assertEquals(239, L.sunX);
		assertEquals(104, L.sunY);
		assertEquals(9, L.sunHalf);
		assertNotNull(L.den);
		assertEquals(310, L.den.x);
		assertEquals(206, L.den.y);
		assertTrue(L.den.large);
		assertTrue(L.den.fox);
		FoxLandscape.Parts p = L.parts();
		assertEquals(315, p.fireX);
		assertTrue(p.hasFox);
		assertEquals(324, p.foxX);
		assertEquals(353, p.holeX0);
		assertEquals(368, p.holeX1);
		assertEquals(194, p.holeTop);
		assertEquals(351, p.lanternX);
		assertEquals(182, p.lanternY);
		assertEquals(392, p.bushX);
		assertEquals(13, p.bushSize);
		assertEquals(22, L.flowers.size());
		assertEquals(36, L.tufts.size());
		assertEquals(13, L.shrubs.size());
		assertArrayEquals(new int[]{190, 144, 2}, L.shrubs.get(0));
		assertEquals(4, L.clouds.size());
		FoxLandscape.Cloud c = L.clouds.get(0);
		assertArrayEquals(new int[]{371, 32, 8, 3, 345761649}, new int[]{c.x, c.y, c.cols, c.rows, c.seed});
		assertEquals(0.03923620367771946, c.speed, 1e-12);
	}

	@Test
	void otherSizesMatchTheLauncherToo() {
		FoxLandscape big = FoxLandscape.build(640, 360, FoxScene.SEED, true);
		assertEquals(198, big.horizon);
		assertEquals(198048, sum(big.ground));
		assertEquals(126, big.trees.size());
		assertArrayEquals(new long[]{262332, 800627432}, treeSums(big));
		assertEquals(358, big.sunX);
		assertEquals(512, big.den.x);
		assertEquals(5, big.clouds.size());

		// Klein: winziger Bau ohne Fuchs (der Fuchs schläft nur am großen Bau).
		FoxLandscape small = FoxLandscape.build(120, 60, FoxScene.SEED, true);
		assertEquals(3, small.bs);
		assertFalse(small.den.large);
		assertFalse(small.den.fox);
		assertFalse(small.parts().hasFox);
		assertArrayEquals(new long[]{18879, 883159639}, treeSums(small));

		// Sehr klein: kein Bau.
		FoxLandscape tiny = FoxLandscape.build(80, 40, FoxScene.SEED, true);
		assertNull(tiny.den);
		assertNull(tiny.parts());
		assertArrayEquals(new long[]{6037, 484656846}, treeSums(tiny));
		assertNull(FoxLandscape.build(427, 240, FoxScene.SEED, false).den, "ohne Bau");
	}

	@Test
	void landscapeIsStableForTheSeed() {
		FoxLandscape a = FoxLandscape.build(427, 240, FoxScene.SEED, true);
		FoxLandscape b = FoxLandscape.build(427, 240, FoxScene.SEED, true);
		assertArrayEquals(a.ground, b.ground);
		assertArrayEquals(treeSums(a), treeSums(b));
		assertNotEquals(sum(a.hillsBack), sum(FoxLandscape.build(427, 240, 7, true).hillsBack));
	}

	@Test
	void spruceTiersZigzagLikeMinecraft() {
		int[] got = new int[9];
		for (int i = 0; i < 9; i++) got[i] = FoxPainter.spruceHalf(i, 9);
		assertArrayEquals(new int[]{0, 1, 1, 1, 2, 1, 3, 2, 3}, got);
	}

	@Test
	void paletteMatchesTheLauncher() {
		FoxPalette p = FoxPalette.of("dark", "fox", 0xE8702A);
		// Launcher: lanternOff [79.2, 52.7, 28.6], lanternOn [255, 169.2, 108.0], lanternCore [255, 230.3, 209.6].
		near(0xFF4F351D, p.lanternOff);
		near(0xFFFFA96C, p.lanternOn);
		near(0xFFFFE6D2, p.lanternCore);
		near(0xFF445C24, p.grass[0]);
		assertEquals(0xFF000000, FoxPalette.of("oled", "fox", 0).sky[0]);
		assertEquals(FoxPalette.LIGHT, FoxPalette.of("light", "fox", 0).variant);
	}

	/** Farben auf ±1 je Kanal (der Launcher rechnet in Gleitkomma). */
	private static void near(int want, int got) {
		for (int sh = 0; sh <= 16; sh += 8) {
			int d = Math.abs(((want >> sh) & 255) - ((got >> sh) & 255));
			assertTrue(d <= 1, "Farbe " + Integer.toHexString(got) + " statt " + Integer.toHexString(want));
		}
	}

	// --- Bewegung und Fuchs ---

	@Test
	void foxBreathesAndTwitchesLikeTheLauncher() {
		double[] s = {0, 1, 1.5, 3.3, 10, 20, 27.5};
		boolean[] inhale = {true, true, false, false, false, false, true};
		for (int i = 0; i < s.length; i++) {
			assertEquals(inhale[i], FoxParticles.inhale(s[i], false), "t=" + s[i]);
			assertFalse(FoxParticles.twitch(s[i], false));
		}
		assertFalse(FoxParticles.inhale(0.5, true), "ruhig: ausgeatmet");
		// In jedem 20-s-Fenster zuckt genau einmal kurz ein Ohr.
		int twitches = 0;
		boolean was = false;
		for (double t = 0; t < 20; t += 0.05) {
			boolean now = FoxParticles.twitch(t, false);
			if (now && !was) twitches++;
			was = now;
		}
		assertEquals(1, twitches);
	}

	@Test
	void sleepingFoxSpriteBreathes() {
		List<int[]> out = FoxPainter.foxPixels(false, false);
		List<int[]> in = FoxPainter.foxPixels(true, false);
		assertEquals(1, backTop(out), "ausgeatmet liegt der Rücken in Zeile 1");
		assertEquals(0, backTop(in), "eingeatmet hebt sich der Rücken um einen Texel");
		for (int[] p : out) {
			assertTrue(p[0] >= 0 && p[0] < FoxPainter.FOX_W && p[1] >= 0 && p[1] < FoxPainter.FOX_H);
			assertNotEquals(0, p[2]);
		}
		assertTrue(FoxPainter.foxPixels(false, true).size() < out.size(), "Ohrzucken: ein Ohr liegt flach");
	}

	/** Oberste Zeile des Rückens (Spalten 10…14). */
	private static int backTop(List<int[]> px) {
		int top = Integer.MAX_VALUE;
		for (int[] p : px) if (p[0] >= 10 && p[0] <= 14) top = Math.min(top, p[1]);
		return top;
	}

	@Test
	void particlesMatchTheLauncher() {
		FoxParticles.Leaves lv = new FoxParticles.Leaves(427, 240, new WinterMath.Rng(42), 1);
		for (int t = 0; t < 50; t++) lv.step(t);
		assertEquals(31, lv.count);
		assertEquals(366.0585646694701, lv.x[0], 1e-9);
		assertEquals(165.05866055817017, lv.y[0], 1e-9);
		assertEquals(2, lv.tone[0]);
		FoxParticles.Fireflies ff = new FoxParticles.Fireflies(new WinterMath.Rng(9), 5, 10, 100, 20, 60);
		ff.at(1, 12.3, false);
		assertEquals(80, ff.ax);
		assertEquals(30, ff.ay);
		assertEquals(0.7255964288573898, ff.aa, 1e-9);
		ff.at(1, 12.3, true);
		assertEquals(0.7, ff.aa, 1e-9, "ruhig: glimmt gleichmäßig");
	}

	@Test
	void sparksRiseAndFade() {
		FoxParticles.Sparks sp = new FoxParticles.Sparks(new WinterMath.Rng(3));
		double[] origin = {100, 50, 6};
		for (int t = 0; t < 200; t++) sp.step(origin, t, 1);
		assertTrue(sp.count > 0 && sp.count <= FoxParticles.Sparks.MAX);
		for (int i = 0; i < sp.count; i++) assertTrue(sp.y[i] <= 50, "Funken steigen");
		for (int t = 200; t < 300; t++) sp.step(null, t, 1);
		assertEquals(0, sp.count, "ohne Feuer vergehen sie");
	}

	// --- Malen ---

	@Test
	void painterComposesAWholeFrame() {
		FoxLandscape L = FoxLandscape.build(427, 240, FoxScene.SEED, true);
		for (String theme : new String[]{"dark", "oled", "light"}) {
			FoxPainter P = new FoxPainter(L, FoxPalette.of(theme, "fox", 0xE8702A));
			FoxPainter.Frame f = new FoxPainter.Frame();
			f.tick = 40;
			f.seconds = 3.2;
			WinterMath.Rng r = new WinterMath.Rng(1);
			f.leaves = new FoxParticles.Leaves(L.w, L.h, r, 1);
			f.smoke = new Snow.Smoke(r);
			f.sparks = new FoxParticles.Sparks(r);
			int[] out = new int[L.w * L.h];
			P.compose(out, f);
			Set<Integer> colors = new HashSet<Integer>();
			for (int v : out) {
				assertEquals(0xFF, v >>> 24, "deckend");
				colors.add(v);
			}
			assertTrue(colors.size() > 200, "viele Töne (Bänder, Texturen, Licht): " + colors.size());
			// Der Fuchs liegt am Feuer: in seinem Rechteck stehen Fuchsfarben (warmes Orange).
			FoxLandscape.Parts p = L.parts();
			int orange = 0;
			for (int y = p.foxY - FoxPainter.FOX_H; y < p.foxY; y++) {
				for (int x = p.foxX; x < p.foxX + FoxPainter.FOX_W; x++) {
					int v = out[y * L.w + x];
					if (((v >> 16) & 255) > ((v & 255) + 60)) orange++;
				}
			}
			assertTrue(orange > 60, theme + ": Fuchs sichtbar (" + orange + ")");
		}
	}

	@Test
	void calmFrameIsStill() {
		FoxLandscape L = FoxLandscape.build(200, 120, FoxScene.SEED, true);
		FoxPainter P = new FoxPainter(L, FoxPalette.of("dark", "fox", 0xE8702A));
		FoxPainter.Frame f = new FoxPainter.Frame();
		f.calm = true;
		int[] a = new int[L.w * L.h];
		int[] b = new int[L.w * L.h];
		f.tick = 10;
		f.seconds = 1;
		P.compose(a, f);
		f.seconds = 7;
		P.compose(b, f);
		assertArrayEquals(a, b, "ohne Animation: Feuer, Glühwürmchen und Licht stehen");
	}
}
