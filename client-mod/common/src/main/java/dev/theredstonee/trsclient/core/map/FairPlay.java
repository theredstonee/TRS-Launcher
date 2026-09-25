package dev.theredstonee.trsclient.core.map;

import java.util.Locale;

/**
 * Fair Play der Karten: ein Schalter für alles, was über das Sichtbare hinausgeht – Höhlenansicht (wirkt wie
 * Röntgenblick) und Markierungen von Spielern/Kreaturen, die man nicht direkt sieht (Radar durch Wände).
 *
 * <p>Zusätzlich werden die verbreiteten Server-Codes der Xaero-Karten beachtet (so verschicken sie z. B. die
 * Plugins, die Fair Play auf Servern erzwingen – als Chat-Nachricht oder im MOTD, als §-Farbcodes, die im Spiel
 * unsichtbar sind): {@code §f§a§i§r§x§a§e§r§o} = Fair Play, {@code §n§o§m§i§n§i§m§a§p} = keine Minimap,
 * {@code §x§a§e§r§o§m§m§n§e§t§h§e§r§i§s§f§a§i§r} (bzw. {@code …w§m…}) = Höhlenansicht im Nether erlaubt,
 * {@code §r§e§s§e§t§x§a§e§r§o} = zurücksetzen. Die Server-Merker gelten bis zum Verlassen des Servers.
 */
public final class FairPlay {
	/** Das Paragraphenzeichen der Minecraft-Farbcodes (als Zahl, damit die Quelltext-Kodierung egal ist). */
	static final char SECTION = (char) 0xA7;
	static final String FAIR = "fairxaero";
	static final String NO_MINIMAP = "nominimap";
	static final String RESET = "resetxaero";
	static final String NETHER_FAIR_MINIMAP = "xaeromm" + "netherisfair";
	static final String NETHER_FAIR_WORLDMAP = "xaerowm" + "netherisfair";

	private boolean manual;
	private boolean serverFair;
	private boolean serverNoMinimap;
	private boolean serverNetherFair;

	/** Der Schalter im TRS-Menü. */
	public void setManual(boolean on) {
		manual = on;
	}

	public boolean manual() {
		return manual;
	}

	/** Verlangt der Server Fair Play? */
	public boolean serverFair() {
		return serverFair;
	}

	public boolean serverNoMinimap() {
		return serverNoMinimap;
	}

	public boolean serverNetherFair() {
		return serverNetherFair;
	}

	/** Irgendeine Einschränkung aktiv (für den Hinweis im Menü/auf der Karte)? */
	public boolean active() {
		return manual || serverFair;
	}

	/** Darf die Höhlenansicht benutzt werden? Im Nether ggf. vom Server ausdrücklich erlaubt. */
	public boolean caveAllowed(boolean nether) {
		if (!manual && !serverFair) return true;
		// Nur der Server kann die Höhlenansicht im Nether freigeben – der eigene Schalter bleibt streng.
		return nether && !manual && serverNetherFair;
	}

	/** Dürfen Spieler/Kreaturen ohne Sichtlinie angezeigt werden? */
	public boolean radarThroughWalls() {
		return !manual && !serverFair;
	}

	/** Darf die Minimap gezeigt werden? */
	public boolean minimapAllowed() {
		return !serverNoMinimap;
	}

	/** Server verlassen: Server-Merker vergessen. */
	public void resetServer() {
		serverFair = false;
		serverNoMinimap = false;
		serverNetherFair = false;
	}

	/**
	 * Prüft einen Text vom Server (Chat-Zeile oder MOTD) auf Codes.
	 *
	 * @return true, wenn sich etwas geändert hat
	 */
	public boolean onServerText(String text) {
		if (text == null || text.indexOf(SECTION) < 0) return false;
		String codes = codes(text);
		if (codes.isEmpty()) return false;
		boolean before = serverFair, beforeNo = serverNoMinimap, beforeNether = serverNetherFair;
		if (codes.contains(RESET)) resetServer();
		if (codes.contains(FAIR)) serverFair = true;
		if (codes.contains(NO_MINIMAP)) serverNoMinimap = true;
		if (codes.contains(NETHER_FAIR_MINIMAP) || codes.contains(NETHER_FAIR_WORLDMAP)) serverNetherFair = true;
		return before != serverFair || beforeNo != serverNoMinimap || beforeNether != serverNetherFair;
	}

	/** Die Zeichen hinter jedem §, hintereinander ("§f§a§i§r" → "fair"). */
	static String codes(String text) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i + 1 < text.length(); i++) {
			if (text.charAt(i) == SECTION) {
				sb.append(text.charAt(i + 1));
				i++;
			}
		}
		return sb.toString().toLowerCase(Locale.ROOT);
	}
}
