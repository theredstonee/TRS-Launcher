package dev.theredstonee.trsclient.core.ui;

/**
 * Ob das Spiel gerade als randloses Vollbild läuft. Dynamische FPS behandelt das wie exklusives Vollbild
 * (fokussiert und sichtbar), sonst würde ein randloses Fenster im Hintergrund-Takt laufen.
 */
public final class BorderlessState {
	private static volatile boolean active;

	private BorderlessState() {
	}

	public static boolean active() {
		return active;
	}

	public static void mark(boolean on) {
		active = on;
	}
}
