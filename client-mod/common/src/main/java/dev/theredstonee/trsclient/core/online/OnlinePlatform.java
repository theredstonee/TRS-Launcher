package dev.theredstonee.trsclient.core.online;

/** Was {@link TrsOnline} vom Spiel wissen muss – je Loader/Version eine kleine Umsetzung. */
public interface OnlinePlatform {
	/** Konto des laufenden Spiels (null = unbekannt). */
	GameSession session();

	/** z. B. "1.21.1". */
	String minecraftVersion();

	/** "fabric", "forge" oder "neoforge". */
	String loader();

	/** Aktuelle Server-Adresse ("host[:port]"), null im Einzelspieler/Menü. */
	String serverAddress();

	/** Meldung ins Spiel-Log (nie mit Token). */
	void log(String message);
}
