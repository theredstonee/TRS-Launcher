package dev.theredstonee.trsclient.core.chatheads;

/**
 * Wer zu einer Chat-Zeile gehört. Eigener TRS-Code (GPL-3.0-only, nicht von Chat Heads abgeleitet).
 * {@link #NONE} heißt: schon geprüft, kein Kopf. {@code null} in einem Cache heißt: noch nicht geprüft.
 */
public final class ChatHeadSender {
	public static final ChatHeadSender NONE = new ChatHeadSender(null, null, false);

	/** Tab-Listen-Schreibweise, oder null. */
	public final String name;
	/** Kanonische UUID ({@code 8-4-4-4-12}), oder null. */
	public final String uuid;
	/** „You whisper …“ / „[me -&gt; …]“: der lokale Spieler. */
	public final boolean self;

	private ChatHeadSender(String name, String uuid, boolean self) {
		this.name = name;
		this.uuid = uuid;
		this.self = self;
	}

	public static ChatHeadSender name(String name) {
		if (name == null || name.isEmpty()) return NONE;
		return new ChatHeadSender(name, null, false);
	}

	public static ChatHeadSender uuid(String uuid, String name) {
		if ((uuid == null || uuid.isEmpty()) && (name == null || name.isEmpty())) return NONE;
		return new ChatHeadSender(emptyToNull(name), emptyToNull(uuid), false);
	}

	public static ChatHeadSender self() {
		return new ChatHeadSender(null, null, true);
	}

	/** Irgendetwas, woraus ein Kopf werden kann. */
	public boolean hasHead() {
		return self || name != null || uuid != null;
	}

	private static String emptyToNull(String value) {
		return value == null || value.isEmpty() ? null : value;
	}
}
