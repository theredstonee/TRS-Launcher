package dev.theredstonee.trsclient.core.cinematic;

import dev.theredstonee.trsclient.core.camera.ServerList;
import dev.theredstonee.trsclient.core.map.FairPlay;

/**
 * Was der Server zur freien Kamera sagt. Überall erlaubt, außer:
 * <ul>
 *   <li>bekannte PvP-/Minigame-Netzwerke ({@link #KNOWN_SERVERS}, inkl. Subdomains) – dort ist sie aus,</li>
 *   <li>der Server schickt den Code {@code §t§r§s§n§o§f§r§e§e§c§a§m} („trsnofreecam“) – aus,</li>
 *   <li>{@code §t§r§s§f§a§i§r§f§r§e§e§c§a§m} („trsfairfreecam“) oder der verbreitete Fair-Play-Code der Xaero-Karten
 *       {@code §f§a§i§r§x§a§e§r§o} – Fair Play wird erzwungen (keine Kamera durch Wände),</li>
 *   <li>{@code §t§r§s§r§e§s§e§t§f§r§e§e§c§a§m} bzw. {@code §r§e§s§e§t§x§a§e§r§o} heben die Server-Merker wieder auf.</li>
 * </ul>
 * Codes kommen wie bei den Karten ({@link FairPlay}) als unsichtbare §-Farbcodes im Chat oder im MOTD; sie gelten bis
 * zum Verlassen des Servers.
 */
public final class FreecamRules {
	static final String NO_FREECAM = "trsnofreecam";
	static final String FAIR_FREECAM = "trsfairfreecam";
	static final String RESET = "trsresetfreecam";
	static final String XAERO_FAIR = "fairxaero";
	static final String XAERO_RESET = "resetxaero";
	/** Netzwerke, deren Regeln eine freie Kamera nicht erlauben (wie „Off on these servers“ beim Freelook). */
	public static final String KNOWN_SERVERS = "hypixel.net minemen.club pvp.land cubecraft.net gommehd.net "
			+ "mineplex.com mccentral.org pika-network.net hivemc.com";

	/** Aktueller Server (null = Einzelspieler bzw. keine Welt). */
	private String address;
	private boolean inWorld;
	private boolean known;
	private boolean serverBlocked;
	private boolean serverFair;

	/**
	 * Einmal je Tick mit der Serveradresse (null = Einzelspieler).
	 *
	 * @return true, wenn ein anderer Server betreten wurde (dann das MOTD mit {@link #onServerText} prüfen)
	 */
	public boolean update(boolean inWorld, String address) {
		if (!inWorld) {
			leave();
			return false;
		}
		boolean changed = !this.inWorld || !same(address, this.address);
		if (this.inWorld && changed) {
			// Direkt auf einen anderen Server: alte Merker gelten nicht mehr.
			serverBlocked = false;
			serverFair = false;
		}
		this.inWorld = true;
		if (changed) {
			this.address = address;
			known = address != null && ServerList.matches(address, KNOWN_SERVERS);
		}
		return changed;
	}

	/** Welt verlassen: alles vergessen. */
	public void leave() {
		inWorld = false;
		address = null;
		known = false;
		serverBlocked = false;
		serverFair = false;
	}

	/**
	 * Server-Text (Chat-Zeile oder MOTD) auf Codes prüfen. Kommt der Chat schon vor dem ersten Tick in der Welt, bleiben
	 * die Merker trotzdem (zurückgesetzt wird nur beim Verlassen bzw. Wechsel des Servers).
	 *
	 * @return true, wenn sich etwas geändert hat
	 */
	public boolean onServerText(String text) {
		if (text == null || text.indexOf(FairPlay.SECTION) < 0) return false;
		String codes = FairPlay.codes(text);
		if (codes.isEmpty()) return false;
		boolean beforeBlocked = serverBlocked, beforeFair = serverFair;
		if (codes.contains(RESET) || codes.contains(XAERO_RESET)) {
			serverBlocked = false;
			serverFair = false;
		}
		if (codes.contains(NO_FREECAM)) serverBlocked = true;
		if (codes.contains(FAIR_FREECAM) || codes.contains(XAERO_FAIR)) serverFair = true;
		return beforeBlocked != serverBlocked || beforeFair != serverFair;
	}

	/** Darf die freie Kamera benutzt werden? */
	public boolean allowed() {
		return !known && !serverBlocked;
	}

	/** Muss Fair Play an sein (Server verlangt es, oder die freie Kamera ist hier ohnehin gesperrt)? */
	public boolean fairForced() {
		return serverFair || !allowed();
	}

	/** Liegt die Sperre an der Liste bekannter Server (statt an einem Code)? */
	public boolean knownServer() {
		return known;
	}

	private static boolean same(String a, String b) {
		return a == null ? b == null : a.equals(b);
	}
}
