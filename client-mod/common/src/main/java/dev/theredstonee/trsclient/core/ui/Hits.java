package dev.theredstonee.trsclient.core.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Klickflächen eines Immediate-Mode-Bildschirms: Sie werden beim Zeichnen registriert und
 * beim Klick von hinten nach vorn geprüft (zuletzt gezeichnet liegt oben). Flächen können
 * zusätzlich ein Ziehen entgegennehmen (Schieberegler, Farbwähler).
 */
public final class Hits {
	/** Ziehen mit gedrückter Maustaste. */
	public interface Drag {
		void to(double mouseX, double mouseY);
	}

	private static final class Spot {
		final int x;
		final int y;
		final int w;
		final int h;
		final int button;
		final Runnable action;
		final Drag drag;

		Spot(int x, int y, int w, int h, int button, Runnable action, Drag drag) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.button = button;
			this.action = action;
			this.drag = drag;
		}

		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final List<Spot> spots = new ArrayList<Spot>();
	private Drag active;
	/** Bereich, auf den weitere Klickflächen begrenzt sind (Scroll-Liste); null = kein Clipping. */
	private int[] clip;

	public void clear() {
		spots.clear();
	}

	/** Klickflächen außerhalb dieses Rechtecks ignorieren (z. B. gescrollte Listen). */
	public void clip(int x, int y, int w, int h) {
		clip = new int[]{x, y, w, h};
	}

	public void noClip() {
		clip = null;
	}

	/** Linksklick-Fläche. */
	public void add(int x, int y, int w, int h, Runnable action) {
		add(x, y, w, h, 0, action);
	}

	/** Klickfläche für eine bestimmte Maustaste (0 links, 1 rechts). */
	public void add(int x, int y, int w, int h, int button, Runnable action) {
		put(new Spot(x, y, w, h, button, action, null));
	}

	/** Fläche, die auch das Ziehen entgegennimmt (Schieberegler); die Aktion läuft beim Drücken. */
	public void addDrag(int x, int y, int w, int h, Drag drag) {
		put(new Spot(x, y, w, h, 0, null, drag));
	}

	private void put(Spot spot) {
		if (clip != null) {
			int x1 = Math.max(spot.x, clip[0]);
			int y1 = Math.max(spot.y, clip[1]);
			int x2 = Math.min(spot.x + spot.w, clip[0] + clip[2]);
			int y2 = Math.min(spot.y + spot.h, clip[1] + clip[3]);
			if (x2 <= x1 || y2 <= y1) return;
			spot = new Spot(x1, y1, x2 - x1, y2 - y1, spot.button, spot.action, spot.drag);
		}
		spots.add(spot);
	}

	/** True, wenn an dieser Stelle eine Fläche für die Taste liegt (für den Mauszeiger/Hover). */
	public boolean hovers(double mx, double my) {
		for (int i = spots.size() - 1; i >= 0; i--) {
			Spot s = spots.get(i);
			if (s.button == 0 && s.contains(mx, my)) return true;
		}
		return false;
	}

	/** Führt die oberste passende Aktion aus; true = getroffen. */
	public boolean click(double mx, double my, int button) {
		for (int i = spots.size() - 1; i >= 0; i--) {
			Spot s = spots.get(i);
			if (s.button != button || !s.contains(mx, my)) continue;
			if (s.drag != null) {
				active = s.drag;
				s.drag.to(mx, my);
			}
			if (s.action != null) s.action.run();
			return true;
		}
		return false;
	}

	/** Ziehen weiterreichen; true, wenn gerade etwas gezogen wird. */
	public boolean drag(double mx, double my) {
		if (active == null) return false;
		active.to(mx, my);
		return true;
	}

	/** Maustaste losgelassen. */
	public void release() {
		active = null;
	}

	public boolean dragging() {
		return active != null;
	}
}
