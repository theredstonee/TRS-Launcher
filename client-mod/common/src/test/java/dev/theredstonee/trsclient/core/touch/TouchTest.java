package dev.theredstonee.trsclient.core.touch;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ScaledCanvas;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TouchTest {

	@AfterEach
	void reset() {
		TouchMode.resetForTests(null);
		TouchKeyboard.setSinkForTests(null);
	}

	// --- Skalierung ---

	@Test
	void desktopIsNeverScaled() {
		TouchMode.resetForTests(Boolean.FALSE);
		assertFalse(TouchMode.enabled());
		assertEquals(1f, TouchMode.uiScale(1920, 1080));
		assertTrue(TouchMode.insetsGui().isZero());
	}

	@Test
	void propertyIsReadOnlyWhenTrue() {
		TouchMode.resetForTests(null);
		// In der Test-JVM ist trs.touch nicht gesetzt.
		assertFalse(TouchMode.enabled());
	}

	@Test
	void effectiveScaleKeepsMinimumVirtualSpace() {
		// Viel Platz: gewünschter Faktor.
		assertEquals(1.5f, TouchMode.effectiveScale(1.5f, 960, 540));
		// 600×270 GUI-Pixel (Handy, GUI-Skalierung 4): Höhe begrenzt → 270/200 = 1.35 → 1.25 (Achtel abgerundet).
		assertEquals(1.25f, TouchMode.effectiveScale(1.5f, 600, 270));
		// Zu klein für jede Vergrößerung.
		assertEquals(1f, TouchMode.effectiveScale(2f, 320, 200));
		assertEquals(1f, TouchMode.effectiveScale(2f, 200, 100));
		// Begrenzung 1..3 und Unsinn.
		assertEquals(3f, TouchMode.effectiveScale(9f, 4000, 4000));
		assertEquals(1f, TouchMode.effectiveScale(0.2f, 4000, 4000));
		assertEquals(1f, TouchMode.effectiveScale(Float.NaN, 4000, 4000));
		assertEquals(1f, TouchMode.effectiveScale(2f, 0, 0));
	}

	@Test
	void scalePropertyIsParsedAndClamped() {
		assertEquals(TouchMode.DEFAULT_SCALE, TouchMode.parseScale(null));
		assertEquals(TouchMode.DEFAULT_SCALE, TouchMode.parseScale("abc"));
		assertEquals(2f, TouchMode.parseScale(" 2 "));
		assertEquals(3f, TouchMode.parseScale("12"));
		assertEquals(1f, TouchMode.parseScale("0.5"));
		assertEquals(TouchMode.DEFAULT_SCALE, TouchMode.parseScale("NaN"));
	}

	// --- Sichere Fläche ---

	@Test
	void insetsAreParsedAndConvertedToGuiPixels() {
		SafeArea.Insets in = SafeArea.parse("88, 0, 88, 24");
		assertEquals(88, in.left);
		assertEquals(24, in.bottom);
		SafeArea.Insets gui = in.toGui(3.0);
		// aufgerundet: 88/3 = 29.33 → 30, 24/3 = 8
		assertEquals(30, gui.left);
		assertEquals(0, gui.top);
		assertEquals(30, gui.right);
		assertEquals(8, gui.bottom);
		assertTrue(SafeArea.parse(null).isZero());
		assertTrue(SafeArea.parse("1,2,3").isZero());
		assertTrue(SafeArea.parse("1,2,-3,4").isZero());
		assertTrue(SafeArea.parse("a,b,c,d").isZero());
		assertEquals(SafeArea.MAX_INSET_PX, SafeArea.parse("99999,0,0,0").left);
		assertEquals(3, SafeArea.parse("2.5;0;0;0").left);
	}

	@Test
	void clampKeepsElementsInsideSafeArea() {
		SafeArea.Insets in = new SafeArea.Insets(30, 5, 20, 8);
		assertArrayEquals(new int[]{30, 5}, SafeArea.clamp(0, 0, 50, 10, 400, 200, in));
		assertArrayEquals(new int[]{400 - 20 - 50, 200 - 8 - 10}, SafeArea.clamp(999, 999, 50, 10, 400, 200, in));
		// Passt nicht in die sichere Fläche → nur auf den Bildschirm.
		assertArrayEquals(new int[]{0, 0}, SafeArea.clamp(-5, -5, 390, 195, 400, 200, in));
	}

	@Test
	void snapUsesSafeEdgesInsteadOfScreenEdges() {
		SafeArea.Insets in = new SafeArea.Insets(30, 0, 30, 0);
		// Nahe am linken sicheren Rand → rastet bei 30 + MARGIN ein, Hilfslinie dort.
		SafeArea.Snap s = SafeArea.snap(36, 100, 40, 10, 400, 300, Collections.<int[]>emptyList(), 10, in);
		assertEquals(30 + SafeArea.MARGIN, s.x);
		assertEquals(30 + SafeArea.MARGIN, s.guideX);
		// In die Notch gezogen → begrenzt, keine Hilfslinie aus der Notch.
		SafeArea.Snap n = SafeArea.snap(0, 100, 40, 10, 400, 300, Collections.<int[]>emptyList(), 0, in);
		assertEquals(30, n.x);
		assertEquals(SafeArea.NO_GUIDE, n.guideX);
		// Rechter sicherer Rand.
		SafeArea.Snap r = SafeArea.snap(400 - 30 - 40 - 6, 100, 40, 10, 400, 300, null, 10, in);
		assertEquals(400 - 30 - SafeArea.MARGIN - 40, r.x);
		// Kante eines anderen Elements.
		List<int[]> others = Collections.singletonList(new int[]{100, 50, 60, 20});
		SafeArea.Snap o = SafeArea.snap(103, 200, 40, 10, 400, 300, others, 10, in);
		assertEquals(100, o.x);
	}

	// --- Scrollen mit Schwung ---

	@Test
	void dragProducesWheelNotchesWithWheelSign() {
		TouchScroller s = new TouchScroller();
		s.start(0);
		assertEquals(0, s.move(10f, 16));
		// 10 + 10 = 20 → eine Raste (14), Rest 6
		assertEquals(1, s.move(10f, 32));
		// Finger nach oben: Rad nach unten
		assertEquals(-2, s.move(-40f, 48));
	}

	@Test
	void flingContinuesAndStops() {
		TouchScroller s = new TouchScroller();
		s.start(0);
		long t = 0;
		for (int i = 0; i < 5; i++) {
			t += 16;
			s.move(-20f, t);
		}
		s.release(t + 5);
		assertTrue(s.flinging());
		int total = 0;
		int frames = 0;
		while (s.flinging() && frames < 1000) {
			total += s.tick(1f / 60f);
			frames++;
		}
		assertFalse(s.flinging());
		assertTrue(total < -5, "Schwung scrollt weiter: " + total);
		assertTrue(frames < 200, "Schwung endet: " + frames);
		assertEquals(0, s.tick(1f / 60f));
	}

	@Test
	void restingFingerHasNoFling() {
		TouchScroller s = new TouchScroller();
		s.start(0);
		s.move(-30f, 16);
		s.release(16 + TouchScroller.STILL_MS + 50);
		assertFalse(s.flinging());
		assertEquals(0, s.tick(0.1f));
	}

	@Test
	void flingDistanceMatchesExponentialIntegral() {
		TouchScroller s = new TouchScroller();
		s.start(0);
		s.move(-30f, 10);
		s.move(-30f, 20);
		float v = s.velocity();
		s.release(20);
		int total = 0;
		while (s.flinging()) total += s.tick(1f / 120f);
		// Ganzer Weg ≈ v·τ (bis zur Abbruchgeschwindigkeit etwas weniger).
		float expected = v * TouchScroller.TAU / TouchScroller.STEP;
		assertTrue(Math.abs(total - expected) <= Math.abs(expected) * 0.2f + 1, total + " vs " + expected);
	}

	// --- Gesten ---

	static final class Recorder implements TouchGestures.Target {
		final List<String> log = new ArrayList<>();
		boolean direct;
		boolean takesRightClick;

		@Override
		public boolean direct(double x, double y) {
			return direct;
		}

		@Override
		public boolean click(double x, double y, int button) {
			log.add("click" + button + "@" + (int) x + "," + (int) y);
			return button != 1 || takesRightClick;
		}

		@Override
		public boolean release(double x, double y, int button) {
			log.add("release" + button);
			return true;
		}

		@Override
		public boolean drag(double x, double y, int button) {
			log.add("drag" + button + "@" + (int) x + "," + (int) y);
			return true;
		}

		@Override
		public boolean scroll(double x, double y, double amount) {
			log.add("scroll" + (amount > 0 ? "+" : "-") + "@" + (int) x + "," + (int) y);
			return true;
		}
	}

	@Test
	void tapClicksAtTouchDownPointOnRelease() {
		Recorder r = new Recorder();
		TouchGestures g = new TouchGestures(r);
		g.down(50, 60, 0, 0);
		assertTrue(r.log.isEmpty(), "noch kein Klick beim Aufsetzen");
		g.move(52, 61, 0, 30);
		g.up(52, 61, 0, 80);
		assertEquals(Arrays.asList("click0@50,60", "release0"), r.log);
		assertFalse(g.active());
	}

	@Test
	void dragScrollsListUnderFingerWithoutClicking() {
		Recorder r = new Recorder();
		TouchGestures g = new TouchGestures(r);
		g.down(100, 100, 0, 0);
		g.move(100, 110, 0, 16);
		g.move(100, 125, 0, 32);
		g.up(100, 125, 0, 300);
		for (String e : r.log) assertTrue(e.startsWith("scroll+@100,100"), e);
		assertEquals(1, r.log.size());
		assertFalse(g.showsPointer());
	}

	@Test
	void longPressIsRightClick() {
		Recorder r = new Recorder();
		r.takesRightClick = true;
		TouchGestures g = new TouchGestures(r);
		g.down(10, 20, 0, 0);
		g.tick(TouchGestures.LONG_PRESS_MS - 1, 0.016f);
		assertTrue(r.log.isEmpty());
		g.tick(TouchGestures.LONG_PRESS_MS, 0.016f);
		assertEquals(Arrays.asList("click1@10,20", "release1"), r.log);
		g.up(10, 20, 0, 900);
		assertEquals(2, r.log.size(), "kein Linksklick nach langem Druck");
	}

	@Test
	void longPressWithoutContextShowsTooltipWhileHeld() {
		Recorder r = new Recorder();
		TouchGestures g = new TouchGestures(r);
		g.down(10, 20, 0, 0);
		g.tick(600, 0.016f);
		assertTrue(g.showsPointer());
		assertEquals(10, (int) g.pointerX());
		g.up(10, 20, 0, 900);
		assertFalse(g.showsPointer());
		assertFalse(r.log.contains("click0@10,20"));
	}

	@Test
	void directTargetsGetRawPointer() {
		Recorder r = new Recorder();
		r.direct = true;
		TouchGestures g = new TouchGestures(r);
		g.down(10, 10, 0, 0);
		g.move(30, 10, 0, 16);
		g.tick(2000, 0.016f);
		g.up(30, 10, 0, 2000);
		assertEquals(Arrays.asList("click0@10,10", "drag0@30,10", "release0"), r.log);
	}

	@Test
	void otherButtonsPassStraightThrough() {
		Recorder r = new Recorder();
		TouchGestures g = new TouchGestures(r);
		g.down(5, 5, 1, 0);
		g.up(5, 5, 1, 10);
		assertEquals(Arrays.asList("click1@5,5", "release1"), r.log);
	}

	@Test
	void newTouchStopsFling() {
		Recorder r = new Recorder();
		TouchGestures g = new TouchGestures(r);
		g.down(0, 100, 0, 0);
		long t = 0;
		for (int i = 0; i < 6; i++) {
			t += 16;
			g.move(0, 100 - 20 * (i + 1), 0, t);
		}
		g.up(0, -20, 0, t + 5);
		g.tick(t + 21, 0.016f);
		int before = r.log.size();
		g.down(0, 0, 0, t + 40);
		g.tick(t + 56, 0.016f);
		g.tick(t + 72, 0.016f);
		assertEquals(before, r.log.size());
	}

	// --- Tastatur ---

	static final class SinkLog implements TouchKeyboard.Sink {
		final List<String> log = new ArrayList<>();

		@Override
		public void changed(boolean show, String field, int seq) {
			log.add((show ? "show:" + field : "hide") + "#" + seq);
		}
	}

	@Test
	void keyboardShowsAtOnceAndHidesAfterDelay() {
		SinkLog out = new SinkLog();
		TouchKeyboard.State st = new TouchKeyboard.State();
		st.update(TouchKeyboard.FIELD_CHAT, out);
		st.update(TouchKeyboard.FIELD_CHAT, out);
		assertEquals(Collections.singletonList("show:chat#1"), out.log);
		st.update(TouchKeyboard.FIELD_TEXT, out);
		assertEquals("show:text#2", out.log.get(1));
		// kurzer Wechsel ohne Feld: kein Flackern
		st.update(null, out);
		st.update(TouchKeyboard.FIELD_TEXT, out);
		assertEquals(2, out.log.size());
		for (int i = 0; i < TouchKeyboard.HIDE_DELAY_TICKS; i++) st.update(null, out);
		assertEquals("hide#3", out.log.get(2));
		st.update(null, out);
		assertEquals(3, out.log.size());
	}

	@Test
	void keyboardMessagesAreFixedJson() {
		assertEquals("{\"type\":\"keyboard.show\",\"field\":\"chat\"}", TouchKeyboard.linkLine(true, "chat"));
		assertEquals("{\"type\":\"keyboard.hide\"}", TouchKeyboard.linkLine(false, null));
		// Unbekannte Feldart wird nie roh weitergereicht.
		assertEquals("{\"type\":\"keyboard.show\",\"field\":\"text\"}", TouchKeyboard.linkLine(true, "x\"}"));
		assertEquals("{\"version\":1,\"keyboard\":true,\"field\":\"sign\",\"seq\":4}", TouchKeyboard.fileJson(true, "sign", 4));
		assertEquals("{\"version\":1,\"keyboard\":false,\"field\":null,\"seq\":5}", TouchKeyboard.fileJson(false, null, 5));
	}

	@Test
	void focusedTextFieldBelongsToItsScreen() {
		TouchMode.resetForTests(Boolean.TRUE);
		SinkLog out = new SinkLog();
		TouchKeyboard.setSinkForTests(out);
		Object screenA = new Object();
		Object screenB = new Object();
		TextInput input = new TextInput(10);
		Object before = TouchKeyboard.enter(screenA);
		input.setFocused(true);
		TouchKeyboard.leave(before);
		assertEquals(TouchKeyboard.FIELD_TEXT, TouchKeyboard.fieldOf(screenA));
		assertNull(TouchKeyboard.fieldOf(screenB));
		input.setFocused(false);
		assertNull(TouchKeyboard.fieldOf(screenA));
	}

	@Test
	void desktopNeverTracksFocus() {
		TouchMode.resetForTests(Boolean.FALSE);
		SinkLog out = new SinkLog();
		TouchKeyboard.setSinkForTests(out);
		Object screen = new Object();
		Object before = TouchKeyboard.enter(screen);
		new TextInput(5).setFocused(true);
		TouchKeyboard.leave(before);
		assertNull(TouchKeyboard.fieldOf(screen));
		TouchKeyboard.tick(TouchKeyboard.FIELD_CHAT, 0);
		assertTrue(out.log.isEmpty());
	}

	@Test
	void tickPrefersTrsFieldWhileReported() {
		TouchMode.resetForTests(Boolean.TRUE);
		SinkLog out = new SinkLog();
		TouchKeyboard.setSinkForTests(out);
		TouchKeyboard.reportUi(TouchKeyboard.FIELD_MULTILINE, 1000);
		TouchKeyboard.tick(null, 1100);
		assertEquals("show:multiline#1", out.log.get(0));
		// Bericht veraltet (Bildschirm zu) → Vanilla-Chat zählt
		TouchKeyboard.tick(TouchKeyboard.FIELD_CHAT, 1000 + TouchKeyboard.UI_REPORT_MS + 1);
		assertEquals("show:chat#2", out.log.get(1));
	}

	// --- Feste Tasten ---

	@Test
	void fixedKeysFireOncePerPress() {
		TouchRuntime.Edges e = new TouchRuntime.Edges();
		assertNull(e.update(false, false, false));
		assertEquals(TouchRuntime.Action.MENU, e.update(true, false, false));
		assertNull(e.update(true, false, false));
		assertNull(e.update(false, false, false));
		assertEquals(TouchRuntime.Action.EMOTE_WHEEL, e.update(false, true, false));
		assertEquals(TouchRuntime.Action.HUD_EDITOR, e.update(false, true, true));
		assertEquals(TouchRuntime.Action.MENU, e.update(true, true, true));
	}

	// --- Touch-Layout ---

	@Test
	void touchLayoutKeepsFreeSpotsAndMovesBlockedOnes() {
		int w = 600;
		int h = 300;
		List<int[]> zones = TouchLayout.zones(w, h);
		SafeArea.Insets in = new SafeArea.Insets(20, 0, 20, 0);
		List<int[]> rects = new ArrayList<>();
		rects.add(new int[]{250, 20, 60, 10}); // oben Mitte: frei
		rects.add(new int[]{2, 280, 50, 12}); // unten links: unter dem Joystick
		rects.add(new int[]{540, 2, 50, 30}); // oben rechts: Leiste + Notch
		rects.add(new int[]{250, 20, 60, 10}); // gleiche Stelle wie das erste
		int[][] placed = TouchLayout.place(rects, w, h, zones, in);
		assertArrayEquals(new int[]{250, 20}, placed[0]);
		for (int i = 0; i < rects.size(); i++) {
			int[] r = rects.get(i);
			assertTrue(TouchLayout.free(placed[i][0], placed[i][1], r[2], r[3], w, h, zones, Collections.<int[]>emptyList(), in),
					"Element " + i + " frei von Zonen/Notch");
			for (int j = 0; j < i; j++) {
				int[] o = {placed[j][0], placed[j][1], rects.get(j)[2], rects.get(j)[3]};
				assertFalse(TouchLayout.overlaps(placed[i][0], placed[i][1], r[2], r[3], o, 0), i + " überlappt " + j);
			}
		}
		// Das blockierte Element landet nahe seinem Wunsch (linke Hälfte, oberhalb des Joysticks).
		assertTrue(placed[1][0] < w / 2 && placed[1][1] < h / 2, Arrays.toString(placed[1]));
	}

	@Test
	void zonesCoverOverlayDefaults() {
		List<int[]> z = TouchLayout.zones(1000, 500);
		assertEquals(TouchLayout.ZONES.length, z.size());
		assertArrayEquals(new int[]{0, 250, 320, 250}, z.get(0));
	}

	// --- Vergrößerte Zeichenfläche ---

	static final class RecCanvas implements Canvas {
		final List<String> log = new ArrayList<>();

		@Override public void fill(int x1, int y1, int x2, int y2, int argb) { log.add("fill " + x1 + "," + y1); }
		@Override public void text(String text, int x, int y, int argb, boolean shadow) { }
		@Override public int textWidth(String text) { return text.length() * 6; }
		@Override public int lineHeight() { return 9; }
		@Override public String clip(String text, int maxWidth) { return text; }
		@Override public void flush() { }
		@Override public void scissor(int x1, int y1, int x2, int y2) { log.add("scissor " + x1 + "," + y1 + "," + x2 + "," + y2); }
		@Override public void noScissor() { log.add("noScissor"); }
		@Override public void raise(float z) { }
		@Override public void push() { log.add("push"); }
		@Override public void translate(float x, float y) { }
		@Override public void scale(float factor) { log.add("scale " + factor); }
		@Override public void pop() { log.add("pop"); }
	}

	@Test
	void scissorIsSetInScreenSpaceWithScaleUndone() {
		RecCanvas raw = new RecCanvas();
		Canvas c = ScaledCanvas.of(raw, 2f);
		c.scissor(10, 11, 20, 21);
		assertEquals(Arrays.asList("push", "scale 0.5", "scissor 20,22,40,42", "pop"), raw.log);
		assertTrue(ScaledCanvas.of(raw, 1f) == raw);
		assertTrue(ScaledCanvas.unwrap(c) == raw);
	}

	// --- Bildschirm im Touch-Modus ---

	static final class TestScreen extends UiScreen {
		final List<String> log = new ArrayList<>();
		int lastW;
		int lastMouseX;

		@Override
		protected void draw(Canvas c, int width, int height, int mouseX, int mouseY, float dt) {
			lastW = width;
			lastMouseX = mouseX;
			hits.add(0, 0, 100, 100, () -> log.add("tap"));
		}

		@Override
		protected void onClosed() {
		}

		@Override
		public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
			log.add("scroll" + (amount > 0 ? "+" : "-"));
			return true;
		}
	}

	@Test
	void desktopScreenPassesInputUnchanged() {
		TouchMode.resetForTests(Boolean.FALSE);
		TestScreen s = new TestScreen();
		RecCanvas c = new RecCanvas();
		s.render(c, 960, 540, 50, 50);
		assertEquals(960, s.lastW);
		assertEquals(50, s.lastMouseX);
		assertFalse(c.log.contains("scale 1.5"));
		assertTrue(s.inputClick(50, 50, 0));
		assertEquals(Collections.singletonList("tap"), s.log, "Klick sofort beim Drücken");
	}

	@Test
	void touchScreenIsScaledAndTapsOnRelease() {
		TouchMode.resetForTests(Boolean.TRUE);
		TestScreen s = new TestScreen();
		RecCanvas c = new RecCanvas();
		s.render(c, 960, 540, 50, 50);
		assertTrue(c.log.contains("scale 1.5"));
		assertEquals(640, s.lastW);
		assertTrue(s.lastMouseX < -1000, "kein Hover ohne Finger");
		// 140 Bildschirm-Pixel = 93 virtuelle → in der Klickfläche (0..100)
		s.inputClick(140, 140, 0);
		assertTrue(s.log.isEmpty());
		s.inputRelease(140, 140, 0);
		assertEquals(Collections.singletonList("tap"), s.log);
		// Außerhalb (160 / 1.5 = 106) kein Treffer
		s.inputClick(160, 10, 0);
		s.inputRelease(160, 10, 0);
		assertEquals(1, s.log.size());
		// Ziehen scrollt
		s.inputClick(30, 30, 0);
		s.inputDrag(30, 60, 0);
		s.inputRelease(30, 60, 0);
		assertTrue(s.log.contains("scroll+"));
		assertEquals(1, Collections.frequency(s.log, "tap"));
	}
}
