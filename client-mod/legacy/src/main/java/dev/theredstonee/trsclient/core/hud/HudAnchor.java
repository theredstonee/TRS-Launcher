package dev.theredstonee.trsclient.core.hud;

/**
 * Ankerpunkt eines HUD-Elements am Bildschirm (3×3-Raster).
 * {@code fx}/{@code fy} sind die relative Lage (0 = links/oben, 1 = rechts/unten).
 */
public enum HudAnchor {
	TOP_LEFT(0f, 0f), TOP_CENTER(0.5f, 0f), TOP_RIGHT(1f, 0f),
	CENTER_LEFT(0f, 0.5f), CENTER(0.5f, 0.5f), CENTER_RIGHT(1f, 0.5f),
	BOTTOM_LEFT(0f, 1f), BOTTOM_CENTER(0.5f, 1f), BOTTOM_RIGHT(1f, 1f);

	public final float fx;
	public final float fy;

	HudAnchor(float fx, float fy) {
		this.fx = fx;
		this.fy = fy;
	}

	/** Anker aus Spalte/Zeile (je 0..2). */
	public static HudAnchor of(int column, int row) {
		return values()[row * 3 + column];
	}

	/** Robustes Parsen aus der Config; unbekannte Werte → {@code fallback}. */
	public static HudAnchor parse(String name, HudAnchor fallback) {
		if (name == null) return fallback;
		for (HudAnchor a : values()) {
			if (a.name().equalsIgnoreCase(name)) return a;
		}
		return fallback;
	}
}
