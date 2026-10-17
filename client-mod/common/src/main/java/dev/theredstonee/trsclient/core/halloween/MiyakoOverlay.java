package dev.theredstonee.trsclient.core.halloween;

import dev.theredstonee.trsclient.core.ui.Affine;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

/**
 * Zeichnet MiyakoMCs Timer-Bild-Schreck ({@code jumpscare.png}) formatfüllend über alles. Läuft auf JEDER Version
 * über {@link Canvas#image} (eigene Textur), nicht nur dem modernen Pfad. Das Bild kommt aus {@link Miyako} (privat,
 * nie in der Mod). Nur für MiyakoMC aktiv.
 */
public final class MiyakoOverlay {
	private static TextureRef tex;
	private static int texW;
	private static int texH;

	private MiyakoOverlay() {
	}

	/** Zeichnet den Bild-Schreck, falls gerade einer läuft. @return true, wenn etwas gezeichnet wurde */
	public static boolean draw(Canvas c, int width, int height, long now) {
		if (width <= 0 || height <= 0) return false;
		Miyako m = Miyako.get();
		float alpha = m.imageAlpha(now);
		if (alpha <= 0.004f) return false;
		c.push();
		c.raise(600f);
		try {
			// Schwarzer Hintergrund hinter dem Bild.
			c.fill(0, 0, width, height, ColorMath.withAlpha(0xFF000000, Math.round(255 * Math.min(1f, alpha))));
			int[] argb = m.imageArgb();
			int iw = m.imageWidth();
			int ih = m.imageHeight();
			if (argb != null && iw > 0 && ih > 0 && c.images()) {
				TextureRef ref = texture(argb, iw, ih);
				if (ref != null) {
					// Formatfüllend (cover): Seitenverhältnis bleibt, Ränder werden beschnitten.
					float s = Math.max(width / (float) iw, height / (float) ih);
					float dw = iw * s;
					float dh = ih * s;
					int tint = ColorMath.withAlpha(0xFFFFFFFF, Math.round(255 * alpha));
					Affine.image(c, ref, (width - dw) / 2f, (height - dh) / 2f, dw, dh, 0, 0, iw, ih, tint);
				}
			}
		} finally {
			c.pop();
		}
		return true;
	}

	private static TextureRef texture(int[] argb, int w, int h) {
		Textures.Store store = Textures.store();
		if (store == null) return null;
		if (tex == null || texW != w || texH != h) {
			tex = store.upload("halloween/miyako_scare", w, h, argb);
			texW = w;
			texH = h;
		}
		return tex;
	}
}
