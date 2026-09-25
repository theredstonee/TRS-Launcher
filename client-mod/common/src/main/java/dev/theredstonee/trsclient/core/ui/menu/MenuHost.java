package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;

import java.util.List;

/**
 * Alles, was die versionsunabhängige Oberfläche von Minecraft braucht. Jede Loader-Variante
 * liefert eine kleine Implementierung (Bildschirme öffnen, Klick-Geräusch, Tastennamen).
 */
public interface MenuHost {
	TrsModules modules();

	/** Klick-Geräusch der Oberfläche. */
	void playClick();

	/** Bildschirm schließen (zurück zum vorherigen Bildschirm/ins Spiel). */
	void closeScreen();

	/** HUD-Editor öffnen. */
	void openHudEditor();

	/** Menü öffnen (z. B. „Zurück“ aus dem HUD-Editor). */
	void openMenu();

	/** Resourcepack-Bildschirm öffnen (nur wenn {@link #hasPacks()}). */
	void openPacks();

	boolean hasPacks();

	/**
	 * Kontobildschirm öffnen (Kontowechsel ohne Neustart, siehe
	 * {@link dev.theredstonee.trsclient.core.ui.account.AccountsUi}). Nur wenn {@link #hasAccounts()}.
	 */
	default void openAccounts() {
	}

	/** Gibt es in dieser Version den Kontobildschirm? */
	default boolean hasAccounts() {
		return false;
	}

	/** Freunde-Bildschirm öffnen (nur wenn {@link #hasFriends()}). */
	default void openFriends() {
	}

	/** Gibt es in dieser Version Freunde im Spiel (TRS-Online-Funktionen)? */
	default boolean hasFriends() {
		return false;
	}

	/** Bildschirm „Clips &amp; Bilder“ öffnen (nur wenn {@link #hasClips()}). */
	default void openClips() {
	}

	default boolean hasClips() {
		return false;
	}

	/**
	 * Gibt es das Modul in dieser Minecraft-Version? Alte Versionen lassen einzelne Module aus
	 * (z. B. Treffer-Farbe in 1.7.10) – die tauchen dann gar nicht erst im Menü auf.
	 */
	boolean supports(Module module);

	/** Zusätzliche Knöpfe eines Moduls (z. B. „Fadenkreuz-Editor“); nie null. */
	List<MenuAction> actions(Module module);

	/** Einstellungen speichern. */
	void save();

	boolean shiftDown();

	/** Anzeigename einer Taste, z. B. {@code "key.keyboard.v"} → "V". */
	String keyLabel(String keyName);

	/** Tastencode dieser Minecraft-Version → Tastenname, z. B. "key.keyboard.v"; null = unbekannt. */
	String keyNameOf(int rawKey);

	/** Anzeigename der Taste, die das TRS-Menü öffnet. */
	String menuKeyLabel();

	/** Anzeigename der Taste, die das HUD-Profil wechselt (leer = unbelegt). */
	String profileKeyLabel();

	/** HUD-Elemente für den Editor (nur aktive werden gezeichnet). */
	List<HudItem> hudItems();

	/** Läuft gerade eine Welt (dann bleibt der HUD-Editor durchsichtig)? */
	boolean inWorld();

	/** Vorschau des eigenen Spielers geht. */
	int PREVIEW_OK = 0;
	/** Diese Minecraft-Version kann den Spieler im Menü nicht zeichnen. */
	int PREVIEW_UNSUPPORTED = 1;
	/** Kein Spieler da (Titelbildschirm). */
	int PREVIEW_NO_PLAYER = 2;

	/** Kann die Live-Vorschau des eigenen Spielers (Umhang-Physik) gerade gezeichnet werden? */
	default int playerPreviewState() {
		return PREVIEW_UNSUPPORTED;
	}

	/** Trägt der eigene Spieler einen sichtbaren Umhang (sonst Hinweis unter der Vorschau)? */
	default boolean previewHasCape() {
		return true;
	}

	/**
	 * Zeichnet den eigenen Spieler (mit simuliertem Umhang) in das Rechteck, um {@code yawDegrees} um die
	 * senkrechte Achse gedreht (0 = Blick zum Betrachter, 180 = Rücken). Der Canvas ist vorher geleert.
	 */
	default void drawPlayerPreview(Canvas c, int x, int y, int w, int h, float yawDegrees) {
	}

	/** Die Vorschau zeigt gerade „gehen“ (Umhang weht wie beim Laufen)? */
	default boolean previewWalking() {
		return false;
	}
}
