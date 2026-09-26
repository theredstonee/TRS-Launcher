package dev.theredstonee.trsclient.core.qol;

import dev.theredstonee.trsclient.core.alert.DeathCompass;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.pvp.HitFeedback;
import dev.theredstonee.trsclient.core.pvp.TotemPops;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.TextWidth;

import java.util.List;

/**
 * Versionsunabhängige Zeichnungen des Pakets: Warnungen/Hinweise mit Todespunkt-Kompass, Hitmarker und Totem-Liste.
 * Die Loader reichen nur Canvas, Textbreite und Spielerposition durch.
 */
public final class QolPanels {
	private static final int COMPASS_H = 14;

	private QolPanels() {
	}

	/** Spielerposition für den Todespunkt-Kompass (vom Loader je Bild gesetzt). */
	public static final class Where {
		public double x, z;
		public float yaw;
		public String dimension;
		public boolean valid;
	}

	// --- Warnungen + Hinweise ---

	/** Text der Kompasszeile oder null (nicht sichtbar). */
	public static String compassText(Qol q, Where w, long now) {
		if (q == null || w == null || !w.valid || !q.m.warnDeathCompass.get() || !q.modules.waypointDeath.get()) return null;
		DeathCompass d = q.death;
		if (!d.visible(w.x, w.z, w.dimension, now)) return null;
		int dist = (int) Math.round(d.distance(w.x, w.z));
		return I18n.tr("warn.death", dist, d.arrow(w.x, w.z, w.yaw));
	}

	public static boolean warningsVisible(Qol q, Where w, long now) {
		return q != null && (!q.notices.isEmpty() || compassText(q, w, now) != null);
	}

	public static int warningsWidth(Qol q, Where w, TextWidth tw, boolean preview, long now) {
		if (q == null) return 60;
		String previewText = I18n.tr("warn.armor", I18n.tr("warn.slot.0"), 8);
		int width = q.notices.width(tw, preview, previewText);
		String compass = preview ? I18n.tr("warn.death", 128, "↗") : compassText(q, w, now);
		if (compass != null) width = Math.max(width, tw.width(compass) + 12);
		return Math.max(width, 20);
	}

	public static int warningsHeight(Qol q, Where w, boolean preview, long now) {
		if (q == null) return 16;
		int h = q.notices.height(preview);
		boolean compass = preview || compassText(q, w, now) != null;
		if (compass) h += (h > 0 ? 2 : 0) + COMPASS_H;
		return Math.max(h, 1);
	}

	public static void drawWarnings(Canvas c, Qol q, Where w, TextWidth tw, boolean preview, long now, int bgArgb, boolean shadow) {
		if (q == null) return;
		int bgAlpha = (bgArgb >>> 24) & 0xFF;
		String previewText = I18n.tr("warn.armor", I18n.tr("warn.slot.0"), 8);
		q.notices.draw(c, tw, now, preview, previewText, bgAlpha == 0 ? 90 : bgAlpha, shadow);
		String compass = preview ? I18n.tr("warn.death", 128, "↗") : compassText(q, w, now);
		if (compass != null) {
			int y = q.notices.height(preview);
			if (y > 0) y += 2;
			int width = tw.width(compass) + 12;
			Paint.roundRect(c, 0, y, width, COMPASS_H, 3, ((Math.max(40, bgAlpha)) << 24) | 0x101014);
			Icons.draw(c, "pin", 3, y + 3, 1, 0xFFFF5A5A);
			c.text(compass, 13, y + 3, 0xFFFFFFFF, shadow);
		}
	}

	// --- Hitmarker ---

	/** Hitmarker in Bildschirmmitte (GUI-Koordinaten). */
	public static void drawHitmarker(Canvas c, Qol q, int screenW, int screenH, long now) {
		if (q == null || !q.m.hitFeedback.isEnabled() || !q.m.hitMarker.get()) return;
		float a = q.hits.alpha(now, (long) q.m.hitMarkerDuration.get());
		if (a <= 0.02f) return;
		int color = q.hits.critical() ? 0xFFFF5A3C : (0xFF000000 | q.m.hitMarkerColor.rgb());
		HitFeedback.draw(c, screenW / 2, screenH / 2, q.m.hitMarkerSize.getInt(), color, a);
	}

	// --- Totem-Pops ---

	/** Zeilen „Name: 2“ der zuletzt aktiven Gegner. */
	public static List<TotemPops.Entry> pops(Qol q, long now) {
		return q.pops.recent(now);
	}
}
