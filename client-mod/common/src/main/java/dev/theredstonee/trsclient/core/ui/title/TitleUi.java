package dev.theredstonee.trsclient.core.ui.title;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.skin.PlayerLook;
import dev.theredstonee.trsclient.core.skin.SkinModel;
import dev.theredstonee.trsclient.core.skin.SkinModelSpec;
import dev.theredstonee.trsclient.core.ui.Anim;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Icons;
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
 *
 * <p>Links steht die eigene Spielerfigur auf einer sich langsam drehenden Redstone-Drehscheibe (ziehen dreht sie),
 * darunter Name und „Konto“; rechts eine Leiste aus Lampen mit Symbolen (Garderobe, Konten, Freunde, Clips/Bilder,
 * TRS-Einstellungen). Bereiche, die es noch nicht gibt, melden „Kommt bald“. In schmalen Fenstern wird die Leiste
 * zu Symbolen (bzw. einer Zeile oben rechts) und die Figur entfällt.
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
	/** Link unten rechts (Übersetzung beim Erstellen – der Startbildschirm wird je Öffnen neu gebaut). */
	private final String classic;

	/** Ein Lampen-Knopf. */
	private static final class Lamp {
		/** Feste ID für Selbsttest/Verknüpfungen ("singleplayer", "trsMenu" …). */
		final String id;
		final String label;
		final Runnable action;
		/** Wird von rechts gespeist (rechte Hälfte einer Zeile). */
		final boolean right;
		/** Symbol (Seitenleiste) oder null. */
		String icon;
		int x;
		int y;
		int w;
		int h = BTN_H;
		/** Signal auf dem Weg zur Lampe (0..1). */
		float power;
		float flash;

		Lamp(String id, boolean right, Runnable action) {
			this.id = id;
			this.label = I18n.tr("title." + id);
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

	/** Darstellung der Seitenleiste. */
	static final int SIDE_FULL = 0;
	static final int SIDE_ICONS = 1;
	static final int SIDE_ROW = 2;
	/** Kamera-Neigung der Figur und passende Stauchung der Drehscheibe. */
	private static final float FIGURE_PITCH = 14f;
	private static final float TABLE_SQUASH = 0.26f;

	private final List<Label> labels = new ArrayList<Label>();
	private final TitleHost host;
	/** Seitenleiste rechts (Lampen mit Symbol). */
	private final List<Lamp> side = new ArrayList<Lamp>();
	private final Turntable turntable = new Turntable();
	private final SkinModel model = new SkinModel();
	private final SkinModelSpec spec = new SkinModelSpec();
	private final String accountLabel;
	private final String rotateHint;
	private int sideMode = SIDE_FULL;
	/** Konto-Knopf unter der Figur (w = 0: ausgeblendet). */
	private int accX;
	private int accY;
	private int accW;
	private float accPower;
	private float accFlash;
	/** Figur sichtbar? (Fläche für das Ziehen) */
	private boolean figureShown;
	private float headYaw;
	private float headPitch;
	private String toast;
	private float toastLeft;
	private float modelMicros;
	private int modelFrames;
	private final CircuitScene scene = new CircuitScene();
	private final List<Lamp> lamps = new ArrayList<Lamp>();
	private final int[][] reserved = new int[4][4];
	/**
	 * Tastatur-Auswahl: 0.. Mitte, dann Seitenleiste, dann Konto-Knopf, zuletzt der Link ({@link #linkIndex()}),
	 * -1 = keine.
	 */
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
		I18n.refresh();
		this.classic = I18n.tr("title.classic");
		this.accountLabel = I18n.tr("title.account");
		this.rotateHint = I18n.tr("title.rotateHint");
		scene.setSimple(host.simpleAnimation());
		final TitleHost h = host;
		sideLamp("wardrobe", "shirt", new Area() {
			@Override
			public boolean open() {
				return h.openWardrobe();
			}
		});
		sideLamp("accounts", "profile", new Area() {
			@Override
			public boolean open() {
				return h.openAccounts();
			}
		});
		sideLamp("friends", "friends", new Area() {
			@Override
			public boolean open() {
				return h.openFriends();
			}
		});
		sideLamp("clips", "image", new Area() {
			@Override
			public boolean open() {
				return h.openClips();
			}
		});
		sideLamp("trsSettings", "gear", new Area() {
			@Override
			public boolean open() {
				h.trsMenu();
				return true;
			}
		});
		lamps.add(new Lamp("singleplayer", false, new Runnable() {
			@Override
			public void run() {
				h.singleplayer();
			}
		}));
		lamps.add(new Lamp("multiplayer", false, new Runnable() {
			@Override
			public void run() {
				h.multiplayer();
			}
		}));
		lamps.add(new Lamp("options", false, new Runnable() {
			@Override
			public void run() {
				h.options();
			}
		}));
		lamps.add(new Lamp("trsMenu", true, new Runnable() {
			@Override
			public void run() {
				h.trsMenu();
			}
		}));
		if (host.hasMods()) {
			lamps.add(new Lamp("mods", false, new Runnable() {
				@Override
				public void run() {
					h.mods();
				}
			}));
		}
		lamps.add(new Lamp("quit", host.hasMods(), new Runnable() {
			@Override
			public void run() {
				h.quit();
			}
		}));
	}

	/** Ein Bereich der Seitenleiste; false = gibt es noch nicht (dann „Kommt bald“). */
	private interface Area {
		boolean open();
	}

	private void sideLamp(String id, String icon, final Area area) {
		final String[] label = new String[1];
		Lamp l = new Lamp(id, false, new Runnable() {
			@Override
			public void run() {
				if (!area.open()) soon(label[0]);
			}
		});
		label[0] = l.label;
		l.icon = icon;
		side.add(l);
	}

	/** Kurzer Hinweis „Kommt bald: …“ über der Fußzeile. */
	void soon(String what) {
		toast = I18n.tr("title.soon", what);
		toastLeft = 2.4f;
		host.narrate(toast);
	}

	// --- Für den Selbsttest ---

	/** Wählt eine Lampe wie per Tastatur aus (ohne Vorlesen); -1 = keine. */
	public void focus(int index) {
		focus = index < -1 || index > linkIndex() ? -1 : index;
	}

	/** Lage einer Lampe {x, y, w, h} im letzten Bild oder null (Selbsttest: echte Klicks). */
	public int[] buttonRect(String label) {
		if (frames == 0) return null;
		for (int i = 0; i < lamps.size(); i++) {
			Lamp l = lamps.get(i);
			if (l.id.equals(label) || l.label.equals(label)) return new int[]{l.x, l.y, l.w, BTN_H};
		}
		for (Lamp l : side) {
			if (l.id.equals(label) || l.label.equals(label)) return new int[]{l.x, l.y, l.w, l.h};
		}
		if (("account".equals(label) || accountLabel.equals(label)) && accW > 0) return new int[]{accX, accY, accW, 16};
		return null;
	}

	/** Darstellung der Seitenleiste im letzten Bild ({@link #SIDE_FULL}, {@link #SIDE_ICONS}, {@link #SIDE_ROW}). */
	public int sideMode() {
		return sideMode;
	}

	/** Dreht die Figur auf einen festen Winkel (Selbsttest: Screenshot von vorn/hinten). */
	public void setFigureAngle(float degrees) {
		turntable.setAngle(degrees);
	}

	/** Wurde die Spielerfigur im letzten Bild gezeichnet? */
	public boolean figureShown() {
		return figureShown;
	}

	/** Mittlere CPU-Zeit von Figur und Drehscheibe je Bild in Mikrosekunden. */
	public float modelMicros() {
		return modelMicros;
	}

	/** Gezeichnete Flächen der Figur im letzten Bild. */
	public int modelFaces() {
		return model.lastFaceCount();
	}

	/** Aktueller Hinweis (Selbsttest) oder null. */
	public String toast() {
		return toastLeft > 0 ? toast : null;
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
		int[] panel = leftPanel(bg, width, height, colX);
		layoutSide(bg, width, height, colX);
		int[] sb = sideBounds();
		setReserved(2, panel);
		setReserved(3, sb);

		if (sceneEnabled) scene.draw(bg, width, height, time, reserved);
		else bg.fill(0, 0, width, height, 0xFF18181C);
		readingPlate(bg, colX - 26, top - 6, BTN_W + 52, rowsY + buttonsH + 6 - (top - 6));
		if (panel != null) readingPlate(bg, panel[0], panel[1], panel[2] - panel[0], panel[3] - panel[1]);
		if (sb != null && sideMode != SIDE_ROW) readingPlate(bg, sb[0], sb[1], sb[2] - sb[0], sb[3] - sb[1]);

		Canvas c = FadeCanvas.of(raw, alpha());
		update(dt, mouseX, mouseY);
		turntable.tick(dt, animated);
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
		long modelStart = System.nanoTime();
		if (panel != null) drawLeft(c, t, panel, mouseX, mouseY, time, animated, dt);
		float micros = (System.nanoTime() - modelStart) / 1000f;
		modelFrames++;
		modelMicros = modelFrames == 1 ? micros : modelMicros + (micros - modelMicros) * 0.05f;
		drawSide(c, t, width, mouseX, mouseY, time);
		logo(c, t, width, top, big, small, logoH, gapA, time, animated);
		footer(c, t, width, height, mouseX, mouseY, dt);
		for (int i = 0; i < labels.size(); i++) {
			Label label = labels.get(i);
			c.text(label.text, label.x, label.y, label.color, label.shadow);
		}
		labels.clear();
		for (int i = 0; i < tooltips.size(); i++) {
			Label tip = tooltips.get(i);
			tooltip(c, t, tip.text, tip.x, tip.y, tip.color, tip.shadow ? 1 : 0, width);
		}
		tooltips.clear();
		drawToast(c, t, width, height, dt);

		if (pending >= 0) {
			pendingIn -= dt;
			if (pendingIn <= 0) {
				int run = pending;
				pending = -1;
				run(run);
			}
		}
		micros = (System.nanoTime() - start) / 1000f;
		frames++;
		uiMicros = frames == 1 ? micros : uiMicros + (micros - uiMicros) * 0.05f;
		frameMs = frames == 1 ? dt * 1000f : frameMs + (dt * 1000f - frameMs) * 0.05f;
	}

	private void setReserved(int index, int[] r) {
		int[] out = reserved[index];
		if (r == null) {
			out[0] = 0;
			out[1] = 0;
			out[2] = 0;
			out[3] = 0;
			return;
		}
		out[0] = r[0];
		out[1] = r[1];
		out[2] = r[2];
		out[3] = r[3];
	}

	// --- Linke Seite: Figur auf der Drehscheibe, Name, Konto ---

	/** Maße der linken Seite in diesem Bild (Figur-Maßstab ×100, Fußpunkt, Mitte, Namenszeile). */
	private int panelScale100;
	private int panelFeetY;
	private int panelCx;
	private int panelNameY;

	/**
	 * Legt die linke Seite fest: {x1, y1, x2, y2} oder null (zu schmal). Setzt Figurgröße, Fußpunkt, Namens- und
	 * Knopfzeile.
	 */
	private int[] leftPanel(Canvas c, int width, int height, int colX) {
		int x1 = 6;
		int x2 = colX - 30;
		int lw = x2 - x1;
		accW = 0;
		figureShown = false;
		if (lw < 56) return null;
		int avail = height - 16 - 8;
		float s = Math.min(Math.min(lw / 26f, (avail - 36) / 41f), 5f);
		boolean figure = s >= 1.5f;
		int figureH = figure ? Math.round(38.5f * s) : 0;
		int total = figureH + 6 + 9 + 5 + 16;
		int y0 = Math.max(8, (height - 14 - total) / 2);
		panelCx = x1 + lw / 2;
		panelScale100 = figure ? Math.round(s * 100f) : 0;
		panelFeetY = y0 + Math.round(33f * s);
		int radius = Math.round(11f * s);
		int tableBottom = panelFeetY + Math.round(radius * TABLE_SQUASH) + Math.round(radius * 0.22f) + 2;
		panelNameY = figure ? tableBottom + 6 : y0;
		int bw = Math.min(lw - 8, Math.max(64, c.textWidth(accountLabel) + 26));
		accW = bw;
		accX = panelCx - bw / 2;
		accY = panelNameY + 9 + 5;
		figureShown = figure;
		int half = Math.max(radius + 10, bw / 2 + 8);
		int left = Math.max(x1, panelCx - half);
		int right = Math.min(x2, panelCx + half);
		return new int[]{left, figure ? y0 - 6 : y0 - 4, right, accY + 16 + 6};
	}

	private void drawLeft(Canvas c, Theme t, int[] panel, int mx, int my, float time, boolean animated, float dt) {
		PlayerLook look = host.look();
		String name = look != null ? look.name : host.playerName();
		if (figureShown) {
			float s = panelScale100 / 100f;
			int radius = Math.round(11f * s);
			int px = Math.max(1, (int) (s / 2f));
			turntable.drawBack(c, panelCx, panelFeetY, radius, TABLE_SQUASH, px, time);
			if (look != null && look.skin != null && c.images()) {
				look.applyTo(spec);
				spec.yaw = turntable.angle();
				spec.pitch = FIGURE_PITCH;
				spec.idleTime = animated ? time : 0f;
				// Kopf folgt der Maus, solange die Figur ungefähr nach vorn schaut.
				float body = ((turntable.angle() + 180f) % 360f) - 180f;
				float wantYaw = 0f;
				float wantPitch = 0f;
				float headY = panelFeetY - 28f * s;
				if (Math.abs(body) < 75f && mx >= 0) {
					float toMouse = (float) Math.toDegrees(Math.atan2(mx - panelCx, 60f * s));
					wantYaw = Math.max(-30f, Math.min(30f, toMouse - body));
					wantPitch = Math.max(-15f, Math.min(15f, (float) Math.toDegrees(Math.atan2(my - headY, 90f * s))));
				}
				headYaw = Anim.approach(headYaw, wantYaw, dt, 0.12f);
				headPitch = Anim.approach(headPitch, wantPitch, dt, 0.12f);
				spec.headYaw = headYaw;
				spec.headPitch = headPitch;
				spec.tint = ColorMath.withAlpha(0xFFFFFFFF, Math.round(255 * alpha()));
				model.draw(FadeCanvas.unwrap(c), panelCx, panelFeetY, s, spec);
			} else {
				// Keine Texturen in dieser Version: Umriss der Figur aus Rechtecken.
				silhouette(c, panelCx, panelFeetY, s);
			}
			turntable.drawFront(c, panelCx, panelFeetY, radius, TABLE_SQUASH, px, time);
			int fh = Math.round(34f * s);
			int fw = Math.round(24f * s);
			hits.addDrag(panelCx - fw / 2, panelFeetY - fh, fw, fh + Math.round(radius * TABLE_SQUASH) + 4, new Hits.Drag() {
				@Override
				public void to(double x, double y) {
					if (!turntable.dragging()) turntable.grab(x);
					else turntable.drag(x);
				}
			});
			// Hinweis „Ziehen zum Drehen“ beim Überfahren – nur wenn er ganz hinpasst.
			int hintW = c.textWidth(rotateHint);
			if (!turntable.dragging() && hintW <= panel[2] - panel[0] + 16 && inside(mx, my, panelCx - fw / 2, panelFeetY - fh, fw, fh)) {
				labels.add(new Label(rotateHint, panelCx - hintW / 2, Math.max(1, panel[1] - 4), 0xFF8F8B89, false));
			}
		}
		// Name und Konto-Knopf
		String n = c.clip(name == null ? "" : name, Math.max(10, accW + 20));
		int nw = c.textWidth(n);
		labels.add(new Label(n, panelCx - nw / 2, panelNameY, t.text, true));
		final int focusIndex = lamps.size() + side.size();
		boolean hover = inside(mx, my, accX, accY, accW, 16);
		boolean on = hover || focus == focusIndex || pending == focusIndex;
		int dy = accFlash > 0.45f ? 1 : 0;
		Redstone.stone(c, accX, accY + dy, accW, 16, on ? t.surfaceHover : t.surfaceHigh,
				on ? ColorMath.lerp(t.border, t.textDim, 0.5f) : t.border);
		if (on) Redstone.dustH(c, accX + 3, accX + accW - 3, accY + dy + 13, t.dustOn, 0f);
		Icons.draw(c, "profile", accX + 5, accY + dy + 4, 1, on ? t.dustOn : t.textDim);
		String lbl = c.clip(accountLabel, accW - 20);
		labels.add(new Label(lbl, accX + 16 + (accW - 18 - c.textWidth(lbl)) / 2, accY + dy + 4 - (on ? 1 : 0), t.text, false));
		if (accFlash > 0.02f) c.fill(accX + 1, accY + 1, accX + accW - 1, accY + 15, ColorMath.withAlpha(0xFFFFF6DC, Math.round(120 * accFlash)));
		hits.add(accX, accY, accW, 16, new Runnable() {
			@Override
			public void run() {
				activate(focusIndex);
			}
		});
	}

	/** Ersatz ohne Texturen: Kopf, Körper, Arme, Beine als Flächen. */
	private static void silhouette(Canvas c, int cx, int feet, float s) {
		int u = Math.max(1, Math.round(s));
		int body = 0xFF3A6EA5;
		int skin = 0xFFC69C6D;
		int legs = 0xFF2E3A8C;
		c.fill(cx - 4 * u, feet - 32 * u, cx + 4 * u, feet - 24 * u, skin);
		c.fill(cx - 4 * u, feet - 24 * u, cx + 4 * u, feet - 12 * u, body);
		c.fill(cx - 8 * u, feet - 24 * u, cx - 4 * u, feet - 12 * u, skin);
		c.fill(cx + 4 * u, feet - 24 * u, cx + 8 * u, feet - 12 * u, skin);
		c.fill(cx - 4 * u, feet - 12 * u, cx + 4 * u, feet, legs);
	}

	// --- Rechte Seitenleiste ---

	private int sideX;
	private int sideY;
	private int sideW;
	private int sideH;

	private void layoutSide(Canvas c, int width, int height, int colX) {
		int n = side.size();
		int maxLabel = 0;
		for (Lamp l : side) maxLabel = Math.max(maxLabel, c.textWidth(l.label));
		int fullW = maxLabel + 34;
		int room = width - (colX + BTN_W + 30) - 8;
		if (room >= fullW + 14) {
			sideMode = SIDE_FULL;
			int gap = 6;
			int h = 20;
			sideW = fullW;
			sideH = n * h + (n - 1) * gap;
			sideX = width - 14 - fullW;
			sideY = Math.max(18, (height - 14 - sideH) / 2);
			for (int i = 0; i < n; i++) {
				Lamp l = side.get(i);
				l.x = sideX;
				l.y = sideY + i * (h + gap);
				l.w = fullW;
				l.h = h;
			}
		} else if (room >= 36) {
			sideMode = SIDE_ICONS;
			int size = 22;
			int gap = 5;
			sideW = size;
			sideH = n * size + (n - 1) * gap;
			sideX = width - 14 - size;
			sideY = Math.max(18, (height - 14 - sideH) / 2);
			for (int i = 0; i < n; i++) {
				Lamp l = side.get(i);
				l.x = sideX;
				l.y = sideY + i * (size + gap);
				l.w = size;
				l.h = size;
			}
		} else {
			// Kein Platz neben der Mitte: kleine Symbole in einer Zeile oben rechts.
			sideMode = SIDE_ROW;
			int size = 16;
			int gap = 3;
			sideW = n * size + (n - 1) * gap;
			sideH = size;
			sideX = width - 4 - sideW;
			sideY = 4;
			for (int i = 0; i < n; i++) {
				Lamp l = side.get(i);
				l.x = sideX + i * (size + gap);
				l.y = sideY;
				l.w = size;
				l.h = size;
			}
		}
	}

	/** Umriss der Seitenleiste inkl. Leitung {x1, y1, x2, y2}. */
	private int[] sideBounds() {
		if (side.isEmpty()) return null;
		if (sideMode == SIDE_ROW) return new int[]{sideX - 3, sideY - 3, sideX + sideW + 3, sideY + sideH + 3};
		return new int[]{sideX - 8, sideY - 16, sideX + sideW + 14, sideY + sideH + 6};
	}

	private void drawSide(Canvas c, Theme t, int width, int mx, int my, float time) {
		int base = lamps.size();
		if (sideMode != SIDE_ROW) {
			// Leitung rechts neben der Leiste, gespeist von einer Fackel oben.
			int busX = sideX + sideW + 6;
			int busTop = sideY - 1;
			Lamp lastLamp = side.get(side.size() - 1);
			int busBottom = lastLamp.y + lastLamp.h / 2 + 1;
			Redstone.dustV(c, busX, busTop, busBottom, t.dustOff, 0f);
			for (Lamp l : side) Redstone.dustH(c, l.x + l.w, busX + 2, l.y + l.h / 2 - 1, t.dustOff, 0f);
			for (Lamp l : side) {
				if (l.power <= 0f) continue;
				int cy = l.y + l.h / 2 - 1;
				int vertical = Math.max(0, cy - busTop);
				int horizontal = Math.max(0, busX + 2 - (l.x + l.w));
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
					Redstone.dustH(c, busX + 2 - s - len, busX + 2 - s, cy, t.dust(level), level / 15f);
					done += len;
				}
			}
			Redstone.torch(c, busX + 1, busTop, 1f, flicker(time, 5.3f));
		}
		for (int i = 0; i < side.size(); i++) {
			Lamp l = side.get(i);
			float lit = lampLit(l);
			int dy = l.flash > 0.45f ? 1 : 0;
			Redstone.lamp(c, l.x, l.y + dy, l.w, l.h, lit, l.flash);
			int iconColor = lit > 0.5f ? t.lampTextLit : t.lampText;
			if (sideMode == SIDE_FULL) {
				Icons.draw(c, l.icon, l.x + 7, l.y + dy + (l.h - 8) / 2, 1, iconColor);
				String s = c.clip(l.label, l.w - 26);
				labels.add(new Label(s, l.x + 20 + (l.w - 24 - c.textWidth(s)) / 2, l.y + dy + (l.h - 8) / 2,
						Redstone.lampTextColor(lit), lit < 0.35f));
			} else {
				int px = l.w >= 20 ? 2 : 1;
				int size = 8 * px;
				Icons.draw(c, l.icon, l.x + (l.w - size) / 2, l.y + dy + (l.h - size) / 2, px, iconColor);
				boolean hover = inside(mx, my, l.x, l.y, l.w, l.h);
				// Beschriftung als Hinweis (zuletzt gezeichnet, über allem): x/y = Symbol, color/shadow = Größe/Zeile
				if (hover || focus == base + i) tooltips.add(new Label(l.label, l.x, l.y, l.w, l.h > 0));
			}
			final int index = base + i;
			hits.add(l.x, l.y, l.w, l.h, new Runnable() {
				@Override
				public void run() {
					activate(index);
				}
			});
		}
	}

	/** Hinweise neben Symbolen (nach dem Text gezeichnet). */
	private final List<Label> tooltips = new ArrayList<Label>();

	/** Beschriftung neben einem Symbol: links davon (Leiste) bzw. darunter (Zeile oben). */
	private void tooltip(Canvas c, Theme t, String text, int x, int y, int size, int unused, int width) {
		int tw = c.textWidth(text) + 8;
		int tx;
		int ty;
		if (sideMode == SIDE_ROW) {
			tx = Math.max(2, Math.min(width - tw - 2, x + size / 2 - tw / 2));
			ty = y + size + 3;
		} else {
			tx = x - tw - 4;
			ty = y + (size - 12) / 2;
		}
		c.push();
		c.raise(150);
		Redstone.stone(c, tx, ty, tw, 12, t.surfaceHigh, t.border);
		c.text(text, tx + 4, ty + 2, t.text, false);
		c.pop();
	}

	private void drawToast(Canvas c, Theme t, int width, int height, float dt) {
		if (toastLeft <= 0f || toast == null) return;
		toastLeft -= dt;
		float a = ColorMath.clamp01(Math.min(toastLeft / 0.4f, (2.4f - toastLeft) / 0.12f + 0.2f));
		int tw = c.textWidth(toast) + 22;
		int x = (width - tw) / 2;
		int y = height - 34;
		Canvas f = FadeCanvas.of(c, a);
		f.push();
		f.raise(200);
		Redstone.stone(f, x, y, tw, 16, t.surfaceHigh, t.border);
		Redstone.pip(f, x + 5, y + 5, 6, 1f);
		f.text(toast, x + 15, y + 4, t.text, false);
		f.pop();
	}

	private void update(float dt, int mx, int my) {
		for (int i = 0; i < lamps.size(); i++) {
			Lamp l = lamps.get(i);
			boolean hover = inside(mx, my, l.x, l.y, l.w, BTN_H);
			boolean on = hover || focus == i || pending == i;
			l.power = on ? Math.min(1f, l.power + dt / SIGNAL_IN) : Math.max(0f, l.power - dt / SIGNAL_OUT);
			l.flash = Math.max(0f, l.flash - dt / 0.25f);
		}
		for (int i = 0; i < side.size(); i++) {
			Lamp l = side.get(i);
			int index = lamps.size() + i;
			boolean hover = inside(mx, my, l.x, l.y, l.w, l.h);
			boolean on = hover || focus == index || pending == index;
			l.power = on ? Math.min(1f, l.power + dt / SIGNAL_IN) : Math.max(0f, l.power - dt / SIGNAL_OUT);
			l.flash = Math.max(0f, l.flash - dt / 0.25f);
		}
		accFlash = Math.max(0f, accFlash - dt / 0.25f);
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
		int cw = c.textWidth(classic);
		int lx = width - cw - 4;
		// Versionszeile weicht dem Link aus (schmale Fenster).
		String version = host.versionLine();
		int room = lx - 18;
		if (c.textWidth(version) > room) version = room > 20 ? c.clip(version, room - c.textWidth("…")) + "…" : "";
		if (!version.isEmpty()) labels.add(new Label(version, 4, height - 10, 0xFF8F8B89, false));
		boolean hover = inside(mx, my, lx - 10, height - 12, cw + 10, 12);
		boolean on = hover || focus == linkIndex();
		linkLit = on ? Math.min(1f, linkLit + dt / 0.08f) : Math.max(0f, linkLit - dt / 0.08f);
		Redstone.pip(c, lx - 9, height - 9, 5, linkLit);
		labels.add(new Label(classic, lx, height - 10, ColorMath.lerp(0xFF8F8B89, t.lampOn, linkLit), false));
		hits.add(lx - 10, height - 12, cw + 14, 12, new Runnable() {
			@Override
			public void run() {
				activate(linkIndex());
			}
		});
	}

	// --- Bedienung ---

	/** Index des Links „Klassischer Titelbildschirm“ (immer der letzte). */
	private int linkIndex() {
		return lamps.size() + side.size() + 1;
	}

	private int accountIndex() {
		return lamps.size() + side.size();
	}

	private void activate(int index) {
		if (pending >= 0) return;
		host.playClick();
		if (index < lamps.size()) lamps.get(index).flash = 1f;
		else if (index < accountIndex()) side.get(index - lamps.size()).flash = 1f;
		else if (index == accountIndex()) accFlash = 1f;
		pending = index;
		pendingIn = PRESS_DELAY;
	}

	/** Führt die Aktion nach dem Aufblitzen aus. */
	private void run(int index) {
		if (index < lamps.size()) {
			lamps.get(index).action.run();
		} else if (index < accountIndex()) {
			side.get(index - lamps.size()).action.run();
		} else if (index == accountIndex()) {
			if (!host.openAccounts()) soon(side.get(1).label);
		} else {
			host.classicTitle();
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		boolean hit = super.mouseClicked(mouseX, mouseY, button);
		if (!hit) focus = -1;
		return hit;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		turntable.release();
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		int count = linkIndex() + 1;
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
		// Ausgeblendeter Konto-Knopf (schmales Fenster) wird übersprungen.
		if (focus == accountIndex() && accW <= 0) focus = (focus + step + count) % count;
		host.narrate(I18n.tr("title.narrateButton", focusLabel(focus)));
	}

	private String focusLabel(int index) {
		if (index < lamps.size()) return lamps.get(index).label;
		if (index < accountIndex()) return side.get(index - lamps.size()).label;
		if (index == accountIndex()) return accountLabel;
		return classic;
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
