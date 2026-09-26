package dev.theredstonee.trsclient.core.online;

/** Was der Lookup über einen Spieler weiß: nutzt er TRS (Abzeichen), welcher Umhang und welche Kopf-Kosmetik. */
public final class PlayerInfo {
	public static final PlayerInfo NONE = new PlayerInfo(false, null);

	public final boolean badge;
	/** null = kein TRS-Umhang (Vanilla/OptiFine bleibt). */
	public final CapeInfo cape;
	/** null = nichts auf dem Kopf (oder eine Vorlage, die diese Mod noch nicht zeichnet). */
	public final HatInfo hat;

	public PlayerInfo(boolean badge, CapeInfo cape) {
		this(badge, cape, null);
	}

	public PlayerInfo(boolean badge, CapeInfo cape, HatInfo hat) {
		this.badge = badge;
		this.cape = cape;
		this.hat = hat;
	}
}
