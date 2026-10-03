package dev.theredstonee.trsclient.core.touch;

/**
 * Fingergesten eines TRS-Bildschirms im Touch-Modus. Die Engine meldet den Finger als linke Maustaste; hier wird
 * daraus:
 * <ul>
 *   <li><b>Antippen</b> → Klick (beim Loslassen, an der Aufsetzstelle)</li>
 *   <li><b>Ziehen</b> → Scrollen der Liste unter dem Finger, mit Schwung ({@link TouchScroller})</li>
 *   <li><b>Lange drücken</b> → Rechtsklick (Kontextmenü); nimmt ihn niemand an, zeigt das Halten den Tooltip</li>
 *   <li><b>Direkt</b> – liegt unter dem Finger ein Regler/eine Vorschau zum Drehen o. Ä.
 *       ({@link Target#direct}), gehen Drücken/Ziehen/Loslassen sofort und unverändert durch.</li>
 * </ul>
 * Andere Maustasten (echte Maus am Tablet) gehen immer direkt durch. Alle Koordinaten in GUI-Pixeln der Oberfläche.
 */
public final class TouchGestures {
	/** Ab dieser Bewegung (GUI-Pixel) ist es kein Antippen mehr, sondern Ziehen. */
	public static final float SLOP = 5f;
	/** So lange halten = langer Druck. */
	public static final long LONG_PRESS_MS = 450;

	/** Die Oberfläche hinter den Gesten (Rückgabe: true = verbraucht). */
	public interface Target {
		/** Soll ein Finger an dieser Stelle sofort direkt bedienen (Schieberegler, Ziehen von Elementen)? */
		boolean direct(double x, double y);

		boolean click(double x, double y, int button);

		boolean release(double x, double y, int button);

		boolean drag(double x, double y, int button);

		boolean scroll(double x, double y, double amount);
	}

	enum Mode {
		IDLE,
		/** Finger liegt, noch unentschieden (Antippen, Ziehen oder langer Druck). */
		PENDING,
		/** Finger bedient direkt (Regler, Ziehen). */
		DIRECT,
		/** Finger scrollt. */
		SCROLL,
		/** Langer Druck: Rechtsklick wurde angenommen. */
		CONTEXT,
		/** Langer Druck ohne Kontextmenü: Tooltip, solange der Finger liegt. */
		HOLD
	}

	private final Target target;
	private final TouchScroller scroller = new TouchScroller();
	private Mode mode = Mode.IDLE;
	private double downX;
	private double downY;
	private double lastX;
	private double lastY;
	private long downAt;
	/** Wo der Schwung scrollt (Aufsetzstelle der letzten Scroll-Geste). */
	private double flingX;
	private double flingY;

	public TouchGestures(Target target) {
		this.target = target;
	}

	/** Maustaste gedrückt. */
	public boolean down(double x, double y, int button, long nowMs) {
		if (button != 0) return target.click(x, y, button);
		scroller.stop();
		downX = lastX = x;
		downY = lastY = y;
		downAt = nowMs;
		if (target.direct(x, y)) {
			mode = Mode.DIRECT;
			return target.click(x, y, 0);
		}
		mode = Mode.PENDING;
		return true;
	}

	/** Maus mit gedrückter Taste bewegt. */
	public boolean move(double x, double y, int button, long nowMs) {
		if (button != 0) return target.drag(x, y, button);
		double dy = y - lastY;
		lastX = x;
		lastY = y;
		switch (mode) {
			case DIRECT:
				return target.drag(x, y, 0);
			case PENDING:
				if (Math.abs(x - downX) <= SLOP && Math.abs(y - downY) <= SLOP) return true;
				// Ab hier Scrollen: der Weg bis zur Schwelle zählt mit, damit nichts „hängt“.
				mode = Mode.SCROLL;
				flingX = downX;
				flingY = downY;
				scroller.start(downAt);
				emit(scroller.move((float) (y - downY), nowMs));
				return true;
			case SCROLL:
				emit(scroller.move((float) dy, nowMs));
				return true;
			default:
				return true;
		}
	}

	/** Maustaste losgelassen. */
	public boolean up(double x, double y, int button, long nowMs) {
		if (button != 0) return target.release(x, y, button);
		Mode was = mode;
		mode = Mode.IDLE;
		switch (was) {
			case DIRECT:
				return target.release(x, y, 0);
			case PENDING:
				// Antippen: Klick an der Aufsetzstelle (kleines Zittern verschiebt das Ziel nicht).
				target.click(downX, downY, 0);
				target.release(downX, downY, 0);
				return true;
			case SCROLL:
				scroller.release(nowMs);
				return true;
			default:
				return true;
		}
	}

	/** Je Frame: langer Druck und Schwung ({@code dt} in Sekunden). */
	public void tick(long nowMs, float dt) {
		if (mode == Mode.PENDING && nowMs - downAt >= LONG_PRESS_MS) {
			boolean taken = target.click(downX, downY, 1);
			target.release(downX, downY, 1);
			mode = taken ? Mode.CONTEXT : Mode.HOLD;
		}
		if (mode == Mode.IDLE || mode == Mode.SCROLL) emit(scroller.tick(dt));
	}

	private void emit(int notches) {
		if (notches == 0) return;
		int step = notches > 0 ? 1 : -1;
		for (int i = 0; i != notches; i += step) target.scroll(flingX, flingY, step);
	}

	/**
	 * Soll die Oberfläche einen Mauszeiger „sehen“ (Hover, Tooltip)? Nur solange ein Finger liegt und nicht
	 * scrollt – nach dem Loslassen bleibt kein Hover-Zustand hängen.
	 */
	public boolean showsPointer() {
		return mode == Mode.PENDING || mode == Mode.DIRECT || mode == Mode.HOLD;
	}

	public double pointerX() {
		return lastX;
	}

	public double pointerY() {
		return lastY;
	}

	/** Liegt ein Finger? */
	public boolean active() {
		return mode != Mode.IDLE;
	}

	Mode mode() {
		return mode;
	}

	TouchScroller scroller() {
		return scroller;
	}
}
