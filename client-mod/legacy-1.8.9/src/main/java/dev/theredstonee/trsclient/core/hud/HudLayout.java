package dev.theredstonee.trsclient.core.hud;

/**
 * Reine Positions-Mathematik für HUD-Elemente (ohne Minecraft-Abhängigkeit).
 * Alle Werte in GUI-Pixeln (skalierte Bildschirmkoordinaten).
 */
public final class HudLayout {
	/** Abstand zum Bildschirmrand beim Einrasten. */
	public static final int EDGE_MARGIN = 2;
	/** Einrast-Distanz in Pixeln. */
	public static final int SNAP_DISTANCE = 5;

	private HudLayout() {
	}

	/** Linke Kante des Elements (auf den Bildschirm begrenzt). */
	public static int resolveX(HudPosition pos, int width, int screenWidth) {
		double x = pos.anchor.fx * (screenWidth - width) + pos.offsetX * screenWidth;
		return clamp((int) Math.round(x), 0, Math.max(0, screenWidth - width));
	}

	/** Obere Kante des Elements (auf den Bildschirm begrenzt). */
	public static int resolveY(HudPosition pos, int height, int screenHeight) {
		double y = pos.anchor.fy * (screenHeight - height) + pos.offsetY * screenHeight;
		return clamp((int) Math.round(y), 0, Math.max(0, screenHeight - height));
	}

	/**
	 * Wandelt eine Pixelposition in Anker + relativen Versatz um.
	 * Der Anker ergibt sich aus dem Bildschirmdrittel, in dem die Elementmitte liegt.
	 */
	public static HudPosition fromPixels(int x, int y, int width, int height, int screenWidth, int screenHeight) {
		HudAnchor anchor = HudAnchor.of(third(x + width / 2.0, screenWidth), third(y + height / 2.0, screenHeight));
		double ox = screenWidth <= 0 ? 0 : (x - anchor.fx * (screenWidth - width)) / screenWidth;
		double oy = screenHeight <= 0 ? 0 : (y - anchor.fy * (screenHeight - height)) / screenHeight;
		return new HudPosition(anchor, ox, oy);
	}

	/** Rastet X an linken/rechten Rand bzw. Bildschirmmitte ein, falls nah genug. */
	public static int snapX(int x, int width, int screenWidth) {
		return snap(x, width, screenWidth);
	}

	/** Rastet Y an oberen/unteren Rand bzw. Bildschirmmitte ein, falls nah genug. */
	public static int snapY(int y, int height, int screenHeight) {
		return snap(y, height, screenHeight);
	}

	/** True, wenn das Element genau mittig liegt (für Hilfslinien im Editor). */
	public static boolean isCentered(int pos, int size, int screenSize) {
		return pos == centered(size, screenSize);
	}

	public static int clamp(int v, int min, int max) {
		return v < min ? min : Math.min(v, max);
	}

	private static int snap(int pos, int size, int screenSize) {
		int start = EDGE_MARGIN;
		int end = screenSize - size - EDGE_MARGIN;
		int center = centered(size, screenSize);
		if (Math.abs(pos - start) <= SNAP_DISTANCE) return start;
		if (Math.abs(pos - end) <= SNAP_DISTANCE) return end;
		if (Math.abs(pos - center) <= SNAP_DISTANCE) return center;
		return clamp(pos, 0, Math.max(0, screenSize - size));
	}

	private static int centered(int size, int screenSize) {
		return (screenSize - size) / 2;
	}

	private static int third(double center, int screenSize) {
		if (screenSize <= 0) return 0;
		double f = center / screenSize;
		return f < 1.0 / 3.0 ? 0 : (f > 2.0 / 3.0 ? 2 : 1);
	}
}
