package dev.theredstonee.trsclient.core.ui;

/**
 * Misst Text in GUI-Pixeln (die Schrift des Spiels) – für Layouts, die ihre Größe kennen müssen,
 * bevor gezeichnet wird (HUD-Elemente). Je Loader z. B. {@code s -> mc.font.width(s)}.
 */
public interface TextWidth {
	int width(String text);
}
