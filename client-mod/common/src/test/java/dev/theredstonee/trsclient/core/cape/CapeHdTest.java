package dev.theredstonee.trsclient.core.cape;

import dev.theredstonee.trsclient.core.online.CapeInfo;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HD-Umhänge (Faktor 16, 32 Bilder): Dekoder-Grenzen, Zerlegen direkt beim Dekodieren, Verkleinern, Textur-Budget. */
class CapeHdTest {
	static final String URL = "https://trs-launcher.theredstonee.de/v1/capes/";

	static CapeInfo cape(String id, int scale, int frames) {
		return CapeInfo.of(id, URL + id + ".png?v=1", scale, frames, frames > 1 ? 100 : null, CapeTest.CONFIG);
	}

	// --- PNG-Bau (RGBA 8 Bit, gestreamt – auch 1024×16384 ohne großen Puffer) ---

	static byte[] rgbaPng(int w, int h, boolean interlaced, IntBinaryOperator f) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
		byte[] ihdr = new byte[13];
		putInt(ihdr, 0, w);
		putInt(ihdr, 4, h);
		ihdr[8] = 8;
		ihdr[9] = 6;
		ihdr[12] = (byte) (interlaced ? 1 : 0);
		chunk(out, "IHDR", ihdr);
		ByteArrayOutputStream z = new ByteArrayOutputStream();
		Deflater deflater = new Deflater(Deflater.BEST_SPEED);
		try (DeflaterOutputStream dos = new DeflaterOutputStream(z, deflater, 1 << 16)) {
			int[][] passes = interlaced
					? new int[][] {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}}
					: new int[][] {{0, 0, 1, 1}};
			byte[] row = new byte[1 + w * 4];
			for (int[] p : passes) {
				if (p[0] >= w) continue;
				for (int y = p[1]; y < h; y += p[3]) {
					int n = 1;
					for (int x = p[0]; x < w; x += p[2]) {
						int c = f.applyAsInt(x, y);
						row[n++] = (byte) (c >> 16);
						row[n++] = (byte) (c >> 8);
						row[n++] = (byte) c;
						row[n++] = (byte) (c >>> 24);
					}
					dos.write(row, 0, n);
				}
			}
		} finally {
			deflater.end();
		}
		chunk(out, "IDAT", z.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
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

	/** Bild f (Zeilen f·512 …) in Farbe f, mit Muster und halbdurchsichtigen Pixeln. */
	static final IntBinaryOperator HD = (x, y) -> {
		int f = y / 512;
		int alpha = (x + y) % 7 == 0 ? 0x40 : 0xFF;
		return (alpha << 24) | ((f * 7) << 16) | ((x * 3) & 0xFF) << 8 | (y * 5) & 0xFF;
	};

	// --- Dekoder-Grenzen ---

	@Test
	void decoderAcceptsTheFullHdStripAndRejectsMore() throws IOException {
		assertEquals(1024L * 512 * 32, PngDecoder.MAX_PIXELS);
		byte[] full = rgbaPng(1024, 512 * 32, false, HD);
		// Volle 32 Bilder, stark verkleinert (spart Speicher im Test) – alle Bilder kommen an, in ihrer Farbe.
		CapeFrames small = CapeFrames.decode(full, cape("hd", 16, 32), 16);
		assertEquals(32, small.count());
		assertEquals(64, small.width);
		assertEquals(32, small.height);
		assertEquals(16, small.downscale);
		for (int f = 0; f < 32; f++) assertEquals(f * 7, (small.frames[f][0] >> 16) & 0xFF, "Bild " + f);
		// Nur das erste Bild in voller Größe (Vorschau): der Rest wird gar nicht entpackt.
		CapeFrames first = CapeFrames.decode(full, 0, 1, 1);
		assertEquals(1, first.count());
		assertEquals(1024, first.width);
		assertEquals(512, first.height);
		assertEquals(HD.applyAsInt(1000, 500), first.frames[0][500 * 1024 + 1000]);

		// Eine Zeile mehr: schon am Kopf abgelehnt (ohne Bilddaten zu lesen).
		byte[] tooBig = headerOnly(1024, 512 * 32 + 1);
		assertThrows(IOException.class, () -> PngDecoder.decode(tooBig));
		// 33 Bilder zu 1024×512 liegen über der Pixel-Grenze; breiter als 1024 ist kein Umhang.
		assertThrows(IOException.class, () -> CapeFrames.decode(headerOnly(1024, 512 * 33), 33, 16, 64));
		assertThrows(IOException.class, () -> CapeFrames.decode(rgbaPng(1088, 544, false, (x, y) -> 0), 1, 1, 64));
		// Die Grenze selbst ist erlaubt: der Kopf geht durch, erst die fehlenden Bilddaten scheitern.
		IOException e = assertThrows(IOException.class, () -> CapeFrames.decode(headerOnly(1024, 512 * 32), 32, 16, 64));
		assertTrue(e.getMessage().contains("unvollständig"), e.getMessage());
	}

	/** Nur Kopf (IHDR, RGBA 8 Bit) und IEND – zum Prüfen der Größen-Grenzen ohne Bilddaten. */
	static byte[] headerOnly(int w, int h) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
		byte[] ihdr = new byte[13];
		putInt(ihdr, 0, w);
		putInt(ihdr, 4, h);
		ihdr[8] = 8;
		ihdr[9] = 6;
		chunk(out, "IHDR", ihdr);
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	@Test
	void decoderRejectsTruncatedData() throws IOException {
		byte[] ok = rgbaPng(64, 32, false, (x, y) -> 0xFF00FF00);
		assertNotNull(PngDecoder.decode(ok));
		// IDAT gekürzt (CRC egal, der Dekoder prüft sie nicht): Daten reichen nicht.
		byte[] cut = rgbaPng(64, 64, false, (x, y) -> x * 31 + y * 17);
		int idat = indexOf(cut, "IDAT");
		int len = ((cut[idat - 4] & 0xFF) << 24) | ((cut[idat - 3] & 0xFF) << 16) | ((cut[idat - 2] & 0xFF) << 8) | (cut[idat - 1] & 0xFF);
		putInt(cut, idat - 4, len / 2);
		byte[] broken = new byte[idat + 4 + len / 2 + 4];
		System.arraycopy(cut, 0, broken, 0, broken.length);
		assertThrows(IOException.class, () -> PngDecoder.decode(broken));
	}

	private static int indexOf(byte[] b, String s) {
		byte[] t = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		outer:
		for (int i = 0; i + t.length <= b.length; i++) {
			for (int j = 0; j < t.length; j++) if (b[i + j] != t[j]) continue outer;
			return i;
		}
		return -1;
	}

	// --- Zerlegen und Verkleinern ---

	@Test
	void scale16FramesMatchImageIoAndTheOldSplit() throws IOException {
		BufferedImage img = CapeTest.strip(16, 2, BufferedImage.TYPE_INT_ARGB);
		byte[] png = CapeTest.png(img);
		CapeInfo hd = cape("hd", 16, 2);
		CapeFrames frames = CapeFrames.decode(png, hd, 1);
		assertEquals(2, frames.count());
		assertEquals(1024, frames.width);
		assertEquals(512, frames.height);
		assertEquals(1, frames.downscale);
		CapeFrames old = CapeFrames.split(PngDecoder.decode(png), hd);
		for (int f = 0; f < 2; f++) {
			assertArrayEquals(old.frames[f], frames.frames[f], "Bild " + f);
			int[] expected = img.getRGB(0, f * 512, 1024, 512, null, 0, 1024);
			assertArrayEquals(expected, frames.frames[f], "Bild " + f + " wie ImageIO");
		}
		// Weich gefilterte (echte HD-)Umhänge belegen zusätzlich ihre Mipmaps.
		long mips = frames.smooth ? CapeFilter.mipBytes(1024, 512) : 0;
		assertEquals((1024L * 512 * 4 + mips) * 2, frames.bytes());
	}

	@Test
	void downscaleWhileDecodingEqualsTheBoxFilter() throws IOException {
		byte[] png = rgbaPng(256, 128 * 3, false, HD);
		CapeFrames full = CapeFrames.decode(png, 3, 1, 64);
		CapeFrames half = CapeFrames.decode(png, 3, 2, 64);
		assertEquals(128, half.width);
		assertEquals(64, half.height);
		assertEquals(2, half.downscale);
		for (int f = 0; f < 3; f++) {
			assertArrayEquals(CapeFrames.downscale(full.frames[f], 256, 128, 2), half.frames[f], "Bild " + f);
		}
		// Nie kleiner als 64×32: Faktor 4 wünscht 16×, mehr als ×4 geht nicht.
		CapeFrames min = CapeFrames.decode(png, 3, 16, 64);
		assertEquals(64, min.width);
		assertEquals(32, min.height);
		assertEquals(4, min.downscale);
		assertEquals(1, CapeFrames.clampDownscale(64, 8));
		assertEquals(8, CapeFrames.clampDownscale(1024, 8));
		assertEquals(16, CapeFrames.clampDownscale(1024, 64));
	}

	@Test
	void interlacedStripsGiveTheSameFrames() throws IOException {
		IntBinaryOperator f = (x, y) -> ((x * y) % 3 == 0 ? 0x80 : 0xFF) << 24 | (y / 64) * 40 << 16 | x << 8 | y % 64;
		byte[] plain = rgbaPng(128, 64 * 3, false, f);
		byte[] adam7 = rgbaPng(128, 64 * 3, true, f);
		for (int k : new int[] {1, 2}) {
			CapeFrames a = CapeFrames.decode(plain, 3, k, 64);
			CapeFrames b = CapeFrames.decode(adam7, 3, k, 64);
			assertEquals(a.width, b.width);
			for (int i = 0; i < 3; i++) assertArrayEquals(a.frames[i], b.frames[i], "Bild " + i + " ×" + k);
		}
	}

	@Test
	void boxFilterWeighsByAlpha() {
		// Ein deckend rotes Pixel und drei durchsichtige: rot bleibt rot (nicht abgedunkelt), Deckkraft ein Viertel.
		int[] px = {0xFFFF0000, 0x00000000, 0x00000000, 0x00000000};
		assertEquals(0x40FF0000, CapeFrames.downscale(px, 2, 2, 2)[0]);
		int[] grey = {0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000};
		assertEquals(0xFF808080, CapeFrames.downscale(grey, 2, 2, 2)[0]);
		assertEquals(0, CapeFrames.downscale(new int[4], 2, 2, 2)[0]);
	}

	@Test
	void byteEstimatesFollowTheApiAndThePixelLimit() {
		assertEquals(64L * 1024 * 1024, CapeFrames.bytes(16, 32, 1), "32 × 1024×512 × 4 B = 64 MB");
		assertEquals(16L * 1024 * 1024, CapeFrames.bytes(16, 32, 2));
		assertEquals(64L * 1024 * 1024, CapeFrames.bytes(16, 64, 1), "mehr als die Pixel-Grenze kann nicht ankommen");
		assertEquals(64L * 32 * 4, CapeFrames.bytes(1, 1, 4), "64×32 wird nicht weiter verkleinert");
	}

	// --- Textur-Budget ---

	/** Lader-Attrappe: liefert sofort leere Bilder der gewünschten Größe und merkt sich die Verkleinerung. */
	static final class FakeLoader implements CapeTextures.Loader {
		final List<String> requests = new ArrayList<>();

		@Override
		public void load(CapeInfo cape, int downscale, java.util.function.Consumer<CapeFrames> done, Runnable failed) {
			requests.add(cape.id + "@" + downscale);
			int k = CapeFrames.clampDownscale(64 * cape.scale, downscale);
			done.accept(new CapeFrames(64 * cape.scale / k, 32 * cape.scale / k, new int[cape.frames][1], k));
		}
	}

	/** Spielt Ticks (50 ms) von {@code from} bis {@code to}; in jedem Tick werden die Umhänge in {@code seen} gezeichnet. */
	static long play(CapeTextures<String> tex, long from, long to, CapeInfo own, CapeInfo... seen) {
		long t = from;
		for (; t <= to; t += 50) {
			tex.cleanup(t);
			if (own != null) tex.texture(own, t, true);
			for (CapeInfo c : seen) tex.texture(c, t);
		}
		return t;
	}

	@Test
	void budgetDownscalesNewCapesAndKeepsTheOwnCapeFull() {
		CapeTest.Backend backend = new CapeTest.Backend();
		FakeLoader loader = new FakeLoader();
		CapeTextures<String> tex = new CapeTextures<>(backend, loader, 100L * 1024 * 1024);
		CapeInfo a = cape("a", 16, 32);
		CapeInfo b = cape("b", 16, 32);
		CapeInfo mine = cape("mine", 16, 32);
		long t = play(tex, 0, 3000, null, a);
		assertEquals(1, tex.downscale(a), "erster Umhang passt voll (64 MB)");
		assertEquals(32, backend.uploaded.size(), "HD-Bilder: eines je Tick");
		t = play(tex, t, t + 3000, null, a, b);
		assertEquals(2, tex.downscale(b), "zweiter passt nur halbiert (16 MB von 36 MB frei)");
		assertTrue(tex.usedBytes() <= 100L * 1024 * 1024);
		play(tex, t, t + 3000, mine, a, b);
		assertEquals(1, tex.downscale(mine), "eigener Umhang immer voll, auch über dem Budget");
		assertEquals(java.util.Arrays.asList("a@1", "b@2", "mine@1"), loader.requests);
	}

	@Test
	void unseenCapesAreReleasedFirstAndDownscaledOnesUpgradeLater() {
		CapeTest.Backend backend = new CapeTest.Backend();
		FakeLoader loader = new FakeLoader();
		CapeTextures<String> tex = new CapeTextures<>(backend, loader, 100L * 1024 * 1024);
		CapeInfo a = cape("a", 16, 32);
		CapeInfo b = cape("b", 16, 32);
		CapeInfo c = cape("c", 16, 32);
		long t = play(tex, 0, 3000, null, a);
		t = play(tex, t, t + 3000, null, a, b);
		assertEquals(2, tex.downscale(b));
		// a ist nicht mehr zu sehen (länger als SEEN_MS): c braucht Platz → a wird freigegeben, c kommt voll.
		t = play(tex, t, t + CapeTextures.SEEN_MS + 3000, null, b);
		int releasedBefore = backend.released.size();
		t = play(tex, t, t + 3000, null, b, c);
		assertEquals(1, tex.downscale(c), "Platz durch den ungesehenen Umhang");
		assertEquals(0, tex.downscale(a), "a freigegeben");
		assertTrue(backend.released.size() >= releasedBefore + 32);
		// b ist weiter halbiert; erst wenn c verschwindet (Leerlauf), passt b voll → wird nachgeladen.
		assertEquals(2, tex.downscale(b));
		play(tex, t, t + CapeTextures.IDLE_MS + 3 * CapeTextures.UPGRADE_CHECK_MS, null, b);
		assertEquals(1, tex.downscale(b), "nachgeladen in voller Qualität");
		assertTrue(loader.requests.contains("b@1"));
		assertFalse(loader.requests.contains("a@2"));
	}

	@Test
	void unlimitedBudgetNeverDownscales() {
		FakeLoader loader = new FakeLoader();
		CapeTextures<String> tex = new CapeTextures<>(new CapeTest.Backend(), loader, Long.MAX_VALUE);
		CapeInfo[] many = new CapeInfo[8];
		for (int i = 0; i < many.length; i++) many[i] = cape("c" + i, 16, 32);
		play(tex, 0, 20_000, null, many);
		for (CapeInfo c : many) assertEquals(1, tex.downscale(c));
		assertNull(tex.texture(null, 0));
	}
}
