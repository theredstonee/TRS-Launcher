package dev.theredstonee.trsclient.core.cape;

import dev.theredstonee.trsclient.core.online.CapeInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** „Automatisch“: Pixel-Art scharf, echte HD-Umhänge weich mit Mipmaps (gleiche Regel wie der Launcher). */
class CapeFilterTest {
	/** Pixel-Art (wenige Farben, Löcher) im 64×32-Raster, pixelgenau mal {@code k}. */
	static int pixelArt(int x, int y, int k) {
		int gx = x / k;
		int gy = y / k;
		int[] colors = {0xFFC81E1E, 0xFF141414, 0xFFF0C828, 0x00000000};
		return colors[(gx * 7 + gy * 3) % colors.length];
	}

	/** Verlauf mit Rauschen – wie ein Foto (keine Blöcke). */
	static int photo(int x, int y) {
		int noise = ((x * 73856093) ^ (y * 19349663)) & 7;
		return 0xFF000000 | ((x * 255 / 1024 + noise) & 0xFF) << 16 | ((y * 255 / 512) & 0xFF) << 8 | (128 + noise);
	}

	static int[] image(int w, int h, java.util.function.IntBinaryOperator f) {
		int[] px = new int[w * h];
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = f.applyAsInt(x, y);
		return px;
	}

	@Test
	void blocksAreUniformAndTransparentPixelsCountAsEqual() {
		int[] art = image(1024, 512, (x, y) -> pixelArt(x, y, 16));
		assertTrue(CapeFilter.blockUniform(art, 1024, 512, 16));
		assertTrue(CapeFilter.blockUniform(art, 1024, 512, 8));
		assertFalse(CapeFilter.blockUniform(art, 1024, 512, 32), "zwei Raster-Pixel je Block");
		assertFalse(CapeFilter.blockUniform(art, 1024, 512, 3), "teilt die Größe nicht");
		// Durchsichtige Pixel mit anderer „Farbe“ brechen keinen Block.
		int[] clear = new int[] {0x00102030, 0x00FFFFFF, 0x00000000, 0x00ABCDEF};
		assertTrue(CapeFilter.blockUniform(clear, 2, 2, 2));
		clear[3] = 0x01ABCDEF;
		assertFalse(CapeFilter.blockUniform(clear, 2, 2, 2));
	}

	@Test
	void onlyRealHdCapesAreSmooth() {
		int[][] art = {image(1024, 512, (x, y) -> pixelArt(x, y, 16))};
		int[][] hd = {image(1024, 512, CapeFilterTest::photo)};
		assertFalse(CapeFilter.looksSmooth(1024, 512, art), "pixelgenau hochskaliert → scharf");
		assertTrue(CapeFilter.looksSmooth(1024, 512, hd), "Foto → weich");
		assertFalse(CapeFilter.looksSmooth(64, 32, new int[][] {image(64, 32, CapeFilterTest::photo)}), "Vanilla-Größe bleibt scharf");
		// Feinere HD-Pixel-Art (Raster 128/256 statt 64) bleibt scharf – so sahen die Studio-Umhänge früher aus.
		assertFalse(CapeFilter.looksSmooth(512, 256, new int[][] {image(512, 256, (x, y) -> pixelArt(x, y, 4))}), "4×4-Blöcke auf 512");
		assertFalse(CapeFilter.looksSmooth(1024, 512, new int[][] {image(1024, 512, (x, y) -> pixelArt(x, y, 2))}), "2×2-Blöcke auf 1024");
		// Palette-Pixel-Art ohne exakte Blöcke (TRS-Studio-Umhänge, ~40 Farben) bleibt scharf.
		assertFalse(CapeFilter.looksSmooth(512, 256, new int[][] {image(512, 256, (x, y) -> 0xFF000000 | (((x * 7 + y * 13) % 40) * 6) << 8)}), "40 Farben");
		// Ein Bild von vielen glatt genügt.
		assertTrue(CapeFilter.looksSmooth(1024, 512, new int[][] {art[0], hd[0]}));
		// Fürs Budget so verkleinert, dass die Blöcke nicht mehr aufgehen (96 = 64 · 1,5): schon gemittelt → weich.
		assertTrue(CapeFilter.looksSmooth(96, 48, new int[][] {new int[96 * 48]}));
	}

	@Test
	void decodingDecidesOnceWithAllPixels() throws IOException {
		byte[] art = CapeHdTest.rgbaPng(1024, 512 * 2, false, (x, y) -> pixelArt(x, y % 512, 16));
		byte[] hd = CapeHdTest.rgbaPng(1024, 512 * 2, false, (x, y) -> photo(x, y % 512));
		assertFalse(CapeFrames.decode(art, 2, 1, CapeFrames.MAX_FRAMES).smooth);
		assertFalse(CapeFrames.decode(art, 2, 2, CapeFrames.MAX_FRAMES).smooth, "halbiert bleiben es 8×8-Blöcke");
		CapeFrames frames = CapeFrames.decode(hd, 2, 1, CapeFrames.MAX_FRAMES);
		assertTrue(frames.smooth);
		// Weich: Mipmaps zählen mit (≈ ein Drittel mehr).
		long base = 1024L * 512 * 4 * 2;
		assertEquals(base + 2 * CapeFilter.mipBytes(1024, 512), frames.bytes());
		assertTrue(frames.bytes() < base * 4 / 3 + 1);
	}

	@Test
	void mipmapsStopAtVanillaSizeAndKeepColorsAtHoles() {
		int[] tex = image(1024, 512, (x, y) -> (x + y) % 2 == 0 ? 0xFFFF0000 : 0x00000000);
		int[][] chain = CapeFilter.mipChain(tex, 1024, 512);
		assertEquals(5, chain.length, "1024, 512, 256, 128, 64");
		assertEquals(64 * 32, chain[4].length);
		// Rot neben Löchern bleibt rot (mit Deckkraft gewichtet), nur halb durchsichtig – nicht dunkel.
		for (int[] level : new int[][] {chain[1], chain[4]}) {
			int c = level[0];
			assertEquals(0xFF, (c >> 16) & 0xFF);
			assertEquals(0, (c >> 8) & 0xFF);
			assertEquals(128, c >>> 24, 1);
		}
		assertEquals(1, CapeFilter.mipChain(new int[96 * 48], 96, 48).length, "96 → 48 wäre unter 64");
		assertEquals(0, CapeFilter.mipBytes(64, 32));
	}

	/** Textur-Attrappe, die sich merkt, ob weich hochgeladen wurde. */
	static final class SmoothBackend implements CapeTextures.Backend<String> {
		final List<Boolean> smooth = new ArrayList<>();

		@Override
		public String upload(String name, int width, int height, int[] argb) {
			throw new AssertionError("muss die Variante mit smooth nehmen");
		}

		@Override
		public String upload(String name, int width, int height, int[] argb, boolean smooth) {
			this.smooth.add(smooth);
			return name;
		}

		@Override
		public void release(String texture) {
		}
	}

	@Test
	void texturesTellTheBackendWhetherToFilterSmoothly() throws IOException {
		CapeInfo photoCape = CapeHdTest.cape("foto", 16, 1);
		CapeInfo artCape = CapeHdTest.cape("pixel", 16, 1);
		CapeFrames hd = CapeFrames.decode(CapeHdTest.rgbaPng(1024, 512, false, CapeFilterTest::photo), photoCape, 1);
		CapeFrames art = CapeFrames.decode(CapeHdTest.rgbaPng(1024, 512, false, (x, y) -> pixelArt(x, y, 16)), artCape, 1);
		List<Consumer<CapeFrames>> pending = new ArrayList<>();
		SmoothBackend backend = new SmoothBackend();
		CapeTextures<String> tex = new CapeTextures<>(backend, (cape, k, done, failed) -> pending.add(done), Long.MAX_VALUE);
		tex.texture(photoCape, 0);
		tex.texture(artCape, 0);
		pending.get(0).accept(hd);
		pending.get(1).accept(art);
		assertNotNull(tex.texture(photoCape, 1));
		tex.cleanup(5);
		assertNotNull(tex.texture(artCape, 10));
		assertEquals(2, backend.smooth.size());
		assertTrue(backend.smooth.get(0), "Foto weich");
		assertFalse(backend.smooth.get(1), "Pixel-Art scharf");
	}
}
