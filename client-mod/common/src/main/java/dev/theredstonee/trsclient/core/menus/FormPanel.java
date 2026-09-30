package dev.theredstonee.trsclient.core.menus;

import java.util.ArrayList;
import java.util.List;

/**
 * Fläche hinter einem Formular der Serverliste („Direkt verbinden“, „Server hinzufügen/bearbeiten“) – versionsunabhängig.
 *
 * <p>Vanilla stellt Felder und Knöpfe dieser Bildschirme mittig untereinander ({@code width/2 − 100}, 200 breit). Andere
 * Mods hängen aber eigene Knöpfe an beliebige Stellen (ViaFabricPlus z. B. „Set version“ oben rechts in der Ecke). Die
 * Fläche umschließt deshalb nicht einfach alle Widgets, sondern nur den Block um die Bildschirmmitte:
 * <ol>
 *   <li>Widgets in der Kopfleiste (oberhalb von {@code header}) zählen nie – dort steht nur der Titel.</li>
 *   <li>Kern = alle Widgets, die die senkrechte Mittellinie schneiden (gibt es keins: das der Mitte nächste).</li>
 *   <li>Dazu kommen Widgets, die an den Block angrenzen (Lücke ≤ {@link #GAP}), wiederholt – so bleiben z. B. zwei
 *       Knöpfe nebeneinander unter den Feldern oder ein Zusatzknopf direkt neben einem Feld dabei.</li>
 *   <li>Die Fläche liegt symmetrisch um die Mitte (Formulare sind zentriert), mit Rand und Platz für die Beschriftung
 *       über dem ersten Feld.</li>
 * </ol>
 */
public final class FormPanel {
	/** Größte Lücke zu einem angrenzenden Widget, das noch zum Formular zählt. */
	public static final int GAP = 24;
	/** Rand links/rechts, oben (Beschriftung des ersten Felds, Vanilla schreibt sie 12–16 px darüber) und unten. */
	public static final int PAD_X = 12;
	public static final int PAD_TOP = 22;
	public static final int PAD_BOTTOM = 10;

	private FormPanel() {
	}

	/**
	 * Fläche {x1, y1, x2, y2} hinter dem Formular oder null (keine Widgets). {@code rects} = sichtbare Widgets als
	 * {x, y, w, h}; {@code header} = Höhe der Kopfleiste (0 = keine).
	 */
	public static int[] bounds(List<int[]> rects, int screenW, int screenH, int header) {
		List<int[]> candidates = new ArrayList<int[]>();
		for (int[] r : rects) {
			if (r == null || r[2] <= 0 || r[3] <= 0) continue;
			if (header > 0 && r[1] < header) continue;
			candidates.add(r);
		}
		if (candidates.isEmpty()) return null;
		int cx = screenW / 2;
		boolean[] in = new boolean[candidates.size()];
		int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, y2 = Integer.MIN_VALUE;
		for (int i = 0; i < candidates.size(); i++) {
			int[] r = candidates.get(i);
			if (r[0] <= cx && r[0] + r[2] >= cx) {
				in[i] = true;
				x1 = Math.min(x1, r[0]);
				y1 = Math.min(y1, r[1]);
				x2 = Math.max(x2, r[0] + r[2]);
				y2 = Math.max(y2, r[1] + r[3]);
			}
		}
		if (x1 == Integer.MAX_VALUE) {
			int best = 0;
			long bestD = Long.MAX_VALUE;
			for (int i = 0; i < candidates.size(); i++) {
				int[] r = candidates.get(i);
				long d = Math.abs((long) r[0] * 2 + r[2] - (long) cx * 2);
				if (d < bestD) {
					bestD = d;
					best = i;
				}
			}
			int[] r = candidates.get(best);
			in[best] = true;
			x1 = r[0];
			y1 = r[1];
			x2 = r[0] + r[2];
			y2 = r[1] + r[3];
		}
		boolean grew = true;
		while (grew) {
			grew = false;
			for (int i = 0; i < candidates.size(); i++) {
				if (in[i]) continue;
				int[] r = candidates.get(i);
				int gapX = Math.max(0, Math.max(r[0] - x2, x1 - (r[0] + r[2])));
				int gapY = Math.max(0, Math.max(r[1] - y2, y1 - (r[1] + r[3])));
				if (gapX > GAP || gapY > GAP) continue;
				in[i] = true;
				grew = true;
				x1 = Math.min(x1, r[0]);
				y1 = Math.min(y1, r[1]);
				x2 = Math.max(x2, r[0] + r[2]);
				y2 = Math.max(y2, r[1] + r[3]);
			}
		}
		// symmetrisch um die Mitte
		int half = Math.max(cx - x1, x2 - cx);
		x1 = cx - half;
		x2 = cx + half;
		int top = header > 0 ? header + 4 : 2;
		return new int[]{Math.max(2, x1 - PAD_X), Math.max(top, y1 - PAD_TOP), Math.min(screenW - 2, x2 + PAD_X),
				Math.min(screenH - 2, y2 + PAD_BOTTOM)};
	}
}
