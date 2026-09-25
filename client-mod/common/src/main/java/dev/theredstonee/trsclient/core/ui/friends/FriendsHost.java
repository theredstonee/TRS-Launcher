package dev.theredstonee.trsclient.core.ui.friends;

/** Was der Freunde-Bildschirm ({@link FriendsUi}) von Minecraft braucht. */
public interface FriendsHost {
	void playClick();

	/** Zurück zum vorherigen Bildschirm. */
	void closeScreen();

	/** User-Agent für das Laden der Gesichter. */
	String userAgent();
}
