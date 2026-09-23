package dev.theredstonee.trsclient.core.ui.title;

/**
 * Was der versionsunabhängige Startbildschirm ({@link TitleUi}) von Minecraft braucht: die Ziele
 * der Knöpfe, ein Klick-Geräusch und – wo es die Version kann – die Sprachausgabe.
 */
public interface TitleHost {
	void singleplayer();

	void multiplayer();

	void options();

	void trsMenu();

	/** Gibt es eine Mod-Liste (Forge immer, Fabric nur mit ModMenu)? */
	boolean hasMods();

	void mods();

	void quit();

	/** Vanilla-Titelbildschirm öffnen. */
	void classicTitle();

	/** Fußzeile links, z. B. "Minecraft 1.21.1 · TRS Client 0.3.0". */
	String versionLine();

	void playClick();

	/** Liest einen Text über den Erzähler vor (nur wenn der Spieler ihn eingeschaltet hat); darf nichts tun. */
	void narrate(String text);

	/** Animierter Hintergrund? (Einstellung des Moduls „Startbildschirm“ – aus = ruhiges Standbild.) */
	boolean animated();

	/** Sparsame Hintergrund-Animation (alte Versionen, die jedes Rechteck einzeln zeichnen). */
	boolean simpleAnimation();
}
