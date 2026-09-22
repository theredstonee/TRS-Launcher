package dev.theredstonee.trsclient.core.hud;

import dev.theredstonee.trsclient.core.module.ChoiceSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometrie des eigenen Fadenkreuzes: liefert Rechtecke relativ zur Bildschirmmitte,
 * die Minecraft-seitig nur noch gefüllt werden. Ein Rechteck = {x1, y1, x2, y2}
 * (x2/y2 exklusiv), Mittelpunkt = Pixel (0, 0).
 */
public final class Crosshair {
	/** Formen des Fadenkreuzes. */
	public enum Shape implements ChoiceSetting.Option {
		CROSS("Kreuz"),
		CROSS_DOT("Kreuz mit Punkt"),
		DOT("Punkt"),
		T("T-Form"),
		CIRCLE("Kreis"),
		CIRCLE_DOT("Kreis mit Punkt");

		private final String label;

		Shape(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	private Crosshair() {
	}

	/**
	 * @param size      Armlänge in Pixeln (Kreis: Radius)
	 * @param gap       Abstand der Arme von der Mitte
	 * @param thickness Strichstärke (≥ 1)
	 */
	public static List<int[]> rects(Shape shape, int size, int gap, int thickness) {
		size = Math.max(1, size);
		gap = Math.max(0, gap);
		thickness = Math.max(1, thickness);
		List<int[]> out = new ArrayList<>();
		// Striche mittig um die 0-Linie: bei ungerader Stärke liegt ein Pixel genau auf 0.
		int lo = -(thickness / 2);
		int hi = lo + thickness;
		switch (shape) {
			case CROSS:
			case CROSS_DOT:
				arms(out, size, gap, lo, hi, true);
				if (shape == Shape.CROSS_DOT) out.add(new int[]{lo, lo, hi, hi});
				break;
			case T:
				arms(out, size, gap, lo, hi, false);
				break;
			case DOT:
				out.add(new int[]{lo, lo, hi, hi});
				break;
			case CIRCLE:
			case CIRCLE_DOT:
				ring(out, size + gap, thickness);
				if (shape == Shape.CIRCLE_DOT) out.add(new int[]{lo, lo, hi, hi});
				break;
		}
		return out;
	}

	/** Umriss: jedes Rechteck um 1 Pixel vergrößert (wird unter die Füllung gezeichnet). */
	public static List<int[]> outline(List<int[]> rects) {
		List<int[]> out = new ArrayList<>(rects.size());
		for (int[] r : rects) out.add(new int[]{r[0] - 1, r[1] - 1, r[2] + 1, r[3] + 1});
		return out;
	}

	/** Umgebendes Rechteck aller Teile (für Vorschau/Zentrierung). */
	public static int[] bounds(List<int[]> rects) {
		int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (int[] r : rects) {
			b[0] = Math.min(b[0], r[0]);
			b[1] = Math.min(b[1], r[1]);
			b[2] = Math.max(b[2], r[2]);
			b[3] = Math.max(b[3], r[3]);
		}
		return rects.isEmpty() ? new int[4] : b;
	}

	/** Vier (T: drei) Arme im Abstand {@code gap} um den Mittelblock [lo, hi). */
	private static void arms(List<int[]> out, int size, int gap, int lo, int hi, boolean withTop) {
		int near = lo - gap;
		int far = hi + gap;
		if (withTop) out.add(new int[]{lo, near - size, hi, near});   // oben
		out.add(new int[]{lo, far, hi, far + size});                  // unten
		out.add(new int[]{near - size, lo, near, hi});                // links
		out.add(new int[]{far, lo, far + size, hi});                  // rechts
	}

	/** Kreisring (Radius r, Stärke t) aus 1-Pixel-hohen Zeilenstücken, symmetrisch. */
	private static void ring(List<int[]> out, int r, int t) {
		double outer = r + 0.5;
		double inner = Math.max(0, r - t + 0.5);
		for (int y = -r; y <= r; y++) {
			double dy = y;
			int xo = (int) Math.floor(Math.sqrt(Math.max(0, outer * outer - dy * dy)));
			double innerSq = inner * inner - dy * dy;
			if (innerSq <= 0) {
				out.add(new int[]{-xo, y, xo + 1, y + 1});
			} else {
				int xi = (int) Math.ceil(Math.sqrt(innerSq));
				if (xi > xo) continue;
				out.add(new int[]{-xo, y, -xi + 1, y + 1});
				out.add(new int[]{xi, y, xo + 1, y + 1});
			}
		}
	}
}
