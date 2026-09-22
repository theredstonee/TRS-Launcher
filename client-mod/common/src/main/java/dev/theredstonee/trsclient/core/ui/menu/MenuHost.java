package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;

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
}
