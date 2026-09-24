package dev.theredstonee.trsclient.core.redstone;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.TextWidth;

/**
 * Zeichnet die beiden Redstone-HUD-Anzeigen über {@link Canvas} – in jeder Minecraft-Version gleich.
 * Größen sind unskaliert (die Skalierung macht der HUD-Manager des Loaders), gezeichnet wird bei (0, 0).
 */
public final class RedstonePanels {
	private static final int PAD_X = 5;
	private static final int PAD_Y = 4;
	private static final int LINE_H = 10;
	private static final int SEGMENTS = 15;
	private static final int SEGMENT_W = 3;
	private static final int BAR_W = SEGMENTS * (SEGMENT_W + 1) - 1;
	private static final int NUMBER_W = 12;
	/** Oszilloskop: ein Pixel je Spiel-Tick (5 Sekunden). */
	public static final int SCOPE_W = 100;
	private static final int SCOPE_H = 18;
	private static final int UNLIT = 0x50FFFFFF;

	private RedstonePanels() {
	}

	// --- Signalstärke ---

	/** Anzeige der Signalstärke des angeschauten Bauteils. */
	public static final class Signal {
		private final HudModule module;
		private final TrsModules modules;
		private final RedstoneTools tools;
		private RedstoneReadout preview;
		private int previewGeneration = -1;

		public Signal(TrsModules modules, RedstoneTools tools) {
			this.modules = modules;
			this.module = modules.redstoneSignal;
			this.tools = tools;
		}

		/** Im Spiel sichtbar (ein Bauteil wird angeschaut)? */
		public boolean visible() {
			return tools.readout().valid;
		}

		/** Anzeige-Daten: echte oder (im HUD-Editor) Beispielwerte. */
		public RedstoneReadout readout(boolean preview) {
			if (!preview || tools.readout().valid) return tools.readout();
			if (this.preview == null || previewGeneration != I18n.generation()) {
				previewGeneration = I18n.generation();
				this.preview = RedstoneReadout.preview();
			}
			return this.preview;
		}

		private boolean name() {
			return modules.redstoneSignalName.get();
		}

		private boolean bar() {
			return modules.redstoneSignalBar.get();
		}

		private boolean details() {
			return modules.redstoneSignalDetails.get();
		}

		/** Breite je Tick nur einmal messen (Sichtbarkeit, Ankerung und Zeichnen fragen mehrmals je Bild). */
		private long widthTick = -1;
		private int widthKey = -1;
		private int widthValue;

		public int width(TextWidth tw, boolean preview) {
			int key = (preview ? 1 : 0) | (name() ? 2 : 0) | (details() ? 4 : 0) | (bar() ? 8 : 0) | (I18n.generation() << 4);
			long tick = tools.tickCount();
			if (tick == widthTick && key == widthKey) return widthValue;
			RedstoneReadout r = readout(preview);
			int w = signalRowWidth(tw, r);
			if (name()) w = Math.max(w, tw.width(r.name));
			if (details()) {
				for (int i = 0; i < r.details.size(); i++) w = Math.max(w, tw.width(r.details.get(i)));
			}
			widthTick = tick;
			widthKey = key;
			widthValue = w + PAD_X * 2 + 2;
			return widthValue;
		}

		public int height(boolean preview) {
			RedstoneReadout r = readout(preview);
			int rows = 1 + (name() ? 1 : 0) + (details() ? r.details.size() : 0);
			return rows * LINE_H - 2 + PAD_Y * 2;
		}

		private int signalRowWidth(TextWidth tw, RedstoneReadout r) {
			if (bar()) return tw.width(r.signalLabel) + 4 + BAR_W + 4 + NUMBER_W;
			return tw.width(signalText(r));
		}

		private static String signalText(RedstoneReadout r) {
			return r.signalLabel + ": " + (r.signal >= 0 ? SignalColors.digits(r.signal) : "?");
		}

		public void draw(Canvas c, TextWidth tw, boolean preview) {
			RedstoneReadout r = readout(preview);
			int w = width(tw, preview);
			int h = height(preview);
			int bg = module.backgroundArgb();
			boolean shadow = module.shadow();
			int text = module.textColor.argb();
			if (bg != 0) c.fill(0, 0, w, h, bg);
			// Redstone-Kante links: leuchtet in der Farbe der Signalstärke.
			c.fill(0, 0, 2, h, r.signal > 0 ? SignalColors.color(r.signal) : ColorMath.withAlpha(SignalColors.OFF, 160));
			int x = PAD_X + 2;
			int y = PAD_Y;
			if (name()) {
				c.text(r.name, x, y, text, shadow);
				y += LINE_H;
			}
			if (bar()) {
				c.text(r.signalLabel, x, y, text, shadow);
				int bx = x + tw.width(r.signalLabel) + 4;
				drawBar(c, bx, y + 1, r.signal);
				String number = r.signal >= 0 ? SignalColors.digits(r.signal) : "?";
				int nx = bx + BAR_W + 4 + NUMBER_W - tw.width(number);
				c.text(number, nx, y, r.signal > 0 ? SignalColors.color(r.signal) : text, shadow);
			} else {
				c.text(signalText(r), x, y, text, shadow);
			}
			y += LINE_H;
			if (details()) {
				for (int i = 0; i < r.details.size(); i++) {
					c.text(r.details.get(i), x, y, text, shadow);
					y += LINE_H;
				}
			}
		}
	}

	/** 15 Segmente, die bis zur Signalstärke leuchten (jede Stufe in ihrer eigenen Farbe). */
	static void drawBar(Canvas c, int x, int y, int signal) {
		for (int i = 0; i < SEGMENTS; i++) {
			int sx = x + i * (SEGMENT_W + 1);
			boolean lit = signal > i;
			c.fill(sx, y, sx + SEGMENT_W, y + 7, lit ? SignalColors.color(i + 1) : UNLIT);
			if (lit) c.fill(sx, y, sx + SEGMENT_W, y + 1, ColorMath.lerp(SignalColors.color(i + 1), 0xFFFFFFFF, 0.35f));
		}
	}

	// --- Takt ---

	/** Liefert den Oszilloskop-Verlauf: Stärke vor {@code ago} Ticks oder -1. */
	public interface Trace {
		int level(int ago);
	}

	/** Beispiel-Takt für die Vorschau: 8 Ticks Periode, 4 an. */
	private static final Trace PREVIEW_TRACE = new Trace() {
		@Override
		public int level(int ago) {
			return (ago % 8) < 4 ? 15 : 0;
		}
	};

	/** Takt-Messer: Frequenz, Periode, Pulslänge und Oszilloskop. */
	public static final class Clock {
		private final HudModule module;
		private final TrsModules modules;
		private final RedstoneTools tools;
		private final Trace live;
		private String[] previewLines;
		private int previewGeneration = -1;

		public Clock(TrsModules modules, RedstoneTools tools) {
			this.modules = modules;
			this.module = modules.redstoneClock;
			this.tools = tools;
			this.live = new Trace() {
				@Override
				public int level(int ago) {
					return RedstonePanels.Clock.this.tools.meter().level(ago);
				}
			};
		}

		private boolean scope() {
			return modules.redstoneClockScope.get();
		}

		/** Im Spiel sichtbar (Bauteil angeschaut oder es schaltet)? */
		public boolean visible() {
			return tools.clockVisible();
		}

		/** Beispielwerte nur, wenn im Editor gerade nichts gemessen wird. */
		private boolean sample(boolean preview) {
			return preview && !tools.clockVisible();
		}

		private String name(boolean preview) {
			return sample(preview) ? previewLines()[0] : tools.clockName();
		}

		private int lineCount(boolean preview) {
			return sample(preview) ? previewLines().length - 1 : tools.clockLines().size();
		}

		private String line(boolean preview, int i) {
			return sample(preview) ? previewLines()[i + 1] : tools.clockLines().get(i);
		}

		private String[] previewLines() {
			if (previewLines == null || previewGeneration != I18n.generation()) {
				previewGeneration = I18n.generation();
				previewLines = new String[]{
						I18n.tr("hud.redstone.preview.repeater"),
						I18n.tr("hud.redstone.hz", String.format(I18n.locale(), "%.2f", 2.5)),
						I18n.tr("hud.redstone.period", 4, 8),
						I18n.tr("hud.redstone.pulse", 2)};
			}
			return previewLines;
		}

		public int width(TextWidth tw, boolean preview) {
			int w = tw.width(name(preview));
			for (int i = 0, n = lineCount(preview); i < n; i++) w = Math.max(w, tw.width(line(preview, i)));
			if (scope()) w = Math.max(w, SCOPE_W);
			return w + PAD_X * 2 + 2;
		}

		public int height(boolean preview) {
			int rows = 1 + lineCount(preview);
			return rows * LINE_H - 2 + PAD_Y * 2 + (scope() ? SCOPE_H + 3 : 0);
		}

		public void draw(Canvas c, TextWidth tw, boolean preview) {
			int w = width(tw, preview);
			int h = height(preview);
			int bg = module.backgroundArgb();
			boolean shadow = module.shadow();
			int text = module.textColor.argb();
			Trace trace = sample(preview) ? PREVIEW_TRACE : live;
			if (bg != 0) c.fill(0, 0, w, h, bg);
			int now = Math.max(0, trace.level(0));
			c.fill(0, 0, 2, h, now > 0 ? SignalColors.color(now) : ColorMath.withAlpha(SignalColors.OFF, 160));
			int x = PAD_X + 2;
			int y = PAD_Y;
			c.text(name(preview), x, y, text, shadow);
			y += LINE_H;
			for (int i = 0, n = lineCount(preview); i < n; i++) {
				c.text(line(preview, i), x, y, i == 0 ? ColorMath.lerp(text, SignalColors.color(15), 0.35f) : text, shadow);
				y += LINE_H;
			}
			if (scope()) drawScope(c, x, y + 1, Math.max(SCOPE_W, w - PAD_X * 2 - 2), trace);
		}
	}

	/** Oszilloskop: ein Pixel je Tick, rechts = jetzt, Höhe = Signalstärke. */
	static void drawScope(Canvas c, int x, int y, int w, Trace trace) {
		c.fill(x, y, x + w, y + SCOPE_H, 0x70000000);
		// Raster: Linie je Sekunde (20 Ticks)
		for (int t = 20; t < w; t += 20) c.fill(x + w - 1 - t, y + 1, x + w - t, y + SCOPE_H - 1, 0x22FFFFFF);
		int prev = Integer.MIN_VALUE;
		for (int i = 0; i < w; i++) {
			int ago = w - 1 - i;
			int level = trace.level(ago);
			if (level < 0) {
				prev = Integer.MIN_VALUE;
				continue;
			}
			int py = y + SCOPE_H - 2 - Math.round(level * (SCOPE_H - 4) / 15f);
			int color = SignalColors.color(level);
			if (prev != Integer.MIN_VALUE && prev != py) {
				c.fill(x + i, Math.min(prev, py), x + i + 1, Math.max(prev, py) + 1, color);
			} else {
				c.fill(x + i, py, x + i + 1, py + 1, color);
			}
			prev = py;
		}
	}

}
