package dev.theredstonee.trsclient.core.account;

import java.nio.file.Path;

/**
 * Was die Kontoverwaltung von Minecraft braucht – je Loader/Version eine kleine Umsetzung (siehe
 * {@code online/SessionSwap} in den Bäumen).
 */
public interface AccountPlatform {
	/** Aktuelle Sitzung des Spiels (Name, UUID, Token); null = unbekannt. Darf aus jedem Thread kommen. */
	SessionData current();

	/** Läuft gerade eine Welt oder Serververbindung? Dann wird nicht gewechselt. */
	boolean inWorld();

	/** Im Spiel-Thread ausführen. */
	void execute(Runnable task);

	/**
	 * Vorbereitung im Konto-Thread (darf ins Netz): z. B. die Mojang-Dienste mit dem neuen Token anlegen, die
	 * alte Versionen schon beim Anlegen abfragen. Das Ergebnis bekommt {@link #apply}.
	 */
	default Object prepare(SessionData session) throws Exception {
		return null;
	}

	/**
	 * Sitzung einsetzen (Spiel-Thread): Minecrafts Benutzer tauschen und alles, was daran hängt
	 * (Dienste für Chat-Signaturen, Blockliste, Meldungen, Telemetrie, Realms, eigenes Profil/Skin).
	 */
	void apply(SessionData session, Object prepared) throws Exception;

	/** Adresse im Browser öffnen (Microsoft-Anmeldung). */
	default void openUrl(String url) {
		Browser.open(url);
	}

	/** {@code config}-Ordner der Instanz. */
	Path configDir();

	String userAgent();

	/** Meldung ins Spiel-Log (nie mit Token). */
	void log(String message);
}
