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
		if (n <= 1 || period <= 0) return;
		if (gap * n > period) gap = period / n;
		// Sortieren (Indizes), dann an der größten Lücke aufschneiden – dort kann nichts zusammenstoßen.
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) {
			s[i] = ((s[i] % period) + period) % period;
			order[i] = i;
		}
		final double[] pos = s;
		Arrays.sort(order, (a, b) -> Double.compare(pos[a], pos[b]));
		int cut = 0;
		double best = -1;
		for (int k = 0; k < n; k++) {
			double next = k + 1 < n ? s[order[k + 1]] : s[order[0]] + period;
			double g = next - s[order[k]];
			if (g > best) {
				best = g;
				cut = (k + 1) % n;
			}
		}
		double[] v = new double[n];
		int[] idx = new int[n];
		double base = s[order[cut]];
		for (int k = 0; k < n; k++) {
			idx[k] = order[(cut + k) % n];
			double x = s[idx[k]];
			if (x < base) x += period;
			v[k] = x;
		}
		// Gruppen zusammenlegen, bis keine sich mehr überlappen.
		int[] start = new int[n];
		int[] len = new int[n];
		double[] center = new double[n];
		int groups = n;
		for (int k = 0; k < n; k++) {
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
					int total = len[g] + len[g + 1];
					double sum = 0;
					for (int k = start[g]; k < start[g] + total; k++) sum += v[k];
					center[g] = sum / total;
					len[g] = total;
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
				double x = center[g] + (k - (len[g] - 1) / 2.0) * gap;
				s[idx[start[g] + k]] = ((x % period) + period) % period;
			}
		}
	}
}
