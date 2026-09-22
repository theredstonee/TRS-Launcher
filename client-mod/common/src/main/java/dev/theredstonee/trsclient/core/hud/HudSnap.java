package dev.theredstonee.trsclient.core.hud;

import java.util.List;

/**
 * Einrasten im HUD-Editor: Ein gezogenes Element rastet an Bildschirmrändern, Bildschirmmitte
 * und an den Kanten/Mitten der anderen Elemente ein. Zu jeder Achse wird die Hilfslinie
 * zurückgeliefert, die der Editor einblendet.
 */
public final class HudSnap {
	/** Abstand zum Bildschirmrand beim Einrasten. */
	public static final int EDGE_MARGIN = HudLayout.EDGE_MARGIN;
	/** Einrast-Distanz in Pixeln. */
	public static final int DISTANCE = 6;
	/** Keine Hilfslinie. */
	public static final int NO_GUIDE = Integer.MIN_VALUE;

	/** Eingerastete Position und die anzuzeigenden Hilfslinien. */
	public static final class Result {
		public final int x;
		public final int y;
		/** Bildschirm-x der senkrechten Hilfslinie oder {@link #NO_GUIDE}. */
		public final int guideX;
		/** Bildschirm-y der waagerechten Hilfslinie oder {@link #NO_GUIDE}. */
		public final int guideY;

		Result(int x, int y, int guideX, int guideY) {
			this.x = x;
			this.y = y;
			this.guideX = guideX;
			this.guideY = guideY;
		}

		public boolean hasGuideX() {
			return guideX != NO_GUIDE;
		}

		public boolean hasGuideY() {
			return guideY != NO_GUIDE;
		}
	}

	private HudSnap() {
	}

	/**
	 * Rastet die linke obere Ecke eines Elements ein.
	 *
	 * @param others Rechtecke der anderen Elemente als {x, y, Breite, Höhe}
	 * @param distance Einrast-Distanz; 0 oder kleiner = nur auf den Bildschirm begrenzen
	 */
	public static Result snap(int x, int y, int w, int h, int screenW, int screenH, List<int[]> others, int distance) {
		int[] rx = axis(x, w, screenW, others, distance, true);
		int[] ry = axis(y, h, screenH, others, distance, false);
		return new Result(HudLayout.clamp(rx[0], 0, Math.max(0, screenW - w)),
				HudLayout.clamp(ry[0], 0, Math.max(0, screenH - h)), rx[1], ry[1]);
	}

	/** Position ohne Einrasten, nur auf den Bildschirm begrenzt (Shift im Editor). */
	public static Result clampOnly(int x, int y, int w, int h, int screenW, int screenH) {
		return new Result(HudLayout.clamp(x, 0, Math.max(0, screenW - w)),
				HudLayout.clamp(y, 0, Math.max(0, screenH - h)), NO_GUIDE, NO_GUIDE);
	}

	/**
	 * Beste Einrastung einer Achse.
	 * @return {Position, Hilfslinie}
	 */
	private static int[] axis(int pos, int size, int screen, List<int[]> others, int distance, boolean horizontal) {
		if (distance <= 0) return new int[]{pos, NO_GUIDE};
		int best = pos;
		int bestGuide = NO_GUIDE;
		int bestDelta = distance + 1;

		// Bildschirm: Rand vorne, Mitte, Rand hinten
		int[][] screenTargets = {
				{EDGE_MARGIN, EDGE_MARGIN},
				{(screen - size) / 2, screen / 2},
				{screen - size - EDGE_MARGIN, screen - EDGE_MARGIN},
		};
		for (int i = 0; i < screenTargets.length; i++) {
			int delta = Math.abs(pos - screenTargets[i][0]);
			if (delta < bestDelta) {
				bestDelta = delta;
				best = screenTargets[i][0];
				bestGuide = screenTargets[i][1];
			}
		}

		// Andere Elemente: gleiche vordere Kante, gleiche hintere Kante, gleiche Mitte
		if (others != null) {
			for (int i = 0; i < others.size(); i++) {
				int[] o = others.get(i);
				int oPos = horizontal ? o[0] : o[1];
				int oSize = horizontal ? o[2] : o[3];
				int[][] targets = {
						{oPos, oPos},
						{oPos + oSize - size, oPos + oSize},
						{oPos + (oSize - size) / 2, oPos + oSize / 2},
				};
				for (int t = 0; t < targets.length; t++) {
					int delta = Math.abs(pos - targets[t][0]);
					if (delta < bestDelta) {
						bestDelta = delta;
						best = targets[t][0];
						bestGuide = targets[t][1];
					}
				}
			}
		}
		return new int[]{best, bestGuide};
	}
}
