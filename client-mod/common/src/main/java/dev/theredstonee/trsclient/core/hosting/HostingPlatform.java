package dev.theredstonee.trsclient.core.hosting;

import java.nio.file.Path;
import java.util.List;

/**
 * Was das Welt-Hosting vom Spiel braucht – je Loader/Version eine Umsetzung ({@code hosting/HostingHooks} in den
 * Bäumen). Methoden ohne Hinweis kommen aus dem Spiel-Thread; Server-Zugriffe muss die Umsetzung selbst auf den
 * Server-Thread legen.
 */
public interface HostingPlatform {
	/** Ein Spieler der gehosteten Welt. */
	final class Player {
		public final String uuid;
		public final String name;

		public Player(String uuid, String name) {
			this.uuid = uuid;
			this.name = name;
		}
	}

	/** Einstellungen der offenen Welt. */
	final class Options {
		public String gameMode = "survival";
		public boolean cheats;
		public boolean pvp = true;
		public int maxPlayers = 8;

		public Options copy() {
			Options o = new Options();
			o.gameMode = gameMode;
			o.cheats = cheats;
			o.pvp = pvp;
			o.maxPlayers = maxPlayers;
			return o;
		}
	}

	/** Rückruf eines langen Vorgangs (Spiel-Thread). {@code error} = i18n-Schlüssel oder null. */
	interface Done {
		void done(Path result, String error);
	}

	/** Läuft gerade eine Einzelspielerwelt (integrierter Server), die sich hosten lässt? */
	boolean canHost();

	/** Name der Welt (Vorschlag für den Raumnamen) oder null. */
	String worldName();

	/** Eindeutige Kennung der laufenden Welt (Ordner) – ändert sie sich, ist das Hosting zu Ende. null = keine. */
	String worldKey();

	String minecraftVersion();

	/** vanilla|fabric|forge|neoforge (wie die API). */
	String loader();

	/** Das Listener-Objekt des integrierten Servers ({@code ServerConnectionListener}/{@code NetworkSystem}) oder null. */
	Object connectionListener();

	/**
	 * Welt für Gäste öffnen – OHNE TCP-Port und OHNE LAN-Rundruf: gilt als veröffentlicht (pausiert nicht mehr),
	 * Spielmodus für neue Spieler, Cheats, PvP, Spielergrenze.
	 */
	void publish(Options o);

	/** Geänderte Einstellungen übernehmen. */
	void apply(Options o);

	/** Veröffentlichung zurücknehmen (Gäste sind dann schon getrennt). */
	void unpublish();

	/** Spieler in der Welt (Host eingeschlossen). Beliebiger Thread erlaubt (Kopie). */
	List<Player> players();

	/**
	 * Spielmodus/OP eines Spielers setzen (die Umsetzung legt es auf den Server-Thread). {@code gameMode}
	 * survival|creative|adventure|spectator oder null = unverändert; {@code op} TRUE = OP geben, FALSE = nehmen,
	 * null = unverändert.
	 */
	void applyRights(String uuid, String gameMode, Boolean op);

	/** Spieler mit Meldung trennen (true = gefunden). */
	boolean disconnect(String uuid, String message);

	/** Welt speichern und als ZIP in den Backup-Ordner legen; {@code done} im Spiel-Thread. */
	void backup(Done done);

	/** Als Gast verbinden (vorher die aktuelle Welt verlassen). {@code address} von TrsConnect/LoopbackBridge. */
	void connect(String address, String label);

	/** Hat dieser Baum den Mixin-Weg für den Client ({@link dev.theredstonee.trsclient.core.hosting.netty.TrsConnect})? */
	boolean channelConnect();

	/** Ist man gerade in einer Welt oder auf einem Server? */
	default boolean inWorld() {
		return false;
	}

	/** Ist das Spiel bereit für einen Beitritt vom Launcher (fertig geladen, Menü sichtbar)? */
	default boolean readyForJoin() {
		return true;
	}

	/** In die Zwischenablage. */
	void copy(String text);

	/** Ins Spiel-Log (nie Tokens). */
	void log(String message);

	/**
	 * Nur Entwicklung/Autotest: Server ohne Mojang-Anmeldung (Test-Konten ohne echte Sitzung). Im normalen Spiel
	 * immer false.
	 */
	default boolean offlineTest() {
		return Boolean.getBoolean("trsclient.hosting.offline");
	}
}
