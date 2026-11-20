package dev.theredstonee.trsclient.core.hud;

import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextWidth;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectsPanelTest {
	private static final TextWidth SIX = new TextWidth() {
		@Override
		public int width(String text) {
			return text.length() * 6;
		}
	};

	private static void fill(EffectsPanel p) {
		p.begin();
		add(p, "speed", 1800, false, EffectsPanel.BENEFICIAL);
		add(p, "poison", 160, false, EffectsPanel.HARMFUL);
		add(p, "night", 0, true, EffectsPanel.BENEFICIAL);
		add(p, "slow", 600, false, EffectsPanel.HARMFUL);
		p.end(true);
	}

	private static void add(EffectsPanel p, String key, int duration, boolean infinite, int category) {
		EffectsPanel.Entry e = p.add(key);
		e.name = key;
		e.duration = duration;
		e.infinite = infinite;
		e.category = category;
	}

	private static String order(EffectsPanel p) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < p.count(); i++) sb.append(i == 0 ? "" : ",").append(p.get(i).key);
		return sb.toString();
	}

	@Test
	void sortsByTimeTypeAndArrival() {
		TrsModules m = new TrsModules();
		EffectsPanel p = new EffectsPanel(m);
		m.effectsSort.set(EffectsPanel.Sort.TIME);
		fill(p);
		assertEquals("poison,slow,speed,night", order(p));
		m.effectsSort.set(EffectsPanel.Sort.TYPE);
		fill(p);
		assertEquals("speed,night,poison,slow", order(p));
		m.effectsSort.set(EffectsPanel.Sort.RECEIVED);
		fill(p);
		assertEquals("speed,poison,night,slow", order(p));
	}

	@Test
	void remembersFullDurationUntilRefreshed() {
		TrsModules m = new TrsModules();
		EffectsPanel p = new EffectsPanel(m);
		p.begin();
		add(p, "speed", 1000, false, EffectsPanel.BENEFICIAL);
		p.end(true);
		p.begin();
		add(p, "speed", 250, false, EffectsPanel.BENEFICIAL);
		p.end(true);
		assertEquals(0.25f, p.get(0).fraction(), 1e-4);
		p.begin();
		add(p, "speed", 400, false, EffectsPanel.BENEFICIAL);
		p.end(true);
		assertEquals(1f, p.get(0).fraction(), 1e-4);
		p.begin();
		add(p, "night", EffectsPanel.LONG_TICKS + 5, false, EffectsPanel.BENEFICIAL);
		p.end(true);
		assertEquals(1, p.count());
		assertTrue(p.get(0).endless());
	}

	@Test
	void layoutSizes() {
		TrsModules m = new TrsModules();
		EffectsPanel p = new EffectsPanel(m);
		m.effectsTimeStyle.set(EffectsPanel.TimeStyle.TEXT);
		m.effectsLayout.set(EffectsPanel.Layout.HORIZONTAL);
		fill(p);
		assertEquals(4 * EffectsPanel.CELL + 3 * EffectsPanel.GAP, p.width(SIX));
		assertEquals(EffectsPanel.CELL, p.height());
		m.effectsLayout.set(EffectsPanel.Layout.VERTICAL);
		assertEquals(EffectsPanel.CELL, p.width(SIX));
		assertEquals(4 * EffectsPanel.CELL + 3 * EffectsPanel.GAP, p.height());
		m.effectsTimeStyle.set(EffectsPanel.TimeStyle.RING);
		assertEquals(EffectsPanel.RING_CELL, p.width(SIX));
		assertTrue(EffectsPanel.ringPixels().length > 100);
		m.effectsTimeStyle.set(EffectsPanel.TimeStyle.ROW);
		m.effectsLayout.set(EffectsPanel.Layout.HORIZONTAL);
		assertEquals(4 * EffectsPanel.CELL + 3 * EffectsPanel.GAP, p.height(), "Zeilen stehen immer untereinander");
	}

	@Test
	void docksBesideTheMinimapOnlyAtTheDefaultPlace() {
		TrsModules m = new TrsModules();
		m.minimap.setEnabled(true);
		m.effects.resetPosition();
		m.minimap.resetPosition();
		int[] out = new int[2];
		m.effectsLayout.set(EffectsPanel.Layout.HORIZONTAL);
		assertTrue(EffectsPanel.dock(m, 100, 22, 640, 360, out));
		assertTrue(out[0] + 100 <= 640 - 118, "links neben der Minimap");
		m.effectsLayout.set(EffectsPanel.Layout.VERTICAL);
		assertTrue(EffectsPanel.dock(m, 22, 100, 640, 360, out));
		assertTrue(out[1] > 100, "unter der Minimap");
		m.effects.position().offsetY = 0.5;
		assertFalse(EffectsPanel.dock(m, 22, 100, 640, 360, out));
		m.effects.resetPosition();
		m.minimap.setEnabled(false);
		assertFalse(EffectsPanel.dock(m, 22, 100, 640, 360, out));
	}

	@Test
	void ringRunsCoverEveryPixelOnce() {
		final int[][] grid = new int[64][64];
		Canvas c = new Canvas() {
			@Override
			public void fill(int x1, int y1, int x2, int y2, int argb) {
				for (int y = y1; y < y2; y++) {
					for (int x = x1; x < x2; x++) grid[y][x] += argb == EffectsPanel.TRACK ? 1000 : 1;
				}
			}

			@Override
			public void text(String text, int x, int y, int argb, boolean shadow) {
			}

			@Override
			public int textWidth(String text) {
				return 0;
			}

			@Override
			public int lineHeight() {
				return 9;
			}

			@Override
			public String clip(String text, int maxWidth) {
				return text;
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
		};
		EffectsPanel.ring(c, 32, 32, 0.5f, 0xFF00FF00);
		int n = EffectsPanel.ringPixels().length / 2;
		int lit = 0;
		int track = 0;
		for (int[] row : grid) {
			for (int v : row) {
				assertTrue(v == 0 || v == 1 || v == 1000, "Pixel doppelt gezeichnet");
				if (v == 1) lit++;
				if (v == 1000) track++;
			}
		}
		assertEquals(Math.round(n * 0.5f), lit);
		assertEquals(n, lit + track);
	}
}
