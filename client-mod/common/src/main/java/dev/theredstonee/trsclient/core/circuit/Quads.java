package dev.theredstonee.trsclient.core.circuit;

import dev.theredstonee.trsclient.core.ui.Affine;
import dev.theredstonee.trsclient.core.ui.Canvas;

/**
 * Freie Flächen und Linien auf dem {@link Canvas}: ein Einheitsquadrat, per {@link Affine} auf ein Parallelogramm
 * abgebildet. Braucht {@link Canvas#images()} (Drehen/Skalieren) – sonst zeichnen die Aufrufer nur Markierungen.
 */
final class Quads {
	private Quads() {
	}

	/** Linie von (x1, y1) nach (x2, y2) mit Breite {@code width} (Bildschirmpixel). */
	static void line(Canvas c, float x1, float y1, float x2, float y2, float width, int argb) {
		float dx = x2 - x1;
		float dy = y2 - y1;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 0.05f) return;
		float nx = -dy / len * width;
		float ny = dx / len * width;
		c.push();
		if (Affine.apply(c, dx, dy, nx, ny, x1 - nx / 2f, y1 - ny / 2f)) c.fill(0, 0, 1, 1, argb);
		c.pop();
	}

	/**
	 * Viereck aus vier Eckpunkten (Reihenfolge rundherum) – exakt, wenn es ein Parallelogramm ist (Vorschau),
	 * sonst die beste Näherung (perspektivische Seitenflächen; die Kanten zeichnet der Aufrufer genau).
	 */
	static void quad(Canvas c, float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy, int argb) {
		// Mittelpunkt und gemittelte Seitenvektoren
		float mx = (ax + bx + cx + dx) / 4f;
		float my = (ay + by + cy + dy) / 4f;
		float ux = ((bx - ax) + (cx - dx)) / 2f;
		float uy = ((by - ay) + (cy - dy)) / 2f;
		float vx = ((dx - ax) + (cx - bx)) / 2f;
		float vy = ((dy - ay) + (cy - by)) / 2f;
		float det = ux * vy - uy * vx;
		if (Math.abs(det) < 0.05f) return;
		if (det < 0) {
			float tx = ux, ty = uy;
			ux = vx;
			uy = vy;
			vx = tx;
			vy = ty;
		}
		c.push();
		if (Affine.apply(c, ux, uy, vx, vy, mx - (ux + vx) / 2f, my - (uy + vy) / 2f)) c.fill(0, 0, 1, 1, argb);
		c.pop();
	}
}
