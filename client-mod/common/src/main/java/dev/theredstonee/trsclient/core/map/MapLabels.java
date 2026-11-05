package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.ui.Canvas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Beschriftungen ohne Überlappung (Weltkarte, HUD): erst sammeln, dann nach Wichtigkeit setzen. Jede Beschriftung
 * versucht ihre Stelle und ein paar Ausweichstellen darunter/darüber; passt keine, entfällt sie – außer sie ist
 * {@link #FORCE} (Zeiger darüber, hervorgehoben). Schon belegte Flächen (z. B. eigene Wegpunkte im HUD, die der Baum
 * zeichnet) meldet {@link #occupy}. Wiederverwendbar je Bild, nur Spiel-/Render-Thread.
 */
public final class MapLabels {
	/** Immer zeichnen (Zeiger darüber, hervorgehoben). */
	public static final int FORCE = 1000;
	public static final int HOVER = FORCE;
	public static final int PLAYER = 40;
	public static final int OWN = 30;
	public static final int FRIEND_HEAD = 35;
	public static final int FRIEND = 10;

	private static final class Label {
		String text;
		int x, y, color, bg, priority, order;
		boolean shadow;
		/** Ausweichen nach unten (true) oder nach oben (false) zuerst. */
		boolean down;
	}

	/** Zeilenhöhe einer Beschriftung samt Rand und die Ausweichstellen (erst die eigene Richtung, dann die andere). */
	private static final int LINE = 10;
	private static final int[] DOWN = {0, LINE + 1, -(LINE + 1), 2 * (LINE + 1)};
	private static final int[] UP = {0, -(LINE + 1), LINE + 1, -2 * (LINE + 1)};

	private final List<Label> labels = new ArrayList<Label>();
	private final List<Label> pool = new ArrayList<Label>();
	private final List<int[]> taken = new ArrayList<int[]>();
	private int used;
	private int dropped;

	private static final Comparator<Label> ORDER = new Comparator<Label>() {
		@Override
		public int compare(Label a, Label b) {
			if (a.priority != b.priority) return b.priority - a.priority;
			return a.order - b.order;
		}
	};

	/**
	 * Beschriftung vormerken: {@code (x, y)} = linke obere Ecke des Textes, {@code bg} = Hintergrund (0 = keiner).
	 * {@code down}: beim Ausweichen erst nach unten.
	 */
	public void add(String text, int x, int y, int color, int bg, boolean shadow, int priority, boolean down) {
		if (text == null || text.isEmpty()) return;
		Label l;
		if (used < pool.size()) {
			l = pool.get(used);
		} else {
			l = new Label();
			pool.add(l);
		}
		used++;
		l.text = text;
		l.x = x;
		l.y = y;
		l.color = color;
		l.bg = bg;
		l.shadow = shadow;
		l.priority = priority;
		l.down = down;
		l.order = labels.size();
		labels.add(l);
	}

	/** Fläche ist schon belegt (Bildschirmkoordinaten, inklusive Rand). */
	public void occupy(int x1, int y1, int x2, int y2) {
		taken.add(new int[]{x1, y1, x2, y2});
	}

	/** Gesetzte Beschriftungen zeichnen und alles für das nächste Bild leeren. */
	public void flush(Canvas c) {
		Collections.sort(labels, ORDER);
		dropped = 0;
		int h = LINE;
		for (Label l : labels) {
			int w = c.textWidth(l.text);
			int x1 = l.x - 2, x2 = l.x + w + 2;
			int placedY = Integer.MIN_VALUE;
			int[] steps = l.down ? DOWN : UP;
			for (int dy : steps) {
				int y1 = l.y - 1 + dy, y2 = l.y + h - 1 + dy;
				if (free(x1, y1, x2, y2)) {
					placedY = l.y + dy;
					break;
				}
			}
			if (placedY == Integer.MIN_VALUE) {
				if (l.priority < FORCE) {
					dropped++;
					continue;
				}
				placedY = l.y;
			}
			taken.add(new int[]{x1, placedY - 1, x2, placedY + h - 1});
			if (l.bg != 0) c.fill(x1, placedY - 1, x2, placedY + h - 1, l.bg);
			c.text(l.text, l.x, placedY, l.color, l.shadow);
		}
		labels.clear();
		taken.clear();
		used = 0;
	}

	/** Beim letzten {@link #flush} weggelassene Beschriftungen (Tests/Autotest). */
	public int dropped() {
		return dropped;
	}

	private boolean free(int x1, int y1, int x2, int y2) {
		for (int i = 0, n = taken.size(); i < n; i++) {
			int[] r = taken.get(i);
			if (x1 < r[2] && x2 > r[0] && y1 < r[3] && y2 > r[1]) return false;
		}
		return true;
	}
}
