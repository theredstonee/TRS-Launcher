package dev.theredstonee.trsclient.core.ui;

import dev.theredstonee.trsclient.core.module.Category;
import dev.theredstonee.trsclient.core.module.ColorSetting;
import dev.theredstonee.trsclient.core.module.KeySetting;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests der versionsunabhängigen Oberflächen-Logik (Raster, Farben, Symbole, Eingabefeld). */
class UiLogicTest {

	@Test
	void gridFillsTheWidthAndScrolls() {
		TileGrid grid = TileGrid.of(320, 100, 7, 100, 46, 5);
		assertEquals(3, grid.columns);
		assertEquals((320 - 10) / 3, grid.tileWidth);
		assertEquals(3, grid.rows);
		assertEquals(0, grid.x(0));
		assertEquals(grid.tileWidth + 5, grid.x(1));
		assertEquals(51, grid.y(3));
		assertTrue(grid.maxScroll > 0);
		assertEquals(grid.maxScroll, grid.clampScroll(9999));
		assertEquals(0, grid.clampScroll(-5));
	}

	@Test
	void gridHitTesting() {
		TileGrid grid = TileGrid.of(320, 100, 7, 100, 46, 5);
		assertEquals(0, grid.indexAt(2, 2, 7));
		assertEquals(1, grid.indexAt(grid.tileWidth + 6, 2, 7));
		// Im Abstand zwischen zwei Kacheln liegt nichts
		assertEquals(-1, grid.indexAt(grid.tileWidth + 2, 2, 7));
		assertEquals(-1, grid.indexAt(2, 2, 0));
	}

	@Test
	void narrowAreaKeepsOneColumn() {
		TileGrid grid = TileGrid.of(60, 50, 3, 100, 46, 5);
		assertEquals(1, grid.columns);
		assertTrue(grid.tileWidth > 0);
	}

	@Test
	void hsvRoundTrip() {
		int[] colors = {0xFFFFFF, 0xE0281E, 0x3DDC84, 0x4DD8E0, 0x000000, 0x123456};
		for (int rgb : colors) {
			float[] hsv = ColorMath.rgbToHsv(rgb);
			int back = ColorMath.hsvToRgb(hsv[0], hsv[1], hsv[2]);
			assertEquals(rgb, back, "Rundlauf für " + Integer.toHexString(rgb));
		}
	}

	@Test
	void chromaCyclesThroughColors() {
		int a = ColorMath.chroma(0, 0);
		int b = ColorMath.chroma(ColorMath.CHROMA_PERIOD_MS / 3, 0);
		assertNotEquals(a, b);
		assertEquals(a, ColorMath.chroma(ColorMath.CHROMA_PERIOD_MS, 0), "nach einer Runde wieder gleich");
		assertEquals(b, ColorMath.chroma(0, ColorMath.CHROMA_PERIOD_MS / 3), "Versatz verschiebt nur den Farbton");
	}

	@Test
	void fadeAndContrast() {
		assertEquals(0x80FFFFFF, ColorMath.fade(0xFFFFFFFF, 0.5f) & 0xFFFFFFFF, 1);
		assertEquals(0xFF10101A, ColorMath.contrastText(0xFFFFB84D));
		assertEquals(0xFFFFFFFF, ColorMath.contrastText(0xFF17171E));
	}

	@Test
	void iconsAreEightByEight() {
		for (String id : Icons.ids()) {
			String[] rows = Icons.rows(id);
			assertEquals(8, rows.length, id + " braucht 8 Zeilen");
			for (String row : rows) {
				assertEquals(8, row.length(), id + ": Zeile '" + row + "' braucht 8 Zeichen");
			}
		}
	}

	@Test
	void everyModuleHasAKnownIcon() {
		TrsModules modules = new TrsModules();
		List<Module> all = modules.registry.all();
		for (Module m : all) {
			assertTrue(Icons.has(m.icon()), m.id() + " nutzt ein unbekanntes Symbol: " + m.icon());
		}
		for (Category c : Category.values()) {
			assertTrue(Icons.has(c.icon()), c + " nutzt ein unbekanntes Symbol");
		}
	}

	@Test
	void searchFindsModulesByNameAndSetting() {
		TrsModules modules = new TrsModules();
		assertTrue(modules.zoom.matches("zoo"));
		assertTrue(modules.zoom.matches("ZOOM"));
		assertTrue(modules.fps.matches("bilder"), "Beschreibung zählt auch");
		assertTrue(modules.crosshair.matches("Form"), "Einstellungsnamen zählen auch");
		assertFalse(modules.fps.matches("waypoint"));
		assertTrue(modules.fps.matches(""));
		assertTrue(modules.fps.matches(null));
	}

	@Test
	void textInputEditing() {
		TextInput input = new TextInput(5);
		assertTrue(input.type('a'));
		assertTrue(input.type('b'));
		assertTrue(input.key(UiKey.LEFT));
		assertTrue(input.type('c'));
		assertEquals("acb", input.text());
		assertTrue(input.key(UiKey.BACKSPACE));
		assertEquals("ab", input.text());
		assertTrue(input.key(UiKey.END));
		input.type('x');
		input.type('y');
		input.type('z');
		assertEquals("abxyz", input.text());
		assertFalse(input.type('!'), "mehr als 5 Zeichen gehen nicht");
		assertFalse(input.type('§'), "Formatierungszeichen sind verboten");
	}

	@Test
	void colorSettingKeepsAlphaAndChroma() {
		ColorSetting c = new ColorSetting("color", "Farbe", 0x80FFB84D, true);
		assertEquals(0x80, c.alpha());
		assertEquals(0xFFB84D, c.rgb());
		c.set(0x3DDC84);
		assertEquals(0x803DDC84, c.storedArgb());
		c.setChroma(true);
		assertEquals(0x80, c.argb() >>> 24, "Chroma behält die Deckkraft");
		assertNotEquals(0x803DDC84, c.argb());
		c.reset();
		assertFalse(c.chroma());
		assertEquals(0x80FFB84D, c.storedArgb());
	}

	@Test
	void colorHexParsing() {
		assertEquals(0xFFFFB84D, ColorSetting.parseHexArgb("#FFB84D", 0));
		assertEquals(0x80FFB84D, ColorSetting.parseHexArgb("#80FFB84D", 0));
		assertEquals(7, ColorSetting.parseHexArgb("nope", 7));
		assertEquals(7, ColorSetting.parseHexArgb("#12345", 7));
		assertEquals(7, ColorSetting.parseHexArgb("#GGGGGG", 7));
		assertEquals("#FFB84D", ColorSetting.toHexArgb(0xFFFFB84D));
		assertEquals("#80FFB84D", ColorSetting.toHexArgb(0x80FFB84D));
	}

	@Test
	void keySettingValidatesNames() {
		KeySetting k = new KeySetting("key", "Taste", "key.keyboard.v");
		assertTrue(k.isBound());
		k.set("rm -rf");
		assertFalse(k.isBound(), "unbekannte Namen werden verworfen");
		k.set("key.mouse.4");
		assertEquals("key.mouse.4", k.get());
		k.reset();
		assertEquals("key.keyboard.v", k.get());
	}

	@Test
	void themeReadsLauncherValuesSafely() {
		assertEquals(0xFF17A34A, Theme.of("dark", "emerald", null).accent);
		assertEquals(0xFF000000, Theme.of("oled", "redstone", null).background);
		assertTrue(Theme.of("light", "lapis", null).light);
		// Unbekanntes Thema/Akzent → Standard
		assertEquals(0xFFE0281E, Theme.of("neon", "türkis", null).accent);
		assertFalse(Theme.of("system", "redstone", null).light);
		// Unbekannter Name, aber gültiger Hex-Wert
		assertEquals(0xFF123456, Theme.of("dark", "custom", "#123456").accent);
		assertEquals(0xFFE0281E, Theme.of("dark", "custom", "#12345").accent);
		assertEquals(0xFFE0281E, Theme.of(null, null, null).accent);
	}

	@Test
	void roundRectDrawsInsideItsBox() {
		RecordingCanvas canvas = new RecordingCanvas();
		Paint.roundRect(canvas, 10, 20, 40, 30, 5, 0xFF123456);
		assertTrue(canvas.minX >= 10 && canvas.minY >= 20, "nichts links/oberhalb der Box");
		assertTrue(canvas.maxX <= 50 && canvas.maxY <= 50, "nichts rechts/unterhalb der Box");
		assertTrue(canvas.calls > 0);
	}

	/** Merkt sich die Ausmaße aller gezeichneten Rechtecke. */
	private static final class RecordingCanvas implements Canvas {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int calls;

		@Override
		public void fill(int x1, int y1, int x2, int y2, int argb) {
			calls++;
			minX = Math.min(minX, x1);
			minY = Math.min(minY, y1);
			maxX = Math.max(maxX, x2);
			maxY = Math.max(maxY, y2);
		}

		@Override
		public void text(String text, int x, int y, int argb, boolean shadow) {
		}

		@Override
		public int textWidth(String text) {
			return text.length() * 6;
		}

		@Override
		public int lineHeight() {
			return 9;
		}

		@Override
		public String clip(String text, int maxWidth) {
			int chars = Math.max(0, maxWidth / 6);
			return text.length() <= chars ? text : text.substring(0, chars);
		}

		@Override
		public void flush() {
		}

		@Override
		public void scissor(int x1, int y1, int x2, int y2) {
		}

		@Override
		public void noScissor() {
		}

		@Override
		public void raise(float z) {
		}

		@Override
		public void push() {
		}

		@Override
		public void translate(float x, float y) {
		}

		@Override
		public void scale(float factor) {
		}

		@Override
		public void pop() {
		}
	}
}
