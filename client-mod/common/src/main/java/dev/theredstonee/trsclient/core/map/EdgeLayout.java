package dev.theredstonee.trsclient.core.map;

import java.util.Arrays;

/**
 * Platzierung von Markierungen am Kartenrand (Wegpunkte außerhalb des Sichtfelds der Minimap): Richtung vom
 * Mittelpunkt → Punkt auf dem Ring (runde Karte) bzw. auf der Kante (eckige Karte). Liegen mehrere dicht
 * beieinander, rücken sie entlang des Randes gerade so weit auseinander, dass sie sich nicht verdecken – als Gruppe
 * um ihre eigentliche Stelle herum. Reine Mathematik (Bildschirm: x nach rechts, y nach unten, Mitte = 0,0).
 */
public final class EdgeLayout {
	private EdgeLayout() {
	}

	/** Länge des Randes: Kreisumfang bzw. Umfang des Quadrats mit halber Kante {@code half}. */
	public static double perimeter(float half, boolean round) {
		return round ? 2 * Math.PI * half : 8.0 * half;
	}

	/**
	 * Position entlang des Randes (0 … {@link #perimeter}) in Richtung (dx, dy). Rund: Bogenlänge ab „rechts“ im
	 * Uhrzeigersinn; eckig: ab der linken oberen Ecke im Uhrzeigersinn.
	 */
	public static double along(double dx, double dy, float half, boolean round) {
		if (round) {
			double a = Math.atan2(dy, dx);
			if (a < 0) a += 2 * Math.PI;
			return a * half;
		}
		double m = Math.max(Math.abs(dx), Math.abs(dy));
		if (m <= 0) return 0;
		double x = dx / m * half, y = dy / m * half;
		double h = half;
		if (Math.abs(y + h) < 1e-6 && x < h) return x + h; // oben
		if (Math.abs(x - h) < 1e-6 && y < h) return 2 * h + (y + h); // rechts
		if (Math.abs(y - h) < 1e-6 && x > -h) return 4 * h + (h - x); // unten
		return 6 * h + (h - y); // links
	}

	/** Punkt auf dem Rand zur Position {@code s} (siehe {@link #along}) → {@code out[0..1]}. */
	public static void point(double s, float half, boolean round, double[] out) {
		double p = perimeter(half, round);
		s = ((s % p) + p) % p;
		if (round) {
			double a = s / half;
			out[0] = Math.cos(a) * half;
			out[1] = Math.sin(a) * half;
			return;
		}
		double h = half;
		if (s < 2 * h) {
			out[0] = s - h;
			out[1] = -h;
		} else if (s < 4 * h) {
			out[0] = h;
			out[1] = s - 3 * h;
		} else if (s < 6 * h) {
			out[0] = 5 * h - s;
			out[1] = h;
		} else {
			out[0] = -h;
			out[1] = 7 * h - s;
		}
	}

	/**
	 * Rückt Positionen entlang eines geschlossenen Randes (Länge {@code period}) auseinander, bis zwischen je zwei
	 * mindestens {@code gap} liegt. Dicht beieinanderliegende bilden eine Gruppe, die gleichmäßig verteilt um den
	 * Mittelwert ihrer eigentlichen Stellen liegt – einzelne Markierungen bleiben genau an ihrem Platz.
	 *
	 * @param s Positionen (werden überschrieben, Reihenfolge der Einträge bleibt)
	 * @param n Anzahl gültiger Einträge
	 */
	public static void spread(double[] s, int n, double gap, double period) {
		spread(s, n, gap, period, null, 0);
	}

	/**
	 * Wie {@link #spread(double[], int, double, double)}, dazu feste Stellen (z. B. die Himmelsrichtungen), die sich
	 * nicht bewegen: Markierungen halten auch zu ihnen mindestens {@code gap} Abstand und weichen seitlich aus –
	 * eine Gruppe, die an eine feste Stelle stößt, richtet sich an ihr aus.
	 *
	 * @param fixed feste Stellen (werden nicht verändert) oder null
	 * @param nFixed Anzahl fester Stellen
	 */
	public static void spread(double[] s, int n, double gap, double period, double[] fixed, int nFixed) {
		if (n <= 0 || period <= 0) return;
		if (fixed == null) nFixed = 0;
		int total = n + nFixed;
		if (total <= 1) return;
		if (gap * total > period) gap = period / total;
		double[] all = new double[total];
		for (int i = 0; i < total; i++) {
			double x = i < n ? s[i] : fixed[i - n];
			all[i] = ((x % period) + period) % period;
		}
		// Sortieren (Indizes), dann an der größten Lücke aufschneiden – dort kann nichts zusammenstoßen.
		Integer[] order = new Integer[total];
		for (int i = 0; i < total; i++) order[i] = i;
		final double[] pos = all;
		Arrays.sort(order, (a, b) -> {
			int c = Double.compare(pos[a], pos[b]);
			return c != 0 ? c : Integer.compare(a, b);
		});
		int cut = 0;
		double best = -1;
		for (int k = 0; k < total; k++) {
			double next = k + 1 < total ? all[order[k + 1]] : all[order[0]] + period;
			double g = next - all[order[k]];
			if (g > best) {
				best = g;
				cut = (k + 1) % total;
			}
		}
		double[] v = new double[total];
		int[] idx = new int[total];
		double base = all[order[cut]];
		for (int k = 0; k < total; k++) {
			idx[k] = order[(cut + k) % total];
			double x = all[idx[k]];
			if (x < base) x += period;
			v[k] = x;
		}
		// Gruppen zusammenlegen, bis keine sich mehr überlappen.
		int[] start = new int[total];
		int[] len = new int[total];
		double[] center = new double[total];
		int groups = total;
		for (int k = 0; k < total; k++) {
			start[k] = k;
			len[k] = 1;
			center[k] = v[k];
		}
		boolean merged = true;
		while (merged && groups > 1) {
			merged = false;
			for (int g = 0; g + 1 < groups; g++) {
				double endA = center[g] + (len[g] - 1) * gap / 2;
				double startB = center[g + 1] - (len[g + 1] - 1) * gap / 2;
				if (startB - endA < gap - 1e-9) {
					int size = len[g] + len[g + 1];
					len[g] = size;
					center[g] = groupCenter(v, idx, start[g], size, n, gap);
					for (int j = g + 1; j + 1 < groups; j++) {
						start[j] = start[j + 1];
						len[j] = len[j + 1];
						center[j] = center[j + 1];
					}
					groups--;
					merged = true;
					break;
				}
			}
		}
		for (int g = 0; g < groups; g++) {
			for (int k = 0; k < len[g]; k++) {
				int i = idx[start[g] + k];
				if (i >= n) continue;
				double x = center[g] + (k - (len[g] - 1) / 2.0) * gap;
				s[i] = ((x % period) + period) % period;
			}
		}
	}

	/** Mitte einer Gruppe: an der ersten festen Stelle ausgerichtet, sonst der Mittelwert der eigentlichen Stellen. */
	private static double groupCenter(double[] v, int[] idx, int start, int size, int movable, double gap) {
		for (int k = 0; k < size; k++) {
			if (idx[start + k] >= movable) return v[start + k] - (k - (size - 1) / 2.0) * gap;
		}
		double sum = 0;
		for (int k = start; k < start + size; k++) sum += v[k];
		return sum / size;
	}
}
