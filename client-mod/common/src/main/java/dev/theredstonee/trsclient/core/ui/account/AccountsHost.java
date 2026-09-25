package dev.theredstonee.trsclient.core.ui.account;

/**
 * Was der Kontobildschirm ({@link AccountsUi}) von Minecraft braucht. Die Konten selbst verwaltet
 * {@link dev.theredstonee.trsclient.core.account.AccountManager}.
 */
public interface AccountsHost {
	void playClick();

	/** Zurück zum vorherigen Bildschirm. */
	void closeScreen();

	/** Für Gesichter aus dem Netz. */
	String userAgent();
}
