package dev.theredstonee.trsclient.core.halloween;

import dev.theredstonee.trsclient.core.ui.Canvas;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spuk-Effekte: Fledermäuse bei Treffern, Geister-Spur, Obergrenzen, Zeichnen über die Projektion. */
class SpookyFxTest {
	/** Zählt Flächen. */
	static final class Count implements Canvas {
		int fills;

		@Override
		public void fill(int x1, int y1, int x2, int y2, int argb) {
			fills++;
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
	}

	@Test
	void batsFlyUpAndFade() {
		SpookyFx fx = new SpookyFx(new Random(1));
		fx.hit(0, 64, 5);
		assertEquals(SpookyFx.BATS_PER_HIT, fx.batCount());
		double y0 = fx.bats.get(0).y;
		fx.step(0.05f);
		assertTrue(fx.bats.get(0).y > y0, "steigen auf");
		for (int i = 0; i < 30; i++) fx.step(0.05f);
		assertEquals(0, fx.batCount(), "nach ~1,25 s weg");
	}

	@Test
	void capsAndTrailThrottle() {
		SpookyFx fx = new SpookyFx(new Random(2));
		for (int i = 0; i < 40; i++) fx.hit(0, 64, 5);
		assertEquals(SpookyFx.MAX_BATS, fx.batCount());
		fx.trail("a", 0, 64, 0, 0f, 1000);
		fx.trail("a", 0, 64, 0, 0f, 1030);
		assertEquals(1, fx.ghostCount(), "höchstens alle 70 ms");
		fx.trail("a", 0, 64, 0, 0f, 1070);
		fx.trail("b", 1, 64, 0, 0f, 1070);
		assertEquals(3, fx.ghostCount());
		for (int i = 0; i < 100; i++) fx.trail("c", 0, 64, 0, 0f, 2000 + i * 100L);
		assertEquals(SpookyFx.MAX_GHOSTS, fx.ghostCount());
		fx.clear();
		assertFalse(fx.any());
	}

	@Test
	void drawsOnlyWhatIsInFrontAndNear() {
		SpookyFx fx = new SpookyFx(new Random(3));
		fx.hit(0, 64, 5);
		fx.trail("me", 0, 63, 3, 0f, 1000);
		Count c = new Count();
		// Kamera bei 0/64/0, Blick nach Süden (+Z).
		fx.draw(c, 0, 64, 0, 0f, 0f, 70, 854, 480);
		assertTrue(c.fills > 0);
		Count behind = new Count();
		fx.draw(behind, 0, 64, 0, 180f, 0f, 70, 854, 480);
		assertEquals(0, behind.fills, "hinter der Kamera nichts");
		Count far = new Count();
		fx.draw(far, 0, 64, -100, 0f, 0f, 70, 854, 480);
		assertEquals(0, far.fills, "über 48 Blöcke nichts");
	}
}
