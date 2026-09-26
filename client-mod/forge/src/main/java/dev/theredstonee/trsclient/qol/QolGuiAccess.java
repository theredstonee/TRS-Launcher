package dev.theredstonee.trsclient.qol;

/**
 * Zugriff auf Titel und Aktionsleiste des HUDs (Gui bis 26.1, Hud ab 26.2), umgesetzt von {@code QolGuiMixin}. Die Werte
 * sind Component (bis 1.15 String) – deshalb Object. Fehlt der Mixin (Forge 1.14.4), ist das HUD kein QolGuiAccess.
 */
public interface QolGuiAccess {
	/** Aktueller Titel oder null. */
	Object trsclient$title();

	/** Aktuelle Aktionsleisten-Nachricht oder null. */
	Object trsclient$overlay();
}
