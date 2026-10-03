package dev.theredstonee.trsclient.core.touch;

import java.util.List;

/**
 * Sichere Fläche des Bildschirms (ohne Notch/Kamera-Loch/abgerundete Ecken) und Einrasten von HUD-Elementen
 * darin. Die Engine gibt die Ränder als {@code -Dtrs.safeInsets=l,t,r,b} in Fensterpixeln mit.
 */
public final class SafeArea {
	/** Abstand zum sicheren Rand beim Einrasten (wie {@code HudLayout.EDGE_MARGIN}). */
	public static final int MARGIN = 2;
	/** Größter angenommener Rand in Fensterpixeln (Schutz vor Unsinn). */
	static final int MAX_INSET_PX = 4096;

	/** Ränder links, oben, rechts, unten. */
	public static final class Insets {
		public static final Insets NONE = new Insets(0, 0, 0, 0);

		public final int left;
		public final int top;
		public final int right;
		public final int bottom;

		public Insets(int left, int top, int right, int bottom) {
			this.left = Math.max(0, left);
			this.top = Math.max(0, top);
			this.right = Math.max(0, right);
			this.bottom = Math.max(0, bottom);
		}

		public boolean isZero() {
			return left == 0 && top == 0 && right == 0 && bottom == 0;
		}

		/** In GUI-Pixel umrechnen (aufrunden – lieber etwas mehr Abstand als in die Notch). */
		public Insets toGui(double guiScale) {
			if (isZero()) return this;
			if (!(guiScale > 0)) return NONE;
			return new Insets(up(left, guiScale), up(top, guiScale), up(right, guiScale), up(bottom, guiScale));
		}

		private static int up(int px, double scale) {
			return (int) Math.ceil(px / scale - 1e-9);
		}

		@Override
		public String toString() {
			return left + "," + top + "," + right + "," + bottom;
		}
	}

	private SafeArea() {
	}

	/** {@code "l,t,r,b"} lesen (Ganz- oder Kommazahlen, Leerzeichen erlaubt); ungültig → {@link Insets#NONE}. */
	public static Insets parse(String value) {
		if (value == null) return Insets.NONE;
		String[] parts = value.trim().split("\\s*[,;]\\s*");
		if (parts.length != 4) return Insets.NONE;
		int[] v = new int[4];
		for (int i = 0; i < 4; i++) {
			try {
				double d = Double.parseDouble(parts[i].trim());
				if (Double.isNaN(d) || d < 0) return Insets.NONE;
				v[i] = (int) Math.min(MAX_INSET_PX, Math.ceil(d));
			} catch (NumberFormatException e) {
				return Insets.NONE;
			}
		}
		return new Insets(v[0], v[1], v[2], v[3]);
	}

	/** Ergebnis des Einrastens: Position + Hilfslinien ({@link #NO_GUIDE} = keine). */
	public static final class Snap {
		public final int x;
		public final int y;
		public final int guideX;
		public final int guideY;

		Snap(int x, int y, int guideX, int guideY) {
			this.x = x;
			this.y = y;
			this.guideX = guideX;
			this.guideY = guideY;
		}
	}

	public static final int NO_GUIDE = Integer.MIN_VALUE;

	/**
	 * Begrenzt ein Element (x, y, w, h) auf die sichere Fläche; passt es nicht hinein, auf den Bildschirm.
	 * @return {x, y}
	 */
	public static int[] clamp(int x, int y, int w, int h, int screenW, int screenH, Insets in) {
		return new int[]{clampAxis(x, w, screenW, in.left, in.right), clampAxis(y, h, screenH, in.top, in.bottom)};
	}

	private static int clampAxis(int pos, int size, int screen, int start, int end) {
		int min = start;
		int max = screen - end - size;
		if (max < min) {
			// Passt nicht in die sichere Fläche: wenigstens auf den Bildschirm.
			min = 0;
			max = Math.max(0, screen - size);
		}
		return pos < min ? min : Math.min(pos, max);
	}

	/**
	 * Rastet ein gezogenes Element ein – an den Rändern der sicheren Fläche (statt der Bildschirmränder), der
	 * Bildschirmmitte und den Kanten/Mitten der anderen Elemente – und begrenzt es auf die sichere Fläche.
	 *
	 * @param others Rechtecke der anderen Elemente als {x, y, Breite, Höhe}
	 * @param distance Einrast-Distanz; 0 oder kleiner = nur begrenzen
	 */
	public static Snap snap(int x, int y, int w, int h, int screenW, int screenH, List<int[]> others, int distance, Insets in) {
		int[] rx = axis(x, w, screenW, in.left, in.right, others, distance, true);
		int[] ry = axis(y, h, screenH, in.top, in.bottom, others, distance, false);
		int[] c = clamp(rx[0], ry[0], w, h, screenW, screenH, in);
		return new Snap(c[0], c[1], c[0] == rx[0] ? rx[1] : NO_GUIDE, c[1] == ry[0] ? ry[1] : NO_GUIDE);
	}

	private static int[] axis(int pos, int size, int screen, int startInset, int endInset, List<int[]> others,
			int distance, boolean horizontal) {
		if (distance <= 0) return new int[]{pos, NO_GUIDE};
		int best = pos;
		int bestGuide = NO_GUIDE;
		int bestDelta = distance + 1;
		int start = startInset + MARGIN;
		int end = screen - endInset - MARGIN;
		int[][] targets = {
				{start, start},
				{(screen - size) / 2, screen / 2},
				{end - size, end},
		};
		for (int[] t : targets) {
			int delta = Math.abs(pos - t[0]);
			if (delta < bestDelta) {
				bestDelta = delta;
				best = t[0];
				bestGuide = t[1];
			}
		}
		if (others != null) {
			for (int[] o : others) {
				int oPos = horizontal ? o[0] : o[1];
				int oSize = horizontal ? o[2] : o[3];
				int[][] ts = {
						{oPos, oPos},
						{oPos + oSize - size, oPos + oSize},
						{oPos + (oSize - size) / 2, oPos + oSize / 2},
				};
				for (int[] t : ts) {
					int delta = Math.abs(pos - t[0]);
					if (delta < bestDelta) {
						bestDelta = delta;
						best = t[0];
						bestGuide = t[1];
					}
				}
			}
		}
		return new int[]{best, bestGuide};
	}
}
