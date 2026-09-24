package dev.theredstonee.trsclient.core.hud;

import dev.theredstonee.trsclient.core.module.ChoiceSetting;

/**
 * Anordnung der Rüstungsanzeige – für alle Loader gleich:
 * <ul>
 *   <li>{@link Orientation#VERTICAL}: Gegenstände untereinander (Helm oben), Haltbarkeit rechts daneben.</li>
 *   <li>{@link Orientation#HORIZONTAL}: Gegenstände in einer Zeile, Haltbarkeit klein darunter
 *       (mittig unter dem Symbol) – so wie in PvP-Clients üblich.</li>
 * </ul>
 * Alle Maße unskaliert; die Skalierung übernimmt der HUD-Manager. Größe und Positionen hängen nur von der
 * Anzahl der Gegenstände und den Textbreiten ab – HUD-Editor und Ankerung rechnen damit dieselbe Box.
 */
public final class ArmorLayout {
	/** Ausrichtung der Anzeige. */
	public enum Orientation implements ChoiceSetting.Option {
		VERTICAL("Vertical"),
		HORIZONTAL("Horizontal");

		private final String label;

		Orientation(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public static final int PAD = 3;
	public static final int ICON = 16;
	/** Zeilenhöhe senkrecht (Symbol + 1 Pixel Abstand). */
	public static final int ROW = 17;
	/** Abstand zwischen den Symbolen waagerecht. */
	public static final int GAP = 2;
	/** Abstand Symbol → Text senkrecht (rechts daneben). */
	public static final int TEXT_GAP = 3;
	/** Höhe der Textzeile unter den Symbolen (waagerecht). */
	public static final int TEXT_LINE = 9;

	private ArmorLayout() {
	}

	/**
	 * Breite der Anzeige.
	 * @param widths Textbreite je Gegenstand (0 = kein Text), die ersten {@code count} zählen
	 */
	public static int width(boolean horizontal, int count, int[] widths) {
		int n = Math.max(1, count);
		if (!horizontal) {
			int text = 0;
			for (int i = 0; i < count; i++) text = Math.max(text, widths[i]);
			return PAD * 2 + ICON + (text > 0 ? TEXT_GAP + text : 0);
		}
		int w = 0;
		for (int i = 0; i < n; i++) w += cell(i < count ? widths[i] : 0);
		return PAD * 2 + w + GAP * (n - 1);
	}

	/** Höhe der Anzeige. */
	public static int height(boolean horizontal, int count, int[] widths) {
		if (!horizontal) return PAD * 2 + Math.max(1, count) * ROW - 1;
		boolean text = false;
		for (int i = 0; i < count; i++) text |= widths[i] > 0;
		return PAD * 2 + ICON + (text ? TEXT_LINE + 1 : 0);
	}

	/**
	 * Positionen: {@code out[4*i]} = Symbol x, {@code out[4*i+1]} = Symbol y, {@code out[4*i+2]} = Text x,
	 * {@code out[4*i+3]} = Text y (Text oben links; die Textbreite steht in {@code widths}).
	 */
	public static void place(boolean horizontal, int count, int[] widths, int[] out) {
		if (!horizontal) {
			for (int i = 0; i < count; i++) {
				int y = PAD + i * ROW;
				out[4 * i] = PAD;
				out[4 * i + 1] = y;
				out[4 * i + 2] = PAD + ICON + TEXT_GAP;
				out[4 * i + 3] = y + 4;
			}
			return;
		}
		int x = PAD;
		for (int i = 0; i < count; i++) {
			int cell = cell(widths[i]);
			out[4 * i] = x + (cell - ICON) / 2;
			out[4 * i + 1] = PAD;
			out[4 * i + 2] = x + (cell - widths[i]) / 2;
			out[4 * i + 3] = PAD + ICON + 1;
			x += cell + GAP;
		}
	}

	private static int cell(int textWidth) {
		return Math.max(ICON, textWidth);
	}
}
