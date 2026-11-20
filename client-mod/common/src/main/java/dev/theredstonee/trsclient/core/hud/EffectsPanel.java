package dev.theredstonee.trsclient.core.hud;

import dev.theredstonee.trsclient.core.format.HudFormat;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MinimapRenderer;
import dev.theredstonee.trsclient.core.module.ChoiceSetting;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextWidth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Effekt-Anzeige für alle Loader: Layout, Sortierung, Restzeit-Stile und Zeichnen. Die Bäume liefern nur die Effekte
 * ({@link #begin()}/{@link #add}/{@link #end}) und zeichnen das Symbol ({@link Icons}).
 */
public final class EffectsPanel {
	public enum Layout implements ChoiceSetting.Option {
		HORIZONTAL("Side by side"),
		VERTICAL("Stacked");

		private final String label;

		Layout(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum TimeStyle implements ChoiceSetting.Option {
		BAR("Bar"),
		TEXT("Time in the icon"),
		RING("Ring"),
		ROW("Icon, name and time");

		private final String label;

		TimeStyle(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum Sort implements ChoiceSetting.Option {
		TIME("Remaining time"),
		TYPE("Good first, then bad"),
		RECEIVED("Order received");

		private final String label;

		Sort(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public static final int BENEFICIAL = 0;
	public static final int NEUTRAL = 1;
	public static final int HARMFUL = 2;

	/** Ab hier gilt ein Effekt als unendlich (Vanilla vor 1.19.4 zeigt dann „**:**“). */
	public static final int LONG_TICKS = 32767;
	public static final int BLINK_TICKS = 200;

	public static final int ICON = 18;
	static final int GAP = 2;
	static final int CELL = 22;
	static final int BAR_H = 2;
	static final int RING_CELL = 28;
	static final float RING_IN = 11.5f;
	static final float RING_OUT = 13.5f;

	static final int GOOD = 0xFF4CD964;
	static final int BAD = 0xFFFF4C4C;
	static final int TRACK = 0x99000000;

	/** Ein aktiver Effekt (wird je Bild wiederverwendet). */
	public static final class Entry {
		public String key;
		public String name;
		public int color;
		public int amplifier;
		public int duration;
		public boolean infinite;
		public int category;
		/** Symbol der Version (für {@link Icons}). */
		public Object icon;
		/** Gesamtdauer für Balken/Ring; 0 = vom Panel gemerkt. */
		public int max;
		long seq;

		boolean endless() {
			return infinite || duration >= LONG_TICKS;
		}

		float fraction() {
			if (endless()) return 1f;
			return max <= 0 ? 1f : Math.max(0f, Math.min(1f, duration / (float) max));
		}
	}

	/** Zeichnet das Effekt-Symbol (18×18) an (x, y); {@code argb} = Tönung, Alpha für das Blinken. */
	public interface Icons {
		void draw(Canvas c, Object icon, int x, int y, int argb);
	}

	private static final class Track {
		long seq;
		int max;
		int last;
		int amplifier;
		int frame;
	}

	private final TrsModules m;
	private final HudModule module;
	private final List<Entry> pool = new ArrayList<Entry>();
	private final List<Entry> list = new ArrayList<Entry>();
	private final Map<String, Track> tracks = new HashMap<String, Track>();
	private long nextSeq;
	private int frame;

	private final Comparator<Entry> byTime = new Comparator<Entry>() {
		@Override
		public int compare(Entry a, Entry b) {
			boolean ea = a.endless();
			boolean eb = b.endless();
			if (ea != eb) return ea ? 1 : -1;
			if (!ea && a.duration != b.duration) return a.duration < b.duration ? -1 : 1;
			return a.seq < b.seq ? -1 : a.seq > b.seq ? 1 : 0;
		}
	};
	private final Comparator<Entry> byType = new Comparator<Entry>() {
		@Override
		public int compare(Entry a, Entry b) {
			if (a.category != b.category) return a.category < b.category ? -1 : 1;
			return byTime.compare(a, b);
		}
	};
	private final Comparator<Entry> bySeq = new Comparator<Entry>() {
		@Override
		public int compare(Entry a, Entry b) {
			return a.seq < b.seq ? -1 : a.seq > b.seq ? 1 : 0;
		}
	};

	public EffectsPanel(TrsModules modules) {
		this.m = modules;
		this.module = modules.effects;
	}

	public TimeStyle style() {
		return m.effectsTimeStyle.get();
	}

	/** Braucht der Stil die Namen? (sonst muss der Baum sie nicht übersetzen) */
	public boolean needsNames() {
		return style() == TimeStyle.ROW;
	}

	public void begin() {
		for (int i = 0; i < list.size(); i++) pool.add(list.get(i));
		list.clear();
	}

	public Entry add(String key) {
		Entry e = pool.isEmpty() ? new Entry() : pool.remove(pool.size() - 1);
		e.key = key;
		e.name = "";
		e.color = 0xFFFFFF;
		e.amplifier = 0;
		e.duration = 0;
		e.infinite = false;
		e.category = BENEFICIAL;
		e.icon = null;
		e.max = 0;
		e.seq = 0;
		list.add(e);
		return e;
	}

	/** Abschluss nach den {@link #add}-Aufrufen. {@code track} = echte Effekte (Vorschau: false). */
	public void end(boolean track) {
		if (track) {
			frame++;
			for (int i = 0; i < list.size(); i++) {
				Entry e = list.get(i);
				Track t = tracks.get(e.key);
				if (t == null) {
					t = new Track();
					t.seq = nextSeq++;
					t.max = e.duration;
					tracks.put(e.key, t);
				} else if (e.duration > t.last + 20 || e.amplifier != t.amplifier) {
					t.max = e.duration;
				}
				t.last = e.duration;
				t.amplifier = e.amplifier;
				t.frame = frame;
				if (e.max <= 0) e.max = Math.max(t.max, e.duration);
				e.seq = t.seq;
			}
			if (tracks.size() > list.size()) {
				for (Iterator<Track> it = tracks.values().iterator(); it.hasNext(); ) {
					if (it.next().frame != frame) it.remove();
				}
			}
		} else {
			for (int i = 0; i < list.size(); i++) list.get(i).seq = i;
		}
		Sort sort = m.effectsSort.get();
		Collections.sort(list, sort == Sort.TYPE ? byType : sort == Sort.RECEIVED ? bySeq : byTime);
	}

	public int count() {
		return list.size();
	}

	Entry get(int i) {
		return list.get(i);
	}

	// Zeilen mit Namen stehen immer untereinander, nebeneinander würden sie zu breit
	static boolean horizontal(TrsModules m) {
		return m.effectsLayout.get() == Layout.HORIZONTAL && m.effectsTimeStyle.get() != TimeStyle.ROW;
	}

	private boolean horizontal() {
		return horizontal(m);
	}

	/** Restzeit als Text; leer bei endlosen Effekten (∞ wird gezeichnet, nicht jede Schrift hat das Zeichen). */
	private String time(Entry e) {
		if (e.endless()) return "";
		return HudFormat.duration(e.duration, false);
	}

	private boolean infinity(Entry e) {
		return e.endless() && m.effectsLevel.get();
	}

	private int timeWidth(Entry e, TextWidth tw) {
		return infinity(e) ? INF_W : tw.width(time(e));
	}

	private String level(Entry e) {
		return m.effectsLevel.get() ? HudFormat.level(e.amplifier) : "";
	}

	private String rowName(Entry e) {
		String lvl = level(e);
		return lvl.isEmpty() ? e.name : e.name + " " + lvl;
	}

	private int cellW(Entry e, TextWidth tw) {
		switch (style()) {
			case RING:
				return RING_CELL;
			case ROW:
				return rowW(e, tw);
			default:
				return CELL;
		}
	}

	private int cellH() {
		switch (style()) {
			case BAR:
				return CELL + BAR_H + 2;
			case RING:
				return RING_CELL;
			default:
				return CELL;
		}
	}

	private int rowW(Entry e, TextWidth tw) {
		int text = Math.max(tw.width(rowName(e)), timeWidth(e, tw));
		return 2 + ICON + 4 + text + 4;
	}

	private int maxRowW(TextWidth tw) {
		int w = 0;
		for (int i = 0; i < list.size(); i++) w = Math.max(w, cellW(list.get(i), tw));
		return w;
	}

	public int width(TextWidth tw) {
		int n = list.size();
		if (n == 0) return CELL;
		if (!horizontal()) return maxRowW(tw);
		int w = 0;
		for (int i = 0; i < n; i++) w += cellW(list.get(i), tw);
		return w + GAP * (n - 1);
	}

	public int height() {
		int n = list.size();
		if (n == 0) return cellH();
		if (horizontal()) return cellH();
		return n * cellH() + GAP * (n - 1);
	}

	public void draw(Canvas c, TextWidth tw, Icons icons, long nowMs) {
		boolean horizontal = horizontal();
		int rowW = horizontal ? 0 : maxRowW(tw);
		int h = cellH();
		int x = 0;
		int y = 0;
		for (int i = 0; i < list.size(); i++) {
			Entry e = list.get(i);
			int w = horizontal ? cellW(e, tw) : rowW;
			drawCell(c, e, x, y, w, h, icons, nowMs);
			if (horizontal) x += w + GAP;
			else y += h + GAP;
		}
	}

	private int alpha(Entry e, long nowMs) {
		if (!m.effectsBlink.get() || e.endless() || e.duration > BLINK_TICKS) return 255;
		double t = (nowMs % 800L) / 800.0;
		return 70 + (int) Math.round(185 * (0.5 + 0.5 * Math.cos(t * 2 * Math.PI)));
	}

	private void drawCell(Canvas c, Entry e, int x, int y, int w, int h, Icons icons, long nowMs) {
		int bg = module.backgroundArgb();
		if (bg != 0) c.fill(x, y, x + w, y + h, bg);
		if (m.effectsBorder.get() && e.category != NEUTRAL) outline(c, x, y, w, h, e.category == BENEFICIAL ? GOOD : BAD);
		int a = alpha(e, nowMs);
		int tint = (a << 24) | 0xFFFFFF;
		int text = withAlpha(module.textColor.argb(), a);
		boolean shadow = module.shadow();
		int color = 0xFF000000 | e.color;
		switch (style()) {
			case BAR: {
				icons.draw(c, e.icon, x + 2, y + 2, tint);
				int bx = x + 2;
				int by = y + CELL;
				c.fill(bx, by, bx + ICON, by + BAR_H, TRACK);
				int fill = Math.round(ICON * e.fraction());
				if (fill > 0) c.fill(bx, by, bx + fill, by + BAR_H, withAlpha(color, a));
				corner(c, e, x, y, w, text, shadow);
				break;
			}
			case TEXT: {
				icons.draw(c, e.icon, x + 2, y + 2, tint);
				corner(c, e, x, y, w, text, shadow);
				String t = time(e);
				if (infinity(e)) {
					infinity(c, x + (w - INF_W) / 2, y + h - 9, text, true);
				} else if (!t.isEmpty()) {
					c.flush();
					int tw = c.textWidth(t);
					float s = tw > w - 2 ? (w - 2) / (float) tw : 1f;
					c.push();
					c.translate(x + (w - tw * s) / 2f, y + h - 1 - 8 * s);
					c.scale(s);
					c.text(t, 0, 0, text, true);
					c.pop();
				}
				break;
			}
			case RING: {
				ring(c, x + RING_CELL / 2f, y + RING_CELL / 2f, e.fraction(), withAlpha(color, a));
				icons.draw(c, e.icon, x + (RING_CELL - ICON) / 2, y + (RING_CELL - ICON) / 2, tint);
				corner(c, e, x, y, w, text, shadow);
				break;
			}
			default: {
				icons.draw(c, e.icon, x + 2, y + 2, tint);
				int tx = x + 2 + ICON + 4;
				c.text(rowName(e), tx, y + 2, withAlpha(readable(color), a), shadow);
				if (infinity(e)) infinity(c, tx, y + 12, text, shadow);
				else c.text(time(e), tx, y + 12, text, shadow);
				break;
			}
		}
	}

	private void corner(Canvas c, Entry e, int x, int y, int w, int argb, boolean shadow) {
		String lvl = level(e);
		if (lvl.isEmpty()) return;
		c.flush();
		c.push();
		float s = 0.75f;
		int tw = c.textWidth(lvl);
		c.translate(x + w - 1 - tw * s, y + 1);
		c.scale(s);
		c.text(lvl, 0, 0, argb, shadow);
		c.pop();
	}

	// ∞ als Pixelbild, 9×5, auf Höhe der Ziffern
	private static final String[] INF = {".##...##.", "#..#.#..#", "#...#...#", "#..#.#..#", ".##...##."};
	static final int INF_W = 9;

	private static void infinity(Canvas c, int x, int y, int argb, boolean shadow) {
		if (shadow) infinityPixels(c, x + 1, y + 2, (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2));
		infinityPixels(c, x, y + 1, argb);
	}

	private static void infinityPixels(Canvas c, int x, int y, int argb) {
		for (int row = 0; row < INF.length; row++) {
			String line = INF[row];
			int start = -1;
			for (int i = 0; i <= line.length(); i++) {
				boolean on = i < line.length() && line.charAt(i) == '#';
				if (on && start < 0) start = i;
				if (!on && start >= 0) {
					c.fill(x + start, y + row, x + i, y + row + 1, argb);
					start = -1;
				}
			}
		}
	}

	private static void outline(Canvas c, int x, int y, int w, int h, int argb) {
		c.fill(x, y, x + w, y + 1, argb);
		c.fill(x, y + h - 1, x + w, y + h, argb);
		c.fill(x, y + 1, x + 1, y + h - 1, argb);
		c.fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
	}

	// dunkle Effektfarben (Langsamkeit, Nachtsicht) auf dem Hintergrund aufhellen
	static int readable(int argb) {
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		if (r * 299 + g * 587 + b * 114 >= 110000) return argb;
		r += (255 - r) / 2;
		g += (255 - g) / 2;
		b += (255 - b) / 2;
		return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
	}

	private static int withAlpha(int argb, int a) {
		int base = (argb >>> 24) & 0xFF;
		return ((base * a / 255) << 24) | (argb & 0xFFFFFF);
	}

	// Ring-Pixel im Uhrzeigersinn ab oben, einmal berechnet
	private static int[] ringPixels;

	static int[] ringPixels() {
		if (ringPixels != null) return ringPixels;
		List<double[]> px = new ArrayList<double[]>();
		int r = (int) Math.ceil(RING_OUT);
		for (int dy = -r; dy < r; dy++) {
			for (int dx = -r; dx < r; dx++) {
				double cx = dx + 0.5;
				double cy = dy + 0.5;
				double d = Math.sqrt(cx * cx + cy * cy);
				if (d < RING_IN || d > RING_OUT) continue;
				double ang = Math.atan2(cx, -cy);
				if (ang < 0) ang += 2 * Math.PI;
				px.add(new double[]{ang, dx, dy});
			}
		}
		Collections.sort(px, new Comparator<double[]>() {
			@Override
			public int compare(double[] a, double[] b) {
				return Double.compare(a[0], b[0]);
			}
		});
		int[] out = new int[px.size() * 2];
		for (int i = 0; i < px.size(); i++) {
			out[2 * i] = (int) px.get(i)[1];
			out[2 * i + 1] = (int) px.get(i)[2];
		}
		ringPixels = out;
		return out;
	}

	// je Bildzeile: dy, dann Paare (dx, Rang im Uhrzeigersinn), nach dx sortiert
	private static int[][] ringRows;

	private static int[][] ringRows() {
		if (ringRows != null) return ringRows;
		int[] px = ringPixels();
		int r = (int) Math.ceil(RING_OUT);
		List<int[]> rows = new ArrayList<int[]>();
		for (int dy = -r; dy < r; dy++) {
			List<int[]> cells = new ArrayList<int[]>();
			for (int i = 0; i < px.length / 2; i++) {
				if (px[2 * i + 1] == dy) cells.add(new int[]{px[2 * i], i});
			}
			if (cells.isEmpty()) continue;
			Collections.sort(cells, new Comparator<int[]>() {
				@Override
				public int compare(int[] a, int[] b) {
					return a[0] - b[0];
				}
			});
			int[] row = new int[1 + cells.size() * 2];
			row[0] = dy;
			for (int k = 0; k < cells.size(); k++) {
				row[1 + 2 * k] = cells.get(k)[0];
				row[2 + 2 * k] = cells.get(k)[1];
			}
			rows.add(row);
		}
		ringRows = rows.toArray(new int[0][]);
		return ringRows;
	}

	// zusammenhängende Pixel einer Zeile als ein Rechteck (deutlich weniger Flächen)
	static void ring(Canvas c, float cx, float cy, float fraction, int argb) {
		int lit = Math.round(ringPixels().length / 2 * fraction);
		int ox = Math.round(cx);
		int oy = Math.round(cy);
		for (int[] row : ringRows()) {
			int y = oy + row[0];
			int n = (row.length - 1) / 2;
			int start = row[1];
			boolean on = row[2] < lit;
			for (int k = 1; k <= n; k++) {
				boolean end = k == n;
				int dx = end ? 0 : row[1 + 2 * k];
				boolean nextOn = !end && row[2 + 2 * k] < lit;
				if (end || dx != row[1 + 2 * (k - 1)] + 1 || nextOn != on) {
					c.fill(ox + start, y, ox + row[1 + 2 * (k - 1)] + 1, y + 1, on ? argb : TRACK);
					if (!end) {
						start = dx;
						on = nextOn;
					}
				}
			}
		}
	}

	/**
	 * Standardplatz: liegt die Anzeige an ihrer unveränderten Standardposition über der Minimap, rückt sie daneben
	 * (waagerecht links davon, senkrecht darunter). Sonst false.
	 */
	public static boolean dock(TrsModules m, int w, int h, int sw, int sh, int[] out) {
		HudModule effects = m.effects;
		HudModule map = m.minimap;
		if (!map.isEnabled() || !effects.isDefaultPosition()) return false;
		MapEngine engine = MapEngine.get();
		if (engine != null && !engine.fairPlay().minimapAllowed()) return false;
		float ms = map.scale.getFloat();
		int mw = (int) Math.ceil(MinimapRenderer.outerWidth(m) * ms);
		int mh = (int) Math.ceil(MinimapRenderer.outerHeight(m) * ms);
		int mx = HudLayout.resolveX(map.position(), mw, sw);
		int my = HudLayout.resolveY(map.position(), mh, sh);
		int x = HudLayout.resolveX(effects.position(), w, sw);
		int y = HudLayout.resolveY(effects.position(), h, sh);
		if (x >= mx + mw || x + w <= mx || y >= my + mh || y + h <= my) return false;
		int gap = 3;
		boolean horizontal = horizontal(m);
		boolean left = mx - gap - w >= 0;
		boolean below = my + mh + gap + h <= sh;
		if (horizontal ? left || !below : !below && left) {
			x = mx - gap - w;
			y = my;
		} else {
			x = mx + mw - w;
			y = my + mh + gap;
		}
		out[0] = Math.max(0, Math.min(x, sw - w));
		out[1] = Math.max(0, Math.min(y, sh - h));
		return true;
	}
}
