package dev.theredstonee.trsclient.core.hosting.net;

import java.net.InetSocketAddress;

/**
 * Ein zuverlässiger, geordneter Bytestrom zu einem Mitspieler – egal ob direkt (P2P über UDP), über das TRS Relay
 * (TCP) oder über den öffentlichen Link. Die Netty-Kanäle ({@code core.hosting.netty}) hängen den Minecraft-Strom
 * daran.
 *
 * <p>Alle Methoden sind threadsicher und blockieren nie. Daten, die vor {@link #start} ankommen, werden gepuffert.
 */
public interface PeerStream {
	/** Weg der Verbindung (für die Spielerliste des Hosts). */
	enum Path {
		/** Direkt (UDP-Lochstanzen). */
		DIRECT,
		/** Über das TRS Relay. */
		RELAY,
		/** Öffentlicher Link (e4mc). */
		PUBLIC
	}

	/** Empfänger – Aufrufe kommen aus dem Netz-Thread des Stroms, nie gleichzeitig. */
	interface Sink {
		/** Neue Bytes (Puffer gehört danach dem Empfänger nicht mehr – sofort kopieren). */
		void data(byte[] b, int off, int len);

		/** Strom zu (Grund für das Log, nie mit Geheimnissen). Danach kommt nichts mehr. */
		void closed(String reason);
	}

	/** Empfang starten (einmal). */
	void start(Sink sink);

	/**
	 * Bytes senden (werden kopiert). false = Strom zu oder Sendepuffer übergelaufen (dann wird er geschlossen).
	 */
	boolean write(byte[] b, int off, int len);

	/** Schließen (mehrfach erlaubt). */
	void close(String reason);

	boolean isOpen();

	Path path();

	/** Adresse der Gegenseite für Minecraft (P2P: echte Adresse; Relay/Link: Platzhalter), nie null. */
	InetSocketAddress remoteAddress();
}
