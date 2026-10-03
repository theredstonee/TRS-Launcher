package dev.theredstonee.trsclient.core.touch;

import java.util.ArrayList;
import java.util.List;

/**
 * „Touch-Layout“: verteilt HUD-Elemente so, dass sie nicht unter den Standard-Knöpfen des Touch-Overlays liegen
 * (Joystick links unten, Aktionsknöpfe rechts, Leisten oben links/rechts, Schnellleiste unten Mitte) und in der
 * sicheren Fläche bleiben. Die Zonen sind Teil des Vertrags mit dem Overlay ({@code docs/touch-mode.md}): seine
 * mitgelieferten Layouts legen ihre Knöpfe nur in diese Bereiche.
 */
public final class TouchLayout {
	/** Name des HUD-Profils (in allen Sprachen gleich). */
	public static final String PROFILE_NAME = "Touch-Layout";
	/** Abstand zu Zonen, Rändern und anderen Elementen (GUI-Pixel). */
	public static final int GAP = 2;
	/** Raster der Platzsuche (GUI-Pixel). */
	static final int STEP = 2;

	/**
	 * Standard-Knopfzonen des Overlays als Anteile des Bildschirms {x1, y1, x2, y2} (0..1).
	 * Reihenfolge: Joystick, Aktionsknöpfe, Leiste oben links, Leiste oben rechts, Vanilla-Schnellleiste.
	 */
	public static final float[][] ZONES = {
			{0.00f, 0.50f, 0.32f, 1.00f},
			{0.68f, 0.45f, 1.00f, 1.00f},
			{0.00f, 0.00f, 0.10f, 0.14f},
			{0.80f, 0.00f, 1.00f, 0.14f},
			{0.30f, 0.86f, 0.70f, 1.00f},
	};

	private TouchLayout() {
	}

	/** Zonen in GUI-Pixeln {x, y, Breite, Höhe} für diese Bildschirmgröße. */
	public static List<int[]> zones(int screenW, int screenH) {
		List<int[]> out = new ArrayList<int[]>();
		for (float[] z : ZONES) {
			int x1 = (int) Math.floor(z[0] * screenW);
			int y1 = (int) Math.floor(z[1] * screenH);
			int x2 = (int) Math.ceil(z[2] * screenW);
			int y2 = (int) Math.ceil(z[3] * screenH);
			out.add(new int[]{x1, y1, x2 - x1, y2 - y1});
		}
		return out;
	}

	/**
	 * Verteilt Elemente der Reihe nach: Jedes bleibt an seiner Wunschposition, wenn sie frei ist (sichere Fläche,
	 * keine Zone, kein schon gesetztes Element); sonst kommt es auf den freien Platz, der ihr am nächsten liegt.
	 * Findet sich keiner, wird es nur in die sichere Fläche geschoben (Überlappung mit Zonen bleibt dann).
	 *
	 * @param rects je Element {Wunsch-x, Wunsch-y, Breite, Höhe} in GUI-Pixeln
	 * @return je Element {x, y}
	 */
	public static int[][] place(List<int[]> rects, int screenW, int screenH, List<int[]> zones, SafeArea.Insets insets) {
		int[][] out = new int[rects.size()][];
		List<int[]> taken = new ArrayList<int[]>();
		for (int i = 0; i < rects.size(); i++) {
			int[] r = rects.get(i);
			int w = r[2];
			int h = r[3];
			int[] wish = SafeArea.clamp(r[0], r[1], w, h, screenW, screenH, insets);
			int[] best = null;
			if (free(r[0], r[1], w, h, screenW, screenH, zones, taken, insets)) {
				best = new int[]{r[0], r[1]};
			} else {
				long bestDist = Long.MAX_VALUE;
				int minX = insets.left + GAP;
				int minY = insets.top + GAP;
				int maxX = screenW - insets.right - GAP - w;
				int maxY = screenH - insets.bottom - GAP - h;
				for (int y = minY; y <= maxY; y += STEP) {
					for (int x = minX; x <= maxX; x += STEP) {
						long dx = x - wish[0];
						long dy = y - wish[1];
						long d = dx * dx + dy * dy;
						if (d >= bestDist) continue;
						if (!free(x, y, w, h, screenW, screenH, zones, taken, insets)) continue;
						bestDist = d;
						best = new int[]{x, y};
					}
				}
			}
			if (best == null) best = wish;
			out[i] = best;
			taken.add(new int[]{best[0], best[1], w, h});
		}
		return out;
	}

	/** Ist das Rechteck in der sicheren Fläche und frei von Zonen und gesetzten Elementen (mit Abstand)? */
	static boolean free(int x, int y, int w, int h, int screenW, int screenH, List<int[]> zones, List<int[]> taken,
			SafeArea.Insets in) {
		if (x < in.left || y < in.top || x + w > screenW - in.right || y + h > screenH - in.bottom) return false;
		for (int[] z : zones) {
			if (overlaps(x, y, w, h, z, GAP)) return false;
		}
		for (int[] t : taken) {
			if (overlaps(x, y, w, h, t, GAP)) return false;
		}
		return true;
	}

	static boolean overlaps(int x, int y, int w, int h, int[] o, int gap) {
		return x < o[0] + o[2] + gap && o[0] < x + w + gap && y < o[1] + o[3] + gap && o[1] < y + h + gap;
	}
}
