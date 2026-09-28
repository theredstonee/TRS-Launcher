package dev.theredstonee.trsclient.core.screenshot;

import dev.theredstonee.trsclient.core.screenshot.edit.EditHistory;
import dev.theredstonee.trsclient.core.screenshot.edit.EditRender;
import dev.theredstonee.trsclient.core.screenshot.edit.EditState;
import dev.theredstonee.trsclient.core.screenshot.edit.Shape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Screenshot-Werkzeuge: Namen, Favoriten, Ordner-Wächter, Editor-Stand/Rastern, Essential, Zwischenablage, Marker. */
class ScreenshotLogicTest {
	/** Kleinstes gültiges PNG-Ende (Länge 0 + „IEND“ + CRC). */
	private static final byte[] IEND = {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

	private static void png(Path p, boolean complete) throws Exception {
		byte[] head = new byte[40];
		head[0] = (byte) 0x89;
		head[1] = 'P';
		head[2] = 'N';
		head[3] = 'G';
		byte[] data = complete ? concat(head, IEND) : head;
		Files.write(p, data);
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	@Test
	void recognisesVanillaNamesAndBuildsCopyNames(@TempDir Path dir) throws Exception {
		assertTrue(ScreenshotNames.vanilla("2026-09-28_14.03.22.png"));
		assertTrue(ScreenshotNames.vanilla("2026-09-28_14.03.22_2.png"));
		assertFalse(ScreenshotNames.vanilla("Copy of 2026-09-28_14.03.22.png"));
		assertFalse(ScreenshotNames.vanilla("trsclient-1.21.11-menu.png"));
		assertFalse(ScreenshotNames.vanilla("2026-09-28_14.03.22.jpg"));
		assertEquals("2026-09-28_14.03.22.png", ScreenshotNames.find("Saved screenshot as 2026-09-28_14.03.22.png [Edit]"));
		assertNull(ScreenshotNames.find("nothing here"));

		Path original = dir.resolve("2026-09-28_14.03.22.png");
		png(original, true);
		Path copy = ScreenshotNames.copyFor(original, "Kopie von");
		assertEquals("Kopie von 2026-09-28_14.03.22.png", copy.getFileName().toString());
		Files.write(copy, new byte[]{1});
		assertEquals("Kopie von 2026-09-28_14.03.22 (2).png", ScreenshotNames.copyFor(original, "Kopie von").getFileName().toString());
		// Unbrauchbarer Präfix (Pfad-/Sonderzeichen) → „Copy of“
		assertEquals("Copy of 2026-09-28_14.03.22.png", ScreenshotNames.copyFor(original, "../x").getFileName().toString());
		assertEquals("Copy of 2026-09-28_14.03.22.png", ScreenshotNames.copyFor(original, "Cópia de").getFileName().toString());
		assertTrue(Files.exists(original), "Original bleibt");
	}

	@Test
	void favouritesArePersistedLocally(@TempDir Path config) throws Exception {
		ScreenshotStore s = new ScreenshotStore(config.resolve("trsclient").resolve(ScreenshotStore.FILE));
		assertTrue(s.toggleFavorite("2026-09-28_14.03.22.png"));
		assertFalse(s.toggleFavorite("../evil.png"));
		assertFalse(s.toggleFavorite("x.exe"));
		s.essentialPreviewOff(true);
		ScreenshotStore again = new ScreenshotStore(config.resolve("trsclient").resolve(ScreenshotStore.FILE));
		again.load();
		assertTrue(again.isFavorite("2026-09-28_14.03.22.png"));
		assertTrue(again.essentialPreviewOff());
		assertFalse(again.toggleFavorite("2026-09-28_14.03.22.png"));
		assertFalse(again.isFavorite("2026-09-28_14.03.22.png"));
	}

	@Test
	void watcherReportsOnlyNewCompleteVanillaScreenshots(@TempDir Path dir) throws Exception {
		png(dir.resolve("2026-01-01_00.00.00.png"), true); // schon vorher da
		long start = System.currentTimeMillis();
		ScreenshotWatcher w = new ScreenshotWatcher(dir, start);
		assertTrue(w.poll(start).isEmpty(), "vorhandene Dateien zählen nicht");
		Path fresh = dir.resolve("2026-09-28_14.03.22.png");
		png(fresh, false);
		png(dir.resolve("Copy of 2026-09-28_14.03.22.png"), true);
		assertTrue(w.poll(start + 20_000).isEmpty(), "erst Kandidat");
		assertTrue(w.poll(start + 20_400).isEmpty(), "unfertig (kein IEND)");
		png(fresh, true);
		assertTrue(w.poll(start + 20_800).isEmpty(), "Größe hat sich gerade geändert");
		List<Path> found = w.poll(start + 21_200);
		assertEquals(Collections.singletonList(fresh), found);
		assertTrue(w.poll(start + 40_000).isEmpty(), "nur einmal");
		// Chatzeile war schneller: announce verhindert eine zweite Meldung.
		assertTrue(w.announce("2026-09-28_14.05.00.png"));
		png(dir.resolve("2026-09-28_14.05.00.png"), true);
		w.poll(start + 40_400);
		assertTrue(w.poll(start + 40_800).isEmpty());
		assertFalse(w.announce("2026-09-28_14.05.00.png"));
	}

	@Test
	void editStateRotatesCropAndShapes() {
		EditState s = EditState.initial(200, 100);
		assertTrue(s.pristine());
		s = s.withCrop(10, 20, 50, 30).withShape(new Shape.Rect(10, 20, 60, 50, 0xFFFF0000, 2));
		EditState r = s.rotatedClockwise();
		assertEquals(100, r.width());
		assertEquals(200, r.height());
		// (x, y, w, h) → (H − y − h, x, h, w)
		assertEquals(100 - 20 - 30, r.cropX);
		assertEquals(10, r.cropY);
		assertEquals(30, r.cropW);
		assertEquals(50, r.cropH);
		Shape.Rect rect = (Shape.Rect) r.shapes.get(0);
		assertEquals(50, rect.x1, 1e-3);
		assertEquals(10, rect.y1, 1e-3);
		assertEquals(80, rect.x2, 1e-3);
		assertEquals(60, rect.y2, 1e-3);
		EditState full = r.rotatedClockwise().rotatedClockwise().rotatedClockwise();
		assertEquals(0, full.rotation);
		assertEquals(s.cropX, full.cropX);
		assertEquals(s.cropY, full.cropY);
		assertEquals(s.cropW, full.cropW);
		assertEquals(s.cropH, full.cropH);
		// Zuschnitt bleibt im Bild und mindestens MIN_CROP groß
		EditState c = EditState.initial(100, 100).withCrop(95, -5, 1, 500);
		assertEquals(EditState.MIN_CROP, c.cropW);
		assertEquals(100 - EditState.MIN_CROP, c.cropX);
		assertEquals(0, c.cropY);
		assertEquals(100, c.cropH);
	}

	@Test
	void historyUndoesAndRedoes() {
		EditHistory h = new EditHistory(EditState.initial(10, 10));
		EditState a = h.current().withCrop(1, 1, 8, 8);
		h.push(a);
		h.push(a.rotatedClockwise());
		assertTrue(h.undo());
		assertEquals(a, h.current());
		assertTrue(h.redo());
		assertEquals(1, h.current().rotation);
		assertTrue(h.undo());
		h.push(a.withShape(new Shape.Arrow(0, 0, 5, 5, 0xFFFFFFFF, 1)));
		assertFalse(h.canRedo(), "neue Änderung löscht Wiederholen");
	}

	@Test
	void renderRotatesCropsAndPixelates() {
		int[] px = {1, 2, 3, 4, 5, 6}; // 3×2
		// Uhrzeigersinn: Breite 2, Höhe 3 → Spalten von unten nach oben
		assertArrayEquals(new int[]{4, 1, 5, 2, 6, 3}, EditRender.rotate(px, 3, 2, 1));
		assertArrayEquals(new int[]{6, 5, 4, 3, 2, 1}, EditRender.rotate(px, 3, 2, 2));
		assertArrayEquals(new int[]{3, 6, 2, 5, 1, 4}, EditRender.rotate(px, 3, 2, 3));
		assertArrayEquals(new int[]{2, 3, 5, 6}, EditRender.crop(px, 3, 2, 1, 0, 2, 2));

		int w = 40, h = 20;
		int[] img = new int[w * h];
		for (int i = 0; i < img.length; i++) img[i] = 0xFF000000 | (i % w < 20 ? 0x000000 : 0xFFFFFF);
		EditState s = EditState.initial(w, h).withShape(new Shape.Pixelate(0, 0, 40, 20, 40));
		int[] out = EditRender.export(img, s);
		assertEquals(0x7F7F7F, out[0] & 0xFFFFFF, "ein Block = Mittelwert");
		assertEquals(out[0], out[out.length - 1]);

		EditState rot = EditState.initial(w, h).rotatedClockwise().withCrop(0, 0, 20, 30);
		int[] cropped = EditRender.export(img, rot);
		assertEquals(20 * 30, cropped.length);
	}

	@Test
	void renderDrawsShapesWithJava2d() {
		int w = 64, h = 48;
		int[] img = new int[w * h];
		Arrays.fill(img, 0xFF000000);
		EditState s = EditState.initial(w, h)
				.withShape(new Shape.Rect(8, 8, 40, 30, 0xFFFF0000, 3))
				.withShape(new Shape.Arrow(50, 40, 30, 20, 0xFF00FF00, 2))
				.withShape(new Shape.Pen(new float[]{2, 44, 10, 40, 20, 44}, 0xFF0000FF, 2))
				.withShape(new Shape.Text(4, 30, "Hi", 0xFFFFFFFF, 12, 0));
		int[] out = EditRender.export(img, s);
		assertEquals(0xFF0000, out[8 * w + 20] & 0xFFFFFF, "Rahmen oben");
		int nonBlack = 0;
		for (int p : out) if ((p & 0xFFFFFF) != 0) nonBlack++;
		assertTrue(nonBlack > 100, "Formen gezeichnet: " + nonBlack);
		assertTrue(Arrays.stream(img).allMatch(p -> p == 0xFF000000), "Original unverändert");
	}

	/** Attrappe von Essentials Konfiguration (gleiche öffentliche Methoden). */
	public static final class FakeEssentialConfig {
		public boolean preview = true;

		public boolean getEssentialScreenshots() {
			return preview;
		}

		public void setEssentialScreenshots(boolean on) {
			preview = on;
		}
	}

	@Test
	void essentialPreviewIsSwitchedOffAndRestoredOnlyByUs(@TempDir Path config) throws Exception {
		FakeEssentialConfig fake = new FakeEssentialConfig();
		EssentialInterop.Handle h = new EssentialInterop.Handle(fake,
				FakeEssentialConfig.class.getMethod("getEssentialScreenshots"),
				FakeEssentialConfig.class.getMethod("setEssentialScreenshots", boolean.class));
		ScreenshotStore store = new ScreenshotStore(config.resolve(ScreenshotStore.FILE));
		assertTrue(EssentialInterop.apply(h, true, store));
		assertFalse(fake.preview);
		assertTrue(store.essentialPreviewOff());
		assertTrue(EssentialInterop.apply(h, false, store));
		assertTrue(fake.preview, "wieder an, weil wir sie ausgeschaltet hatten");
		assertFalse(store.essentialPreviewOff());
		// Vom Spieler selbst ausgeschaltet → wir schalten sie nicht ein.
		fake.preview = false;
		assertTrue(EssentialInterop.apply(h, false, store));
		assertFalse(fake.preview);
	}

	@Test
	void dibIsBottomUpBgra() {
		byte[] dib = ImageClipboard.dib(2, 2, new int[]{0xFF112233, 0xFF445566, 0xFF778899, 0xFFAABBCC});
		assertNotNull(dib);
		assertEquals(40 + 16, dib.length);
		assertEquals(40, dib[0]);
		assertEquals(32, dib[14]);
		// erste Zeile im Speicher = unterste Bildzeile, Pixel als B, G, R, A
		assertEquals((byte) 0x99, dib[40]);
		assertEquals((byte) 0x88, dib[41]);
		assertEquals((byte) 0x77, dib[42]);
		assertEquals((byte) 0xFF, dib[43]);
		assertNull(ImageClipboard.dib(0, 5, new int[0]));
	}

	@Test
	void markersNeedTheSessionSecret() {
		String m = Screenshots.marker(Screenshots.Action.EDIT, "2026-09-28_14.03.22.png");
		assertEquals("2026-09-28_14.03.22.png", Screenshots.markerName(m));
		assertEquals(Screenshots.Action.EDIT, Screenshots.markerAction(m));
		assertNotEquals(m, Screenshots.marker(Screenshots.Action.COPY, "2026-09-28_14.03.22.png"));
		assertNull(Screenshots.markerName("trs-shot:0123456789abcdef:e:2026-09-28_14.03.22.png"));
		String prefix = m.substring(0, m.length() - "2026-09-28_14.03.22.png".length());
		assertNull(Screenshots.markerName(prefix + "../options.txt"));
		assertNull(Screenshots.markerName(prefix.substring(0, prefix.length() - 2) + "x:a.png"));
		assertNull(Screenshots.markerName(null));
	}
}
