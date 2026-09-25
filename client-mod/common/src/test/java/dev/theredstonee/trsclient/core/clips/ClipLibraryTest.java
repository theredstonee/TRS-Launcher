package dev.theredstonee.trsclient.core.clips;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Clips &amp; Bilder: Ordner finden, Dateien lesen, MP4-Dauer, Löschen nur bekannter Dateien, Verkleinern. */
class ClipLibraryTest {
	private static final Executor NOW = Runnable::run;

	static byte[] mp4(int timescale, long duration, boolean version1, boolean moovFirst) {
		ByteArrayOutputStream moov = new ByteArrayOutputStream();
		byte[] mvhd = new byte[version1 ? 112 : 100];
		mvhd[0] = (byte) (version1 ? 1 : 0);
		int ts = version1 ? 20 : 12;
		int du = version1 ? 24 : 16;
		for (int i = 0; i < 4; i++) mvhd[ts + i] = (byte) (timescale >>> (24 - 8 * i));
		int len = version1 ? 8 : 4;
		for (int i = 0; i < len; i++) mvhd[du + i] = (byte) (duration >>> (8 * (len - 1 - i)));
		box(moov, "mvhd", mvhd);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		box(out, "ftyp", new byte[8]);
		if (moovFirst) box(out, "moov", moov.toByteArray());
		box(out, "mdat", new byte[1000]);
		if (!moovFirst) box(out, "moov", moov.toByteArray());
		return out.toByteArray();
	}

	static void box(ByteArrayOutputStream out, String type, byte[] body) {
		int size = body.length + 8;
		out.write(size >>> 24);
		out.write(size >>> 16);
		out.write(size >>> 8);
		out.write(size);
		out.write(type.getBytes(StandardCharsets.US_ASCII), 0, 4);
		out.write(body, 0, body.length);
	}

	@Test
	void readsMp4Duration(@TempDir Path dir) throws Exception {
		Path a = dir.resolve("a.mp4");
		Files.write(a, mp4(1000, 15_000, false, true));
		assertEquals(15_000, ClipLibrary.mp4DurationMs(a));
		Path b = dir.resolve("b.mp4");
		Files.write(b, mp4(90_000, 90_000L * 62, true, false));
		assertEquals(62_000, ClipLibrary.mp4DurationMs(b));
		Path c = dir.resolve("c.mp4");
		Files.write(c, "kein mp4".getBytes(StandardCharsets.UTF_8));
		assertEquals(-1, ClipLibrary.mp4DurationMs(c));
	}

	@Test
	void findsTheClipFolder(@TempDir Path dir) throws Exception {
		Path game = dir.resolve("instances").resolve("survival").resolve("minecraft");
		Path config = game.resolve("config");
		assertEquals(dir.resolve("clips").resolve("survival").toAbsolutePath().normalize(), ClipConfig.clipsDir(config, game),
				"ohne Angabe: Standardordner des Launchers");
		Files.createDirectories(config.resolve("trsclient"));
		Path custom = dir.resolve("Meine Clips").resolve("survival").toAbsolutePath();
		String json = "{\"version\":2,\"enabled\":true,\"port\":1234,\"clipsDir\":\"" + custom.toString().replace("\\", "\\\\") + "\"}";
		Files.write(config.resolve("trsclient").resolve("clips.json"), json.getBytes(StandardCharsets.UTF_8));
		assertEquals(custom.normalize(), ClipConfig.clipsDir(config, game), "Angabe des Launchers gewinnt");
		assertNull(ClipConfig.safeDir("relativ/pfad"));
		assertNull(ClipConfig.safeDir("C:\\a" + (char) 0 + "b"));
		assertNull(ClipConfig.clipsDir(null, dir.resolve("irgendwo")), "fremder Spielordner: unbekannt");
	}

	@Test
	void listsClipsAndScreenshotsNewestFirst(@TempDir Path dir) throws Exception {
		Path game = dir.resolve("instances").resolve("x").resolve("minecraft");
		Path shots = game.resolve("screenshots");
		Path clips = dir.resolve("clips").resolve("x");
		Files.createDirectories(shots);
		Files.createDirectories(clips);
		Path old = shots.resolve("2026-01-01_10.00.00.png");
		Files.write(old, new byte[]{1});
		Files.setLastModifiedTime(old, FileTime.fromMillis(1_000_000L));
		Path clip = clips.resolve("Welt 2026-09-25.mp4");
		Files.write(clip, mp4(1000, 5_000, false, true));
		Files.setLastModifiedTime(clip, FileTime.fromMillis(2_000_000L));
		Files.write(shots.resolve("notiz.txt"), new byte[]{1});
		Files.write(clips.resolve(".versteckt.mp4"), new byte[]{1});

		ClipLibrary lib = new ClipLibrary(game.resolve("config"), game, NOW);
		lib.refresh();
		ClipLibrary.Listing l = lib.listing();
		assertTrue(l.scanned);
		assertEquals(2, l.entries.size());
		assertEquals(ClipLibrary.Type.CLIP, l.entries.get(0).type, "neueste zuerst");
		assertEquals(5_000, l.entries.get(0).durationMs);
		assertEquals(1, l.count(ClipLibrary.Type.SCREENSHOT));

		AtomicReference<Boolean> result = new AtomicReference<>();
		ClipLibrary.Entry foreign = new ClipLibrary.Entry(dir.resolve("fremd.png"), ClipLibrary.Type.SCREENSHOT, "fremd.png", 1, 1, -1);
		Files.write(dir.resolve("fremd.png"), new byte[]{1});
		lib.delete(foreign, result::set);
		assertFalse(result.get(), "nur angezeigte Dateien");
		assertTrue(Files.exists(dir.resolve("fremd.png")));

		lib.delete(l.entries.get(1), result::set);
		assertTrue(result.get());
		assertFalse(Files.exists(old));
		assertEquals(1, lib.listing().entries.size(), "danach neu gelesen");
	}

	@Test
	void downscalesToOpaqueThumbnails() {
		int[] src = new int[4 * 2];
		for (int i = 0; i < src.length; i++) src[i] = (i % 2 == 0) ? 0x00FF0000 : 0x000000FF;
		int[] out = Thumbnails.downscale(src, 4, 2, 2, 1);
		assertEquals(2, out.length);
		assertEquals(0xFF7F007F, out[0], "Mittelwert, deckend");
		int[] same = Thumbnails.downscale(new int[]{0x12345678}, 1, 1, 1, 1);
		assertArrayEquals(new int[]{0xFF345678}, same);
	}
}
