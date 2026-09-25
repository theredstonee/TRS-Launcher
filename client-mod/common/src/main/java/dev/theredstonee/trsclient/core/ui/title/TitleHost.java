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

	// --- Neue Bereiche (Seitenleiste, Konto). Spätere Pakete setzen sie je Loader um; false = „Kommt bald“. ---

	/**
	 * Aussehen des eigenen Spielers für die Figur auf der Drehscheibe (Name, Skin, Umhang) oder null
	 * (dann nur der Name aus {@link #playerName()}, keine Figur). Wird je Bild gefragt (Render-Thread).
	 */
	default dev.theredstonee.trsclient.core.skin.PlayerLook look() {
		return null;
	}

	/** Spielername, falls {@link #look()} null liefert. */
	default String playerName() {
		return "Player";
	}

	/** Garderobe öffnen (Skins, Umhänge, Emotes); false = noch nicht verfügbar. */
	default boolean openWardrobe() {
		return false;
	}

	/** Kontoverwaltung öffnen (Konto wechseln/hinzufügen); false = noch nicht verfügbar. */
	default boolean openAccounts() {
		return false;
	}

	/** Freundesliste öffnen; false = noch nicht verfügbar. */
	default boolean openFriends() {
		return false;
	}

	/** Clips und Bilder öffnen; false = noch nicht verfügbar. */
	default boolean openClips() {
		return false;
	}
}
