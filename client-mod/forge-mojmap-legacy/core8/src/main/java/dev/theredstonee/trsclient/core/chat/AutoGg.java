package dev.theredstonee.trsclient.core.chat;

import dev.theredstonee.trsclient.core.util.RateLimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Auto-GG: erkennt typische Spielende-Meldungen und schickt danach einen frei einstellbaren
 * Text ("gg"). Standardmäßig AUS. Streng begrenzt (höchstens einmal pro Minute, nie zweimal
 * kurz hintereinander) – der Client soll nie den Chat fluten.
 *
 * <p>Eine Nachricht löst nur aus, wenn der Auslöser am Anfang der Zeile steht: alles davor
 * darf keinen Doppelpunkt/"&gt;" enthalten, damit ein Mitspieler nicht per Chat auslösen kann
 * ("[MVP+] Foo: winner: haha").
 */
public final class AutoGg {
	/** Standard-Auslöser (Hypixel &amp; Co., deutsch und englisch), klein geschrieben. */
	public static final String[] DEFAULT_TRIGGERS = {
			"1st killer",
			"winner:",
			"winner!",
			"winning team",
			"won the game",
			"victory!",
			"game over",
			"sieger:",
			"gewinner:",
			"hat das spiel gewonnen",
			"spiel beendet",
	};

	/** Frühestens eine Minute nach der letzten Nachricht, nie zweimal innerhalb von 10 s. */
	private final RateLimiter limiter = new RateLimiter(60_000, 1, 60_000);
	private boolean pending;
	private long sendAt;

	/** Zerlegt die zusätzlichen Auslöser des Benutzers (durch ";" getrennt). */
	public static List<String> extraTriggers(String text) {
		List<String> list = new ArrayList<>();
		if (text == null) return list;
		for (String part : text.split(";")) {
			String t = part.trim().toLowerCase(Locale.ROOT);
			if (!t.isEmpty()) list.add(t);
		}
		return list;
	}

	/** Passt die Nachricht auf einen der Auslöser? */
	public static boolean matches(String plain, List<String> extra) {
		if (plain == null || plain.isEmpty()) return false;
		String lower = plain.toLowerCase(Locale.ROOT);
		for (String trigger : DEFAULT_TRIGGERS) {
			if (isLineStart(lower, trigger)) return true;
		}
		if (extra != null) {
			for (String trigger : extra) {
				if (isLineStart(lower, trigger)) return true;
			}
		}
		return false;
	}

	/** Steht {@code trigger} am Anfang der Zeile (nur Deko davor)? */
	private static boolean isLineStart(String lower, String trigger) {
		int idx = lower.indexOf(trigger);
		if (idx < 0) return false;
		for (int i = 0; i < idx; i++) {
			char c = lower.charAt(i);
			// Vor dem Auslöser darf nur "Deko" stehen – kein Chat-Text eines Spielers.
			if (c == ':' || c == '>' || c == '<') return false;
		}
		return true;
	}

	/**
	 * Meldet eine eingehende Nachricht; plant bei einem Treffer das Senden.
	 *
	 * @return true, wenn jetzt geplant wurde
	 */
	public boolean onMessage(String plain, List<String> extra, long nowMs, long delayMs) {
		if (pending || !matches(plain, extra)) return false;
		if (!limiter.tryAcquire(nowMs)) return false;
		pending = true;
		sendAt = nowMs + Math.max(0, delayMs);
		return true;
	}

	/** Ist die geplante Nachricht jetzt fällig? Liefert genau einmal true. */
	public boolean due(long nowMs) {
		if (!pending || nowMs < sendAt) return false;
		pending = false;
		return true;
	}

	public boolean pending() {
		return pending;
	}

	/** Welt verlassen / Modul aus: geplante Nachricht verwerfen. */
	public void cancel() {
		pending = false;
	}

	public void reset() {
		pending = false;
		limiter.reset();
	}
}
