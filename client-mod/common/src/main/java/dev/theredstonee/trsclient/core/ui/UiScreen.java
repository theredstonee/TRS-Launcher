package dev.theredstonee.trsclient.core.ui;

import dev.theredstonee.trsclient.core.touch.TouchGestures;
import dev.theredstonee.trsclient.core.touch.TouchKeyboard;
import dev.theredstonee.trsclient.core.touch.TouchMode;

/**
 * Versionsunabhängiger Bildschirm. Der Minecraft-Bildschirm der jeweiligen Version reicht nur
 * Zeichnen und Eingaben hierher weiter (siehe {@code screen/TrsUiScreen}); die gesamte Oberfläche
 * (Menü, HUD-Editor) steht in {@code core.ui} und kennt Minecraft nicht.
 */
public abstract class UiScreen {
	/** Dauer der Öffnen-/Schließen-Animation in Sekunden. */
	protected static final float OPEN_TIME = 0.16f;

	protected final Hits hits = new Hits();
	/** Öffnungsfortschritt 0..1 (Animation). */
	protected float open;
	private boolean closing;
	private boolean closed;
	private long lastNanos;

	/** Zeichnet den Bildschirm. */
	public final void render(Canvas c, int width, int height, int mouseX, int mouseY) {
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 1f / 60f : Math.min(0.25f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;
		open = Anim.approach(open, closing ? 0f : 1f, dt, OPEN_TIME / 2.2f);
		if (closing && open < 0.02f && !closed) {
			closed = true;
			onClosed();
			return;
		}
		if (TouchMode.enabled()) {
			renderTouch(c, width, height, dt);
			return;
		}
		hits.clear();
		draw(c, width, height, mouseX, mouseY, dt);
	}

	// --- Touch-Modus (nur mit -Dtrs.touch=true; sonst laufen alle Eingaben unverändert durch) ---

	/** Abstand des „Mauszeigers“, wenn kein Finger liegt (kein Hover, keine Tooltips). */
	private static final int NO_POINTER = -10000;
	private TouchGestures gestures;
	/** Vergrößerung des letzten Frames (Eingaben werden damit umgerechnet). */
	private float touchScale = 1f;

	private void renderTouch(Canvas c, int width, int height, float dt) {
		long nowMs = System.currentTimeMillis();
		Object before = TouchKeyboard.enter(this);
		try {
			// Langer Druck und Schwung zuerst – sie nutzen die Klickflächen des letzten Frames.
			gestures().tick(nowMs, dt);
			float s = touchScale(width, height);
			touchScale = s;
			int vw = Math.max(1, (int) (width / s));
			int vh = Math.max(1, (int) (height / s));
			TouchGestures g = gestures();
			int px = g.showsPointer() ? (int) Math.floor(g.pointerX()) : NO_POINTER;
			int py = g.showsPointer() ? (int) Math.floor(g.pointerY()) : NO_POINTER;
			hits.clear();
			if (s == 1f) {
				draw(c, width, height, px, py, dt);
			} else {
				c.push();
				c.scale(s);
				draw(ScaledCanvas.of(c, s), vw, vh, px, py, dt);
				c.pop();
			}
			TouchKeyboard.reportUi(TouchKeyboard.fieldOf(this), nowMs);
		} finally {
			TouchKeyboard.leave(before);
		}
	}

	/**
	 * Vergrößerung dieses Bildschirms im Touch-Modus (Standard: {@link TouchMode#uiScale}). Bildschirme, die echte
	 * Bildschirmpositionen zeigen (HUD-Editor, Weltkarte), bleiben bei 1.
	 */
	protected float touchScale(int width, int height) {
		return TouchMode.uiScale(width, height);
	}

	/**
	 * Soll ein Finger an dieser Stelle sofort direkt bedienen (statt Antippen/Scrollen zu erkennen)? Standard: wenn
	 * dort eine Zieh-Fläche liegt (Schieberegler, Farbwähler, Vorschau zum Drehen).
	 */
	protected boolean touchDirect(double x, double y) {
		return hits.dragAt(x, y);
	}

	private TouchGestures gestures() {
		if (gestures == null) {
			gestures = new TouchGestures(new TouchGestures.Target() {
				@Override
				public boolean direct(double x, double y) {
					return touchDirect(x, y);
				}

				@Override
				public boolean click(double x, double y, int button) {
					return mouseClicked(x, y, button);
				}

				@Override
				public boolean release(double x, double y, int button) {
					return mouseReleased(x, y, button);
				}

				@Override
				public boolean drag(double x, double y, int button) {
					return mouseDragged(x, y, button);
				}

				@Override
				public boolean scroll(double x, double y, double amount) {
					return mouseScrolled(x, y, amount);
				}
			});
		}
		return gestures;
	}

	// --- Eingaben vom Minecraft-Bildschirm (immer über diese Methoden, damit der Touch-Modus greift) ---

	/** Maustaste gedrückt (Bildschirmkoordinaten). */
	public final boolean inputClick(double mouseX, double mouseY, int button) {
		if (!TouchMode.enabled()) return mouseClicked(mouseX, mouseY, button);
		Object before = TouchKeyboard.enter(this);
		try {
			return gestures().down(mouseX / touchScale, mouseY / touchScale, button, System.currentTimeMillis());
		} finally {
			TouchKeyboard.leave(before);
		}
	}

	/** Maustaste losgelassen. */
	public final boolean inputRelease(double mouseX, double mouseY, int button) {
		if (!TouchMode.enabled()) return mouseReleased(mouseX, mouseY, button);
		Object before = TouchKeyboard.enter(this);
		try {
			return gestures().up(mouseX / touchScale, mouseY / touchScale, button, System.currentTimeMillis());
		} finally {
			TouchKeyboard.leave(before);
		}
	}

	/** Maus mit gedrückter Taste bewegt. */
	public final boolean inputDrag(double mouseX, double mouseY, int button) {
		if (!TouchMode.enabled()) return mouseDragged(mouseX, mouseY, button);
		Object before = TouchKeyboard.enter(this);
		try {
			return gestures().move(mouseX / touchScale, mouseY / touchScale, button, System.currentTimeMillis());
		} finally {
			TouchKeyboard.leave(before);
		}
	}

	/** Mausrad (echtes Rad oder Touchpad – im Touch-Modus nur umgerechnet). */
	public final boolean inputScroll(double mouseX, double mouseY, double amount) {
		if (!TouchMode.enabled()) return mouseScrolled(mouseX, mouseY, amount);
		return mouseScrolled(mouseX / touchScale, mouseY / touchScale, amount);
	}

	/** Taste (wie {@link #keyPressed}; im Touch-Modus mit Fokus-Zuordnung für die Bildschirmtastatur). */
	public final boolean inputKey(int rawKey, UiKey key, boolean shift) {
		if (!TouchMode.enabled()) return keyPressed(rawKey, key, shift);
		Object before = TouchKeyboard.enter(this);
		try {
			return keyPressed(rawKey, key, shift);
		} finally {
			TouchKeyboard.leave(before);
		}
	}

	/** Zeichen (wie {@link #charTyped}). */
	public final boolean inputChar(char c) {
		if (!TouchMode.enabled()) return charTyped(c);
		Object before = TouchKeyboard.enter(this);
		try {
			return charTyped(c);
		} finally {
			TouchKeyboard.leave(before);
		}
	}

	/** Zeichnen der Unterklasse; {@code dt} = Sekunden seit dem letzten Frame. */
	protected abstract void draw(Canvas c, int width, int height, int mouseX, int mouseY, float dt);

	/** Wird nach der Schließ-Animation aufgerufen (Bildschirm wirklich schließen). */
	protected abstract void onClosed();

	/** Schließen anstoßen (Esc oder Knopf) – schließt nach der Animation. */
	public void requestClose() {
		closing = true;
	}

	public boolean isClosing() {
		return closing;
	}

	/** Wie stark das Menü eingeblendet ist (für Ein-/Ausblenden der Farben). */
	public float alpha() {
		return Anim.easeOut(open);
	}

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return hits.click(mouseX, mouseY, button);
	}

	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		boolean was = hits.dragging();
		hits.release();
		return was;
	}

	public boolean mouseDragged(double mouseX, double mouseY, int button) {
		return hits.drag(mouseX, mouseY);
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return false;
	}

	/**
	 * Taste gedrückt.
	 * @param rawKey versionsabhängiger Tastencode (für das Belegen von Tasten)
	 * @param key logische Taste
	 */
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		return false;
	}

	/** Zeichen eingegeben (Textfelder). */
	public boolean charTyped(char c) {
		return false;
	}

	/**
	 * Braucht der Bildschirm Texteingabe (Zeichen)? Ab Minecraft 26.3 (SDL) kommen Zeichen nur an, solange die
	 * Texteingabe des Fensters an ist – der Minecraft-Bildschirm schaltet sie danach. Standard: an, weil Tippen ohne
	 * Fokus in vielen Seiten direkt ins Suchfeld/Eingabefeld geht. Im Touch-Modus nur mit fokussiertem Feld (sonst
	 * käme die System-Tastatur bei jedem TRS-Bildschirm).
	 */
	public boolean wantsTextInput() {
		return !TouchMode.enabled() || TouchKeyboard.fieldOf(this) != null;
	}

	/** Pausiert das Spiel nicht. */
	public boolean pausesGame() {
		return false;
	}

	/**
	 * Kleine Einblendung über dem laufenden Spiel (Schnellantwort): kein abgedunkelter Hintergrund, das HUD bleibt
	 * sichtbar, das Spiel pausiert nicht.
	 */
	public boolean overlay() {
		return false;
	}
}
