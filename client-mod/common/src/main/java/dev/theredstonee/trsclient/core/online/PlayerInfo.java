package dev.theredstonee.trsclient.core.online;

/** Was der Lookup über einen Spieler weiß: nutzt er TRS (Abzeichen) und welcher Umhang. */
public final class PlayerInfo {
	public static final PlayerInfo NONE = new PlayerInfo(false, null);

	public final boolean badge;
	/** null = kein TRS-Umhang (Vanilla/OptiFine bleibt). */
	public final CapeInfo cape;

	public PlayerInfo(boolean badge, CapeInfo cape) {
		this.badge = badge;
		this.cape = cape;
	}
}
