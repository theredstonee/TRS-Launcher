package dev.theredstonee.trsclient.core.halloween;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ist ein TRS-Event (gerade nur {@value #ID}) für diesen Spieler aktiv? Drei Quellen:
 * <ol>
 *   <li>Testschalter {@code -Dtrsclient.halloween=true|false} (bzw. {@link #force}) – gilt vor allem anderen.</li>
 *   <li>Die TRS API: {@code GET /v1/me} → {@code events} und das SSE-Ereignis {@code events_changed} – sobald sie einmal
 *   geantwortet hat, gilt nur noch sie (das Team kann das Event auch mitten im Spiel beenden).</li>
 *   <li>Vorher die Datei des Launchers ({@code launcher-theme.json} → {@code "event": "halloween"}), damit schon der
 *   Ladebildschirm beim Start passt.</li>
 * </ol>
 * Lesen ist billig (ein {@code volatile}-Feld) – die Oberfläche fragt in jedem Bild. Schreiben aus beliebigen Threads.
 */
public final class Halloween {
	/** ID des Events (API, launcher-theme.json, Freischaltung der Kosmetik). */
	public static final String ID = "halloween";
	/** Höchstens so viele Event-IDs werden übernommen (Schutz vor kaputten Antworten). */
	static final int MAX_EVENTS = 16;

	private static volatile Set<String> launcher = Collections.emptySet();
	/** null = die API hat noch nicht geantwortet. */
	private static volatile Set<String> online;
	/** null = kein Testschalter. */
	private static volatile Boolean forced = parseForce(System.getProperty("trsclient.halloween"));
	/** Steigt bei jeder Änderung (Oberflächen bauen damit Zwischenspeicher neu). */
	private static volatile int version;
	/** „Flackern &amp; Rauschen“ laut Launcher ({@code launcher-theme.json → halloweenFlicker}); null = Launcher sagt nichts. */
	private static volatile Boolean launcherFlicker;

	private Halloween() {
	}

	/** Halloween-Look und -Kosmetik für diesen Spieler? */
	public static boolean active() {
		return eventActive(ID);
	}

	/** Ist das Event {@code id} gerade für diesen Spieler aktiv (Testschalter beachtet nur {@value #ID})? */
	public static boolean eventActive(String id) {
		if (id == null) return false;
		Boolean f = forced;
		if (f != null && ID.equals(id)) return f;
		Set<String> o = online;
		return (o != null ? o : launcher).contains(id);
	}

	/** Zähler für Änderungen (z. B. Szene neu bauen). */
	public static int version() {
		return version;
	}

	/**
	 * Feld {@code halloweenFlicker} aus {@code launcher-theme.json} (Einstellung „Flackern &amp; Rauschen“ im Launcher):
	 * Standard des Clients, solange dort niemand selbst umgeschaltet hat. null = Feld fehlt.
	 */
	public static void flickerFromLauncher(Boolean on) {
		launcherFlicker = on;
	}

	/** Wert aus dem Launcher oder null. */
	public static Boolean launcherFlicker() {
		return launcherFlicker;
	}

	/** Feld {@code event} aus {@code launcher-theme.json} (null/leer = keins). */
	public static void fromLauncher(String event) {
		String e = clean(event);
		Set<String> next = e == null ? Collections.<String>emptySet() : Collections.singleton(e);
		if (!next.equals(launcher)) {
			launcher = next;
			version++;
		}
	}

	/**
	 * Aktive Events laut TRS API ({@code me.events} bzw. {@code events_changed}). {@code null} = die Antwort kennt das Feld
	 * nicht (alte API) – dann bleibt es bei der Launcher-Datei.
	 */
	public static void fromApi(Collection<String> events) {
		if (events == null) return;
		Set<String> next = new LinkedHashSet<String>();
		for (String s : events) {
			String e = clean(s);
			if (e != null && next.size() < MAX_EVENTS) next.add(e);
		}
		Set<String> now = Collections.unmodifiableSet(next);
		if (!now.equals(online)) {
			online = now;
			version++;
		}
	}

	/** Testschalter: {@code true}/{@code false} erzwingt, {@code null} hebt auf. */
	public static void force(Boolean on) {
		forced = on;
		version++;
	}

	/** Aktive Events (für Tests und Protokolle). */
	public static List<String> activeEvents() {
		Set<String> o = online;
		List<String> out = new ArrayList<String>(o != null ? o : launcher);
		Boolean f = forced;
		if (f != null) {
			out.remove(ID);
			if (f) out.add(0, ID);
		}
		return out;
	}

	/** Nur für Tests: alles zurücksetzen (Testschalter aus). */
	public static void reset() {
		launcher = Collections.emptySet();
		online = null;
		forced = null;
		launcherFlicker = null;
		version++;
	}

	static Boolean parseForce(String value) {
		if (value == null) return null;
		String v = value.trim().toLowerCase(Locale.ROOT);
		if (v.equals("true") || v.equals("on") || v.equals("1")) return Boolean.TRUE;
		if (v.equals("false") || v.equals("off") || v.equals("0")) return Boolean.FALSE;
		return null;
	}

	/** Event-ID prüfen: {@code [a-z0-9_-]{1,32}}, sonst null. */
	static String clean(String s) {
		if (s == null) return null;
		String t = s.trim().toLowerCase(Locale.ROOT);
		if (t.isEmpty() || t.length() > 32) return null;
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i);
			if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) return null;
		}
		return t;
	}
}
