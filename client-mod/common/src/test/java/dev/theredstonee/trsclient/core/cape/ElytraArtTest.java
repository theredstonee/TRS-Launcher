package dev.theredstonee.trsclient.core.cape;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Elytra-Look aus dem Umhang-Motiv: Lage der Flächen, Strecken, Spiegeln, Löcher, HD, Animation. */
class ElytraArtTest {
	static final int RED = 0xFFFF0000;

	/** Umhang-Bild 64s × 32s: Außenseite = Motiv (kein Rot), Elytra-Bereich komplett rot (wie viele Team-Umhänge). */
	static int[] frame(int s, int seed) {
		int w = 64 * s;
		int h = 32 * s;
		int[] px = new int[w * h];
		for (int y = 0; y < 22 * s; y++) {
			for (int x = 22 * s; x < 46 * s; x++) px[y * w + x] = RED;
		}
		for (int y = 0; y < 16 * s; y++) {
			for (int x = 0; x < 10 * s; x++) px[(s + y) * w + s + x] = design(x / s, y / s, seed);
		}
		return px;
	}

	/** Motiv-Pixel (Einheiten 10×16): jede Stelle eine eigene Farbe, nie Rot. */
	static int design(int x, int y, int seed) {
		return 0xFF000000 | (((x * 23 + seed * 7) & 0x7F) << 8) | ((y * 15 + seed * 3) & 0xFF);
	}

	static int at(int[] px, int w, int x, int y) {
		return px[y * w + x];
	}

	/** Alle sichtbaren Elytra-Flächen (Pixelkoordinaten bei Faktor s). */
	static boolean visible(int u, int v, int s) {
		int uu = u / s;
		int vv = v / s;
		if (vv >= 2 && vv < 22) return uu >= 22 && uu < 46;
		return vv < 2 && uu >= 24 && uu < 44;
	}

	@Test
	void classicCapeGetsWingsFromTheFrontFace() {
		int s = 1;
		int w = 64;
		int[] px = frame(s, 0);
		int[] before = px.clone();
		ElytraArt.apply(px, w, 32);

		// Keine roten Reste, nichts Durchsichtiges auf sichtbaren Flächen.
		for (int v = 0; v < 22; v++) {
			for (int u = 22; u < 46; u++) {
				if (!visible(u, v, s)) continue;
				int c = at(px, w, u, v);
				assertNotEquals(RED, c, "rot bei " + u + "," + v);
				assertEquals(0xFF, c >>> 24, "durchsichtig bei " + u + "," + v);
			}
		}
		// Außenseite (36..46): Spalte = Motivspalte, Zeilen 16 → 20 gestreckt (erste/letzte Zeile bleiben).
		for (int x = 0; x < 10; x++) {
			assertEquals(design(x, 0, 0), at(px, w, 36 + x, 2));
			assertEquals(design(x, 15, 0), at(px, w, 36 + x, 21));
		}
		// Jede Quellzeile kommt vor, jede vierte doppelt (gleichmäßig verteilt).
		int[] count = new int[16];
		for (int y = 0; y < 20; y++) {
			int c = at(px, w, 36, 2 + y);
			for (int r = 0; r < 16; r++) if (design(0, r, 0) == c) count[r]++;
		}
		for (int r = 0; r < 16; r++) assertEquals(r % 4 == 0 ? 2 : 1, count[r], "Zeile " + r);
		// Innenseite (24..34) = Außenseite gespiegelt und dunkler; Kanten aus den Randspalten.
		for (int y = 0; y < 20; y++) {
			for (int x = 0; x < 10; x++) {
				assertEquals(ElytraArt.shade(at(px, w, 36 + 9 - x, 2 + y), 3, 5), at(px, w, 24 + x, 2 + y));
			}
			int shoulder = ElytraArt.shade(at(px, w, 36, 2 + y), 4, 5);
			int end = ElytraArt.shade(at(px, w, 45, 2 + y), 4, 5);
			assertEquals(shoulder, at(px, w, 34, 2 + y));
			assertEquals(shoulder, at(px, w, 35, 2 + y));
			assertEquals(end, at(px, w, 22, 2 + y));
			assertEquals(end, at(px, w, 23, 2 + y));
		}
		// Oben/unten: oberste/unterste Motivzeile, an der Schulter (u 33 bzw. 43) die erste Motivspalte.
		for (int v = 0; v < 2; v++) {
			assertEquals(ElytraArt.shade(design(0, 0, 0), 4, 5), at(px, w, 33, v));
			assertEquals(ElytraArt.shade(design(9, 0, 0), 4, 5), at(px, w, 24, v));
			assertEquals(ElytraArt.shade(design(0, 15, 0), 4, 5), at(px, w, 43, v));
			assertEquals(ElytraArt.shade(design(9, 15, 0), 4, 5), at(px, w, 34, v));
		}
		// Ungenutzte Ecken des Bereichs sind leer (wie im Launcher).
		assertEquals(0, at(px, w, 22, 0));
		assertEquals(0, at(px, w, 45, 1));
		// Umhang selbst und der Rest der Textur bleiben unverändert.
		for (int v = 0; v < 32; v++) {
			for (int u = 0; u < 64; u++) {
				if (u >= 22 && u < 46 && v < 22) continue;
				assertEquals(before[v * w + u], at(px, w, u, v));
			}
		}
	}

	@Test
	void wingFacesMatchAtTheSharedEdges() {
		// Die Fläche um den Quader herum: Innenseite | Schulterkante | Außenseite – an der Schulter dieselbe Motivspalte,
		// am Flügelende (u 24 ↔ u 45 über die Kante 22..24) ebenfalls.
		int w = 64;
		int[] px = frame(1, 3);
		ElytraArt.apply(px, w, 32);
		for (int y = 2; y < 22; y++) {
			assertEquals(ElytraArt.shade(at(px, w, 36, y), 3, 5), at(px, w, 33, y));
			assertEquals(ElytraArt.shade(at(px, w, 45, y), 3, 5), at(px, w, 24, y));
		}
	}

	@Test
	void hdCapeKeepsPixelArtCrisp() {
		// Faktor 16 (1024×512) mit HD-Pixeln 8×8 (wie TRS Studio): jedes Motiv-Pixel wird genau 10 Zeilen hoch.
		int s = 16;
		int w = 64 * s;
		int h = 32 * s;
		int[] px = new int[w * h];
		for (int y = 0; y < 22 * s; y++) for (int x = 22 * s; x < 46 * s; x++) px[y * w + x] = RED;
		for (int y = 0; y < 16 * s; y++) {
			for (int x = 0; x < 10 * s; x++) px[(s + y) * w + s + x] = design(x / 8, y / 8, 1);
		}
		ElytraArt.apply(px, w, h);
		for (int v = 0; v < 22 * s; v++) {
			for (int u = 22 * s; u < 46 * s; u++) {
				if (!visible(u, v, s)) continue;
				int c = at(px, w, u, v);
				assertNotEquals(RED, c);
				assertEquals(0xFF, c >>> 24);
			}
		}
		int ox = 36 * s;
		int oy = 2 * s;
		for (int row = 0; row < 32; row++) {
			int c = design(0, row, 1);
			for (int y = 0; y < 10; y++) assertEquals(c, at(px, w, ox, oy + row * 10 + y), "HD-Zeile " + row + "/" + y);
		}
		// Spalten 1:1 (gleiche Breite) – HD-Pixel bleiben 8 breit.
		for (int x = 0; x < 10 * s; x++) assertEquals(design(x / 8, 0, 1), at(px, w, ox + x, oy));
		// Spiegelung der Innenseite auch in HD.
		for (int x = 0; x < 10 * s; x += 7) {
			assertEquals(ElytraArt.shade(at(px, w, ox + 10 * s - 1 - x, oy + 50), 3, 5),
					at(px, w, 24 * s + x, oy + 50));
		}
	}

	@Test
	void holesAreFilledWithTheNearestOpaqueColour() {
		int w = 64;
		int[] px = frame(1, 0);
		// Loch in Spalte 0..4 der obersten Motivzeile (durchsichtig) und halbdurchsichtige Ecke unten rechts.
		for (int x = 0; x < 5; x++) px[1 * w + 1 + x] = 0x00000000;
		px[16 * w + 10] = 0x40FFFFFF;
		ElytraArt.apply(px, w, 32);
		// Oberste Außenzeile: Spalten 0..3 nehmen die Farbe direkt darunter (nächstes deckendes Pixel).
		for (int x = 0; x < 4; x++) assertEquals(design(x, 1, 0), at(px, w, 36 + x, 2));
		int tie = at(px, w, 40, 2);
		assertTrue(tie == design(4, 1, 0) || tie == design(5, 0, 0), "Gleichstand " + Integer.toHexString(tie));
		for (int x = 5; x < 10; x++) assertEquals(design(x, 0, 0), at(px, w, 36 + x, 2));
		int corner = at(px, w, 45, 21);
		assertEquals(0xFF, corner >>> 24);
		assertTrue(corner == design(9, 14, 0) || corner == design(8, 15, 0), "Ecke " + Integer.toHexString(corner));
	}

	@Test
	void emptyFrontBecomesGrey() {
		int w = 64;
		int[] empty = new int[64 * 32];
		ElytraArt.apply(empty, w, 32);
		assertEquals(ElytraArt.FALLBACK, at(empty, w, 40, 10));
		for (int v = 0; v < 22; v++) {
			for (int u = 22; u < 46; u++) if (visible(u, v, 1)) assertEquals(0xFF, at(empty, w, u, v) >>> 24);
		}
	}

	@Test
	void everyAnimationFrameGetsItsOwnWings() throws Exception {
		// Zwei Bilder mit verschiedenem Motiv: nach dem Laden zeigt jedes Bild seine eigenen Flügel.
		int[] a = frame(2, 0);
		int[] b = frame(2, 5);
		CapeFrames frames = new CapeFrames(128, 64, new int[][] {a, b});
		ElytraArt.apply(frames);
		assertEquals(design(3, 0, 0), at(a, 128, 72 + 6, 4));
		assertEquals(design(3, 0, 5), at(b, 128, 72 + 6, 4));
		assertNotEquals(at(a, 128, 80, 20), at(b, 128, 80, 20));
	}

	@Test
	void oddDownscaledSizesStayInsideTheRegion() {
		// Faktor 3 halbiert (96×48): Bereichsgrenzen gerundet, nichts außerhalb des Elytra-Bereichs angefasst.
		int w = 96;
		int h = 48;
		int[] px = new int[w * h];
		java.util.Arrays.fill(px, 0xFF0000FF);
		int[] before = px.clone();
		ElytraArt.apply(px, w, h);
		for (int v = 0; v < h; v++) {
			for (int u = 0; u < w; u++) {
				if (u >= ElytraArt.p(22, w) && u < ElytraArt.p(46, w) && v < ElytraArt.p(22, w)) continue;
				assertEquals(before[v * w + u], px[v * w + u]);
			}
		}
		assertEquals(0xFF0000FF, at(px, w, ElytraArt.p(36, w), ElytraArt.p(2, w)));
	}
}
