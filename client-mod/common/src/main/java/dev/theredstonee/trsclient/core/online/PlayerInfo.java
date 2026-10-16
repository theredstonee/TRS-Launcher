package dev.theredstonee.trsclient.core.online;

import dev.theredstonee.trsclient.core.creator.CreatorTier;

import java.util.Collections;
import java.util.List;

/**
 * Was der Lookup über einen Spieler weiß: nutzt er TRS (Abzeichen), welcher Umhang, welche Kopf-Kosmetik und welcher
 * Begleiter, ist er Creator (Stern), wessen Fan-Symbol trägt er, welche Profil-Abzeichen (Halloween 2026 …) zeigt er und
 * welche Spuk-Effekte darf man bei ihm sehen.
 */
public final class PlayerInfo {
	public static final PlayerInfo NONE = new PlayerInfo(false, null);

	/** Spuk-Effekte ({@code fx} im Lookup, nur während Halloween): Fledermäuse bei Treffern. */
	public static final int FX_HITS = 1;
	/** Spuk-Effekte: Geister-Spur beim Sprinten. */
	public static final int FX_TRAIL = 2;

	public final boolean badge;
	/** null = kein TRS-Umhang (Vanilla/OptiFine bleibt). */
	public final CapeInfo cape;
	/** null = nichts auf dem Kopf (oder eine Vorlage, die diese Mod noch nicht zeichnet). */
	public final HatInfo hat;
	/** Stufe, wenn der Spieler ein aktiver Creator ist, sonst null. */
	public final CreatorTier creator;
	/** Fan-Symbol, das vor dem Namen steht, sonst null. */
	public final FanInfo fan;
	/** Begleiter neben dem Hut (Format 2, z. B. die Fledermaus) oder null. */
	public final HatInfo companion;
	/** Profil-Abzeichen ({@code badges} im Lookup, z. B. {@code halloween2026}); nie null, unveränderlich. */
	public final List<String> badges;
	/** Spuk-Effekte, die andere sehen dürfen ({@link #FX_HITS}, {@link #FX_TRAIL}). */
	public final int fx;
	/** Flügel am Rücken (Format 2, {@code attach: "body"}) oder null. */
	public final HatInfo wings;

	public PlayerInfo(boolean badge, CapeInfo cape) {
		this(badge, cape, null);
	}

	public PlayerInfo(boolean badge, CapeInfo cape, HatInfo hat) {
		this(badge, cape, hat, null, null);
	}

	public PlayerInfo(boolean badge, CapeInfo cape, HatInfo hat, CreatorTier creator, FanInfo fan) {
		this(badge, cape, hat, creator, fan, null, null, 0);
	}

	public PlayerInfo(boolean badge, CapeInfo cape, HatInfo hat, CreatorTier creator, FanInfo fan, HatInfo companion,
			List<String> badges, int fx) {
		this(badge, cape, hat, creator, fan, companion, badges, fx, null);
	}

	public PlayerInfo(boolean badge, CapeInfo cape, HatInfo hat, CreatorTier creator, FanInfo fan, HatInfo companion,
			List<String> badges, int fx, HatInfo wings) {
		this.badge = badge;
		this.cape = cape;
		this.hat = hat;
		this.creator = creator;
		this.fan = fan;
		this.companion = companion;
		this.badges = badges == null || badges.isEmpty() ? Collections.<String>emptyList() : Collections.unmodifiableList(badges);
		this.fx = fx;
		this.wings = wings;
	}

	/** Gleiche Infos, anderes Live-Abzeichen. */
	PlayerInfo withBadge(boolean badge) {
		return new PlayerInfo(badge, cape, hat, creator, fan, companion, badges, fx, wings);
	}

	/** Hat der Spieler das Profil-Abzeichen {@code id}? */
	public boolean hasBadge(String id) {
		return badges.contains(id);
	}
}
