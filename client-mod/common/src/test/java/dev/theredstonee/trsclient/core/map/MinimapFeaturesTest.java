package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.module.NewSince;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimap 2: Texturfarben (PNG-Dekoder, Mittelung, Palette, Abtastung), Wegpunkte am Rand, Auto-Zoom,
 * Kreatur-Köpfe/Symbole.
 */
class MinimapFeaturesTest {
	@AfterEach
	void noStore() {
		Textures.replaceForTests(null);
	}

	// --- PNG-Dekoder: alle Bittiefen, Interlacing ---

	/** Baut eine PNG-Datei aus fertigen (ungefilterten) Durchgangs-Zeilen. */
	static byte[] png(int w, int h, int depth, int colorType, int interlace, byte[] plte, byte[] trns, byte[] raw) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
			byte[] ihdr = new byte[13];
			putInt(ihdr, 0, w);
			putInt(ihdr, 4, h);
			ihdr[8] = (byte) depth;
			ihdr[9] = (byte) colorType;
			ihdr[12] = (byte) interlace;
			chunk(out, "IHDR", ihdr);
			if (plte != null) chunk(out, "PLTE", plte);
			if (trns != null) chunk(out, "tRNS", trns);
			Deflater d = new Deflater();
			d.setInput(raw);
			d.finish();
			ByteArrayOutputStream z = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			while (!d.finished()) z.write(buf, 0, d.deflate(buf));
			d.end();
			chunk(out, "IDAT", z.toByteArray());
			chunk(out, "IEND", new byte[0]);
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void putInt(byte[] b, int o, int v) {
		b[o] = (byte) (v >>> 24);
		b[o + 1] = (byte) (v >>> 16);
		b[o + 2] = (byte) (v >>> 8);
		b[o + 3] = (byte) v;
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
		byte[] len = new byte[4];
		putInt(len, 0, data.length);
		out.write(len);
		byte[] t = type.getBytes("US-ASCII");
		out.write(t);
		out.write(data);
		CRC32 crc = new CRC32();
		crc.update(t);
		crc.update(data);
		byte[] c = new byte[4];
		putInt(c, 0, (int) crc.getValue());
		out.write(c);
	}

	/** RGBA 8 Bit, Pixel (x, y) → ARGB nach {@code f}, ohne Interlacing. */
	static byte[] rgbaPng(int w, int h, java.util.function.IntBinaryOperator f) {
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		for (int y = 0; y < h; y++) {
			raw.write(0);
			for (int x = 0; x < w; x++) {
				int c = f.applyAsInt(x, y);
				raw.write((c >> 16) & 0xFF);
				raw.write((c >> 8) & 0xFF);
				raw.write(c & 0xFF);
				raw.write(c >>> 24);
			}
		}
		return png(w, h, 8, 6, 0, null, null, raw.toByteArray());
	}

	@Test
	void pngDecodesFourBitPaletteWithTransparency() throws IOException {
		// 3×2, Palette mit 3 Farben (Index 2 durchsichtig), 4 Bit je Pixel → 2 Bytes je Zeile.
		byte[] plte = {(byte) 0xFF, 0, 0, 0, (byte) 0xFF, 0, 0, 0, (byte) 0xFF};
		byte[] trns = {(byte) 0xFF, (byte) 0xFF, 0};
		byte[] raw = {0, 0x01, 0x20, 0, 0x21, 0x00};
		PngDecoder.Image img = PngDecoder.decode(png(3, 2, 4, 3, 0, plte, trns, raw));
		assertEquals(0xFFFF0000, img.argb[0]);
		assertEquals(0xFF00FF00, img.argb[1]);
		assertEquals(0x000000FF, img.argb[2]);
		assertEquals(0x000000FF, img.argb[3]);
		assertEquals(0xFF00FF00, img.argb[4]);
		assertEquals(0xFFFF0000, img.argb[5]);
	}

	@Test
	void pngDecodesOneBitGrayAndSixteenBitRgba() throws IOException {
		// 1 Bit Graustufen: 10 Pixel = 2 Bytes je Zeile.
		byte[] raw = {0, (byte) 0b10100000, (byte) 0b01000000};
		PngDecoder.Image g = PngDecoder.decode(png(10, 1, 1, 0, 0, null, null, raw));
		assertEquals(0xFFFFFFFF, g.argb[0]);
		assertEquals(0xFF000000, g.argb[1]);
		assertEquals(0xFFFFFFFF, g.argb[2]);
		assertEquals(0xFFFFFFFF, g.argb[9]);
		// 16 Bit RGBA: nur das höhere Byte zählt.
		byte[] raw16 = {0, 0x12, 0x34, 0x56, 0x78, (byte) 0x9A, (byte) 0xBC, (byte) 0x80, 0};
		PngDecoder.Image c = PngDecoder.decode(png(1, 1, 16, 6, 0, null, null, raw16));
		assertEquals(0x8012569A, c.argb[0]);
		// Graustufe mit durchsichtigem Wert (tRNS) in 8 Bit.
		PngDecoder.Image t = PngDecoder.decode(png(2, 1, 8, 0, 0, null, new byte[] {0, 7}, new byte[] {0, 7, 9}));
		assertEquals(0x00070707, t.argb[0]);
		assertEquals(0xFF090909, t.argb[1]);
	}

	@Test
	void pngDecodesAdam7Interlacing() throws IOException {
		int w = 5, h = 5;
		java.util.function.IntBinaryOperator f = (x, y) -> 0xFF000000 | (x * 40) << 16 | (y * 40) << 8 | (x + y);
		int[][] passes = {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}};
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		for (int[] p : passes) {
			for (int y = p[1]; y < h; y += p[3]) {
				if (p[0] >= w) continue;
				raw.write(0);
				for (int x = p[0]; x < w; x += p[2]) {
					int c = f.applyAsInt(x, y);
					raw.write((c >> 16) & 0xFF);
					raw.write((c >> 8) & 0xFF);
					raw.write(c & 0xFF);
				}
			}
		}
		PngDecoder.Image img = PngDecoder.decode(png(w, h, 8, 2, 1, null, null, raw.toByteArray()));
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) assertEquals(f.applyAsInt(x, y), img.argb[y * w + x], "Pixel " + x + "," + y);
		}
	}

	// --- Texturfarben ---

	@Test
	void textureAverageIsAlphaWeightedAndUsesTheFirstFrame() {
		int[] px = {0xFFFF0000, 0xFF0000FF, 0x00FFFFFF, 0x0000FF00};
		assertEquals(0x7F007F, TexturePalette.average(px, 2, 2), "durchsichtige Pixel zählen nicht");
		assertEquals(TexturePalette.UNKNOWN, TexturePalette.average(new int[] {0, 0}, 1, 2));
		// Animiert (1×3): nur das erste Bild.
		assertEquals(0x102030, TexturePalette.average(new int[] {0xFF102030, 0xFFFFFFFF, 0xFFFFFFFF}, 1, 3));
		// Halb deckend zählt halb.
		assertEquals(0x555555, TexturePalette.average(new int[] {0xFF000000, 0x80FFFFFF}, 2, 1));
		assertEquals(0x485E1B, TexturePalette.tint(0x7F7F7F, 0x91BD37), "Graustufe × Tönung");
		assertEquals(0x7F7F7F, TexturePalette.tint(0x7F7F7F, -1));
	}

	@Test
	void paletteResolvesOnceReadsEachTextureOnceAndResetsOnReload() {
		TexturePalette p = new TexturePalette(true);
		final int[] reads = {0};
		final Map<String, byte[]> files = new HashMap<String, byte[]>();
		files.put("minecraft:textures/block/stone.png", rgbaPng(2, 2, (x, y) -> 0xFF808080));
		files.put("minecraft:textures/block/grass_block_top.png", rgbaPng(2, 2, (x, y) -> x == 0 ? 0xFF404040 : 0xFFC0C0C0));
		p.setResources((ns, path) -> {
			reads[0]++;
			return files.get(ns + ":" + path);
		});
		Object stone = new Object(), stone2 = new Object(), grass = new Object(), glass = new Object();
		assertEquals(TexturePalette.UNRESOLVED, p.lookup(stone));
		assertEquals(0x808080, p.resolve(stone, "minecraft:block/stone", false));
		assertEquals(0x808080, p.resolve(stone2, "minecraft:block/stone", false));
		assertEquals(1, reads[0], "gleiche Textur nur einmal gelesen");
		assertEquals(0x808080 | TexturePalette.TINTED, p.resolve(grass, "minecraft:block/grass_block_top", true));
		assertEquals(TexturePalette.UNKNOWN, p.resolve(glass, "minecraft:block/missing", false), "fehlende Datei");
		assertEquals(TexturePalette.UNKNOWN, p.resolve(new Object(), null, false));
		assertEquals(0x808080, p.lookup(stone));
		assertTrue(p.takeChanged());
		assertFalse(p.takeChanged());
		// Direkt bekannte Farbe (Legacy: Pixel im Speicher).
		Object direct = new Object();
		assertEquals(0x123456 | TexturePalette.TINTED, p.resolveColor(direct, 0x123456, true));
		// Reload: gleiches Token nichts, neues Token verwirft alles.
		Object token = new Object();
		assertFalse(p.checkGeneration(token));
		assertFalse(p.checkGeneration(token));
		int gen = p.generation();
		assertTrue(p.checkGeneration(new Object()));
		assertEquals(gen + 1, p.generation());
		assertEquals(TexturePalette.UNRESOLVED, p.lookup(stone));
		assertArrayEquals(new int[] {2, 2}, p.imageSize("minecraft:textures/block/stone.png"));
		assertArrayEquals(new int[] {0, 0}, p.imageSize("minecraft:textures/entity/none.png"));
	}

	/** Liest wie der Spielcode: Textur der Oberseite je „Zustand“ (hier: Kartenfarbe). */
	static final class TexturedReader implements ChunkReader {
		final MapTest.FakeReader base = new MapTest.FakeReader();
		final Map<Integer, Integer> textures = new HashMap<Integer, Integer>();

		@Override
		public boolean isLoaded(int chunkX, int chunkZ) {
			return base.isLoaded(chunkX, chunkZ);
		}

		@Override
		public boolean open(int chunkX, int chunkZ) {
			return base.open(chunkX, chunkZ);
		}

		@Override
		public int minY() {
			return base.minY();
		}

		@Override
		public int top(int x, int z) {
			return base.top(x, z);
		}

		@Override
		public int block(int x, int y, int z) {
			return base.block(x, y, z);
		}

		@Override
		public int tint(int x, int y, int z) {
			return base.tint(x, y, z);
		}

		@Override
		public int textureColor(int x, int y, int z) {
			Integer t = textures.get(base.block(x, y, z) & MapColors.RGB);
			return t == null ? -1 : t;
		}
	}

	@Test
	void scannerUsesTextureColorsWithTintAndFallsBackToVanilla() {
		TexturedReader r = new TexturedReader();
		r.textures.put(MapColors.MAP_GRASS, 0x808080 | TexturePalette.TINTED);
		r.textures.put(MapTest.SAND, 0xDBCFA3);
		r.base.column(1, 0, new int[] {MapTest.STONE, MapTest.SAND, ChunkReader.AIR});
		int[] px = new int[256], h = new int[256];
		assertTrue(ColumnScanner.surface(r, 0, 0, ColumnScanner.NO_CUT, true, px, h));
		assertEquals(MapColors.KNOWN | TexturePalette.tint(0x808080, 0x91BD59), px[0], "Gras: Textur × Biom");
		assertEquals(MapColors.KNOWN | 0xDBCFA3, px[1], "Sand aus der Textur");
		assertTrue(ColumnScanner.surface(r, 0, 0, ColumnScanner.NO_CUT, false, px, h));
		assertEquals(MapColors.KNOWN | MapColors.tint(MapColors.MAP_GRASS, 0x91BD59), px[0], "Vanilla-Farben");
		// Wasser: Wassertextur (Graustufe) × Biom-Wasserfarbe.
		r.textures.put(MapColors.MAP_WATER, 0xB1B1B1);
		r.base.column(3, 0, new int[] {MapTest.SAND, MapColors.MAP_WATER, MapColors.MAP_WATER, ChunkReader.AIR});
		assertTrue(ColumnScanner.surface(r, 0, 0, ColumnScanner.NO_CUT, true, px, h));
		assertEquals(MapColors.KNOWN | MapColors.WATER | MapColors.waterOver(0xDBCFA3, TexturePalette.tint(0xB1B1B1, 0x3F76E4), 2), px[3]);
		assertNotEquals(MapColors.KNOWN | MapColors.WATER | MapColors.water(MapTest.SAND, 0x3F76E4, 2), px[3]);
		// Unterschied messen (Selbsttest-Hilfe): Vanilla → Textur.
		MapLayer layer = new MapLayer("surface", 1, Integer.MIN_VALUE, null, null);
		assertTrue(ColumnScanner.surface(r, 0, 0, ColumnScanner.NO_CUT, false, px, h));
		layer.forWrite(0, 0, 0).writeChunk(0, 0, px, h, 0);
		MapColorDiff diff = MapColorDiff.snapshot(layer, 8, 8, 40);
		assertTrue(ColumnScanner.surface(r, 0, 0, ColumnScanner.NO_CUT, true, px, h));
		layer.forWrite(0, 0, 0).writeChunk(0, 0, px, h, 0);
		long[] cmp = diff.compare(layer);
		assertEquals(256, cmp[0]);
		assertTrue(cmp[1] > 200, "fast alles Gras ändert sich: " + MapColorDiff.describe(cmp));
		// Stein hat keine Texturfarbe → Vanilla.
		r.base.column(2, 0, new int[] {MapTest.STONE, ChunkReader.AIR});
		assertTrue(ColumnScanner.surface(r, 0, 0, ColumnScanner.NO_CUT, true, px, h));
		assertEquals(MapColors.KNOWN | MapTest.STONE, px[2]);
	}

	// --- Wegpunkte am Rand ---

	@Test
	void edgePositionsRoundTripOnCircleAndSquare() {
		double[] out = new double[2];
		for (boolean round : new boolean[] {true, false}) {
			for (int deg = 0; deg < 360; deg += 15) {
				double a = Math.toRadians(deg);
				double dx = Math.cos(a) * 100, dy = Math.sin(a) * 100;
				double s = EdgeLayout.along(dx, dy, 50f, round);
				assertTrue(s >= 0 && s < EdgeLayout.perimeter(50f, round) + 1e-6);
				EdgeLayout.point(s, 50f, round, out);
				// Gleiche Richtung, auf dem Rand.
				double cross = out[0] * dy - out[1] * dx;
				assertEquals(0, cross / 100, 1e-3, (round ? "rund " : "eckig ") + deg);
				assertTrue(out[0] * dx + out[1] * dy > 0);
				if (round) assertEquals(50, Math.hypot(out[0], out[1]), 1e-6);
				else assertEquals(50, Math.max(Math.abs(out[0]), Math.abs(out[1])), 1e-6);
			}
		}
	}

	@Test
	void edgeSpreadSeparatesCloseMarkersAroundTheirCenter() {
		double[] s = {100, 102, 300};
		EdgeLayout.spread(s, 3, 10, 400);
		assertEquals(10, s[1] - s[0], 1e-9, "Abstand");
		assertEquals(101, (s[0] + s[1]) / 2, 1e-9, "Gruppe um ihre Mitte");
		assertEquals(300, s[2], 1e-9, "einzelne bleiben");
		// Drei dicht beieinander, über die Nahtstelle 0/400 hinweg.
		double[] w = {398, 1, 3};
		EdgeLayout.spread(w, 3, 10, 400);
		double[] unwrapped = {w[0] > 200 ? w[0] - 400 : w[0], w[1] > 200 ? w[1] - 400 : w[1], w[2] > 200 ? w[2] - 400 : w[2]};
		assertEquals(10, unwrapped[1] - unwrapped[0], 1e-9);
		assertEquals(10, unwrapped[2] - unwrapped[1], 1e-9);
		// Kettenreaktion: zusammengelegte Gruppe stößt an die nächste.
		double[] c = {0, 1, 2, 14, 15};
		EdgeLayout.spread(c, 5, 10, 1000);
		for (int i = 0; i + 1 < 5; i++) {
			double d = ((c[i + 1] - c[i]) % 1000 + 1000) % 1000;
			assertTrue(d >= 10 - 1e-9 && d < 500, "Reihenfolge + Abstand " + i);
		}
		// Zu viele für den Umfang: gleichmäßig verteilt.
		double[] many = new double[8];
		EdgeLayout.spread(many, 8, 100, 400);
		for (int i = 0; i < 8; i++) {
			for (int j = i + 1; j < 8; j++) {
				double d = Math.abs(many[i] - many[j]);
				assertTrue(Math.min(d, 400 - d) >= 50 - 1e-6);
			}
		}
	}

	@Test
	void edgeMarkersMakeWayForCompassLetters() {
		// Himmelsrichtung fest bei 100: ein Kästchen genau dort weicht aus, die feste Stelle bleibt.
		double[] fixed = {100, 200, 300, 0};
		double[] s = {101};
		EdgeLayout.spread(s, 1, 12, 400, fixed, 4);
		assertTrue(Math.abs(s[0] - 100) >= 12 - 1e-9, "Abstand zur Himmelsrichtung: " + s[0]);
		assertArrayEquals(new double[] {100, 200, 300, 0}, fixed, "feste Stellen unverändert");
		// Zwei Kästchen links und rechts nah an der festen Stelle: beide weichen zur eigenen Seite aus.
		double[] two = {97, 104};
		EdgeLayout.spread(two, 2, 12, 400, fixed, 4);
		assertEquals(88, two[0], 1e-9);
		assertEquals(112, two[1], 1e-9);
		// Über die Nahtstelle 0/400: Kästchen bei 399 und fester Punkt bei 0.
		double[] wrap = {399};
		EdgeLayout.spread(wrap, 1, 12, 400, fixed, 4);
		double d = Math.min(Math.abs(wrap[0] - 0), 400 - Math.abs(wrap[0] - 0));
		assertTrue(d >= 12 - 1e-9, "über die Naht: " + wrap[0]);
		// Weit weg: bleibt, wo es ist.
		double[] far = {150};
		EdgeLayout.spread(far, 1, 12, 400, fixed, 4);
		assertEquals(150, far[0], 1e-9);
	}

	@Test
	void edgeBoxLettersAndContrast() {
		assertEquals("B", MinimapRenderer.initial("basis"));
		assertEquals("Ü", MinimapRenderer.initial("  übersee"));
		assertEquals("7", MinimapRenderer.initial("#7 Mine"));
		assertEquals("?", MinimapRenderer.initial(""));
		assertEquals("?", MinimapRenderer.initial(null));
		assertEquals(0xFF15131A, MinimapRenderer.textOn(0xF2E14C), "dunkel auf Gelb");
		assertEquals(0xFFFFFFFF, MinimapRenderer.textOn(0x3D2B8F), "weiß auf Dunkelblau");
	}

	// --- Auto-Zoom ---

	@Test
	void autoZoomStepsOutWhenFastAndBackAfterAPause() {
		AutoZoom z = new AutoZoom();
		for (int i = 0; i < 20; i++) z.tick(0.1, 0, false, true, true);
		assertEquals(2f, z.target(2f), "gehen = eingestellter Zoom");
		for (int i = 0; i < AutoZoom.OUT_TICKS + 5; i++) z.tick(0.28, MapPlatform.SPRINTING, false, true, true);
		assertEquals(1, z.speedSteps(), "Sprinten = eine Stufe");
		assertEquals(1f, z.target(2f));
		for (int i = 0; i < 20; i++) z.tick(1.6, MapPlatform.GLIDING, false, true, true);
		assertEquals(2, z.speedSteps(), "Elytra = zwei Stufen");
		assertEquals(0.5f, z.target(2f));
		// Anhalten: erst nach einer Pause zurück.
		for (int i = 0; i < 10; i++) z.tick(0, 0, false, true, true);
		assertEquals(2, z.speedSteps(), "nicht sofort");
		for (int i = 0; i < AutoZoom.IN_TICKS + 10; i++) z.tick(0, 0, false, true, true);
		assertEquals(0, z.speedSteps());
		// Ein kurzer Sprung zählt nicht.
		z.tick(0.8, 0, false, true, true);
		z.tick(0, 0, false, true, true);
		assertEquals(0, z.speedSteps());
		// Boot: eine Stufe.
		for (int i = 0; i < 20; i++) z.tick(0.2, MapPlatform.BOAT, false, true, true);
		assertEquals(1, z.speedSteps());
		// Tempo-Zoom aus → sofort zurück.
		z.tick(0.2, MapPlatform.BOAT, false, false, true);
		assertEquals(0, z.speedSteps());
	}

	@Test
	void autoZoomGoesCloserIndoorsAndBlendsSmoothly() {
		AutoZoom z = new AutoZoom();
		for (int i = 0; i < 20; i++) z.tick(0, 0, true, true, true);
		assertEquals(1, z.indoorSteps());
		assertEquals(3f, z.target(2f), "drinnen eine Stufe näher");
		assertEquals(6f, z.target(6f), "am Ende der Leiter begrenzt");
		for (int i = 0; i < 20; i++) z.tick(0, 0, true, true, false);
		assertEquals(0, z.indoorSteps(), "abgeschaltet");
		// Überblenden: erst der Startwert, dann weich zum neuen Ziel.
		AutoZoom b = new AutoZoom();
		assertEquals(2f, b.frame(2f, 0.016f), 1e-4);
		for (int i = 0; i < 20; i++) b.tick(1.0, 0, false, true, true);
		float first = b.frame(2f, 0.05f);
		assertTrue(first < 2f && first > 0.5f, "unterwegs: " + first);
		float v = first;
		for (int i = 0; i < 200; i++) v = b.frame(2f, 0.05f);
		assertEquals(0.5f, v, 1e-4, "am Ende genau die Stufe (scharf)");
		assertEquals(4f, AutoZoom.step(3f, 1));
		assertEquals(0.5f, AutoZoom.step(1f, -3));
	}

	// --- Kreaturen ---

	@Test
	void mobKindsNormalizeAcrossVersions() {
		assertEquals("zombie", MobHeads.normalize("minecraft:zombie"));
		assertEquals("zombified_piglin", MobHeads.normalize("PigZombie"));
		assertEquals("zombified_piglin", MobHeads.normalize("minecraft:zombie_pigman"));
		assertEquals("cave_spider", MobHeads.normalize("CaveSpider"));
		assertEquals("iron_golem", MobHeads.normalize("VillagerGolem"));
		assertEquals("snow_golem", MobHeads.normalize("SnowMan"));
		assertEquals("mooshroom", MobHeads.normalize("MushroomCow"));
		assertEquals("horse", MobHeads.normalize("EntityHorse"));
		assertEquals("modid:thing", MobHeads.normalize("ModId:Thing"));
		assertEquals("", MobHeads.normalize(null));
		assertTrue(MobHeads.known("Creeper"));
		assertFalse(MobHeads.known("minecraft:axolotl"));
		assertEquals(32, MobHeads.logicalHeight(64, 128, 64), "HD 64×32");
		assertEquals(64, MobHeads.logicalHeight(64, 64, 64));
	}

	@Test
	void mobHeadsPickTheFirstExistingTextureWithItsAspect() {
		final Map<String, TextureRef> made = new HashMap<String, TextureRef>();
		Textures.replaceForTests(new Textures.Store() {
			@Override
			public TextureRef upload(String name, int width, int height, int[] argb) {
				return null;
			}

			@Override
			public void release(TextureRef texture) {
			}

			@Override
			public TextureRef game(String location, int width, int height) {
				TextureRef r = new TextureRef(location, width, height);
				made.put(location, r);
				return r;
			}

			@Override
			public Textures.DefaultSkin defaultSkin(UUID uuid) {
				return null;
			}
		});
		TexturePalette p = new TexturePalette(true);
		final Map<String, byte[]> files = new HashMap<String, byte[]>();
		// Nur die alte Kuh-Textur (64×32) gibt es.
		files.put("minecraft:textures/entity/cow/cow.png", rgbaPng(64, 32, (x, y) -> 0xFF000000));
		p.setResources((ns, path) -> files.get(ns + ":" + path));
		MobHeads heads = new MobHeads();
		MobHeads.Head cow = heads.head("Cow", p, p.generation());
		assertNotNull(cow);
		assertEquals("minecraft:textures/entity/cow/cow.png", cow.texture.id);
		assertEquals(32, cow.texture.height);
		assertEquals(6, cow.u);
		assertEquals(8, cow.w);
		assertNull(heads.head("minecraft:zombie", p, p.generation()), "keine Textur → Symbol");
		assertNull(heads.head("minecraft:axolotl", p, p.generation()), "unbekannte Art → Symbol");
	}

	@Test
	void creatureFilterAndColors() {
		TrsModules m = new TrsModules();
		m.minimapHostile.set(true);
		m.minimapPassive.set(false);
		assertTrue(MinimapRenderer.showCreature(m, MapEntity.HOSTILE));
		assertTrue(MinimapRenderer.showCreature(m, MapEntity.NEUTRAL), "neutral mit feindlichen");
		assertFalse(MinimapRenderer.showCreature(m, MapEntity.PASSIVE));
		m.minimapHostile.set(false);
		assertFalse(MinimapRenderer.showCreature(m, MapEntity.NEUTRAL));
		assertEquals(MinimapRenderer.NEUTRAL, MinimapRenderer.creatureColor(MapEntity.NEUTRAL));
		assertEquals(TrsModules.MobIcons.HEADS, m.minimapMobIcons.get(), "Standard: Köpfe");
		assertEquals(TrsModules.MapColorMode.TEXTURES, m.minimapColors.get(), "Standard: Texturfarben");
		assertEquals("0.13.0", NewSince.of("minimap.colors"));
		assertEquals("0.13.0", NewSince.of("minimap.edgeWaypoints"));
	}
}
