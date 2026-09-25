package dev.theredstonee.trsclient.core.intro;

import dev.theredstonee.trsclient.core.config.ConfigStore;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.sync.ClientSync;

import java.io.IOException;

/**
 * Wann die Einführung von selbst erscheint: beim ersten Öffnen des Startbildschirms, solange sie in dieser Instanz
 * nicht erledigt ist. Mit TRS-Konto (und Sync) wird kurz ({@link #WAIT_MS}) auf den ersten Abgleich gewartet – hat
 * das Konto die Einführung schon auf einem anderen PC erledigt, erscheint stattdessen nur eine kurze Begrüßung.
 * Im Autotest nie (außer {@code -Dtrsclient.autotest.only=intro}).
 */
public final class IntroGate {
	public static final long WAIT_MS = 5000L;

	public enum Decision {
		/** Noch auf den ersten Abgleich warten. */
		WAIT,
		SHOW,
		SKIP
	}

	private static volatile TrsModules modules;
	private static long firstAsked;
	private static boolean decided;

	private IntroGate() {
	}

	/** Module des laufenden Spiels (von {@link TrsModules} selbst gesetzt). */
	public static void register(TrsModules m) {
		modules = m;
	}

	public static TrsModules modules() {
		return modules;
	}

	/** Entscheidung für den Startbildschirm (einmal je Sitzung „SHOW“). */
	public static synchronized Decision decide(long now) {
		TrsModules m = modules;
		if (m == null || decided) return Decision.SKIP;
		if (Boolean.getBoolean("trsclient.autotest") && !"intro".equals(System.getProperty("trsclient.autotest.only"))) {
			decided = true;
			return Decision.SKIP;
		}
		if (m.clientState.introDone()) {
			decided = true;
			return Decision.SKIP;
		}
		if (firstAsked == 0) firstAsked = now;
		ClientSync sync = ClientSync.get();
		if (sync != null && sync.enabled() && !sync.firstRoundDone() && now - firstAsked < WAIT_MS) return Decision.WAIT;
		if (m.clientState.introDone()) {
			decided = true;
			return Decision.SKIP;
		}
		decided = true;
		return Decision.SHOW;
	}

	private static volatile String notice;

	/** Hinweis für den nächsten Startbildschirm (z. B. „Einführung abgeschlossen“). */
	public static void notice(String text) {
		notice = text;
	}

	/** Holt den Hinweis ab (null = keiner). */
	public static String takeNotice() {
		String n = notice;
		notice = null;
		return n;
	}

	/** Nur Tests/Autotest: nächste Entscheidung neu treffen. */
	public static synchronized void reset() {
		decided = false;
		firstAsked = 0;
	}

	/**
	 * Kurze Begrüßung fällig? (Einführung wurde über das TRS-Konto auf einem anderen PC erledigt.) Gibt es nur einmal
	 * je Instanz.
	 */
	public static boolean takeWelcome() {
		TrsModules m = modules;
		if (m == null || m.clientState.welcomeShown() || !ClientState.ACCOUNT.equals(m.clientState.introHow())) return false;
		m.clientState.markWelcomeShown();
		save(m);
		return true;
	}

	/** Speichert die Config (im Hintergrund). */
	public static void save(TrsModules m) {
		ConfigStore store = ConfigStore.active();
		if (store == null || m == null) return;
		try {
			store.saveLater(m.registry);
		} catch (IOException ignored) {
			// Wird beim nächsten Speichern erneut versucht.
		}
	}
}
