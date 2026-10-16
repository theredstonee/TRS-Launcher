package dev.theredstonee.trsclient.core.creator;

/** Meilenstein eines Creators (aktive Fans, API.md §35.1) – Farbe des Rahmens ums Fan-Symbol. */
public enum Milestone {
	BRONZE(0xFFCD7F32),
	SILVER(0xFFC0C0C0),
	GOLD(0xFFFFCF4D),
	DIAMOND(0xFF5FF2FF);

	/** Rahmenfarbe (ARGB, deckend). */
	public final int color;

	Milestone(int color) {
		this.color = color;
	}

	/** {@code "gold"} → GOLD; null/unbekannt → null (kein Rahmen). */
	public static Milestone parse(String raw) {
		if (raw == null) return null;
		switch (raw) {
			case "bronze":
				return BRONZE;
			case "silver":
				return SILVER;
			case "gold":
				return GOLD;
			case "diamond":
				return DIAMOND;
			default:
				return null;
		}
	}
}
