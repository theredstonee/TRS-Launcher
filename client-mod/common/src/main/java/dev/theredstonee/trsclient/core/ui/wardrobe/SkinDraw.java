package dev.theredstonee.trsclient.core.ui.wardrobe;

import dev.theredstonee.trsclient.core.ui.Affine;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;

/**
 * Kleine, billige Vorschaubilder für das Raster: Skins flach von vorn (12 Textur-Rechtecke statt der ganzen 3D-Figur),
 * Umhänge mit ihrer Außenseite. Ohne Textur-Unterstützung wird wenigstens das Gesicht aus Rechtecken gezeichnet.
 */
final class SkinDraw {
	private SkinDraw() {
	}

	/** Breite × Höhe der flachen Figur in Skin-Pixeln. */
	static final int DOLL_W = 16;
	static final int DOLL_H = 32;

	/** Flache Vorderansicht in das Rechteck (zentriert, unten bündig). */
	static void doll(Canvas c, TextureRef tex, int[] pixels, boolean slim, int x, int y, int w, int h) {
		float s = Math.min(w / (float) DOLL_W, h / (float) DOLL_H);
		if (s >= 2f) s = (float) Math.floor(s);
		float ox = x + (w - DOLL_W * s) / 2f;
		float oy = y + h - DOLL_H * s;
		if (tex == null || !c.images()) {
			face(c, pixels, Math.round(ox + 4 * s), Math.round(oy), Math.max(1, Math.round(s)));
			return;
		}
		int arm = slim ? 3 : 4;
		// Grundebene
		part(c, tex, ox + 4 * s, oy, 8, 8, 8, 8, s);
		part(c, tex, ox + 4 * s, oy + 8 * s, 20, 20, 8, 12, s);
		part(c, tex, ox + (4 - arm) * s, oy + 8 * s, 44, 20, arm, 12, s);
		part(c, tex, ox + 12 * s, oy + 8 * s, 36, 52, arm, 12, s);
		part(c, tex, ox + 4 * s, oy + 20 * s, 4, 20, 4, 12, s);
		part(c, tex, ox + 8 * s, oy + 20 * s, 20, 52, 4, 12, s);
		// Zweite Ebene
		part(c, tex, ox + 4 * s, oy, 40, 8, 8, 8, s);
		part(c, tex, ox + 4 * s, oy + 8 * s, 20, 36, 8, 12, s);
		part(c, tex, ox + (4 - arm) * s, oy + 8 * s, 44, 36, arm, 12, s);
		part(c, tex, ox + 12 * s, oy + 8 * s, 52, 52, arm, 12, s);
		part(c, tex, ox + 4 * s, oy + 20 * s, 4, 36, 4, 12, s);
		part(c, tex, ox + 8 * s, oy + 20 * s, 4, 52, 4, 12, s);
	}

	private static void part(Canvas c, TextureRef tex, float x, float y, int u, int v, int tw, int th, float s) {
		Affine.image(c, tex, x, y, tw * s, th * s, u, v, tw, th, 0xFFFFFFFF);
	}

	/** Gesicht (mit Hut) aus Rechtecken. */
	static void face(Canvas c, int[] px, int x, int y, int size) {
		if (px == null) return;
		for (int i = 0; i < 64; i++) {
			int fx = x + (i % 8) * size;
			int fy = y + (i / 8) * size;
			int base = px[(8 + i / 8) * 64 + 8 + i % 8] | 0xFF000000;
			c.fill(fx, fy, fx + size, fy + size, base);
			int hat = px[(8 + i / 8) * 64 + 40 + i % 8];
			if ((hat >>> 24) >= 0x80) c.fill(fx, fy, fx + size, fy + size, hat | 0xFF000000);
		}
	}

	/** Umhang-Außenseite (10×16 Vanilla-Einheiten) in das Rechteck. */
	static void cape(Canvas c, TextureRef tex, int x, int y, int w, int h) {
		if (tex == null || !c.images()) return;
		float unit = tex.width / 64f;
		float s = Math.min(w / 10f, h / 16f);
		float ox = x + (w - 10 * s) / 2f;
		float oy = y + (h - 16 * s) / 2f;
		Affine.image(c, tex, ox, oy, 10 * s, 16 * s, unit, unit, Math.round(10 * unit), Math.round(16 * unit), 0xFFFFFFFF);
	}
}
