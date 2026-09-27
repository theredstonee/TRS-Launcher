package dev.theredstonee.trsclient.core.tooltip;

/**
 * Wo steht der Vanilla-Tooltip, und wohin passt die TRS-Zusatzkarte (Shulker-Raster, Karte, Hunger) daneben?
 *
 * <p>Die Lage des Tooltips wird mit Vanillas Regeln nachgerechnet (Maus + 12/−12, links von der Maus, wenn rechts kein
 * Platz ist, am unteren Rand hochgeschoben) – ab 1.20 mit {@code DefaultTooltipPositioner}, davor mit
 * {@code Screen#renderTooltip}. Der Rahmen liegt 4 px um den Textbereich. Die Karte kommt über den Tooltip, sonst
 * darunter, sonst daneben – nie darüber gelegt.
 */
public final class TooltipPlacement {
	/** Abstand zwischen Tooltip-Rahmen und Karte. */
	public static final int GAP = 2;
	/** Rahmen um den Textbereich des Tooltips. */
	public static final int BORDER = 4;

	private TooltipPlacement() {
	}

	/** Höhe des Textbereichs eines Tooltips mit {@code lines} Zeilen (erste Zeile hat 2 px Abstand). */
	public static int contentHeight(int lines) {
		if (lines <= 1) return 8;
		return 10 + (lines - 1) * 10;
	}

	/**
	 * Textbereich des Vanilla-Tooltips.
	 *
	 * @param modern Positionierung ab 1.20 ({@code DefaultTooltipPositioner}); sonst die ältere Regel
	 * @return {x, y, w, h}
	 */
	public static int[] tooltip(int mouseX, int mouseY, int width, int height, int screenW, int screenH, boolean modern) {
		int x = mouseX + 12;
		int y = mouseY - 12;
		if (modern) {
			if (x + width > screenW) x = Math.max(x - 24 - width, 4);
			if (y + height + 3 > screenH) y = screenH - height - 3;
		} else {
			if (x + width > screenW) x -= 28 + width;
			if (y + height + 6 > screenH) y = screenH - height - 6;
		}
		return new int[]{x, y, width, height};
	}

	/**
	 * Lage der Zusatzkarte ({@code cardW}×{@code cardH}) zum Tooltip-Textbereich {@code tip}.
	 *
	 * @return {x, y}
	 */
	public static int[] card(int[] tip, int cardW, int cardH, int screenW, int screenH) {
		int left = tip[0] - BORDER, top = tip[1] - BORDER;
		int right = tip[0] + tip[2] + BORDER, bottom = tip[1] + tip[3] + BORDER;
		int x = left;
		int y = top - GAP - cardH;
		if (y < 2) {
			y = bottom + GAP;
			if (y + cardH > screenH - 2) {
				// Weder darüber noch darunter Platz: daneben (rechts, sonst links), oben bündig.
				y = Math.max(2, Math.min(top, screenH - cardH - 2));
				x = right + GAP;
				if (x + cardW > screenW - 2) x = left - GAP - cardW;
			}
		}
		x = Math.max(2, Math.min(x, screenW - cardW - 2));
		return new int[]{x, y};
	}
}
