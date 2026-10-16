package dev.theredstonee.trsclient.core.creator;

import dev.theredstonee.trsclient.core.cape.CapeTextures;
import dev.theredstonee.trsclient.core.online.FanInfo;
import dev.theredstonee.trsclient.core.online.OnlineConfig;
import dev.theredstonee.trsclient.core.online.SymbolInfo;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fan-Symbole: Streifen zerlegen, Meilenstein-Rahmen, Platten-Cache, Texturen. */
class FanSymbolsTest {
	static final OnlineConfig CONFIG = new OnlineConfig(true, "https://trs-launcher.theredstonee.de", "https://sessionserver.mojang.com");
	static final String CREATOR = "0123456789abcdef0123456789abcdef";

	@TempDir
	Path dir;

	/** PNG-Streifen: {@code n} Bilder {@code s×s}, Bild f in Farbe (f+1)·40 (rot), Ecke oben links weiß. */
	static byte[] strip(int s, int n) throws IOException {
		BufferedImage img = new BufferedImage(s, s * n, BufferedImage.TYPE_INT_ARGB);
		for (int f = 0; f < n; f++) {
			for (int y = 0; y < s; y++) {
				for (int x = 0; x < s; x++) img.setRGB(x, f * s + y, 0xFF000000 | (((f + 1) * 40) << 16));
			}
			img.setRGB(0, f * s, 0xFFFFFFFF);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	static SymbolInfo info(int size, int frames, String sha) {
		return SymbolInfo.of("/v1/creator-symbols/" + sha + ".png", size, frames, 100, sha, CONFIG);
	}

	@Test
	void decodesSquareStrips() throws IOException {
		SymbolFrames f = SymbolFrames.decode(strip(16, 4), null);
		assertEquals(16, f.size);
		assertEquals(4, f.count());
		assertEquals(0xFF280000, f.frames[0][1]);
		assertEquals(0xFFA00000, f.frames[3][1]);
		assertEquals(0xFFFFFFFF, f.frames[2][0]);
		// Die API verspricht weniger Bilder → nie mehr als versprochen.
		assertEquals(2, SymbolFrames.decode(strip(16, 4), info(16, 2, sha('a'))).count());
		assertEquals(1, SymbolFrames.decode(strip(64, 1), null).count());
		assertThrows(IOException.class, () -> SymbolFrames.decode(strip(8, 1), null), "zu klein");
		assertThrows(IOException.class, () -> SymbolFrames.decode(strip(16, 9), null), "zu viele Bilder");
		assertThrows(IOException.class, () -> SymbolFrames.decode(new byte[]{1, 2, 3}, null));
	}

	@Test
	void milestoneFrameIsDrawnAround() {
		int[] px = new int[16 * 16];
		java.util.Arrays.fill(px, 0xFF112233);
		assertSame(px, SymbolArt.framed(px, 16, null), "ohne Meilenstein unverändert");
		int[] out = SymbolArt.framed(px, 16, Milestone.GOLD);
		assertEquals(18 * 18, out.length);
		assertEquals(18, SymbolArt.framedSize(16, Milestone.GOLD));
		assertEquals(0xFFFFCF4D, out[0]);
		assertEquals(0xFFFFCF4D, out[18 * 18 - 1]);
		assertEquals(0xFF112233, out[18 + 1], "innen das Symbol");
		assertEquals(4, SymbolArt.border(64));
		int[] big = SymbolArt.framed(new int[64 * 64], 64, Milestone.DIAMOND);
		assertEquals(72 * 72, big.length);
		assertEquals(0xFF5FF2FF, big[72 + 2], "Rahmen 4 px");
		assertTrue(big[0] != 0xFF5FF2FF, "Glanzkante oben links heller");
		assertEquals(Milestone.BRONZE, Milestone.parse("bronze"));
		assertEquals(0xCD7F32, Milestone.BRONZE.color & 0xFFFFFF);
		assertEquals(0xC0C0C0, Milestone.SILVER.color & 0xFFFFFF);
		assertEquals(CreatorTier.TOP, CreatorTier.parse("top"));
		assertEquals(0x9FE0FF, CreatorTier.STARTER.color);
		assertEquals(0xC79BFF, CreatorTier.PARTNER.color);
	}

	static String sha(char c) {
		char[] a = new char[64];
		java.util.Arrays.fill(a, c);
		return new String(a);
	}

	@Test
	void diskCacheKeepsOnlyMatchingFiles() throws IOException {
		byte[] png = strip(16, 1);
		String real = SymbolCache.sha256(png);
		SymbolCache cache = new SymbolCache(dir);
		int[] fetches = {0};
		SymbolCache.Fetch net = url -> {
			fetches[0]++;
			return png;
		};
		assertArrayEquals(png, cache.load(real, "u", net));
		assertArrayEquals(png, cache.load(real, "u", net));
		assertEquals(1, fetches[0], "zweites Mal von der Platte");
		assertTrue(Files.exists(dir.resolve(real + ".png")));
		// Abweichende Prüfsumme: Datei wird benutzt, aber nie gespeichert.
		String other = sha('c');
		assertArrayEquals(png, cache.load(other, "u", net));
		assertTrue(!Files.exists(dir.resolve(other + ".png")));
		assertThrows(IOException.class, () -> cache.load("x", "u", net));
	}

	/** Attrappe: Texturen sind Texte, mitgeschrieben. */
	static final class Backend implements CapeTextures.Backend<String> {
		final List<String> uploaded = new ArrayList<>();
		final List<String> released = new ArrayList<>();
		final List<int[]> sizes = new ArrayList<>();

		@Override
		public String upload(String name, int width, int height, int[] argb) {
			uploaded.add(name);
			sizes.add(new int[]{width, height});
			return name;
		}

		@Override
		public void release(String texture) {
			released.add(texture);
		}
	}

	@Test
	void texturesLoadOnceAnimateAndAreReleased() throws IOException {
		Backend backend = new Backend();
		List<Runnable> pending = new ArrayList<>();
		int[] loads = {0};
		final SymbolFrames decoded = SymbolFrames.decode(strip(16, 4), null);
		FanSymbols.Loader loader = (symbol, done, failed) -> {
			loads[0]++;
			pending.add(() -> done.accept(decoded));
		};
		FanSymbols<String> symbols = new FanSymbols<>(backend, loader);
		FanInfo fan = FanInfo.of(CREATOR, "Pixelmaus", info(16, 4, sha('a')), "silver");
		assertNull(symbols.texture(fan, 0), "noch nicht geladen");
		assertNull(symbols.texture(fan, 10));
		assertEquals(1, loads[0], "nur einmal angefordert");
		pending.remove(0).run();
		TextureRef first = symbols.texture(fan, 0);
		assertNotNull(first);
		assertEquals(4, backend.uploaded.size());
		assertEquals(18, first.width, "16 px + Silber-Rahmen");
		assertEquals(18, backend.sizes.get(0)[1]);
		TextureRef later = symbols.texture(fan, 150);
		assertNotSame(first.id, later.id, "nächstes Bild nach 100 ms");
		assertEquals(first.id, symbols.texture(fan, 400).id, "rundherum");
		assertTrue(symbols.ready(fan));

		// Gleiches Symbol ohne Meilenstein = eigene Texturen.
		FanInfo plain = FanInfo.of(CREATOR, null, info(16, 4, sha('a')), null);
		assertNull(symbols.texture(plain, 0));
		pending.remove(0).run();
		assertEquals(16, symbols.texture(plain, 0).width);

		// Lange nicht benutzt → freigegeben.
		symbols.cleanup(FanSymbols.IDLE_MS + 10_000);
		assertEquals(8, backend.released.size());
		assertEquals(0, symbols.size());
	}

	@Test
	void failedLoadsAreRetriedLater() {
		Backend backend = new Backend();
		List<Runnable> failures = new ArrayList<>();
		int[] loads = {0};
		FanSymbols.Loader loader = (symbol, done, failed) -> {
			loads[0]++;
			failures.add(failed);
		};
		FanSymbols<String> symbols = new FanSymbols<>(backend, loader);
		FanInfo fan = FanInfo.of(CREATOR, null, info(16, 1, sha('d')), null);
		long now = System.currentTimeMillis();
		symbols.texture(fan, now);
		failures.remove(0).run();
		symbols.texture(fan, now + 1000);
		assertEquals(1, loads[0], "nicht sofort erneut");
		symbols.texture(fan, now + FanSymbols.RETRY_MS + 5000);
		assertEquals(2, loads[0]);
	}
}
