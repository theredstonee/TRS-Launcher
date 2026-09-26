package dev.theredstonee.trsclient.core.ui;

/**
 * Logische Tasten der Oberfläche. Die Tastencodes unterscheiden sich je nach Minecraft-Version
 * (GLFW, SDL, LWJGL 2) – der versionsabhängige Bildschirm übersetzt sie einmal in diese Werte.
 */
public enum UiKey {
	NONE,
	ESCAPE,
	ENTER,
	BACKSPACE,
	DELETE,
	TAB,
	LEFT,
	RIGHT,
	UP,
	DOWN,
	HOME,
	END,
	/** Strg+V (Cmd+V): Einfügen aus der Zwischenablage. */
	PASTE,
	/** Strg+A: alles markieren (Textfelder: Cursor ans Ende). */
	SELECT_ALL
}
