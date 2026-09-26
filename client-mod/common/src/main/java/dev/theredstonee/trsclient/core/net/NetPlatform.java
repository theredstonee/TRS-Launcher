package dev.theredstonee.trsclient.core.net;

import io.netty.channel.ChannelHandler;

/**
 * Was je Minecraft-Version anders ist (Paketklassen, Vanilla-Handler, Senden). Umgesetzt in jedem Loader-Baum
 * ({@code net/NetHooks}); alle Methoden müssen schnell sein und dürfen nichts werfen.
 */
public interface NetPlatform {
	/** Kein Paket dieser Art: Rückgabe der Erkennungs-Methoden. */
	long NONE = Long.MIN_VALUE;

	/**
	 * Ersatz für Vanillas Entpacker ("decompress") mit gleicher Schwelle – eine Unterklasse der Vanilla-Klasse, damit
	 * Vanillas {@code instanceof} weiter stimmt. null = nicht ersetzen (fremde Klasse, oder Vanilla ist schon schlank).
	 */
	ChannelHandler upgradeDecompress(ChannelHandler vanilla);

	/** Entpackt Vanilla in dieser Version schon ohne Kopien (dann gibt es dort nichts zu verbessern)? */
	default boolean inflateAlreadyLean() {
		return false;
	}

	/** Wie {@link #upgradeDecompress} für den Packer ("compress"). */
	ChannelHandler upgradeCompress(ChannelHandler vanilla);

	/** Ist das Minecrafts eigener Entschlüsselungs-Handler (genau diese Klasse, kein Mod)? */
	boolean vanillaDecrypt(ChannelHandler handler);

	/** Zeitstempel einer Ping-Antwort ({@code ClientboundPongResponsePacket}, ab 1.20.2) oder {@link #NONE}. */
	long pongTime(Object packet);

	/** Spielzeit (Ticks) eines Zeit-Pakets oder {@link #NONE}. */
	long gameTime(Object packet);

	/** Kennung eines Keepalive-Pakets vom Server oder {@link #NONE}. */
	long keepAliveId(Object packet);

	/** Erlaubt die Version eigene Ping-Anfragen im Spiel ({@code ServerboundPingRequestPacket}, ab 1.20.2)? */
	boolean activePing();

	/**
	 * Sendet eine Ping-Anfrage mit diesem Zeitstempel (Hauptthread, nur wenn {@link #activePing()}) – nur, wenn die
	 * Verbindung gerade im Spiel-Protokoll ist (nicht während einer Rekonfiguration über einen Proxy).
	 *
	 * @return true = gesendet
	 */
	boolean sendPing(long millis);

	/** Uhr, mit der Vanilla Ping-Zeitstempel bildet ({@code Util.getMillis()}); von jedem Thread aufrufbar. */
	long millis();

	/** Latenz des eigenen Spielers laut Spielerliste (vom Server gemessen) oder -1. */
	int serverLatency();

	/** Mit einem echten Server verbunden (nicht Einzelspieler, nicht Ladebildschirm)? */
	boolean multiplayer();
}
