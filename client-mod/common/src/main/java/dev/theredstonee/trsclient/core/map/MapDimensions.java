package dev.theredstonee.trsclient.core.map;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Gespeicherte Dimensionen einer Welt für die Weltkarte: welche es gibt (auch die, in denen man gerade nicht ist),
 * welche Ebenen darin liegen (Oberfläche, Höhlenschichten) und wie Koordinaten zwischen Oberwelt und Nether umgerechnet
 * werden (1 Block im Nether = 8 in der Oberwelt). Ordnernamen sind gekürzt ({@link MapDisk#safeName}) – die echte
 * Kennung steht in {@value MapDisk#DIMENSION_FILE} oder wird aus dem Namen samt Prüfwert zurückgewonnen.
 */
public final class MapDimensions {
	public static final int OVERWORLD = 0;
	public static final int NETHER = 1;
	public static final int END = 2;
	public static final int OTHER = 3;

	/** Umrechnung Nether ↔ Oberwelt. */
	public static final int NETHER_SCALE = 8;

	private MapDimensions() {
	}

	/** Eine gespeicherte Ebene (Ordner mit Kartenbereichen). */
	public static final class LayerInfo {
		/** Ebenen-Kennung wie in {@link MapEngine} ({@code surface}, {@code cave<band>}, {@code roof<höhe>}). */
		public final String id;
		public final Path dir;
		public final int regions;

		public LayerInfo(String id, Path dir, int regions) {
			this.id = id;
			this.dir = dir;
			this.regions = regions;
		}

		/** Höhlenschicht (Nummer des 8-Block-Bands) oder {@link Integer#MIN_VALUE}. */
		public int caveBand() {
			if (id == null || !id.startsWith("cave")) return Integer.MIN_VALUE;
			try {
				return Integer.parseInt(id.substring(4));
			} catch (NumberFormatException e) {
				return Integer.MIN_VALUE;
			}
		}

		public boolean cave() {
			return caveBand() != Integer.MIN_VALUE;
		}

		public boolean surface() {
			return "surface".equals(id);
		}

		/** Bezugshöhe der Höhlenansicht (wie {@link MapEngine} sie anlegt) oder {@link Integer#MIN_VALUE}. */
		public int caveRef() {
			int band = caveBand();
			return band == Integer.MIN_VALUE ? Integer.MIN_VALUE : band * MapEngine.CAVE_BAND + MapEngine.CAVE_BAND + 3;
		}
	}

	/** Eine gespeicherte Dimension. */
	public static final class DimInfo {
		public final String id;
		public final Path dir;
		public final List<LayerInfo> layers;

		public DimInfo(String id, Path dir, List<LayerInfo> layers) {
			this.id = id;
			this.dir = dir;
			this.layers = layers;
		}

		public LayerInfo layer(String layerId) {
			for (LayerInfo l : layers) {
				if (l.id.equals(layerId)) return l;
			}
			return null;
		}

		public int regionCount() {
			int n = 0;
			for (LayerInfo l : layers) n += l.regions;
			return n;
		}
	}

	/** Art einer Dimension (Oberwelt, Nether, End, sonstige). */
	public static int kind(String dim) {
		if (dim == null) return OTHER;
		if (MapEngine.isNether(dim)) return NETHER;
		if (MapEngine.isEnd(dim)) return END;
		String d = dim.toLowerCase(Locale.ROOT);
		if (d.contains("overworld") || d.equals("dim0") || d.equals("0")) return OVERWORLD;
		return OTHER;
	}

	/**
	 * Faktor, mit dem Koordinaten aus {@code from} in {@code to} umgerechnet werden (Nether → Oberwelt 8, umgekehrt
	 * 1/8, gleiche Art 1), {@link Double#NaN} = keine sinnvolle Umrechnung (End, fremde Dimensionen).
	 */
	public static double factor(String from, String to) {
		int a = kind(from), b = kind(to);
		if (a == b && a != OTHER) return 1.0;
		if (a == OTHER || b == OTHER) return from != null && from.equals(to) ? 1.0 : Double.NaN;
		if (a == NETHER && b == OVERWORLD) return NETHER_SCALE;
		if (a == OVERWORLD && b == NETHER) return 1.0 / NETHER_SCALE;
		return Double.NaN;
	}

	/** Blockkoordinate umrechnen (abgerundet wie Minecraft), z. B. Nether 100 → Oberwelt 800, Oberwelt −1 → Nether −1. */
	public static int convert(int block, String from, String to) {
		double f = factor(from, to);
		if (Double.isNaN(f)) return block;
		return (int) Math.floor(block * f);
	}

	/**
	 * Gegenstück für die Koordinatenanzeige: Nether ↔ Oberwelt, sonst null.
	 */
	public static String counterpart(String dim) {
		int k = kind(dim);
		if (k == NETHER) return legacyStyle(dim) ? "dim0" : "minecraft:overworld";
		if (k == OVERWORLD) return legacyStyle(dim) ? "dim-1" : "minecraft:the_nether";
		return null;
	}

	private static boolean legacyStyle(String dim) {
		return dim != null && dim.startsWith("dim");
	}

	/**
	 * Echte Kennung aus einem gekürzten Ordnernamen ({@code <name>-<prüfwert>}) zurückgewinnen: der Name selbst oder
	 * mit dem ersten „_“ als „:“ (z. B. {@code minecraft_the_nether} → {@code minecraft:the_nether}); gilt nur, wenn der
	 * Prüfwert passt. null = nicht eindeutig (dann hilft nur {@value MapDisk#DIMENSION_FILE}).
	 */
	public static String decodeDirName(String dirName) {
		if (dirName == null) return null;
		int dash = dirName.lastIndexOf('-');
		if (dash <= 0) return null;
		String base = dirName.substring(0, dash);
		if (MapDisk.safeName(base).equals(dirName)) return base;
		for (int i = 0; i < base.length(); i++) {
			if (base.charAt(i) != '_') continue;
			String candidate = base.substring(0, i) + ":" + base.substring(i + 1);
			if (MapDisk.safeName(candidate).equals(dirName)) return candidate;
		}
		return null;
	}

	/** Ebenen-Kennung aus einem Ordnernamen (Prüfwert muss passen), sonst null. */
	static String decodeLayerName(String dirName) {
		String id = decodeDirName(dirName);
		if (id == null) return null;
		if ("surface".equals(id) || id.startsWith("cave") || id.startsWith("roof")) return id;
		return null;
	}

	/** Anzeigereihenfolge: Oberwelt, Nether, End, Rest alphabetisch. */
	public static final Comparator<DimInfo> ORDER = new Comparator<DimInfo>() {
		@Override
		public int compare(DimInfo a, DimInfo b) {
			int ka = kind(a.id), kb = kind(b.id);
			if (ka != kb) return ka < kb ? -1 : 1;
			return a.id.compareTo(b.id);
		}
	};

	/**
	 * Liest alle gespeicherten Dimensionen einer Welt (blockiert – nur im Kartenthread oder in Tests). Dachebenen
	 * (Innenansicht) werden nicht angeboten: sie sind nur Stückwerk um Gebäude herum.
	 */
	public static List<DimInfo> scan(Path worldDir) {
		List<DimInfo> out = new ArrayList<DimInfo>();
		if (worldDir == null || !Files.isDirectory(worldDir)) return out;
		try (DirectoryStream<Path> dims = Files.newDirectoryStream(worldDir)) {
			for (Path d : dims) {
				if (!Files.isDirectory(d)) continue;
				String id = readId(d);
				if (id == null) continue;
				List<LayerInfo> layers = new ArrayList<LayerInfo>();
				try (DirectoryStream<Path> ls = Files.newDirectoryStream(d)) {
					for (Path l : ls) {
						if (!Files.isDirectory(l)) continue;
						String lid = decodeLayerName(l.getFileName().toString());
						if (lid == null || lid.startsWith("roof")) continue;
						int n = countRegions(l);
						if (n > 0) layers.add(new LayerInfo(lid, l, n));
					}
				} catch (IOException e) {
					// Ebenen, die gelesen wurden, reichen
				}
				if (layers.isEmpty()) continue;
				Collections.sort(layers, LAYER_ORDER);
				out.add(new DimInfo(id, d, layers));
			}
		} catch (IOException | RuntimeException e) {
			// was gefunden wurde, reicht
		}
		Collections.sort(out, ORDER);
		return out;
	}

	/** Oberfläche zuerst, dann Höhlenschichten von oben nach unten. */
	static final Comparator<LayerInfo> LAYER_ORDER = new Comparator<LayerInfo>() {
		@Override
		public int compare(LayerInfo a, LayerInfo b) {
			if (a.surface() != b.surface()) return a.surface() ? -1 : 1;
			return Integer.compare(b.caveBand(), a.caveBand());
		}
	};

	private static String readId(Path dimDir) {
		Path f = dimDir.resolve(MapDisk.DIMENSION_FILE);
		if (Files.isRegularFile(f)) {
			try {
				String s = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim();
				// Nur annehmen, wenn die Datei auch zu diesem Ordner gehört.
				if (!s.isEmpty() && s.length() < 200 && MapDisk.safeName(s).equals(dimDir.getFileName().toString())) return s;
			} catch (IOException e) {
				// weiter mit dem Ordnernamen
			}
		}
		return decodeDirName(dimDir.getFileName().toString());
	}

	private static int countRegions(Path layerDir) {
		int n = 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(layerDir, "r.*.trsm")) {
			for (Path ignored : files) n++;
		} catch (IOException e) {
			return n;
		}
		return n;
	}

	/**
	 * Standard-Ebene beim Ansehen einer Dimension von außen: im Nether die meistgefüllte Höhlenschicht (die
	 * „Oberfläche“ dort ist nur die Bedrock-Decke), sonst die Oberfläche; ohne beides die größte Ebene.
	 */
	public static LayerInfo defaultLayer(DimInfo dim) {
		if (dim == null || dim.layers.isEmpty()) return null;
		LayerInfo surface = dim.layer("surface");
		LayerInfo bestCave = null;
		for (LayerInfo l : dim.layers) {
			if (l.cave() && (bestCave == null || l.regions > bestCave.regions)) bestCave = l;
		}
		if (kind(dim.id) == NETHER && bestCave != null) return bestCave;
		if (surface != null) return surface;
		if (bestCave != null) return bestCave;
		return dim.layers.get(0);
	}

	/** Wie groß ist eine Ebene (für die Anzeige „Höhle Y 56–63“)? Unterkante des Bands. */
	public static int bandBottom(int band) {
		return band * MapEngine.CAVE_BAND;
	}
}
