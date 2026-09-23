package dev.theredstonee.trsclient.core.ui.title;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.PixelFont;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * TRS-Startbildschirm, versionsunabhängig: animierte Redstone-Schaltung als Hintergrund, der
 * Pixel-Schriftzug mit pulsierendem Leuchten und Knöpfe als Redstone-Lampen. Eine Lampe geht an,
 * sobald die Maus darauf liegt oder sie per Tastatur (Tab/Pfeile, Enter) ausgewählt ist – das Signal
 * läuft dabei sichtbar von einer Fackel über die Leitung in die Lampe.
 */
public final class TitleUi extends UiScreen {
	/** Gemeinsamer Zeitbezug – die Animation läuft beim Zurückkehren weiter statt neu zu starten. */
	private static final long EPOCH = System.nanoTime();
	private static final int BTN_W = 200;
	private static final int BTN_H = 20;
	private static final int GAP = 5;
	private static final int HALF = (BTN_W - 6) / 2;
	/** Zeit, bis das Signal die Lampe erreicht (Sekunden). */
	private static final float SIGNAL_IN = 0.11f;
	private static final float SIGNAL_OUT = 0.08f;
	/** Verzögerung zwischen Drücken und Aktion – so ist das Aufblitzen sichtbar. */
	private static final float PRESS_DELAY = 0.07f;
	private static final String CLASSIC = "Klassischer Titelbildschirm";

	/** Ein Lampen-Knopf. */
	private static final class Lamp {
		final String label;
		final Runnable action;
		/** Wird von rechts gespeist (rechte Hälfte einer Zeile). */
		final boolean right;
		int x;
		int y;
		int w;
		/** Signal auf dem Weg zur Lampe (0..1). */
		float power;
		float flash;

		Lamp(String label, boolean right, Runnable action) {
			this.label = label;
			this.right = right;
			this.action = action;
		}
	}

	/** Text wird zuletzt gezeichnet: Minecraft zeichnet bei jedem Text den Puffer – so nur einmal je Bild. */
	private static final class Label {
		final String text;
		final int x;
		final int y;
		final int color;
		final boolean shadow;

		Label(String text, int x, int y, int color, boolean shadow) {
			this.text = text;
			this.x = x;
			this.y = y;
			this.color = color;
			this.shadow = shadow;
		}
	}

	private final List<Label> labels = new ArrayList<Label>();
	private final TitleHost host;
	private final CircuitScene scene = new CircuitScene();
	private final List<Lamp> lamps = new ArrayList<Lamp>();
	private final int[][] reserved = new int[2][4];
	/** Tastatur-Auswahl: Index der Lampe, {@code lamps.size()} = Link, -1 = keine. */
	private int focus = -1;
	private int pending = -1;
	private float pendingIn;
	private float linkLit;
	private boolean sceneEnabled = true;
	private float uiMicros;
	private float frameMs;
	private int frames;

	public TitleUi(TitleHost host) {
		this.host = host;
		scene.setSimple(host.simpleAnimation());
		final TitleHost h = host;
		lamps.add(new Lamp("Einzelspieler", false, new Runnable() {
			@Override
			public void run() {
				h.singleplayer();
			}
		}));
		lamps.add(new Lamp("Mehrspieler", false, new Runnable() {
			@Override
			public void run() {
				h.multiplayer();
			}
		}));
		lamps.add(new Lamp("Einstellungen", false, new Runnable() {
			@Override
			public void run() {
				h.options();
			}
		}));
		lamps.add(new Lamp("TRS-Menü", true, new Runnable() {
			@Override
			public void run() {
				h.trsMenu();
			}
		}));
		if (host.hasMods()) {
			lamps.add(new Lamp("Mods", false, new Runnable() {
				@Override
				public void run() {
					h.mods();
				}
			}));
		}
		lamps.add(new Lamp("Beenden", host.hasMods(), new Runnable() {
			@Override
			public void run() {
				h.quit();
			}
		}));
	}

	// --- Für den Selbsttest ---

	/** Wählt eine Lampe wie per Tastatur aus (ohne Vorlesen); -1 = keine. */
	public void focus(int index) {
		focus = index < -1 || index > lamps.size() ? -1 : index;
	}

	/** Lage einer Lampe {x, y, w, h} im letzten Bild oder null (Selbsttest: echte Klicks). */
	public int[] buttonRect(String label) {
		if (frames == 0) return null;
		for (int i = 0; i < lamps.size(); i++) {
			Lamp l = lamps.get(i);
			if (l.label.equals(label)) return new int[]{l.x, l.y, l.w, BTN_H};
		}
		return null;
	}

	/** Hintergrund-Animation an/aus (Selbsttest: Kosten der Animation messen). */
	public void setSceneEnabled(boolean enabled) {
		sceneEnabled = enabled;
	}

	/** Mittlere CPU-Zeit des Hintergrunds je Bild in Mikrosekunden. */
	public float sceneMicros() {
		return scene.averageMicros();
	}

	/** Mittlere CPU-Zeit des ganzen Bildschirms je Bild in Mikrosekunden. */
	public float frameMicros() {
		return uiMicros;
	}

	/** Mittlerer Abstand zwischen zwei Bildern in Millisekunden (gesamte Bildzeit des Spiels). */
	public float frameMillis() {
		return frameMs;
	}

	public boolean sparseScene() {
		return scene.isSimple();
	}

	// --- Zeichnen ---

	@Override
	protected void draw(Canvas raw, int width, int height, int mouseX, int mouseY, float dt) {
		long start = System.nanoTime();
		Theme t = Theme.get();
		boolean animated = host.animated();
		float time = animated ? (System.nanoTime() - EPOCH) / 1_000_000_000f : 1.35f;
		boolean compact = height < 300;

		// Maße: Schriftzug, Untertitel, vier Knopfzeilen – als Block senkrecht zentriert.
		int big = compact ? 5 : (height >= 440 ? 9 : 8);
		int small = compact ? 2 : 3;
		int logoH = PixelFont.HEIGHT * big;
		int subH = PixelFont.HEIGHT * small;
		int gapA = compact ? 6 : 10;
		int gapB = compact ? 12 : 20;
		int buttonsH = 4 * BTN_H + 3 * GAP;
		int blockH = logoH + gapA + subH + gapB + buttonsH;
		int top = Math.max(compact ? 8 : 14, (height - 14 - blockH) / 2);
		int colX = (width - BTN_W) / 2;
		int rowsY = top + logoH + gapA + subH + gapB;
		layout(colX, rowsY);

		reserved[0][0] = colX - 22;
		reserved[0][1] = top - 8;
		reserved[0][2] = colX + BTN_W + 22;
		reserved[0][3] = rowsY + buttonsH + 6;
		reserved[1][0] = 0;
		reserved[1][1] = height - 14;
		reserved[1][2] = width;
		reserved[1][3] = height;

		Canvas bg = FadeCanvas.unwrap(raw);
		if (sceneEnabled) scene.draw(bg, width, height, time, reserved);
		else bg.fill(0, 0, width, height, 0xFF18181C);
		readingPlate(bg, colX - 26, top - 6, BTN_W + 52, rowsY + buttonsH + 6 - (top - 6));

		Canvas c = FadeCanvas.of(raw, alpha());
		update(dt, mouseX, mouseY);
		circuitry(c, t, colX, top, rowsY, time);
		for (int i = 0; i < lamps.size(); i++) {
			Lamp l = lamps.get(i);
			float lit = lampLit(l);
			int dy = l.flash > 0.45f ? 1 : 0;
			Redstone.lamp(c, l.x, l.y + dy, l.w, BTN_H, lit, l.flash);
			String s = c.textWidth(l.label) <= l.w - 10 ? l.label : c.clip(l.label, l.w - 10);
			labels.add(new Label(s, l.x + (l.w - c.textWidth(s)) / 2, l.y + dy + (BTN_H - 8) / 2, Redstone.lampTextColor(lit), lit < 0.35f));
			final int index = i;
			hits.add(l.x, l.y, l.w, BTN_H, new Runnable() {
				@Override
				public void run() {
					activate(index);
				}
			});
		}
		logo(c, t, width, top, big, small, logoH, gapA, time, animated);
		footer(c, t, width, height, mouseX, mouseY, dt);
		for (int i = 0; i < labels.size(); i++) {
			Label label = labels.get(i);
			c.text(label.text, label.x, label.y, label.color, label.shadow);
		}
		labels.clear();

		if (pending >= 0) {
			pendingIn -= dt;
			if (pendingIn <= 0) {
				int run = pending;
				pending = -1;
				if (run < lamps.size()) lamps.get(run).action.run();
				else host.classicTitle();
			}
		}
		float micros = (System.nanoTime() - start) / 1000f;
		frames++;
		uiMicros = frames == 1 ? micros : uiMicros + (micros - uiMicros) * 0.05f;
		frameMs = frames == 1 ? dt * 1000f : frameMs + (dt * 1000f - frameMs) * 0.05f;
	}

	private void layout(int colX, int rowsY) {
		int row = 0;
		int col = 0;
		for (int i = 0; i < lamps.size(); i++) {
			Lamp l = lamps.get(i);
			int y = rowsY + row * (BTN_H + GAP);
			boolean full = i < 2;
			if (full) {
				l.x = colX;
				l.w = BTN_W;
				row++;
				continue;
			}
			boolean last = i == lamps.size() - 1;
			if (col == 0 && last) {
				// Allein in der Zeile (ohne Mod-Liste): mittig.
				l.x = colX + (BTN_W - HALF) / 2;
				l.w = HALF;
				l.y = y;
				row++;
				continue;
			}
			l.x = col == 0 ? colX : colX + BTN_W - HALF;
			l.w = HALF;
			l.y = y;
			if (col == 1) row++;
			col = 1 - col;
		}
		for (int i = 0; i < Math.min(2, lamps.size()); i++) lamps.get(i).y = rowsY + i * (BTN_H + GAP);
	}

	private void update(float dt, int mx, int my) {
		for (int i = 0; i < lamps.size(); i++) {
			Lamp l = lamps.get(i);
			boolean hover = inside(mx, my, l.x, l.y, l.w, BTN_H);
			boolean on = hover || focus == i || pending == i;
			l.power = on ? Math.min(1f, l.power + dt / SIGNAL_IN) : Math.max(0f, l.power - dt / SIGNAL_OUT);
			l.flash = Math.max(0f, l.flash - dt / 0.25f);
		}
	}

	private static float lampLit(Lamp l) {
		return ColorMath.clamp01((l.power - 0.75f) / 0.25f);
	}

	/** Dunkle, weich auslaufende Fläche hinter Schriftzug und Knöpfen – die Schrift bleibt lesbar. */
	private static void readingPlate(Canvas c, int x, int y, int w, int h) {
		for (int i = 0; i < 5; i++) {
			int d = (4 - i) * 7;
			Redstone.block(c, x - d, y - d, w + 2 * d, h + 2 * d, 0x1E000000);
		}
	}

	/** Leitungen neben den Knöpfen: Fackel oben, Bus nach unten, Abzweig in jede Lampe. */
	private void circuitry(Canvas c, Theme t, int colX, int top, int rowsY, float time) {
		int leftBus = colX - 14;
		int rightBus = colX + BTN_W + 12;
		int leftTop = rowsY - 1;
		int rightTop = Integer.MAX_VALUE;
		int leftBottom = leftTop;
		int rightBottom = 0;
		for (int i = 0; i < lamps.size(); i++) {
			Lamp l = lamps.get(i);
			int cy = l.y + BTN_H / 2 - 1;
			if (l.right) {
				rightTop = Math.min(rightTop, l.y - 1);
				rightBottom = Math.max(rightBottom, cy + 2);
			} else {
				leftBottom = Math.max(leftBottom, cy + 2);
			}
		}
		// Unbestromte Leitungen
		Redstone.dustV(c, leftBus, leftTop, leftBottom, t.dustOff, 0f);
		if (rightBottom > 0) Redstone.dustV(c, rightBus, rightTop, rightBottom, t.dustOff, 0f);
		for (int i = 0; i < lamps.size(); i++) {
			Lamp l = lamps.get(i);
			int cy = l.y + BTN_H / 2 - 1;
			if (l.right) Redstone.dustH(c, l.x + l.w, rightBus + 2, cy, t.dustOff, 0f);
			else Redstone.dustH(c, leftBus, l.x, cy, t.dustOff, 0f);
		}
		// Signal zu den gewählten Lampen – die Stärke sinkt alle 12 Pixel um eins.
		for (int i = 0; i < lamps.size(); i++) {
			Lamp l = lamps.get(i);
			if (l.power <= 0f) continue;
			int cy = l.y + BTN_H / 2 - 1;
			int busX = l.right ? rightBus : leftBus;
			int busTop = l.right ? rightTop : leftTop;
			int vertical = Math.max(0, cy - busTop);
			int horizontal = l.right ? Math.max(0, rightBus + 2 - (l.x + l.w)) : Math.max(0, l.x - leftBus);
			float lit = l.power * (vertical + horizontal);
			float done = 0;
			for (int s = 0; s < vertical && done < lit; s += 12) {
				int len = (int) Math.min(Math.min(12, vertical - s), lit - done);
				int level = 15 - (int) (done / 12);
				Redstone.dustV(c, busX, busTop + s, busTop + s + len, t.dust(level), level / 15f);
				done += len;
			}
			for (int s = 0; s < horizontal && done < lit; s += 12) {
				int len = (int) Math.min(Math.min(12, horizontal - s), lit - done);
				int level = 15 - (int) (done / 12);
				if (l.right) Redstone.dustH(c, rightBus + 2 - s - len, rightBus + 2 - s, cy, t.dust(level), level / 15f);
				else Redstone.dustH(c, busX + s, busX + s + len, cy, t.dust(level), level / 15f);
				done += len;
			}
		}
		// Fackeln als Quellen – immer an, sie flackern leicht.
		float f1 = flicker(time, 0.7f);
		Redstone.torch(c, leftBus + 1, leftTop, 1f, f1);
		if (rightBottom > 0) Redstone.torch(c, rightBus + 1, rightTop, 1f, flicker(time, 3.1f));
	}

	private static float flicker(float time, float seed) {
		double a = Math.sin(time * 8.3 + seed) * 0.6 + Math.sin(time * 21.1 + seed * 2.3) * 0.4;
		return ColorMath.clamp01((float) (0.5 + a * 0.5));
	}

	private void logo(Canvas c, Theme t, int width, int top, int big, int small, int logoH, int gapA, float time, boolean animated) {
		int logoW = PixelFont.width("TRS") * big;
		int x = (width - logoW) / 2;
		int y = top;
		float pulse = animated ? 0.7f + 0.3f * (float) (0.5 + 0.5 * Math.sin(time * Math.PI * 2 / 3.4)) : 0.85f;
		List<int[]> rects = PixelFont.rects("TRS");
		// Leuchten: jede Pixelzeile zweimal vergrößert und sehr durchsichtig.
		int glowNear = ColorMath.withAlpha(t.glow, Math.round(46 * pulse));
		int glowFar = ColorMath.withAlpha(t.glow, Math.round(12 * pulse));
		int g1 = Math.max(2, big / 2);
		int g2 = big;
		for (int[] r : rects) {
			c.fill(x + r[0] * big - g2, y + r[1] * big - g2 / 2, x + r[2] * big + g2, y + r[3] * big + g2 / 2, glowFar);
		}
		for (int[] r : rects) {
			c.fill(x + r[0] * big - g1, y + r[1] * big - g1, x + r[2] * big + g1, y + r[3] * big + g1, glowNear);
		}
		// Schlagschatten, dann die Pixel: oberste Zeile heller, unterste dunkler (Pixel-Kante).
		int shadow = ColorMath.lerp(ColorMath.scaleRgb(t.accent, 0.28f), 0xFF000000, 0.35f);
		int off = Math.max(2, big / 2);
		for (int[] r : rects) {
			c.fill(x + r[0] * big + off, y + r[1] * big + off, x + r[2] * big + off, y + r[3] * big + off, shadow);
		}
		int face = t.accent;
		int light = ColorMath.lerp(face, 0xFFFFFFFF, 0.3f);
		int dark = ColorMath.scaleRgb(face, 0.72f);
		for (int[] r : rects) {
			int color = r[1] == 0 ? light : (r[1] == PixelFont.HEIGHT - 1 ? dark : face);
			c.fill(x + r[0] * big, y + r[1] * big, x + r[2] * big, y + r[3] * big, color);
		}
		// Glanzpunkt: ein heller Pixel oben links in jedem Buchstaben.
		int sheen = ColorMath.withAlpha(0xFFFFFFFF, Math.round(90 * pulse));
		c.fill(x, y, x + Math.max(1, big / 3), y + Math.max(1, big / 3), sheen);

		// "CLIENT" mit einer kleinen Lampe daneben
		int subW = PixelFont.width("CLIENT") * small;
		int sx = (width - subW) / 2;
		int sy = top + logoH + gapA;
		for (int[] r : PixelFont.rects("CLIENT")) {
			c.fill(sx + r[0] * small, sy + r[1] * small, sx + r[2] * small, sy + r[3] * small, 0xFFDAD3CC);
		}
		int pip = small * 3 + 1;
		Redstone.pip(c, sx + subW + small * 3, sy + (PixelFont.HEIGHT * small - pip) / 2, pip, pulse);
	}

	private void footer(Canvas c, Theme t, int width, int height, int mx, int my, float dt) {
		int cw = c.textWidth(CLASSIC);
		int lx = width - cw - 4;
		// Versionszeile weicht dem Link aus (schmale Fenster).
		String version = host.versionLine();
		int room = lx - 18;
		if (c.textWidth(version) > room) version = room > 20 ? c.clip(version, room - c.textWidth("…")) + "…" : "";
		if (!version.isEmpty()) labels.add(new Label(version, 4, height - 10, 0xFF8F8B89, false));
		boolean hover = inside(mx, my, lx - 10, height - 12, cw + 10, 12);
		boolean on = hover || focus == lamps.size();
		linkLit = on ? Math.min(1f, linkLit + dt / 0.08f) : Math.max(0f, linkLit - dt / 0.08f);
		Redstone.pip(c, lx - 9, height - 9, 5, linkLit);
		labels.add(new Label(CLASSIC, lx, height - 10, ColorMath.lerp(0xFF8F8B89, t.lampOn, linkLit), false));
		hits.add(lx - 10, height - 12, cw + 14, 12, new Runnable() {
			@Override
			public void run() {
				activate(lamps.size());
			}
		});
	}

	// --- Bedienung ---

	private void activate(int index) {
		if (pending >= 0) return;
		host.playClick();
		if (index < lamps.size()) lamps.get(index).flash = 1f;
		pending = index;
		pendingIn = PRESS_DELAY;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		boolean hit = super.mouseClicked(mouseX, mouseY, button);
		if (!hit) focus = -1;
		return hit;
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		int count = lamps.size() + 1;
		switch (key) {
			case TAB:
				moveFocus(shift ? -1 : 1, count);
				return true;
			case DOWN:
			case RIGHT:
				moveFocus(1, count);
				return true;
			case UP:
			case LEFT:
				moveFocus(-1, count);
				return true;
			case ENTER:
				if (focus < 0) return false;
				activate(focus);
				return true;
			default:
				return false;
		}
	}

	private void moveFocus(int step, int count) {
		if (focus < 0) focus = step > 0 ? 0 : count - 1;
		else focus = (focus + step + count) % count;
		host.narrate((focus < lamps.size() ? lamps.get(focus).label : CLASSIC) + ", Schaltfläche");
	}

	/** Der Startbildschirm schließt nicht (Esc tut nichts). */
	@Override
	public void requestClose() {
	}

	@Override
	protected void onClosed() {
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
