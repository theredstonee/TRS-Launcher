package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.waypoint.Waypoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Filter der Wegpunkt-Liste auf der Weltkarte – reine Logik (Tests): Suche (alle Wörter müssen im Namen vorkommen,
 * Groß-/Kleinschreibung egal; eine Zahl findet auch die Koordinaten), Dimension (alle oder eine; Wegpunkte ohne
 * Dimension gelten überall), waagerechte Entfernung zum Spieler (über Dimensionsgrenzen Nether ↔ Oberwelt
 * umgerechnet) und Sortierung: nächste zuerst, ohne Entfernung ans Ende, dann nach Name.
 */
public final class WaypointFilter {
	/** Dimensionsfilter „alle“. */
	public static final String ALL = "*";

	private WaypointFilter() {
	}

	/** Eine Zeile der Liste. */
	public static final class Row {
		public final Waypoint waypoint;
		/** Waagerechte Entfernung in Blöcken oder {@link Double#NaN} (andere Dimension ohne Umrechnung). */
		public final double distance;
		/** Entfernung über die Nether/Oberwelt-Umrechnung bestimmt? */
		public final boolean converted;

		Row(Waypoint waypoint, double distance, boolean converted) {
			this.waypoint = waypoint;
			this.distance = distance;
			this.converted = converted;
		}
	}

	/**
	 * @param all       alle Wegpunkte der Welt
	 * @param query     Suchtext ("" = alle)
	 * @param dimFilter {@link #ALL} oder eine Dimensionskennung
	 * @param playerDim Dimension des Spielers (für die Entfernung)
	 */
	public static List<Row> apply(List<Waypoint> all, String query, String dimFilter, String playerDim, double px, double pz) {
		String[] words = words(query);
		List<Row> out = new ArrayList<Row>();
		for (Waypoint w : all) {
			if (w == null) continue;
			if (dimFilter != null && !ALL.equals(dimFilter) && !w.inDimension(dimFilter)) continue;
			if (!matches(w, words)) continue;
			out.add(row(w, playerDim, px, pz));
		}
		Collections.sort(out, ORDER);
		return out;
	}

	/** Zeile mit Entfernung. */
	public static Row row(Waypoint w, String playerDim, double px, double pz) {
		String wd = w.dimension == null || w.dimension.isEmpty() ? playerDim : w.dimension;
		double f = MapDimensions.factor(wd, playerDim);
		if (Double.isNaN(f)) return new Row(w, Double.NaN, false);
		double wx = (w.x + 0.5) * f, wz = (w.z + 0.5) * f;
		double dx = wx - px, dz = wz - pz;
		return new Row(w, Math.sqrt(dx * dx + dz * dz), f != 1.0);
	}

	static final Comparator<Row> ORDER = new Comparator<Row>() {
		@Override
		public int compare(Row a, Row b) {
			boolean na = Double.isNaN(a.distance), nb = Double.isNaN(b.distance);
			if (na != nb) return na ? 1 : -1;
			if (!na) {
				int c = Double.compare(a.distance, b.distance);
				if (c != 0) return c;
			}
			return name(a.waypoint).compareTo(name(b.waypoint));
		}
	};

	private static String name(Waypoint w) {
		return w.name == null ? "" : w.name.toLowerCase(Locale.ROOT);
	}

	static String[] words(String query) {
		if (query == null) return new String[0];
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return new String[0];
		return q.split("\\s+");
	}

	/** Passt ein Wegpunkt zu allen Suchwörtern (Name enthält das Wort, oder das Wort ist eine seiner Koordinaten)? */
	static boolean matches(Waypoint w, String[] words) {
		if (words.length == 0) return true;
		String n = name(w);
		for (String word : words) {
			if (n.contains(word)) continue;
			if (word.equals(Integer.toString(w.x)) || word.equals(Integer.toString(w.y)) || word.equals(Integer.toString(w.z))) {
				continue;
			}
			return false;
		}
		return true;
	}

	/**
	 * Dimensionen für den Filter-Knopf: {@link #ALL}, dann die Dimension des Spielers, dann alle anderen, in denen
	 * Wegpunkte liegen (Reihenfolge Oberwelt, Nether, End, Rest).
	 */
	public static List<String> dimensions(List<Waypoint> all, String playerDim) {
		Set<String> dims = new LinkedHashSet<String>();
		if (playerDim != null && !playerDim.isEmpty()) dims.add(playerDim);
		List<String> others = new ArrayList<String>();
		for (Waypoint w : all) {
			if (w.dimension != null && !w.dimension.isEmpty() && !dims.contains(w.dimension) && !others.contains(w.dimension)) {
				others.add(w.dimension);
			}
		}
		Collections.sort(others, new Comparator<String>() {
			@Override
			public int compare(String a, String b) {
				int ka = MapDimensions.kind(a), kb = MapDimensions.kind(b);
				return ka != kb ? Integer.compare(ka, kb) : a.compareTo(b);
			}
		});
		List<String> out = new ArrayList<String>();
		out.add(ALL);
		out.addAll(dims);
		out.addAll(others);
		return out;
	}

	/** Entfernung kurz: „850“, „12.4k“, „—“. */
	public static String distanceText(double d) {
		if (Double.isNaN(d)) return "—";
		long r = Math.round(d);
		return r >= 10000 ? String.format(Locale.ROOT, "%.1fk", r / 1000.0) : Long.toString(r);
	}
}
