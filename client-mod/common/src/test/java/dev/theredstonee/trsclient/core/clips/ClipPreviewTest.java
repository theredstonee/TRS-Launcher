package dev.theredstonee.trsclient.core.clips;

import static org.junit.jupiter.api.Assertions.*;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.link.FakeLauncher;
import dev.theredstonee.trsclient.core.link.TrsLink;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Clip-Vorschau im Spiel: Zeitraffer-Logik, strenge Prüfung von Datei und Aufbau, Anfrage über den TRS-Link (nur
 * Dateinamen, nie Pfade) und „Im Launcher öffnen“ – gegen die Launcher-Attrappe.
 */
class ClipPreviewTest {
	private static final Executor DIRECT = Runnable::run;

	@AfterEach
	void reset() {
		Textures.replaceForTests(null);
		I18n.use("en");
	}

	// --- Zeitraffer ----------------------------------------------------------------------------------

	@Test
	void zeitrafferLaeuftImKreisUndPausiert() {
		PreviewAnimation a = new PreviewAnimation();
		a.reset(4, 1000);
		assertEquals(250, PreviewAnimation.stepMs(1000), "1 Bild je Sekunde Clip → Zeitraffer ×4");
		assertEquals(90, PreviewAnimation.stepMs(100), "nie schneller als 90 ms");
		assertEquals(250, PreviewAnimation.stepMs(10_000), "nie langsamer als 250 ms");
		assertTrue(a.playing());
		a.advance(249);
		assertEquals(0, a.index());
		a.advance(1);
		assertEquals(1, a.index());
		a.advance(500);
		assertEquals(3, a.index());
		assertEquals(3000, a.positionMs());
		assertEquals(1f, a.progress(), 1e-6);
		a.advance(250);
		assertEquals(0, a.index(), "läuft im Kreis");
		a.toggle();
		a.advance(10_000);
		assertEquals(0, a.index(), "pausiert");
		a.seek(0.7f);
		assertEquals(2, a.index());
		a.toggle();
		a.advance(60_000);
		// 60 s Pause (Fenster im Hintergrund) zählt nur als 1 s = 4 Bilder → wieder bei Bild 2.
		assertEquals(2, a.index(), "großer Sprung wird gedeckelt");
		// Ein einzelnes Bild bewegt sich nie.
		a.reset(1, 1000);
		a.advance(5_000);
		assertEquals(0, a.index());
		assertEquals(0f, a.progress());
	}

	// --- Datei + Aufbau ------------------------------------------------------------------------------

	static byte[] png(int w, int h, int argbTopLeftOfFrame1, int frameW) throws IOException {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		img.setRGB(frameW, 0, argbTopLeftOfFrame1 & 0xFFFFFF);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	static TrsLink.PreviewDto meta(String path, int frames, int cols, int rows) {
		TrsLink.PreviewDto m = new TrsLink.PreviewDto();
		m.path = path;
		m.frames = frames;
		m.cols = cols;
		m.rows = rows;
		m.frameWidth = 16;
		m.frameHeight = 9;
		m.intervalMs = 1000;
		m.durationMs = (long) frames * 1000;
		return m;
	}

	@Test
	void rasterWirdStrengGeprueft() throws Exception {
		byte[] ok = png(64, 18, 0xFF0000, 16);
		ClipPreview.Sheet s = ClipPreview.decode(ok, meta(null, 6, 4, 2));
		assertEquals(6, s.frames);
		assertEquals(16, s.u(1));
		assertEquals(0, s.v(1));
		assertEquals(16, s.u(5));
		assertEquals(9, s.v(5));
		assertEquals(0xFFFF0000, s.argb[16], "Bild 1 beginnt bei x = 16");
		// Mehr Bilder als Platz, Raster größer als das PNG, fehlende Angaben, zu große Bilder.
		assertThrows(IOException.class, () -> ClipPreview.decode(ok, meta(null, 9, 4, 2)));
		assertThrows(IOException.class, () -> ClipPreview.decode(ok, meta(null, 6, 5, 2)));
		TrsLink.PreviewDto broken = meta(null, 6, 4, 2);
		broken.frameWidth = null;
		assertThrows(IOException.class, () -> ClipPreview.decode(ok, broken));
		TrsLink.PreviewDto huge = meta(null, 6, 4, 2);
		huge.frameWidth = 5000;
		assertThrows(IOException.class, () -> ClipPreview.decode(ok, huge));
		assertThrows(IOException.class, () -> ClipPreview.decode(new byte[]{1, 2, 3}, meta(null, 1, 1, 1)));
	}

	@Test
	void nurAbsolutePngDateien(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("strip.png");
		Files.write(file, png(16, 9, 0, 0));
		assertEquals(file, ClipPreview.checkedPath(file.toString()));
		assertNull(ClipPreview.checkedPath("strip.png"), "relativ");
		assertNull(ClipPreview.checkedPath(dir.toString()), "Ordner");
		Path txt = dir.resolve("x.txt");
		Files.write(txt, new byte[]{1});
		assertNull(ClipPreview.checkedPath(txt.toString()), "kein PNG");
		assertNull(ClipPreview.checkedPath(dir.resolve("fehlt.png").toString()));
		assertNull(ClipPreview.checkedPath(null));
		assertNull(ClipPreview.checkedPath(""));
		Path empty = dir.resolve("leer.png");
		Files.write(empty, new byte[0]);
		assertNull(ClipPreview.checkedPath(empty.toString()));
	}

	// --- Über den Link --------------------------------------------------------------------------------

	static final class FakeStore implements Textures.Store {
		final Map<String, int[]> uploaded = new HashMap<String, int[]>();
		int releases;

		@Override
		public TextureRef upload(String name, int width, int height, int[] argb) {
			assertTrue(Textures.validName(name), name);
			assertEquals(width * height, argb.length);
			uploaded.put(name, argb);
			return new TextureRef(name, width, height);
		}

		@Override
		public void release(TextureRef texture) {
			releases++;
		}

		@Override
		public TextureRef game(String location, int width, int height) {
			return new TextureRef(location, width, height);
		}

		@Override
		public Textures.DefaultSkin defaultSkin(UUID uuid) {
			return new Textures.DefaultSkin(new TextureRef("default", 64, 64), false);
		}
	}

	private static TrsLink connect(FakeLauncher launcher, Path dir) throws Exception {
		Path cfg = dir.resolve("trsclient");
		Files.createDirectories(cfg);
		Files.write(cfg.resolve("clips.json"), ("{\"version\":2,\"enabled\":true,\"port\":" + launcher.port() + "}")
				.getBytes(StandardCharsets.UTF_8));
		TrsLink link = new TrsLink(dir, launcher.env());
		link.start();
		long until = System.currentTimeMillis() + 20000;
		while (!link.status().connected && System.currentTimeMillis() < until) Thread.sleep(20);
		assertTrue(link.status().connected, "verbunden");
		return link;
	}

	private static void await(ClipPreview p, ClipPreview.State want) throws InterruptedException {
		long until = System.currentTimeMillis() + 20000;
		long now = 1;
		while (p.state() != want && System.currentTimeMillis() < until) {
			p.frame(now++);
			Thread.sleep(10);
		}
		assertEquals(want, p.state(), "Code: " + p.code());
	}

	@Test
	void vorschauKommtUeberDenLinkUndLaeuft(@TempDir Path dir) throws Exception {
		FakeStore store = new FakeStore();
		Textures.replaceForTests(store);
		Path strip = dir.resolve("cache").resolve("abc.png");
		Files.createDirectories(strip.getParent());
		Files.write(strip, png(64, 18, 0x00FF00, 16));
		try (FakeLauncher launcher = new FakeLauncher()) {
			launcher.features = "[\"clips\",\"accounts\",\"clips.enable\",\"clips.preview\",\"clips.open\"]";
			launcher.previewJson = "{\"path\":" + new com.google.gson.Gson().toJson(strip.toString())
					+ ",\"frames\":6,\"cols\":4,\"rows\":2,\"frameWidth\":16,\"frameHeight\":9,\"intervalMs\":1000,\"durationMs\":6000}";
			TrsLink link = connect(launcher, dir);
			try {
				ClipPreview preview = new ClipPreview(ClipPreview.of(link), DIRECT);
				assertNull(preview.unavailableReason());
				preview.load("Survival 2026-09-24 15-30-12.mp4");
				await(preview, ClipPreview.State.READY);
				assertEquals(1, launcher.previews.get());
				String sent = null;
				for (String line : launcher.received) if (line.contains("clips.preview")) sent = line;
				assertNotNull(sent);
				assertTrue(sent.contains("\"clip\":\"Survival 2026-09-24 15-30-12.mp4\""), sent);
				assertFalse(sent.contains(dir.toString().replace("\\", "\\\\")), "nie ein Pfad in der Anfrage");
				// Eine Textur für das ganze Raster, Animation läuft.
				assertEquals(1, store.uploaded.size());
				ClipPreview.Sheet sheet = preview.sheet();
				assertNotNull(sheet);
				assertEquals(64, preview.texture().width);
				preview.frame(10_000); // Bezugszeit
				preview.animation().reset(sheet.frames, sheet.intervalMs);
				preview.frame(10_260);
				assertEquals(1, preview.animation().index(), "läuft im Render-Takt weiter");
				// Gleicher Clip noch einmal: keine zweite Anfrage.
				preview.load("Survival 2026-09-24 15-30-12.mp4");
				assertEquals(1, launcher.previews.get());

				// „Im Launcher öffnen“
				final String[] result = {"pending"};
				preview.openInLauncher("Survival 2026-09-24 15-30-12.mp4", code -> result[0] = code == null ? "ok" : code);
				assertEquals("Survival 2026-09-24 15-30-12.mp4", launcher.opened.poll(10, TimeUnit.SECONDS));
				long until = System.currentTimeMillis() + 5000;
				while ("pending".equals(result[0]) && System.currentTimeMillis() < until) Thread.sleep(10);
				assertEquals("ok", result[0]);

				preview.release();
				assertEquals(1, store.releases, "Textur wieder freigegeben");
				assertNull(preview.sheet());
			} finally {
				link.stop();
			}
		}
	}

	@Test
	void fehlerUndAlterLauncher(@TempDir Path dir) throws Exception {
		Textures.replaceForTests(new FakeStore());
		try (FakeLauncher launcher = new FakeLauncher()) {
			launcher.features = "[\"clips\",\"accounts\",\"clips.preview\",\"clips.open\"]";
			launcher.previewError = "no_ffmpeg";
			TrsLink link = connect(launcher, dir);
			try {
				ClipPreview preview = new ClipPreview(ClipPreview.of(link), DIRECT);
				preview.load("a.mp4");
				await(preview, ClipPreview.State.FAILED);
				assertEquals("no_ffmpeg", preview.code());
				// Unbekannte Codes des Launchers werden nie durchgereicht.
				launcher.previewError = "<script>";
				preview.retry();
				await(preview, ClipPreview.State.FAILED);
				assertEquals("error", preview.code());
				// Kaputte Antwort (Datei fehlt): Fehler statt Absturz.
				launcher.previewJson = "{\"path\":\"C:/gibt/es/nicht.png\",\"frames\":1,\"cols\":1,\"rows\":1,\"frameWidth\":16,\"frameHeight\":9}";
				preview.load("b.mp4");
				await(preview, ClipPreview.State.FAILED);
				assertEquals("error", preview.code());
			} finally {
				link.stop();
			}
		}
		// Launcher ohne die Merkmale (älter): gar nicht erst fragen.
		try (FakeLauncher launcher = new FakeLauncher()) {
			TrsLink link = connect(launcher, dir);
			try {
				ClipPreview preview = new ClipPreview(ClipPreview.of(link), DIRECT);
				preview.load("a.mp4");
				assertEquals(ClipPreview.State.UNAVAILABLE, preview.state());
				assertEquals("old_launcher", preview.code());
				assertEquals(0, launcher.previews.get());
				final String[] result = {null};
				preview.openInLauncher("a.mp4", code -> result[0] = code);
				assertEquals("old_launcher", result[0]);
			} finally {
				link.stop();
			}
		}
	}

	@Test
	void ohneTrsLauncherNurEinHinweis(@TempDir Path dir) {
		TrsLink link = new TrsLink(dir, null);
		ClipPreview preview = new ClipPreview(ClipPreview.of(link), DIRECT);
		preview.load("a.mp4");
		assertEquals(ClipPreview.State.UNAVAILABLE, preview.state());
		assertEquals("no_launcher", preview.code());
		final String[] result = {null};
		preview.openInLauncher("a.mp4", code -> result[0] = code);
		assertEquals("no_launcher", result[0]);
		for (String lang : I18n.LANGUAGES) {
			I18n.use(lang);
			String text = dev.theredstonee.trsclient.core.ui.clips.ClipsUi.previewError("no_launcher", false);
			assertFalse(text.isEmpty() || text.startsWith("clips."), lang);
		}
	}
}
