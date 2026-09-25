package dev.theredstonee.trsclient.core.wardrobe;

/**
 * UV-Aufteilung eines 64×64-Skins: welcher Texel zu welchem Körperteil, welcher Ebene (Grund/zweite) und welcher
 * Quaderseite gehört – und welcher Texel ihm beim Spiegeln (links ↔ rechts) entspricht.
 *
 * <p>Dieselbe Aufteilung wie {@link dev.theredstonee.trsclient.core.skin.SkinModel} (Vanilla-Box-UV): je Quader
 * vorn (u+d, v+d), hinten (u+2d+w, v+d), rechts (u, v+d), links (u+d+w, v+d), oben (u+d, v), unten (u+d+w, v).
 * Beim Spiegeln an der senkrechten Mittelebene tauschen rechter/linker Arm bzw. rechtes/linkes Bein; Kopf und
 * Körper spiegeln auf sich selbst; die Seiten „rechts“/„links“ tauschen, alle anderen bleiben; in jeder Seite
 * läuft die waagerechte Texel-Achse danach umgekehrt (i → Breite−1−i), die senkrechte bleibt.
 */
public final class SkinLayout {
	public static final int SIZE = 64;

	public static final int HEAD = 0;
	public static final int BODY = 1;
	public static final int RIGHT_ARM = 2;
	public static final int LEFT_ARM = 3;
	public static final int RIGHT_LEG = 4;
	public static final int LEFT_LEG = 5;
	public static final int PARTS = 6;

	public static final int FRONT = 0;
	public static final int BACK = 1;
	public static final int RIGHT = 2;
	public static final int LEFT = 3;
	public static final int TOP = 4;
	public static final int BOTTOM = 5;

	/** Grundebene bzw. zweite Ebene (Hut, Jacke, Ärmel, Hosenbeine). */
	public static final int BASE = 0;
	public static final int OVERLAY = 1;

	private static final SkinLayout CLASSIC = new SkinLayout(false);
	private static final SkinLayout SLIM = new SkinLayout(true);

	/** Je Texel: Region-Nummer + 1 (0 = gehört zu keiner Seite). Region = (Teil, Ebene, Seite). */
	private final short[] region = new short[SIZE * SIZE];
	/** Je Texel: gespiegelter Texel (Index) oder −1. */
	private final short[] mirror = new short[SIZE * SIZE];
	/** Je Region: x, y, Breite, Höhe der Seite im Skin. */
	private final int[][] rects = new int[PARTS * 2 * 6][];
	public final boolean slim;

	private SkinLayout(boolean slim) {
		this.slim = slim;
		for (int part = 0; part < PARTS; part++) {
			for (int layer = 0; layer < 2; layer++) {
				int[] box = box(part, layer, slim);
				for (int face = 0; face < 6; face++) {
					int[] r = faceRect(box, face);
					int id = regionId(part, layer, face);
					rects[id] = r;
					for (int y = r[1]; y < r[1] + r[3]; y++) {
						for (int x = r[0]; x < r[0] + r[2]; x++) region[y * SIZE + x] = (short) (id + 1);
					}
				}
			}
		}
		for (int i = 0; i < SIZE * SIZE; i++) {
			mirror[i] = -1;
			int id = region[i] - 1;
			if (id < 0) continue;
			int part = id / 12;
			int layer = (id / 6) % 2;
			int face = id % 6;
			int[] r = rects[id];
			int[] m = rects[regionId(mirrorPart(part), layer, mirrorFace(face))];
			int col = (i % SIZE) - r[0];
			int row = (i / SIZE) - r[1];
			if (m[2] != r[2] || m[3] != r[3]) continue;
			mirror[i] = (short) ((m[1] + row) * SIZE + m[0] + (r[2] - 1 - col));
		}
	}

	public static SkinLayout of(boolean slim) {
		return slim ? SLIM : CLASSIC;
	}

	static int regionId(int part, int layer, int face) {
		return part * 12 + layer * 6 + face;
	}

	/** Quader {u, v, w, h, d} eines Teils in einer Ebene (wie SkinModel). */
	public static int[] box(int part, int layer, boolean slim) {
		int arm = slim ? 3 : 4;
		switch (part) {
			case HEAD:
				return layer == BASE ? new int[]{0, 0, 8, 8, 8} : new int[]{32, 0, 8, 8, 8};
			case BODY:
				return layer == BASE ? new int[]{16, 16, 8, 12, 4} : new int[]{16, 32, 8, 12, 4};
			case RIGHT_ARM:
				return layer == BASE ? new int[]{40, 16, arm, 12, 4} : new int[]{40, 32, arm, 12, 4};
			case LEFT_ARM:
				return layer == BASE ? new int[]{32, 48, arm, 12, 4} : new int[]{48, 48, arm, 12, 4};
			case RIGHT_LEG:
				return layer == BASE ? new int[]{0, 16, 4, 12, 4} : new int[]{0, 32, 4, 12, 4};
			case LEFT_LEG:
				return layer == BASE ? new int[]{16, 48, 4, 12, 4} : new int[]{0, 48, 4, 12, 4};
			default:
				throw new IllegalArgumentException("Teil " + part);
		}
	}

	/** Rechteck {x, y, w, h} einer Seite eines Quaders {u, v, w, h, d}. */
	public static int[] faceRect(int[] box, int face) {
		int u = box[0];
		int v = box[1];
		int w = box[2];
		int h = box[3];
		int d = box[4];
		switch (face) {
			case FRONT:
				return new int[]{u + d, v + d, w, h};
			case BACK:
				return new int[]{u + 2 * d + w, v + d, w, h};
			case RIGHT:
				return new int[]{u, v + d, d, h};
			case LEFT:
				return new int[]{u + d + w, v + d, d, h};
			case TOP:
				return new int[]{u + d, v, w, d};
			case BOTTOM:
				return new int[]{u + d + w, v, w, d};
			default:
				throw new IllegalArgumentException("Seite " + face);
		}
	}

	static int mirrorPart(int part) {
		switch (part) {
			case RIGHT_ARM:
				return LEFT_ARM;
			case LEFT_ARM:
				return RIGHT_ARM;
			case RIGHT_LEG:
				return LEFT_LEG;
			case LEFT_LEG:
				return RIGHT_LEG;
			default:
				return part;
		}
	}

	static int mirrorFace(int face) {
		if (face == RIGHT) return LEFT;
		if (face == LEFT) return RIGHT;
		return face;
	}

	/** Region des Texels (≥ 0) oder −1, wenn er zu keiner Seite gehört. */
	public int region(int x, int y) {
		if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return -1;
		return region[y * SIZE + x] - 1;
	}

	/** Ebene einer Region ({@link #BASE}/{@link #OVERLAY}). */
	public static int layerOf(int region) {
		return (region / 6) % 2;
	}

	/** Teil einer Region. */
	public static int partOf(int region) {
		return region / 12;
	}

	/** Rechteck {x, y, w, h} einer Region (nicht verändern). */
	public int[] rect(int region) {
		return rects[region];
	}

	/** Gespiegelter Texel-Index zu {@code index} (y·64+x) oder −1. */
	public int mirror(int index) {
		if (index < 0 || index >= SIZE * SIZE) return -1;
		return mirror[index];
	}

	/** Gehört der Texel zur Ebene {@code layer}? */
	public boolean inLayer(int x, int y, int layer) {
		int r = region(x, y);
		return r >= 0 && layerOf(r) == layer;
	}
}
