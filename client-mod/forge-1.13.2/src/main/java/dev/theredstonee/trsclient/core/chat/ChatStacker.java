package dev.theredstonee.trsclient.core.chat;

/**
 * Wiederholte Chat-Nachrichten zusammenfassen: statt fünf gleicher Zeilen bleibt eine
 * mit "(x5)". Merkt sich nur die zuletzt angezeigte Nachricht – ältere Zeilen bleiben stehen.
 */
public final class ChatStacker {
	/** Danach gilt eine gleiche Nachricht wieder als neu (Standard: 30 s). */
	public static final long DEFAULT_WINDOW_MS = 30_000;

	private String lastText;
	private int count;
	private long lastTime;

	/**
	 * Meldet eine eingehende Nachricht.
	 *
	 * @return 1 = neue Nachricht (normal anzeigen), ≥ 2 = Wiederholung; die vorherige Zeile
	 * 		   soll durch diese mit "(xN)" ersetzt werden
	 */
	public int accept(String plain, long nowMs, long windowMs) {
		if (plain == null) plain = "";
		if (lastText != null && lastText.equals(plain) && nowMs - lastTime <= windowMs) {
			count++;
		} else {
			lastText = plain;
			count = 1;
		}
		lastTime = nowMs;
		return count;
	}

	/** Aktuelle Wiederholungszahl. */
	public int count() {
		return count;
	}

	/** Chat gelöscht / Welt gewechselt / fremde Zeile dazwischen: neu anfangen. */
	public void reset() {
		lastText = null;
		count = 0;
		lastTime = 0;
	}

	/** Anhang für eine Wiederholung ("" bei count ≤ 1). */
	public static String suffix(int count) {
		return count <= 1 ? "" : " (x" + count + ")";
	}
}
