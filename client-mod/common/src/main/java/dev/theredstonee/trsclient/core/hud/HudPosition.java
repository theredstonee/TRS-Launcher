package dev.theredstonee.trsclient.core.hud;

/**
 * Auflösungsunabhängige Position: Anker + Versatz als Anteil der Bildschirmgröße.
 * Beispiel: TOP_RIGHT mit Versatz (0, 0) klebt immer bündig oben rechts.
 */
public final class HudPosition {
	public HudAnchor anchor;
	/** Versatz relativ zur Bildschirmbreite (z. B. 0.01 = 1 %). */
	public double offsetX;
	/** Versatz relativ zur Bildschirmhöhe. */
	public double offsetY;

	public HudPosition(HudAnchor anchor, double offsetX, double offsetY) {
		this.anchor = anchor;
		this.offsetX = offsetX;
		this.offsetY = offsetY;
	}

	public HudPosition copy() {
		return new HudPosition(anchor, offsetX, offsetY);
	}

	public void set(HudPosition other) {
		this.anchor = other.anchor;
		this.offsetX = other.offsetX;
		this.offsetY = other.offsetY;
	}

	@Override
	public String toString() {
		return anchor + "(" + offsetX + ", " + offsetY + ")";
	}
}
