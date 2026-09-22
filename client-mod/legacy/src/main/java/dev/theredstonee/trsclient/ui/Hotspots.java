package dev.theredstonee.trsclient.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Klickflächen eines Immediate-Mode-Bildschirms: werden beim Zeichnen registriert
 * und beim Klick rückwärts geprüft (später gezeichnet = liegt oben).
 */
public final class Hotspots {
	private static final class Spot {
		final int x;
		final int y;
		final int w;
		final int h;
		final int button;
		final Runnable action;

		Spot(int x, int y, int w, int h, int button, Runnable action) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.button = button;
			this.action = action;
		}
	}

	private final List<Spot> spots = new ArrayList<>();

	public void clear() {
		spots.clear();
	}

	/** Linksklick-Fläche. */
	public void add(int x, int y, int w, int h, Runnable action) {
		add(x, y, w, h, 0, action);
	}

	/** Klickfläche für eine bestimmte Maustaste (0 links, 1 rechts). */
	public void add(int x, int y, int w, int h, int button, Runnable action) {
		spots.add(new Spot(x, y, w, h, button, action));
	}

	/** Führt die oberste passende Aktion aus; true = getroffen. */
	public boolean click(double mx, double my, int button) {
		for (int i = spots.size() - 1; i >= 0; i--) {
			Spot s = spots.get(i);
			if (s.button == button && mx >= s.x && mx < s.x + s.w && my >= s.y && my < s.y + s.h) {
				s.action.run();
				return true;
			}
		}
		return false;
	}
}
