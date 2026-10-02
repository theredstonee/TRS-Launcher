package dev.theredstonee.trsclient.core.ui;

/**
 * Letzte Fenster-Lage im Fenstermodus. Wird jedes Bild aktualisiert, solange das Fenster weder exklusiv noch
 * randlos ist, und beim Verlassen des Vollbilds zurückgesetzt.
 */
public final class WindowMemory {
	private int x;
	private int y;
	private int width;
	private int height;
	private boolean saved;

	/** Übernimmt die Lage, wenn Breite und Höhe mindestens 1 sind. Sonst bleibt der vorige Stand. */
	public void track(int x, int y, int width, int height) {
		if (width < 1 || height < 1) return;
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.saved = true;
	}

	public void clear() {
		saved = false;
	}

	public boolean saved() {
		return saved;
	}

	public int x() {
		return x;
	}

	public int y() {
		return y;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}
}
