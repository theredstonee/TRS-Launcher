package dev.theredstonee.trsclient.core.cape;

import dev.theredstonee.trsclient.core.online.CapeInfo;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.OnlineConfig;
import dev.theredstonee.trsclient.core.online.TrsApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Umhang-Texturen: PNG-Dekoder, Zerlegen in Bilder, Platten-Cache mit ETag, Hochladen im Spiel. */
class CapeTest {
	static final OnlineConfig CONFIG = new OnlineConfig(true, "https://trs-launcher.theredstonee.de", "https://sessionserver.mojang.com");

	@TempDir
	Path dir;

	/** Umhang mit {@code frames} Bildern; Bild f ist in Farbe f kodiert, Pixel (x, y) variiert zusätzlich. */
	static BufferedImage strip(int scale, int frames, int type) {
		int w = 64 * scale;
		int h = 32 * scale;
		BufferedImage img = new BufferedImage(w, h * frames, type);
		for (int f = 0; f < frames; f++) {
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int alpha = (x + y) % 5 == 0 ? 0x80 : 0xFF;
					if (type != BufferedImage.TYPE_INT_ARGB) alpha = 0xFF;
					img.setRGB(x, f * h + y, (alpha << 24) | ((f * 30) << 16) | ((x * 3) & 0xFF) << 8 | (y * 5) & 0xFF);
				}
			}
		}
		return img;
	}

	static byte[] png(BufferedImage img) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	@Test
	void decoderMatchesImageIoForRgbaAndRgb() throws IOException {
		for (int type : new int[]{BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB}) {
			BufferedImage img = strip(2, 2, type);
			PngDecoder.Image dec = PngDecoder.decode(png(img));
			assertEquals(img.getWidth(), dec.width);
			assertEquals(img.getHeight(), dec.height);
			int[] expected = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
			assertArrayEquals(expected, dec.argb, "Farbtyp " + type);
		}
	}

	@Test
	void decoderHandlesPalettesAndRejectsGarbage() throws IOException {
		BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_BYTE_INDEXED);
		img.setRGB(3, 4, 0xFFFF0000);
		PngDecoder.Image dec = PngDecoder.decode(png(img));
		assertEquals(img.getRGB(3, 4), dec.argb[4 * 64 + 3]);
		assertThrows(IOException.class, () -> PngDecoder.decode(new byte[40]));
		assertThrows(IOException.class, () -> PngDecoder.decode(null));
	}

	@Test
	void framesAreSplitVertically() throws IOException {
		CapeInfo team = CapeInfo.of("team", "https://trs-launcher.theredstonee.de/v1/capes/team.png?v=1", 2, 8, 150, CONFIG);
		CapeFrames frames = CapeFrames.split(PngDecoder.decode(png(strip(2, 8, BufferedImage.TYPE_INT_ARGB))), team);
		assertEquals(8, frames.count());
		assertEquals(128, frames.width);
		assertEquals(64, frames.height);
		assertEquals(5 * 30, (frames.frames[5][0] >> 16) & 0xFF, "Bild 5 hat die Farbe von Bild 5");
		assertThrows(IOException.class, () -> CapeFrames.split(new PngDecoder.Image(100, 50, new int[5000]), team),
				"Breite kein Vielfaches von 64");
		assertThrows(IOException.class, () -> CapeFrames.split(new PngDecoder.Image(64, 40, new int[64 * 40]), team),
				"Höhe passt nicht zu 2:1-Bildern");
	}

	@Test
	void hdCapesUpToScale8AreAccepted() throws IOException {
		CapeInfo nether = CapeInfo.of("nether", "https://trs-launcher.theredstonee.de/v1/capes/nether.png?v=1", 8, 4, 150, CONFIG);
		assertNotNull(nether);
		CapeFrames frames = CapeFrames.split(PngDecoder.decode(png(strip(8, 4, BufferedImage.TYPE_INT_ARGB))), nether);
		assertEquals(512, frames.width);
		assertEquals(4, frames.count());
		assertNull(CapeInfo.of("big", "https://trs-launcher.theredstonee.de/v1/capes/big.png?v=1", 9, 1, null, CONFIG));
		assertThrows(IOException.class, () -> CapeFrames.split(new PngDecoder.Image(576, 288, new int[576 * 288]), nether));
	}

	@Test
	void diskCacheUsesEtagAndSkipsTheNetworkForTheSameUrl() throws Exception {
		byte[] body = png(strip(1, 1, BufferedImage.TYPE_INT_ARGB));
		List<Http.Request> seen = new ArrayList<>();
		final int[] status = {200};
		Http http = request -> {
			seen.add(request);
			Map<String, String> h = new HashMap<>();
			h.put("etag", "\"abc\"");
			return new Http.Response(status[0], h, status[0] == 200 ? body : new byte[0]);
		};
		TrsApi api = new TrsApi(http, CONFIG);
		CapeDiskCache cache = new CapeDiskCache(dir.resolve("capes"));
		CapeInfo v1 = CapeInfo.of("red", "https://trs-launcher.theredstonee.de/v1/capes/red.png?v=1", 1, 1, null, CONFIG);
		assertArrayEquals(body, cache.load(v1, api, null));
		assertEquals(1, seen.size());
		assertNull(seen.get(0).headers.get("If-None-Match"));
		assertArrayEquals(body, cache.load(v1, api, null));
		assertEquals(1, seen.size(), "gleiche URL → Platte, kein Netz");
		CapeInfo v2 = CapeInfo.of("red", "https://trs-launcher.theredstonee.de/v1/capes/red.png?v=2", 1, 1, null, CONFIG);
		status[0] = 304;
		assertArrayEquals(body, cache.load(v2, api, null));
		assertEquals(2, seen.size());
		assertEquals("\"abc\"", seen.get(1).headers.get("If-None-Match"));
	}

	/** Textur-Attrappe: zählt Hochladen/Freigeben. */
	static final class Backend implements CapeTextures.Backend<String> {
		final List<String> uploaded = new ArrayList<>();
		final List<String> released = new ArrayList<>();

		@Override
		public String upload(String name, int width, int height, int[] argb) {
			uploaded.add(name);
			return name;
		}

		@Override
		public void release(String texture) {
			released.add(texture);
		}
	}

	@Test
	void texturesUploadInSmallStepsAndFollowTheClock() throws IOException {
		CapeInfo team = CapeInfo.of("team", "https://trs-launcher.theredstonee.de/v1/capes/team.png?v=1", 1, 12, 100, CONFIG);
		CapeFrames frames = CapeFrames.split(PngDecoder.decode(png(strip(1, 12, BufferedImage.TYPE_INT_ARGB))), team);
		List<Consumer<CapeFrames>> pending = new ArrayList<>();
		Backend backend = new Backend();
		CapeTextures<String> tex = new CapeTextures<>(backend, (cape, done, failed) -> pending.add(done));
		assertNull(tex.texture(team, 0), "noch nicht geladen");
		assertNull(tex.texture(team, 10));
		assertEquals(1, pending.size(), "nur eine Ladeanfrage");
		pending.get(0).accept(frames);
		assertNull(tex.texture(team, 20), "erst 8 von 12 Bildern hochgeladen");
		assertEquals(CapeTextures.UPLOADS_PER_CALL, backend.uploaded.size());
		String f0 = tex.texture(team, 30);
		assertNotNull(f0);
		assertEquals(12, backend.uploaded.size());
		assertEquals(backend.uploaded.get(5), tex.texture(team, 530), "Bild = floor(t / 100) % 12");
		assertSame(tex.texture(team, 1200), tex.texture(team, 0));
		tex.cleanup(1200 + CapeTextures.IDLE_MS + 1);
		assertEquals(12, backend.released.size(), "unbenutzt → freigegeben");
	}

	@Test
	void failedLoadsAreRetriedLater() {
		CapeInfo red = CapeInfo.of("red", "https://trs-launcher.theredstonee.de/v1/capes/red.png?v=1", 1, 1, null, CONFIG);
		List<Runnable> fails = new ArrayList<>();
		CapeTextures<String> tex = new CapeTextures<>(new Backend(), (cape, done, failed) -> fails.add(failed));
		assertNull(tex.texture(red, 0));
		fails.get(0).run();
		assertNull(tex.texture(red, 10));
		assertEquals(1, fails.size(), "nicht sofort erneut");
		assertTrue(tex.size() > 0);
	}
}
