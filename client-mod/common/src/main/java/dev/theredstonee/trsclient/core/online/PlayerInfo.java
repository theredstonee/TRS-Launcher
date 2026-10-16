package dev.theredstonee.trsclient.core.online;

import dev.theredstonee.trsclient.core.creator.CreatorTier;

/**
 * Was der Lookup über einen Spieler weiß: nutzt er TRS (Abzeichen), welcher Umhang und welche Kopf-Kosmetik, ist er
 * Creator (Stern) und wessen Fan-Symbol trägt er.
 */
public final class PlayerInfo {
	public static final PlayerInfo NONE = new PlayerInfo(false, null);

	public final boolean badge;
	/** null = kein TRS-Umhang (Vanilla/OptiFine bleibt). */
	public final CapeInfo cape;
	/** null = nichts auf dem Kopf (oder eine Vorlage, die diese Mod noch nicht zeichnet). */
	public final HatInfo hat;
	/** Stufe, wenn der Spieler ein aktiver Creator ist, sonst null. */
	public final CreatorTier creator;
	/** Fan-Symbol, das vor dem Namen steht, sonst null. */
	public final FanInfo fan;

	public PlayerInfo(boolean badge, CapeInfo cape) {
		this(badge, cape, null);
	}

	public PlayerInfo(boolean badge, CapeInfo cape, HatInfo hat) {
		this(badge, cape, hat, null, null);
	}

	public PlayerInfo(boolean badge, CapeInfo cape, HatInfo hat, CreatorTier creator, FanInfo fan) {
		this.badge = badge;
		this.cape = cape;
		this.hat = hat;
		this.creator = creator;
		this.fan = fan;
	}

	/** Gleiche Infos, anderes Live-Abzeichen. */
	PlayerInfo withBadge(boolean badge) {
		return new PlayerInfo(badge, cape, hat, creator, fan);
	}
}
