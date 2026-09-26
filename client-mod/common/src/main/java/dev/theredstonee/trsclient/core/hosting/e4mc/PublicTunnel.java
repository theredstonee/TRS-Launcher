package dev.theredstonee.trsclient.core.hosting.e4mc;

import dev.theredstonee.trsclient.core.hosting.net.PeerStream;

/**
 * Schnittstelle zum öffentlichen Link (e4mc-Relay über QUIC). Die Umsetzung ({@code e4mcbridge}) läuft in einem
 * eigenen ClassLoader mit eigenem Netty 4.2 + QUIC – getrennt von Minecrafts Netty. Nur Typen aus diesem Paket,
 * {@link PeerStream} und {@code java.*} gehen über die Grenze.
 */
public interface PublicTunnel {
	/** Rückrufe aus den Netty-Threads der Brücke. */
	interface Events {
		/** Öffentliche Adresse vergeben (z. B. {@code abc-def.de.e4mc.link}). */
		void domain(String domain);

		/** Ein Spieler verbindet sich über den Link (eigener Strom je Verbindung). */
		void stream(PeerStream stream);

		/** Nachricht des e4mc-Betreibers an alle (z. B. Wartung). */
		void broadcast(String message);

		/** Verbindung zum e4mc-Relay verloren/fehlgeschlagen (Grund fürs Log). */
		void failed(String reason);
	}

	/** Verbinden (blockiert höchstens bis die Anfrage gestellt ist; das Ergebnis kommt über {@code events}). */
	void start(String brokerUrl, Events events) throws Exception;

	/** Link schließen. */
	void stop();
}
