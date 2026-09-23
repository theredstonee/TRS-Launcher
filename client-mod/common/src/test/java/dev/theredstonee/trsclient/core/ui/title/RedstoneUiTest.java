package dev.theredstonee.trsclient.core.ui.title;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Redstone-Startbildschirm: Schaltung, Signal-Timing, Tastatur-Bedienung. */
class RedstoneUiTest {
	private static final int W = 480;
	private static final int H = 270;
	private static final int[][] RESERVED = {{140, 40, 340, 220}, {0, 256, 480, 270}};

	@Test
	void circuitIsDeterministicAndKeepsTheContentFree() {
		CircuitScene a = new CircuitScene();
		a.build(W, H, RESERVED, false);
		CircuitScene b = new CircuitScene();
		b.build(W, H, RESERVED, false);
		assertTrue(a.circuitCount() >= 3, "zu wenige Leitungen: " + a.circuitCount());
		assertEquals(a.circuitCount(), b.circuitCount());
		for (int i = 0; i < a.circuitCount(); i++) {
			assertEquals(a.circuits().get(i).nodes.size(), b.circuits().get(i).nodes.size());
		}
		for (CircuitScene.Circuit c : a.circuits()) {
			for (CircuitScene.Node n : c.nodes) {
				int px = n.cx * CircuitScene.CELL;
				int py = n.cy * CircuitScene.CELL;
				for (int[] r : RESERVED) {
					boolean overlaps = px + CircuitScene.CELL > r[0] && px < r[2] && py + CircuitScene.CELL > r[1] && py < r[3];
					assertFalse(overlaps, "Leitung im freien Bereich bei " + n.cx + "," + n.cy);
				}
			}
		}
	}

	@Test
	void signalFadesAndRepeatersRefreshIt() {
		CircuitScene scene = new CircuitScene();
		scene.build(W, H, RESERVED, false);
		int repeaters = 0;
		for (CircuitScene.Circuit c : scene.circuits()) {
			List<CircuitScene.Node> nodes = c.nodes;
			assertEquals(CircuitScene.TORCH, nodes.get(0).kind);
			int last = nodes.get(nodes.size() - 1).kind;
			assertTrue(last == CircuitScene.LAMP || last == CircuitScene.PISTON);
			float previousArrival = -1f;
			for (int i = 0; i < nodes.size(); i++) {
				CircuitScene.Node n = nodes.get(i);
				assertTrue(n.level >= 1 && n.level <= 15, "Signalstärke " + n.level);
				assertTrue(n.arrival > previousArrival, "Signal läuft rückwärts");
				previousArrival = n.arrival;
				if (i > 0 && n.kind == CircuitScene.DUST) {
					CircuitScene.Node prev = nodes.get(i - 1);
					int expected = prev.kind == CircuitScene.DUST ? prev.level - 1 : 15;
					assertEquals(Math.max(1, expected), n.level);
				}
				if (n.kind == CircuitScene.REPEATER) {
					repeaters++;
					assertTrue(n.delay >= 1 && n.delay <= 4);
					assertEquals(n.arrival + n.delay * CircuitScene.TICK, n.outArrival, 0.0001f);
					assertEquals(n.dirIn, n.dirOut, "Verstärker nur auf geraden Stücken");
				}
			}
			assertTrue(c.pulse < c.period);
		}
		assertTrue(repeaters > 0);
	}

	@Test
	void powerFollowsThePulse() {
		CircuitScene.Circuit c = new CircuitScene.Circuit();
		c.period = 3f;
		c.pulse = 1f;
		c.phase = 0f;
		assertEquals(1f, CircuitScene.power(c, 0f, 0.5f, 0f, 0f, 0f), 0.0001f);
		assertEquals(0f, CircuitScene.power(c, 0f, 1.5f, 0f, 0f, 0f), 0.0001f);
		// Später ankommend = später an
		assertEquals(0f, CircuitScene.power(c, 0.8f, 0.5f, 0f, 0f, 0f), 0.0001f);
		assertEquals(1f, CircuitScene.power(c, 0.8f, 1.5f, 0f, 0f, 0f), 0.0001f);
		// Periodisch
		assertEquals(1f, CircuitScene.power(c, 0f, 6.5f, 0f, 0f, 0f), 0.0001f);
		// Weiches Einblenden
		float rising = CircuitScene.power(c, 0f, 0.05f, 0f, 0.1f, 0f);
		assertTrue(rising > 0.3f && rising < 0.7f);
	}

	@Test
	void sceneDrawsWithinBudgetOfRectangles() {
		CountingCanvas canvas = new CountingCanvas();
		CircuitScene scene = new CircuitScene();
		scene.draw(canvas, 960, 540, 1.3f, new int[][]{{380, 60, 580, 400}});
		int full = canvas.fills;
		assertTrue(full > 100 && full < 4000, "Rechtecke: " + full);
		CountingCanvas lite = new CountingCanvas();
		CircuitScene simple = new CircuitScene();
		simple.setSimple(true);
		simple.draw(lite, 960, 540, 1.3f, new int[][]{{380, 60, 580, 400}});
		assertTrue(lite.fills < full, "sparsam " + lite.fills + " / voll " + full);
	}

	@Test
	void keyboardFocusCyclesAndActivatesAfterTheFlash() {
		Theme.set(Theme.of("dark", "redstone", null));
		FakeHost host = new FakeHost();
		TitleUi ui = new TitleUi(host);
		CountingCanvas canvas = new CountingCanvas();
		ui.render(canvas, W, H, -1, -1);
		assertTrue(ui.keyPressed(0, UiKey.TAB, false));
		assertEquals("Singleplayer, button", host.narrated.get(host.narrated.size() - 1));
		ui.keyPressed(0, UiKey.DOWN, false);
		assertEquals("Multiplayer, button", host.narrated.get(host.narrated.size() - 1));
		// Rückwärts über den Anfang hinaus landet beim Link am Ende
		ui.keyPressed(0, UiKey.UP, false);
		ui.keyPressed(0, UiKey.UP, false);
		assertEquals("Classic title screen, button", host.narrated.get(host.narrated.size() - 1));
		ui.keyPressed(0, UiKey.TAB, false);
		assertTrue(ui.keyPressed(0, UiKey.ENTER, false));
		assertEquals(1, host.clicks);
		assertEquals(0, host.singleplayer, "erst nach dem Aufblitzen");
		long until = System.nanoTime() + 2_000_000_000L;
		while (host.singleplayer == 0 && System.nanoTime() < until) {
			ui.render(canvas, W, H, -1, -1);
			sleep();
		}
		assertEquals(1, host.singleplayer);
		// Esc schließt den Startbildschirm nicht
		assertFalse(ui.keyPressed(0, UiKey.ESCAPE, false));
		ui.requestClose();
		assertFalse(ui.isClosing());
	}

	@Test
	void layoutWithoutModsCentersQuit() {
		FakeHost host = new FakeHost();
		host.mods = false;
		TitleUi ui = new TitleUi(host);
		ui.render(new CountingCanvas(), W, H, -1, -1);
		ui.focus(4);
		ui.keyPressed(0, UiKey.ENTER, false);
		long until = System.nanoTime() + 2_000_000_000L;
		while (host.quit == 0 && System.nanoTime() < until) {
			ui.render(new CountingCanvas(), W, H, -1, -1);
			sleep();
		}
		assertEquals(1, host.quit);
		assertNotEquals(0, host.clicks);
	}

	private static void sleep() {
		try {
			Thread.sleep(5);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static final class FakeHost implements TitleHost {
		final List<String> narrated = new ArrayList<String>();
		boolean mods = true;
		int clicks;
		int singleplayer;
		int quit;

		@Override public void singleplayer() { singleplayer++; }
		@Override public void multiplayer() { }
		@Override public void options() { }
		@Override public void trsMenu() { }
		@Override public boolean hasMods() { return mods; }
		@Override public void mods() { }
		@Override public void quit() { quit++; }
		@Override public void classicTitle() { }
		@Override public String versionLine() { return "Minecraft test"; }
		@Override public void playClick() { clicks++; }
		@Override public void narrate(String text) { narrated.add(text); }
		@Override public boolean animated() { return true; }
		@Override public boolean simpleAnimation() { return false; }
	}

	private static final class CountingCanvas implements Canvas {
		int fills;

		@Override public void fill(int x1, int y1, int x2, int y2, int argb) { fills++; }
		@Override public void text(String text, int x, int y, int argb, boolean shadow) { }
		@Override public int textWidth(String text) { return text.length() * 6; }
		@Override public int lineHeight() { return 9; }
		@Override public String clip(String text, int maxWidth) { return text.substring(0, Math.max(0, Math.min(text.length(), maxWidth / 6))); }
		@Override public void flush() { }
		@Override public void scissor(int x1, int y1, int x2, int y2) { }
		@Override public void noScissor() { }
		@Override public void raise(float z) { }
		@Override public void push() { }
		@Override public void translate(float x, float y) { }
		@Override public void scale(float factor) { }
		@Override public void pop() { }
	}
}
