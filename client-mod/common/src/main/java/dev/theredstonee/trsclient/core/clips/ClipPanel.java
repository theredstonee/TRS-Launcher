package dev.theredstonee.trsclient.core.clips;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.TextWidth;

/**
 * HUD-Anzeige "Clips & Aufnahme": roter Punkt + Zeit während der Aufnahme, dezentes Puffer-Symbol,
 * Einblendung "Clip gespeichert (30 s)". Gezeichnet über {@link Canvas} bei (0, 0), unskaliert.
 */
public final class ClipPanel {
	private static final int PAD_X = 5;
	private static final int PAD_Y = 4;
	private static final int LINE_H = 10;
	private static final int DOT = 7;
	/** So lange steht eine Meldung im HUD. */
	static final long NOTICE_MS = 3500;
	private static final int RED = 0xFFE5322D;
	private static final int OK = 0xFF55D86A;
	private static final int WARN = 0xFFF2C744;

	private final HudModule module;
	private final TrsModules modules;
	public ClipPanel(TrsModules modules) {
		this.modules = modules;
		this.module = modules.clips;
	}

	/** Immer die aktuelle Instanz (init kann nach dem HUD-Aufbau laufen). */
	private static Clips clips() {
		return Clips.get();
	}

	private boolean bufferIcon() {
		return modules.clipsBufferIcon.get();
	}

	/** Zeile oben: Aufnahme oder Puffer; null = keine. */
	String statusLine(boolean preview, long now) {
		ClipStatus s = clips().status();
		if (s.recording) return I18n.tr("hud.clips.rec", ClipNotice.clock(s.recordingMillis(now)));
		if (s.buffer && bufferIcon()) return I18n.tr("hud.clips.buffer", ClipNotice.duration(s.clipSeconds));
		if (preview) return I18n.tr("hud.clips.rec", "0:42");
		return null;
	}

	private boolean recordingLook(boolean preview) {
		ClipStatus s = clips().status();
		return s.recording || (preview && !(s.buffer && bufferIcon()));
	}

	/** Im Spiel sichtbar? */
	public boolean visible() {
		long now = System.currentTimeMillis();
		return statusLine(false, now) != null || clips().notice(now) != null;
	}

	public int width(TextWidth tw, boolean preview) {
		long now = System.currentTimeMillis();
		int w = 0;
		String status = statusLine(preview, now);
		if (status != null) w = DOT + 4 + tw.width(status);
		ClipNotice notice = clips().notice(now);
		if (notice != null) w = Math.max(w, DOT + 4 + tw.width(notice.text()));
		return w + PAD_X * 2;
	}

	public int height(boolean preview) {
		long now = System.currentTimeMillis();
		int rows = (statusLine(preview, now) != null ? 1 : 0) + (clips().notice(now) != null ? 1 : 0);
		return Math.max(1, rows) * LINE_H - 2 + PAD_Y * 2;
	}

	public void draw(Canvas c, TextWidth tw, boolean preview) {
		long now = System.currentTimeMillis();
		int w = width(tw, preview);
		int h = height(preview);
		int bg = module.backgroundArgb();
		boolean shadow = module.shadow();
		int text = module.textColor.argb();
		if (bg != 0) c.fill(0, 0, w, h, bg);
		int y = PAD_Y;
		String status = statusLine(preview, now);
		if (status != null) {
			if (recordingLook(preview)) {
				// Roter Punkt, blinkt im Sekundentakt.
				boolean on = (now / 500) % 2 == 0 || preview;
				dot(c, PAD_X, y, on ? RED : ColorMath.withAlpha(RED, 90));
				c.text(status, PAD_X + DOT + 4, y, text, shadow);
			} else {
				// Puffer: Ring, gedämpft.
				ring(c, PAD_X, y, ColorMath.withAlpha(text, 150));
				c.text(status, PAD_X + DOT + 4, y, ColorMath.withAlpha(text, 170), shadow);
			}
			y += LINE_H;
		}
		ClipNotice notice = clips().notice(now);
		if (notice != null) {
			int color = notice.success() ? OK : (notice.type == ClipNotice.Type.FAILED ? RED : WARN);
			dot(c, PAD_X, y, color);
			c.text(notice.text(), PAD_X + DOT + 4, y, text, shadow);
		}
	}

	/** 7×7-Pixelkreis. */
	private static void dot(Canvas c, int x, int y, int argb) {
		c.fill(x + 2, y, x + 5, y + 7, argb);
		c.fill(x + 1, y + 1, x + 6, y + 6, argb);
		c.fill(x, y + 2, x + 7, y + 5, argb);
	}

	/** 7×7-Ring. */
	private static void ring(Canvas c, int x, int y, int argb) {
		c.fill(x + 2, y, x + 5, y + 1, argb);
		c.fill(x + 2, y + 6, x + 5, y + 7, argb);
		c.fill(x, y + 2, x + 1, y + 5, argb);
		c.fill(x + 6, y + 2, x + 7, y + 5, argb);
		c.fill(x + 1, y + 1, x + 2, y + 2, argb);
		c.fill(x + 5, y + 1, x + 6, y + 2, argb);
		c.fill(x + 1, y + 5, x + 2, y + 6, argb);
		c.fill(x + 5, y + 5, x + 6, y + 6, argb);
	}
}
