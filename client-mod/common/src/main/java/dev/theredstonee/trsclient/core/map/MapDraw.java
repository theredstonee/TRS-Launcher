package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;

/**
 * Karte v3: Textur-Ausschnitt nahtlos zeichnen. Kartentexturen wiederholen sich an ihren Rändern (Textur-Wiederholung
 * der Grafikkarte) – fällt ein Bildschirmpixel genau auf die Kante, holte er sich die gegenüberliegende Zeile (bei
 * unerkundetem Rand: eine dunkle Naht quer über die Karte). Darum wird der Ausschnitt an den Texturkanten um 1/64 Texel
 * nach innen gerückt; die Fläche auf dem Bildschirm bleibt exakt gleich groß.
 */
final class MapDraw {
	/** Unterteilung je Texel für den Ausschnitt. */
	static final int FINE = 64;

	private MapDraw() {
	}

	/**
	 * Zeichnet die Texel [u0,u1]×[v0,v1] von {@code tex} in das Rechteck (0,0)–(w,h) der aktuellen Transformation.
	 */
	static void image(Canvas c, TextureRef tex, double u0, double v0, double u1, double v1, float w, float h, int tint) {
		int tw = tex.width * FINE, th = tex.height * FINE;
		int U0 = (int) Math.round(u0 * FINE), U1 = (int) Math.round(u1 * FINE);
		int V0 = (int) Math.round(v0 * FINE), V1 = (int) Math.round(v1 * FINE);
		if (U0 <= 0) U0 = 1;
		if (V0 <= 0) V0 = 1;
		if (U1 >= tw) U1 = tw - 1;
		if (V1 >= th) V1 = th - 1;
		if (U1 <= U0 || V1 <= V0 || w <= 0f || h <= 0f) return;
		TextureRef fine = new TextureRef(tex.id, tw, th);
		c.push();
		c.scale(w / (U1 - U0), h / (V1 - V0));
		c.image(fine, U0, V0, U1 - U0, V1 - V0, tint);
		c.pop();
	}
}
