package dev.theredstonee.trsclient.core.connect;

/**
 * „Schneller Serverwechsel“ (Proxy-Netzwerke wie Velocity/BungeeCord): merkt sich, dass gerade ein Wechsel läuft – vom
 * Beginn der Konfigurationsphase (ab 1.20.2) bis der Ladebildschirm wieder zu ist. Solange darf Minecraft nicht auf
 * 60/30/10 Bilder pro Sekunde drosseln (jede Rückfrage des Servers wartet sonst auf das nächste Bild). Außerdem
 * misst es die Dauer des Wechsels fürs Log – damit sich die Wirkung prüfen lässt.
 */
public final class FastSwitch {
	/** Länger dauernde Wechsel gelten als beendet (Fehlerfall, Warteschlange …). */
	static final long MAX_MS = 30_000L;

	private static volatile long startedNanos;
	private static volatile boolean running;
	private static volatile long lastMs = -1;
	private static volatile int count;

	private FastSwitch() {
	}

	/** Server beginnt einen Wechsel (Konfigurationsphase bzw. neue Welt). */
	public static void begin() {
		startedNanos = System.nanoTime();
		running = true;
	}

	/** Neue Welt vom Server: nur ein Beginn, wenn nicht schon die Konfigurationsphase einen Wechsel begonnen hat. */
	public static void beginIfIdle() {
		if (!running || System.nanoTime() - startedNanos > MAX_MS * 1_000_000L) begin();
	}

	/** Läuft gerade ein Wechsel (und ist „Schneller Serverwechsel“ an)? */
	public static boolean active() {
		if (!running) return false;
		if (System.nanoTime() - startedNanos > MAX_MS * 1_000_000L) {
			running = false;
			return false;
		}
		return ServerPacks.fastSwitchOn();
	}

	/** Je Client-Tick: Welt da und kein Ladebildschirm mehr → Wechsel fertig (einmal loggen). */
	public static void tick(boolean levelReady) {
		if (!running || !levelReady) return;
		running = false;
		long ms = (System.nanoTime() - startedNanos) / 1_000_000L;
		lastMs = ms;
		count++;
		FastConnect.info("Server switch finished in " + ms + " ms (fast switch " + (ServerPacks.fastSwitchOn() ? "on" : "off") + ")");
	}

	/** 1.18.2–1.19.2: die 2-Sekunden-Wartezeit von „Lade Gelände …“ umgehen (Regel von 1.19.3)? */
	public static boolean fixTerrainWait() {
		return ServerPacks.fastSwitchOn();
	}

	/** Getrennt: kein Wechsel mehr. */
	public static void reset() {
		running = false;
	}

	/** Zahl der fertigen Wechsel (für Tests). */
	public static int count() {
		return count;
	}

	/** Dauer des letzten Wechsels in ms oder -1. */
	public static long lastMs() {
		return lastMs;
	}
}
