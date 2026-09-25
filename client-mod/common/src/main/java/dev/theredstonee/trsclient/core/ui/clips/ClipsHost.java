package dev.theredstonee.trsclient.core.ui.clips;

import java.nio.file.Path;

/** Was der Bildschirm „Clips &amp; Bilder“ ({@link ClipsUi}) von Minecraft braucht. */
public interface ClipsHost {
	void playClick();

	/** Zurück zum vorherigen Bildschirm. */
	void closeScreen();

	/** Spielordner der Instanz ({@code screenshots/} liegt darin). */
	Path gameDir();

	/** Konfigurationsordner ({@code trsclient/clips.json}). */
	Path configDir();
}
