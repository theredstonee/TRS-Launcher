package dev.theredstonee.trsclient.core.ui.winter;

import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.intro.ClientState;
import dev.theredstonee.trsclient.core.ui.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Design „Winternacht“: Theme-Achse, Launcher-Datei, Landschaft (gleich wie im Launcher), Szene, Pinguin. */
class WinterTest {
	@AfterEach
	void reset() {
		Theme.set(Theme.of("dark", "redstone", null));
	}

	// --- Design im Theme ---

	@Test
	void designParsing() {
		assertTrue(Theme.of("dark", "ice", null, "winter").winter);
		assertEquals(Theme.WINTER, Theme.of("dark", "ice", null, " Winter ").designName);
		assertFalse(Theme.of("dark", "redstone", null).winter, "ohne Design: Redstone");
		assertEquals(Theme.REDSTONE, Theme.of("dark", "redstone", null, null).designName);
		assertEquals(Theme.REDSTONE, Theme.of("dark", "redstone", null, "neon").designName);
		assertEquals(Theme.REDSTONE, Theme.of("dark", "redstone", null, "wïnter").designName);
		assertEquals(Theme.REDSTONE, Theme.normalizeDesign("winterwinterwinterwinterwinter"));
		assertEquals(0xFF2B97D6, Theme.of("dark", "ice", null, "winter").accent);
		assertEquals(0xFF2B97D6, Theme.of("dark", "ice", "#000000").accent, "Eis ist ein bekannter Akzent");
	}

	@Test
	void winterPaletteIsNightBlueAndIce() {
		Theme w = Theme.of("dark", "ice", null, "winter");
		assertEquals(0xFF0D1424, w.background);
		assertEquals(0xFF15203A, w.surface);
		assertEquals(0xFFEAF4FF, w.text);
		assertEquals(0xFF8FDCFF, w.lampOn, "Lampen sind Eis, nicht Bernstein");
		assertEquals(0xFF000000, Theme.of("oled", "ice", null, "winter").background);
		Theme day = Theme.of("light", "ice", null, "winter");
		assertTrue(day.light);
		assertEquals(0xFFE8EFF7, day.background);
		assertEquals(0xFF0B1426, day.text);
		// Andere Akzente bleiben frei wählbar.
		assertEquals(0xFF17A34A, Theme.of("dark", "emerald", null, "winter").accent);
	}

	@Test
	void accentFollowsDesignLikeTheLauncher() {
		assertEquals("ice", Theme.accentForDesign("winter", "redstone"));
		assertEquals("redstone", Theme.accentForDesign("redstone", "ice"));
		assertEquals("emerald", Theme.accentForDesign("winter", "emerald"));
		assertEquals("lapis", Theme.accentForDesign("redstone", "lapis"));
	}

	@Test
	void launcherFileCarriesTheDesign(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("trsclient").resolve("launcher-theme.json");
		Files.createDirectories(file.getParent());
		Files.write(file, ("{\"version\":1,\"theme\":\"oled\",\"accent\":\"ice\",\"accentColor\":\"#2B97D6\",\"language\":\"de\","
				+ "\"design\":\"winter\",\"event\":\"halloween\"}").getBytes(StandardCharsets.UTF_8));
		assertTrue(Theme.loadFrom(dir));
		// Das Halloween-Event legt sich als eigener Look darüber – das eingestellte Thema bleibt Winternacht.
		assertTrue(dev.theredstonee.trsclient.core.halloween.Halloween.active());
		assertTrue(Theme.get().halloween);
		assertTrue(Theme.plain().winter);
		assertEquals("oled", Theme.plain().themeName);
		assertEquals(0xFF2B97D6, Theme.plain().accent);
		dev.theredstonee.trsclient.core.halloween.Halloween.reset();
		// Ältere Launcher ohne Design-Feld → Redstone.
		Files.write(file, "{\"version\":1,\"theme\":\"dark\",\"accent\":\"lapis\"}".getBytes(StandardCharsets.UTF_8));
		assertTrue(Theme.loadFrom(dir));
		assertFalse(Theme.get().winter);
		// Unbekanntes Design → Redstone.
		Files.write(file, "{\"version\":1,\"theme\":\"dark\",\"accent\":\"lapis\",\"design\":\"neon\"}".getBytes(StandardCharsets.UTF_8));
		assertTrue(Theme.loadFrom(dir));
		assertEquals(Theme.REDSTONE, Theme.get().designName);
	}

	@Test
	void clientStateRemembersTheDesign() {
		ClientState cs = new ClientState();
		cs.chooseLook("dark", "ice", "de", "winter", 1234L);
		TrsConfig config = new TrsConfig();
		cs.write(config);
		assertEquals("winter", config.clientState.lookDesign);
		ClientState back = new ClientState();
		back.read(config);
		assertEquals("winter", back.lookDesign());
		assertEquals("ice", back.lookAccent());
		// Alte Wahl ohne Design bleibt ohne Design (null = Launcher/Datei entscheidet).
		cs.chooseLook("light", "lamp", "en", 2000L);
		assertEquals("winter", cs.lookDesign(), "die alte Signatur ändert das Design nicht");
		config.clientState.lookDesign = null;
		back.read(config);
		assertNull(back.lookDesign());
	}

	// --- Landschaft: gleiche Zahlen wie app/utils/winter/landscape.ts ---

	@Test
	void noiseAndRandomMatchTheLauncher() {
		assertEquals(0.5008470041211694, WinterMath.noise(1, 2, 3), 1e-15);
		assertEquals(0.31052807718515396, WinterMath.noise(-5, 7, 1440), 1e-15);
		assertEquals(0.6114174074027687, WinterMath.noise(12345, 678, 65535 + 51), 1e-15);
		WinterMath.Rng r = new WinterMath.Rng(0x5a0 ^ 0x5eed);
		assertEquals(0.4594532446935773, r.next(), 1e-15);
		assertEquals(0.38138311193324625, r.next(), 1e-15);
		assertEquals(0.8263559793122113, r.next(), 1e-15);
	}

	private static int sum(short[] a) {
		int s = 0;
		for (short v : a) s += v;
		return s;
	}

	@Test
	void landscapeMatchesTheLauncher() {
		// Werte aus buildLandscape({cols: 427, rows: 240, seed: 0x5a0}) im Launcher.
		Landscape L = Landscape.build(427, 240, WinterScene.SEED, -1, true);
		assertEquals(134, L.horizon);
		assertEquals(36, L.mountainAmp);
		assertEquals(48942, sum(L.mountainsBack));
		assertEquals(52261, sum(L.mountains));
		assertEquals(58190, sum(L.forest));
		assertEquals(60689, sum(L.hills));
		assertEquals(86773, sum(L.ground));
		assertEquals(92, L.pines.size());
		assertEquals(60, L.pines.get(0).x);
		assertEquals(137, L.pines.get(0).base);
		assertEquals(9, L.pines.get(0).h);
		assertEquals(771710188, L.pines.get(0).seed);
		Landscape.Pine last = L.pines.get(L.pines.size() - 1);
		assertEquals(-2, last.x);
		assertEquals(101, last.h);
		assertEquals(176, L.stars.size());
		assertEquals(342, L.moonX);
		assertEquals(36, L.moonY);
		assertEquals(8, L.moonR);
		assertEquals(360, L.cabinX);
		assertEquals(204, L.cabinY);
		assertEquals(171, L.sparkles.length);
		assertEquals(7, L.tufts.size());
		assertEquals(4, L.clouds.size());
		assertEquals(49, L.clouds.get(0).w);
		assertEquals(0.02872442388208583, L.clouds.get(0).speed, 1e-12);

		Landscape small = Landscape.build(200, 120, WinterScene.SEED, -1, true);
		assertEquals(67, small.horizon);
		assertEquals(11497, sum(small.mountainsBack));
		assertEquals(20271, sum(small.ground));
		assertEquals(50, small.pines.size());
		assertEquals(144, small.cabinX);
		assertEquals(102, small.cabinY);
	}

	@Test
	void landscapeIsDeterministicAndRealMountains() {
		Landscape a = Landscape.build(480, 270, 7, -1, true);
		Landscape b = Landscape.build(480, 270, 7, -1, true);
		assertArrayEquals(a.mountainsBack, b.mountainsBack);
		assertArrayEquals(a.ground, b.ground);
		assertEquals(a.pines.size(), b.pines.size());
		assertNotEquals(Arrays.hashCode(a.mountainsBack), Arrays.hashCode(Landscape.build(480, 270, 8, -1, true).mountainsBack));
		// Gipfel ragen über den Horizont, aber ohne Nadeln (höchstens 2 Texel Sprung je Spalte).
		int peak = a.horizon;
		for (int x = 0; x < a.w; x++) {
			peak = Math.min(peak, a.mountainsBack[x]);
			if (x > 0) assertTrue(Math.abs(a.mountainsBack[x] - a.mountainsBack[x - 1]) <= 2, "Nadel bei " + x);
		}
		assertTrue(a.horizon - peak >= a.mountainAmp / 2, "es gibt richtige Gipfel");
		assertTrue(a.hasCabin());
		assertNotNull(a.chimney());
		assertFalse(Landscape.build(140, 80, 7, -1, true).hasCabin(), "zu klein für die Hütte");
	}

	@Test
	void auroraComesAndGoes() {
		assertEquals(1.0, Landscape.auroraEnvelope(10, WinterScene.SEED), 1e-9);
		assertEquals(0.0, Landscape.auroraEnvelope(130, WinterScene.SEED), 1e-9);
		assertEquals(1.0, Landscape.auroraEnvelope(160, WinterScene.SEED), 1e-9);
		assertEquals(0.0, Landscape.auroraEnvelope(0, WinterScene.SEED), 1e-9);
	}

	// --- Bild ---

	@Test
	void composeFillsEveryPixelAndAuroraOnlyAtNight() {
		Landscape L = Landscape.build(427, 240, WinterScene.SEED, -1, true);
		WinterPainter night = new WinterPainter(L, WinterPalette.of("dark", "ice", 0xFF2B97D6));
		WinterPainter.Frame f = new WinterPainter.Frame();
		f.seconds = 20;
		int[] calm = new int[L.w * L.h];
		night.compose(calm, f);
		for (int v : calm) assertEquals(0xFF, v >>> 24, "deckend");
		f.aurora = 1;
		int[] aurora = new int[L.w * L.h];
		night.compose(aurora, f);
		int changed = 0;
		for (int i = 0; i < calm.length; i++) {
			if (calm[i] != aurora[i]) {
				changed++;
				assertTrue(i / L.w < L.horizon + 2, "Polarlicht nur am Himmel");
			}
		}
		assertTrue(changed > 500, "Polarlicht sichtbar: " + changed);
		// Am hellen Wintertag gibt es kein Polarlicht.
		WinterPainter day = new WinterPainter(L, WinterPalette.of("light", "ice", 0xFF2B97D6));
		int[] d0 = new int[calm.length];
		int[] d1 = new int[calm.length];
		f.aurora = 0;
		day.compose(d0, f);
		f.aurora = 1;
		day.compose(d1, f);
		assertArrayEquals(d0, d1);
		// Warmes Fenster der Hütte.
		int win = calm[(L.cabinTop() + 24) * L.w + L.cabinX + 12];
		assertTrue(((win >> 16) & 255) > ((win & 255) + 40), "Fenster leuchtet warm: " + Integer.toHexString(win));
	}

	@Test
	void penguinGoesOutWavesAndReturns() {
		Penguin p = new Penguin(new WinterMath.Rng(1), false, false);
		assertEquals(Penguin.WINDOW, p.phase());
		p.trigger();
		boolean waved = false;
		boolean doorOpened = false;
		int minX = Integer.MAX_VALUE;
		for (int i = 0; i < 400 && !(waved && p.phase() == Penguin.WINDOW); i++) {
			p.step();
			if (p.phase() == Penguin.WAVE) {
				waved = true;
				assertTrue(p.wave() >= 0);
			}
			if (p.door() == 2) doorOpened = true;
			if (p.outside()) minX = Math.min(minX, p.x());
		}
		assertTrue(waved && doorOpened);
		assertEquals(Penguin.TARGET_X, minX, "watschelt bis vors Fenster");
		assertEquals(Penguin.WINDOW, p.phase(), "zurück im Haus");
		assertEquals(0, p.door());
		p.setStill(true);
		assertEquals(Penguin.STILL, p.phase());
		assertTrue(p.outside(), "ohne Animation steht er neben der Tür");
		// Santa-Mütze: rote Pixel ganz oben.
		boolean red = false;
		for (int[] px : Penguin.pixels(0, -1)) if (px[1] == 0 && px[2] == 0xFFD8263A) red = true;
		assertTrue(red);
	}

	@Test
	void snowStaysInTheSceneAndSmokeRises() {
		Snow.Fall f = new Snow.Fall(300, 200, new WinterMath.Rng(3), 1);
		assertEquals(200, f.count);
		for (int t = 0; t < 2000; t++) f.step(t);
		for (int i = 0; i < f.count; i++) {
			assertTrue(f.x[i] >= -3 && f.x[i] <= 303, "x " + f.x[i]);
			assertTrue(f.y[i] <= 202 + 1, "y " + f.y[i]);
		}
		Snow.Smoke s = new Snow.Smoke(new WinterMath.Rng(4));
		for (int t = 0; t < 200; t++) s.step(true, 100, 50, t);
		assertTrue(s.count > 0 && s.count <= Snow.Smoke.MAX);
		for (int i = 0; i < s.count; i++) assertTrue(s.y[i] <= 50);
	}

	@Test
	void texelGrowsOnlyForHugeWindows() {
		assertEquals(1, WinterScene.texelFor(427, 240));
		assertEquals(1, WinterScene.texelFor(640, 360));
		assertEquals(2, WinterScene.texelFor(960, 540));
		assertEquals(3, WinterScene.texelFor(1920, 1080));
	}
}
