package dev.theredstonee.trsclient.core.ui.menus;

/** Was der Bildschirm „Server-Info“ ({@link ServerInfoUi}) von Minecraft braucht. */
public interface ServerInfoHost {
	/** Momentaufnahme der Verbindung (alle Felder optional). */
	final class Info {
		/** Name aus der Serverliste (oder Weltname im Einzelspieler). */
		public String name;
		/** Adresse "host[:port]"; null im Einzelspieler. */
		public String address;
		public boolean singleplayer;
		/** Einzelspieler-Welt im LAN geöffnet. */
		public boolean lan;
		/** Server-Software laut Server (z. B. "Paper"). */
		public String brand;
		/** Minecraft-Version des Spiels. */
		public String version;
		/** Eigene Latenz in ms (−1 = unbekannt). */
		public int ping = -1;
		/** Spieler in der Tabliste (−1 = unbekannt). */
		public int players = -1;
		/** Dimension (z. B. "minecraft:overworld"). */
		public String dimension;
		/** Eigene Position, schon formatiert. */
		public String position;
		/** Server-Ressourcenpaket aktiv? */
		public boolean serverPack;
	}

	void playClick();

	void closeScreen();

	/** Aktuelle Werte (jedes Bild, billig). */
	Info info();

	/** Adresse in die Zwischenablage; false = geht nicht. */
	boolean copy(String text);

	/** User-Agent für das Laden der Gesichter. */
	String userAgent();
}
