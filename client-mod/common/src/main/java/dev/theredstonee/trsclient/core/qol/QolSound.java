package dev.theredstonee.trsclient.core.qol;

/**
 * Klänge des Komfort-/PvP-Pakets mit ihren Namen je Minecraft-Ära: ab 1.13 ({@link #modern}), 1.9–1.12
 * ({@link #legacy}) und 1.8.9 ({@link #old}). null = in dieser Ära nicht vorhanden (dann Rückfall auf {@link #PLING}).
 */
public enum QolSound {
	PLING("block.note_block.pling", "block.note.pling", "note.pling"),
	BELL("block.note_block.bell", "block.note.bell", null),
	CHIME("block.note_block.chime", "block.note.chime", null),
	ORB("entity.experience_orb.pickup", "entity.experience_orb.pickup", "random.orb"),
	BASS("block.note_block.bass", "block.note.bass", "note.bass"),
	HAT("block.note_block.hat", "block.note.hat", "note.hat"),
	ARROW_HIT("entity.arrow.hit_player", "entity.arrow.hit_player", "random.successful_hit"),
	CRIT("entity.player.attack.crit", "entity.player.attack.crit", null);

	public final String modern;
	public final String legacy;
	public final String old;

	QolSound(String modern, String legacy, String old) {
		this.modern = modern;
		this.legacy = legacy;
		this.old = old;
	}

	/** Name für die Ära (1 = ab 1.13, 2 = 1.9–1.12, 3 = 1.8.9); null = gibt es dort nicht. */
	public String name(int era) {
		return era == 1 ? modern : era == 2 ? legacy : old;
	}

	/** Klang eines Erwähnungs-Tons. */
	public static QolSound of(dev.theredstonee.trsclient.core.chat.ChatMentions.Sound s) {
		if (s == null) return PLING;
		switch (s) {
			case BELL:
				return BELL;
			case ORB:
				return ORB;
			case CHIME:
				return CHIME;
			default:
				return PLING;
		}
	}

	/** Klang eines Treffer-Tons (null = aus). */
	public static QolSound of(dev.theredstonee.trsclient.core.pvp.HitFeedback.Sound s) {
		if (s == null) return null;
		switch (s) {
			case ORB:
				return ORB;
			case ARROW:
				return ARROW_HIT;
			case HAT:
				return HAT;
			case PLING:
				return PLING;
			case CRIT:
				return CRIT;
			default:
				return null;
		}
	}
}
