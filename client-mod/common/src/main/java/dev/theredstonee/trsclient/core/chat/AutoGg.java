package dev.theredstonee.trsclient.core.chat;

import dev.theredstonee.trsclient.core.util.RateLimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Auto-GG: erkennt typische Spielende-Meldungen und schickt danach einen frei einstellbaren
 * Text ("gg"). Standardmäßig AUS. Streng begrenzt: genau eine Nachricht je Rundenende, frühestens 0,5 s und
 * spätestens 2 s danach, höchstens einmal je 10 s – so, wie es z. B. Hypixel ausdrücklich duldet. Der Client soll
 * nie den Chat fluten.
 *
 * <p>Eine Nachricht löst nur aus, wenn der Auslöser am Anfang der Zeile steht: alles davor
 * darf keinen Doppelpunkt/"&gt;" enthalten, damit ein Mitspieler nicht per Chat auslösen kann
 * ("[MVP+] Foo: winner: haha").
 */
public final class AutoGg {
	/**
	 * Standard-Auslöser, klein geschrieben: Hypixel (Bed Wars, SkyWars, Duels …: „1st Killer“, „Winner:“, „Winning
	 * Team“), Übungs-/Duell-Server wie Minemen und PvP.Land („Match Results“, „won the duel“), dazu deutsch.
	 */
	public static final String[] DEFAULT_TRIGGERS = {
			"1st killer",
			"1st place",
			"winner:",
			"winners:",
			"winner!",
			"winning team",
			"won the game",
			"victory!",
			"game over",
			"match results",
			"match result",
			"won the match",
			"won the duel",
			"has won the duel",
			"duel ended",
			"match ended",
			"sieger:",
			"gewinner:",
			"hat das spiel gewonnen",
			"spiel beendet",
	};
	/** Titel (nur der Server kann sie setzen) am Rundenende, klein geschrieben, am Anfang des Titels. */
	public static final String[] TITLE_TRIGGERS = {"victory", "you win", "you won", "game over", "sieg", "gewonnen"};

	/** Frühestens/spätestens so lange nach dem Rundenende wird gesendet. */
	public static final long MIN_DELAY_MS = 500;
	public static final long MAX_DELAY_MS = 2000;
	/** Höchstens einmal je 10 s (eine Runde = ein „gg“). */
	public static final long INTERVAL_MS = 10_000;

	private final RateLimiter limiter = new RateLimiter(INTERVAL_MS, 1, INTERVAL_MS);
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
		return matches(plain, extra, true);
	}

	/** Passt die Nachricht auf einen der Auslöser? {@code presets} = eingebaute Muster bekannter Server nutzen. */
	public static boolean matches(String plain, List<String> extra, boolean presets) {
		if (plain == null || plain.isEmpty()) return false;
		String lower = ChatText.strip(plain).toLowerCase(Locale.ROOT);
		if (presets) {
			for (String trigger : DEFAULT_TRIGGERS) {
				if (isLineStart(lower, trigger)) return true;
			}
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

	/** Passt ein Titel (vom Server) auf ein Rundenende? */
	public static boolean matchesTitle(String title, List<String> extra, boolean presets) {
		if (title == null) return false;
		String lower = ChatText.strip(title).trim().toLowerCase(Locale.ROOT);
		while (!lower.isEmpty() && !Character.isLetterOrDigit(lower.charAt(0))) lower = lower.substring(1);
		if (lower.isEmpty()) return false;
		if (presets) {
			for (String t : TITLE_TRIGGERS) {
				if (lower.startsWith(t)) return true;
			}
		}
		if (extra != null) {
			for (String t : extra) {
				if (lower.startsWith(t)) return true;
			}
		}
		return false;
	}

	/**
	 * Meldet eine eingehende Nachricht; plant bei einem Treffer das Senden.
	 *
	 * @return true, wenn jetzt geplant wurde
	 */
	public boolean onMessage(String plain, List<String> extra, long nowMs, long delayMs) {
		return onMessage(plain, extra, true, nowMs, delayMs);
	}

	public boolean onMessage(String plain, List<String> extra, boolean presets, long nowMs, long delayMs) {
		if (pending || !matches(plain, extra, presets)) return false;
		return schedule(nowMs, delayMs);
	}

	/** Titel vom Server (z. B. „VICTORY!“). */
	public boolean onTitle(String title, List<String> extra, boolean presets, long nowMs, long delayMs) {
		if (pending || !matchesTitle(title, extra, presets)) return false;
		return schedule(nowMs, delayMs);
	}

	private boolean schedule(long nowMs, long delayMs) {
		if (!limiter.tryAcquire(nowMs)) return false;
		pending = true;
		sendAt = nowMs + clampDelay(delayMs);
		return true;
	}

	/** Verzögerung immer zwischen 0,5 und 2 s – auch bei von Hand geänderter Config. */
	public static long clampDelay(long delayMs) {
		return Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, delayMs));
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
