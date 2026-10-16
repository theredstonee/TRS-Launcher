package dev.theredstonee.trsclient.core.creator;

/** Stufe eines aktiven Creators ({@code creator.tier} im Lookup, API.md §35.5) – Farbe des Creator-Sterns. */
public enum CreatorTier {
	STARTER(0x9FE0FF),
	PARTNER(0xC79BFF),
	TOP(0xFFCF4D);

	/** Farbe des Sterns (RGB). */
	public final int color;

	CreatorTier(int color) {
		this.color = color;
	}

	/** {@code "partner"} → PARTNER; null/unbekannt → null (kein Stern). */
	public static CreatorTier parse(String raw) {
		if (raw == null) return null;
		switch (raw) {
			case "starter":
				return STARTER;
			case "partner":
				return PARTNER;
			case "top":
				return TOP;
			default:
				return null;
		}
	}
}
